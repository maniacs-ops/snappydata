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
package org.apache.spark.sql.execution.columnar

import java.sql.{Connection, PreparedStatement, SQLException}
import java.util.UUID

import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.sources.ConnectionProperties

trait ExternalStore extends Serializable {

  final val columnPrefix = "Col_"

  def storeCachedBatch(tableName: String, batch: CachedBatch,
      partitionId: Int = -1, batchId: Option[UUID] = None): Unit

  def getCachedBatchRDD(tableName: String, requiredColumns: Array[String],
      sparkContext: SparkContext): RDD[CachedBatch]

  def getConnectedStore(id: String, onExecutor: Boolean) : ConnectedExternalStore

  def getConnection(id: String, onExecutor: Boolean): java.sql.Connection

  def connProperties: ConnectionProperties

  def tryExecute[T: ClassTag](tableName: String,
      f: Connection => T,
      closeOnSuccess: Boolean = true, onExecutor: Boolean = false): T = {
    val conn = getConnection(tableName, onExecutor)
    var isClosed = false
    try {
      f(conn)
    } catch {
      case t: Throwable =>
        conn.close()
        isClosed = true
        throw t
    } finally {
      if (closeOnSuccess && !isClosed) {
        conn.close()
      }
    }
  }

} // ExternalStore


trait ConnectedExternalStore extends ExternalStore {

  protected[this] val connectedInstance: Connection

  private[this] val preparedStatements = ArrayBuffer.empty[PreparedStatement]

  def prepareStatement(dml: String): java.sql.PreparedStatement = {
    val ps = connectedInstance.prepareStatement(dml)
    preparedStatements += ps
    ps
  }

  def tryExecuteWithDependents[T: ClassTag](tableName: String,
      f: (Connection, ArrayBuffer[PreparedStatement]) => T,
      onExecutor: Boolean = false): T = {
    val conn = getConnection(tableName, onExecutor)
    try {
      f(conn, preparedStatements)
    } catch {
      case t: Throwable =>
        close()
        throw t
    }
  }

  def commit(): Unit = connectedInstance.commit()

  def close(): Unit = {
    preparedStatements.foreach(_.close())
    preparedStatements.clear()
    connectedInstance.close()
  }

} // ConnectedExternalStore