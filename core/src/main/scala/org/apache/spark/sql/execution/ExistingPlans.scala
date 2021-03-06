/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql.execution

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode}
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, _}
import org.apache.spark.sql.catalyst.plans.physical.{HashPartitioning, Partitioning, SinglePartition}
import org.apache.spark.sql.catalyst.{InternalRow, TableIdentifier}
import org.apache.spark.sql.collection.ToolsCallbackInit
import org.apache.spark.sql.execution.columnar.impl.BaseColumnFormatRelation
import org.apache.spark.sql.execution.columnar.{ColumnTableScan, ConnectionType}
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.execution.row.RowFormatRelation
import org.apache.spark.sql.sources.{BaseRelation, PrunedUnsafeFilteredScan, SamplingRelation}
import org.apache.spark.sql.types._


/** Physical plan node for scanning data from an DataSource scan RDD.
 * If user knows that the data is partitioned or replicated across
 * all nodes this SparkPla can be used to avoid expensive shuffle
 * and Broadcast joins. This plan overrides outputPartitioning and
 * make it inline with the partitioning of the underlying DataSource */
private[sql] abstract class PartitionedPhysicalScan(
    output: Seq[Attribute],
    dataRDD: RDD[Any],
    numBuckets: Int,
    partitionColumns: Seq[Expression],
    @transient override val relation: BaseRelation,
    // not used currently (if need to use then get from relation.table)
    override val metastoreTableIdentifier: Option[TableIdentifier] = None)
    extends DataSourceScanExec with CodegenSupport {

  def getMetrics: Map[String, SQLMetric] = Map(
    "numOutputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows"))

  override lazy val metrics: Map[String, SQLMetric] = getMetrics

  private val extraInformation = relation.toString

  protected lazy val numPartitions: Int = dataRDD.getNumPartitions

  override lazy val schema: StructType = StructType.fromAttributes(output)

  // RDD cast as RDD[InternalRow] below just to satisfy interfaces like
  // inputRDDs though its actually of CachedBatches, CompactExecRows, etc
  override val rdd: RDD[InternalRow] = dataRDD.asInstanceOf[RDD[InternalRow]]

  override def inputRDDs(): Seq[RDD[InternalRow]] = {
    rdd :: Nil
  }

  protected override def doExecute(): RDD[InternalRow] = {
    WholeStageCodegenExec(this).execute()
  }

  /** Specifies how data is partitioned across different nodes in the cluster. */
  override lazy val outputPartitioning: Partitioning = {
    if (numPartitions == 1) {
      SinglePartition
    } else if (partitionColumns.nonEmpty) {
      val callbacks = ToolsCallbackInit.toolsCallback
      if (callbacks != null) {
        callbacks.getOrderlessHashPartitioning(partitionColumns,
          numPartitions, numBuckets)
      } else {
        HashPartitioning(partitionColumns, numPartitions)
      }
    } else super.outputPartitioning
  }

  override def simpleString: String = "Partitioned Scan " + extraInformation +
      " , Requested Columns = " + output.mkString("[", ",", "]") +
      " partitionColumns = " + partitionColumns.mkString("[", ",", "]" +
      " numBuckets= " + numBuckets +
      " numPartitions= " + numPartitions)
}

private[sql] object PartitionedPhysicalScan {

  private[sql] val CT_NUMROWS_POSITION = 3
  private[sql] val CT_STATROW_POSITION = 4
  private[sql] val CT_COLUMN_START = 5

  def createFromDataSource(
      output: Seq[Attribute],
      numBuckets: Int,
      partitionColumns: Seq[Expression],
      rdd: RDD[Any],
      otherRDDs: Seq[RDD[InternalRow]],
      relation: PartitionedDataSourceScan,
      allFilters: Seq[Expression],
      schemaAttributes: Seq[AttributeReference]): PartitionedPhysicalScan =
    relation match {
      case r: BaseColumnFormatRelation =>
        ColumnTableScan(output, rdd, otherRDDs, numBuckets,
          partitionColumns, relation, allFilters, schemaAttributes)
      case r: SamplingRelation =>
        ColumnTableScan(output, rdd, otherRDDs, numBuckets,
          partitionColumns, relation, allFilters, schemaAttributes)
      case _: RowFormatRelation =>
        RowTableScan(output, rdd, numBuckets,
          partitionColumns, relation)
    }
}

trait PartitionedDataSourceScan extends PrunedUnsafeFilteredScan {

  def table: String

  def schema: StructType

  def numBuckets: Int

  def partitionColumns: Seq[String]

  def connectionType: ConnectionType.Value
}

trait BatchConsumer extends CodegenSupport {

  /**
   * Generate Java source code to do any processing before a batch is consumed
   * by a [[DataSourceScanExec]] that does batch processing (e.g. per-batch
   * optimizations, initializations etc).
   * <p>
   * Implementations should use this for additional optimizations that can be
   * done at batch level when a batched scan is being done. They should not
   * depend on this being invoked since many scans will not be batched.
   */
  def batchConsume(ctx: CodegenContext, input: Seq[ExprCode]): String = ""
}

/**
 * Extended information for ExprCode to also hold the hashCode variable,
 * variable having dictionary reference and its index when dictionary
 * encoding is being used.
 */
final case class ExprCodeEx(var hash: Option[String], dictionaryCode: String,
    dictionary: String, dictionaryIndex: String)
