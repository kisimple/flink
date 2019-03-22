/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.sources

import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.RowTypeInfo
import org.apache.flink.streaming.api.datastream.DataStream
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.table.api.TableSchema
import org.apache.flink.table.util.DimTableSourceProvider
import org.apache.flink.types.Row

/**
 * Defines an external dimension table source.
 *
 * @param typeName Dim table source type to match with [[DimTableSourceProvider.typeName()]].
 * @param params Parameters to be used by [[DimTableSourceProvider.init()]].
 * @param fieldNames Field names of this dim table source.
 * @param fieldTypes Field types of this dim table source.
 */
class DimTableSource(
    val typeName: String,
    val params: java.util.Map[String, java.io.Serializable],
    val fieldNames: Array[String],
    val fieldTypes: Array[TypeInformation[_]])
  extends StreamTableSource[Row] {

  override def getDataStream(execEnv: StreamExecutionEnvironment): DataStream[Row] = {
    throw new UnsupportedOperationException
  }

  override def getReturnType = new RowTypeInfo(fieldTypes, fieldNames)

  override def getTableSchema = new TableSchema(fieldNames, fieldTypes)

}
