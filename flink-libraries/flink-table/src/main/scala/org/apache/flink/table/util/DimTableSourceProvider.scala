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

package org.apache.flink.table.util

import org.apache.flink.table.expressions.Expression
import org.apache.flink.table.sources.DimTableSource
import org.apache.flink.types.Row

/**
 * A [[DimTableSource]] physical runner which is loaded by [[java.util.ServiceLoader]].
 */
trait DimTableSourceProvider {

  /**
    * Dim table source type which matches with [[DimTableSource.typeName]].
    * @return The Dim table source type.
    */
  def typeName(): String

  /**
    * Initialization of the dim table source runner.
    * @param params Parameters including [[DimTableSource.params]].
    *
    * <p>NOTE: "_fieldNames" and "_fieldTypes" are preserved keys for
    * [[DimTableSource.fieldNames]] and [[DimTableSource.fieldTypes]] respectively.</p>
    */
  def init(params: java.util.Map[String, java.io.Serializable]): Unit

  /**
    * A query for dim table records.
    *
    * @param requiredColumns Columns which are required for this query.
    * @param filters Predicates which should be applied for this query. If there are some filters
    *                that can not be supported, this query should fail.
    * @return The result row which should contain the required columns and respect the ordering.
    */
  def scan(requiredColumns: Array[String],
      filters: java.util.List[Expression]): java.util.Iterator[Row]

}
