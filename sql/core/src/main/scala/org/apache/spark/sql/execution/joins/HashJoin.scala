/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.joins

import java.util.NoSuchElementException

import org.apache.spark.TaskContext
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.execution.{RowIterator, SparkPlan}
import org.apache.spark.sql.execution.metric.LongSQLMetric
import org.apache.spark.sql.types.{IntegerType, IntegralType, LongType}
import org.apache.spark.util.collection.CompactBuffer

trait HashJoin {
  self: SparkPlan =>

  val leftKeys: Seq[Expression]
  val rightKeys: Seq[Expression]
  val joinType: JoinType
  val buildSide: BuildSide
  val condition: Option[Expression]
  val left: SparkPlan
  val right: SparkPlan

  override def output: Seq[Attribute] = {
    joinType match {
      case Inner =>
        left.output ++ right.output
      case LeftOuter =>
        left.output ++ right.output.map(_.withNullability(true))
      case RightOuter =>
        left.output.map(_.withNullability(true)) ++ right.output
      case LeftSemi =>
        left.output
      case x =>
        throw new IllegalArgumentException(s"HashJoin should not take $x as the JoinType")
    }
  }

  protected lazy val (buildPlan, streamedPlan) = buildSide match {
    case BuildLeft => (left, right)
    case BuildRight => (right, left)
  }

  protected lazy val (buildKeys, streamedKeys) = buildSide match {
    case BuildLeft => (leftKeys, rightKeys)
    case BuildRight => (rightKeys, leftKeys)
  }

  /**
   * Try to rewrite the key as LongType so we can use getLong(), if they key can fit with a long.
   *
   * If not, returns the original expressions.
   */
  def rewriteKeyExpr(keys: Seq[Expression]): Seq[Expression] = {
    var keyExpr: Expression = null
    var width = 0
    keys.foreach { e =>
      e.dataType match {
        case dt: IntegralType if dt.defaultSize <= 8 - width =>
          if (width == 0) {
            if (e.dataType != LongType) {
              keyExpr = Cast(e, LongType)
            } else {
              keyExpr = e
            }
            width = dt.defaultSize
          } else {
            val bits = dt.defaultSize * 8
            // hashCode of Long is (l >> 32) ^ l.toInt, it means the hash code of an long with same
            // value in high 32 bit and low 32 bit will be 0. To avoid the worst case that keys
            // with two same ints have hash code 0, we rotate the bits of second one.
            val rotated = if (e.dataType == IntegerType) {
              // (e >>> 15) | (e << 17)
              BitwiseOr(ShiftRightUnsigned(e, Literal(15)), ShiftLeft(e, Literal(17)))
            } else {
              e
            }
            keyExpr = BitwiseOr(ShiftLeft(keyExpr, Literal(bits)),
              BitwiseAnd(Cast(rotated, LongType), Literal((1L << bits) - 1)))
            width -= bits
          }
        // TODO: support BooleanType, DateType and TimestampType
        case other =>
          return keys
      }
    }
    keyExpr :: Nil
  }

  protected lazy val canJoinKeyFitWithinLong: Boolean = {
    val sameTypes = buildKeys.map(_.dataType) == streamedKeys.map(_.dataType)
    val key = rewriteKeyExpr(buildKeys)
    sameTypes && key.length == 1 && key.head.dataType.isInstanceOf[LongType]
  }

  protected def buildSideKeyGenerator(): Projection =
    UnsafeProjection.create(rewriteKeyExpr(buildKeys), buildPlan.output)

  protected def streamSideKeyGenerator(): UnsafeProjection =
    UnsafeProjection.create(rewriteKeyExpr(streamedKeys), streamedPlan.output)

  @transient private[this] lazy val boundCondition = if (condition.isDefined) {
    newPredicate(condition.get, streamedPlan.output ++ buildPlan.output)
  } else {
    (r: InternalRow) => true
  }

  protected def createResultProjection(): (InternalRow) => InternalRow = {
    if (joinType == LeftSemi) {
      UnsafeProjection.create(output, output)
    } else {
      // Always put the stream side on left to simplify implementation
      // both of left and right side could be null
      UnsafeProjection.create(
        output, (streamedPlan.output ++ buildPlan.output).map(_.withNullability(true)))
    }
  }

  private def innerJoin(
      streamIter: Iterator[InternalRow],
      hashedRelation: HashedRelation): Iterator[InternalRow] = {
    val joinRow = new JoinedRow
    val joinKeys = streamSideKeyGenerator()
    streamIter.flatMap { srow =>
      joinRow.withLeft(srow)
      val matches = hashedRelation.get(joinKeys(srow))
      if (matches != null) {
        matches.map(joinRow.withRight(_)).filter(boundCondition)
      } else {
        Seq.empty
      }
    }
  }

  private def outerJoin(
      streamedIter: Iterator[InternalRow],
    hashedRelation: HashedRelation): Iterator[InternalRow] = {
    val joinedRow = new JoinedRow()
    val keyGenerator = streamSideKeyGenerator()
    val nullRow = new GenericInternalRow(buildPlan.output.length)

    streamedIter.flatMap { currentRow =>
      val rowKey = keyGenerator(currentRow)
      joinedRow.withLeft(currentRow)
      val buildIter = hashedRelation.get(rowKey)
      new RowIterator {
        private var found = false
        override def advanceNext(): Boolean = {
          while (buildIter != null && buildIter.hasNext) {
            val nextBuildRow = buildIter.next()
            if (boundCondition(joinedRow.withRight(nextBuildRow))) {
              found = true
              return true
            }
          }
          if (!found) {
            joinedRow.withRight(nullRow)
            found = true
            return true
          }
          false
        }
        override def getRow: InternalRow = joinedRow
      }.toScala
    }
  }

  private def semiJoin(
      streamIter: Iterator[InternalRow],
      hashedRelation: HashedRelation): Iterator[InternalRow] = {
    val joinKeys = streamSideKeyGenerator()
    val joinedRow = new JoinedRow
    streamIter.filter { current =>
      val key = joinKeys(current)
      lazy val buildIter = hashedRelation.get(key)
      !key.anyNull && buildIter != null && (condition.isEmpty || buildIter.exists {
        (row: InternalRow) => boundCondition(joinedRow(current, row))
      })
    }
  }

  protected def join(
      streamedIter: Iterator[InternalRow],
      hashed: HashedRelation,
      numOutputRows: LongSQLMetric): Iterator[InternalRow] = {

    val joinedIter = joinType match {
      case Inner =>
        innerJoin(streamedIter, hashed)
      case LeftOuter | RightOuter =>
        outerJoin(streamedIter, hashed)
      case LeftSemi =>
        semiJoin(streamedIter, hashed)
      case x =>
        throw new IllegalArgumentException(
          s"BroadcastHashJoin should not take $x as the JoinType")
    }

    val resultProj = createResultProjection
    joinedIter.map { r =>
      numOutputRows += 1
      resultProj(r)
    }
  }
}
