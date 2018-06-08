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

package org.apache.flink.cep.examples.java.monitoring.events;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Temperature alert event.
 */
public class TemperatureAlert {

	private int rackID;
	// the time when last warning occurred
	private LocalDateTime warningTime;

	// the time when this alert is generated
	private LocalDateTime datetime;

	public TemperatureAlert() {
		this(-1, LocalDateTime.now());
	}

	public TemperatureAlert(int rackID, LocalDateTime warningTime) {
		this.rackID = rackID;
		this.warningTime = warningTime;
		this.datetime = LocalDateTime.now();
	}

	public int getRackID() {
		return rackID;
	}

	public void setRackID(int rackID) {
		this.rackID = rackID;
	}

	public LocalDateTime getWarningTime() {
		return warningTime;
	}

	public void setWarningTime(LocalDateTime warningTime) {
		this.warningTime = warningTime;
	}

	public LocalDateTime getDatetime() {
		return datetime;
	}

	public void setDatetime(LocalDateTime datetime) {
		this.datetime = datetime;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		TemperatureAlert that = (TemperatureAlert) o;

		return rackID == that.rackID
			&& warningTime.equals(that.warningTime)
			&& datetime.equals(that.datetime);
	}

	@Override
	public int hashCode() {
		return Objects.hash(rackID, warningTime, datetime);
	}

	@Override
	public String toString() {
		return "TemperatureAlert("
			+ getRackID() + ", "
			+ "warningTime=" + warningTime + ", "
			+ "alertTime=" + datetime + ")";
	}

}
