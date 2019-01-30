/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.checkpoint;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.runtime.executiongraph.Execution;
import org.apache.flink.runtime.executiongraph.ExecutionJobVertex;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.jobgraph.OperatorInstanceID;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.apache.flink.runtime.state.KeyGroupsStateHandle;
import org.apache.flink.runtime.state.KeyedStateHandle;
import org.apache.flink.runtime.state.LocalKeyedStateHandle;
import org.apache.flink.runtime.state.OperatorStateHandle;
import org.apache.flink.util.Preconditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * This class encapsulates the operation of assigning restored state when restoring from a checkpoint.
 */
public class StateAssignmentOperation {

	private static final Logger LOG = LoggerFactory.getLogger(StateAssignmentOperation.class);

	private final Map<JobVertexID, ExecutionJobVertex> tasks;
	private final Map<OperatorID, OperatorState> operatorStates;

	private final long restoreCheckpointId;
	private final boolean allowNonRestoredState;

	public StateAssignmentOperation(
		long restoreCheckpointId,
		Map<JobVertexID, ExecutionJobVertex> tasks,
		Map<OperatorID, OperatorState> operatorStates,
		boolean allowNonRestoredState) {

		this.restoreCheckpointId = restoreCheckpointId;
		this.tasks = Preconditions.checkNotNull(tasks);
		this.operatorStates = Preconditions.checkNotNull(operatorStates);
		this.allowNonRestoredState = allowNonRestoredState;
	}

	public void assignStates() {
		Map<OperatorID, OperatorState> localOperators = new HashMap<>(operatorStates);

		checkStateMappingCompleteness(allowNonRestoredState, operatorStates, tasks);

		for (Map.Entry<JobVertexID, ExecutionJobVertex> task : this.tasks.entrySet()) {
			final ExecutionJobVertex executionJobVertex = task.getValue();

			// find the states of all operators belonging to this task
			List<OperatorID> operatorIDs = executionJobVertex.getOperatorIDs();
			List<OperatorID> altOperatorIDs = executionJobVertex.getUserDefinedOperatorIDs();
			List<OperatorState> operatorStates = new ArrayList<>(operatorIDs.size());
			boolean statelessTask = true;
			for (int x = 0; x < operatorIDs.size(); x++) {
				OperatorID operatorID = altOperatorIDs.get(x) == null
					? operatorIDs.get(x)
					: altOperatorIDs.get(x);

				OperatorState operatorState = localOperators.remove(operatorID);
				if (operatorState == null) {
					operatorState = new OperatorState(
						operatorID,
						executionJobVertex.getParallelism(),
						executionJobVertex.getMaxParallelism());
				} else {
					statelessTask = false;
				}
				operatorStates.add(operatorState);
			}
			if (statelessTask) { // skip tasks where no operator has any state
				continue;
			}

			assignAttemptState(task.getValue(), operatorStates);
		}

	}

	private void assignAttemptState(ExecutionJobVertex executionJobVertex, List<OperatorState> operatorStates) {

		List<OperatorID> operatorIDs = executionJobVertex.getOperatorIDs();

		//1. first compute the new parallelism
		checkParallelismPreconditions(operatorStates, executionJobVertex);

		int newParallelism = executionJobVertex.getParallelism();

		List<KeyGroupRange> keyGroupPartitions = createKeyGroupPartitions(
			executionJobVertex.getMaxParallelism(),
			newParallelism);

		final int expectedNumberOfSubTasks = newParallelism * operatorIDs.size();

		/*
		 * Redistribute ManagedOperatorStates and RawOperatorStates from old parallelism to new parallelism.
		 *
		 * The old ManagedOperatorStates with old parallelism 3:
		 *
		 * 		parallelism0 parallelism1 parallelism2
		 * op0   states0,0    state0,1	   state0,2
		 * op1
		 * op2   states2,0    state2,1	   state1,2
		 * op3   states3,0    state3,1     state3,2
		 *
		 * The new ManagedOperatorStates with new parallelism 4:
		 *
		 * 		parallelism0 parallelism1 parallelism2 parallelism3
		 * op0   state0,0	  state0,1 	   state0,2		state0,3
		 * op1
		 * op2   state2,0	  state2,1 	   state2,2		state2,3
		 * op3   state3,0	  state3,1 	   state3,2		state3,3
		 */
		Map<OperatorInstanceID, List<OperatorStateHandle>> newManagedOperatorStates =
			new HashMap<>(expectedNumberOfSubTasks);
		Map<OperatorInstanceID, List<OperatorStateHandle>> newRawOperatorStates =
			new HashMap<>(expectedNumberOfSubTasks);

		reDistributePartitionableStates(
			operatorStates,
			newParallelism,
			operatorIDs,
			newManagedOperatorStates,
			newRawOperatorStates);

		Map<OperatorInstanceID, List<KeyedStateHandle>> newManagedKeyedState =
			new HashMap<>(expectedNumberOfSubTasks);
		Map<OperatorInstanceID, List<KeyedStateHandle>> newRawKeyedState =
			new HashMap<>(expectedNumberOfSubTasks);

		reDistributeKeyedStates(
			operatorStates,
			newParallelism,
			operatorIDs,
			keyGroupPartitions,
			newManagedKeyedState,
			newRawKeyedState);

		/*
		 *  An executionJobVertex's all state handles needed to restore are something like a matrix
		 *
		 * 		parallelism0 parallelism1 parallelism2 parallelism3
		 * op0   sh(0,0)     sh(0,1)       sh(0,2)	    sh(0,3)
		 * op1   sh(1,0)	 sh(1,1)	   sh(1,2)	    sh(1,3)
		 * op2   sh(2,0)	 sh(2,1)	   sh(2,2)		sh(2,3)
		 * op3   sh(3,0)	 sh(3,1)	   sh(3,2)		sh(3,3)
		 *
		 */
		assignTaskStateToExecutionJobVertices(
			executionJobVertex,
			newManagedOperatorStates,
			newRawOperatorStates,
			newManagedKeyedState,
			newRawKeyedState,
			newParallelism);
	}

	private void assignTaskStateToExecutionJobVertices(
			ExecutionJobVertex executionJobVertex,
			Map<OperatorInstanceID, List<OperatorStateHandle>> subManagedOperatorState,
			Map<OperatorInstanceID, List<OperatorStateHandle>> subRawOperatorState,
			Map<OperatorInstanceID, List<KeyedStateHandle>> subManagedKeyedState,
			Map<OperatorInstanceID, List<KeyedStateHandle>> subRawKeyedState,
			int newParallelism) {

		List<OperatorID> operatorIDs = executionJobVertex.getOperatorIDs();

		for (int subTaskIndex = 0; subTaskIndex < newParallelism; subTaskIndex++) {

			Execution currentExecutionAttempt = executionJobVertex.getTaskVertices()[subTaskIndex]
				.getCurrentExecutionAttempt();

			TaskStateSnapshot taskState = new TaskStateSnapshot(operatorIDs.size());
			boolean statelessTask = true;

			for (OperatorID operatorID : operatorIDs) {
				OperatorInstanceID instanceID = OperatorInstanceID.of(subTaskIndex, operatorID);

				OperatorSubtaskState operatorSubtaskState = operatorSubtaskStateFrom(
					instanceID,
					subManagedOperatorState,
					subRawOperatorState,
					subManagedKeyedState,
					subRawKeyedState);

				if (operatorSubtaskState.hasState()) {
					statelessTask = false;
				}
				taskState.putSubtaskStateByOperatorID(operatorID, operatorSubtaskState);
			}

			if (!statelessTask) {
				JobManagerTaskRestore taskRestore = new JobManagerTaskRestore(restoreCheckpointId, taskState);
				currentExecutionAttempt.setInitialState(taskRestore);
			}
		}
	}

	public static OperatorSubtaskState operatorSubtaskStateFrom(
			OperatorInstanceID instanceID,
			Map<OperatorInstanceID, List<OperatorStateHandle>> subManagedOperatorState,
			Map<OperatorInstanceID, List<OperatorStateHandle>> subRawOperatorState,
			Map<OperatorInstanceID, List<KeyedStateHandle>> subManagedKeyedState,
			Map<OperatorInstanceID, List<KeyedStateHandle>> subRawKeyedState) {

		if (!subManagedOperatorState.containsKey(instanceID) &&
			!subRawOperatorState.containsKey(instanceID) &&
			!subManagedKeyedState.containsKey(instanceID) &&
			!subRawKeyedState.containsKey(instanceID)) {

			return new OperatorSubtaskState();
		}
		if (!subManagedKeyedState.containsKey(instanceID)) {
			checkState(!subRawKeyedState.containsKey(instanceID));
		}
		return new OperatorSubtaskState(
			new StateObjectCollection<>(subManagedOperatorState.getOrDefault(instanceID, Collections.emptyList())),
			new StateObjectCollection<>(subRawOperatorState.getOrDefault(instanceID, Collections.emptyList())),
			new StateObjectCollection<>(subManagedKeyedState.getOrDefault(instanceID, Collections.emptyList())),
			new StateObjectCollection<>(subRawKeyedState.getOrDefault(instanceID, Collections.emptyList())));
	}

	public void checkParallelismPreconditions(List<OperatorState> operatorStates, ExecutionJobVertex executionJobVertex) {
		for (OperatorState operatorState : operatorStates) {
			checkParallelismPreconditions(operatorState, executionJobVertex);
		}
	}

	private void reDistributeKeyedStates(
			List<OperatorState> oldOperatorStates,
			int newParallelism,
			List<OperatorID> newOperatorIDs,
			List<KeyGroupRange> newKeyGroupPartitions,
			Map<OperatorInstanceID, List<KeyedStateHandle>> newManagedKeyedState,
			Map<OperatorInstanceID, List<KeyedStateHandle>> newRawKeyedState) {
		//TODO: rewrite this method to only use OperatorID
		checkState(newOperatorIDs.size() == oldOperatorStates.size(),
			"This method still depends on the order of the new and old operators");

		for (int operatorIndex = 0; operatorIndex < newOperatorIDs.size(); operatorIndex++) {
			OperatorState operatorState = oldOperatorStates.get(operatorIndex);
			List<KeyedStateHandle>[] reDistributedLocalKeyedStates =
				reDistributeLocalKeyedStates(operatorState, newParallelism);
			int oldParallelism = operatorState.getParallelism();
			for (int subTaskIndex = 0; subTaskIndex < newParallelism; subTaskIndex++) {
				OperatorInstanceID instanceID = OperatorInstanceID.of(subTaskIndex, newOperatorIDs.get(operatorIndex));
				Tuple2<List<KeyedStateHandle>, List<KeyedStateHandle>> subKeyedStates = reAssignSubKeyedStates(
					operatorState,
					newKeyGroupPartitions,
					subTaskIndex,
					newParallelism,
					oldParallelism,
					reDistributedLocalKeyedStates);
				newManagedKeyedState.put(instanceID, subKeyedStates.f0);
				newRawKeyedState.put(instanceID, subKeyedStates.f1);
			}
		}
	}

	// TODO rewrite based on operator id
	private Tuple2<List<KeyedStateHandle>, List<KeyedStateHandle>> reAssignSubKeyedStates(
			OperatorState operatorState,
			List<KeyGroupRange> keyGroupPartitions,
			int subTaskIndex,
			int newParallelism,
			int oldParallelism,
			List<KeyedStateHandle>[] reDistributedLocalKeyedStates) {

		List<KeyedStateHandle> subManagedKeyedState;
		List<KeyedStateHandle> subRawKeyedState;

		if (newParallelism == oldParallelism) {
			if (operatorState.getState(subTaskIndex) != null) {
				subManagedKeyedState = operatorState.getState(subTaskIndex).getManagedKeyedState().asList();
				subRawKeyedState = operatorState.getState(subTaskIndex).getRawKeyedState().asList();
			} else {
				subManagedKeyedState = Collections.emptyList();
				subRawKeyedState = Collections.emptyList();
			}
		} else {
			subManagedKeyedState = reDistributedLocalKeyedStates.length > 0 ?
				reDistributedLocalKeyedStates[subTaskIndex] :
				getManagedKeyedStateHandles(operatorState, keyGroupPartitions.get(subTaskIndex));
			subRawKeyedState = getRawKeyedStateHandles(operatorState, keyGroupPartitions.get(subTaskIndex));
		}

		if (subManagedKeyedState.isEmpty() && subRawKeyedState.isEmpty()) {
			return new Tuple2<>(Collections.emptyList(), Collections.emptyList());
		} else {
			return new Tuple2<>(subManagedKeyedState, subRawKeyedState);
		}
	}

	private void reDistributePartitionableStates(
			List<OperatorState> oldOperatorStates,
			int newParallelism,
			List<OperatorID> newOperatorIDs,
			Map<OperatorInstanceID, List<OperatorStateHandle>> newManagedOperatorStates,
			Map<OperatorInstanceID, List<OperatorStateHandle>> newRawOperatorStates) {

		//TODO: rewrite this method to only use OperatorID
		checkState(newOperatorIDs.size() == oldOperatorStates.size(),
			"This method still depends on the order of the new and old operators");

		//collect the old partitionable state
		List<List<OperatorStateHandle>> oldManagedOperatorStates = new ArrayList<>(oldOperatorStates.size());
		List<List<OperatorStateHandle>> oldRawOperatorStates = new ArrayList<>(oldOperatorStates.size());

		collectPartionableStates(oldOperatorStates, oldManagedOperatorStates, oldRawOperatorStates);

		//redistribute
		OperatorStateRepartitioner opStateRepartitioner = RoundRobinOperatorStateRepartitioner.INSTANCE;

		for (int operatorIndex = 0; operatorIndex < oldOperatorStates.size(); operatorIndex++) {
			OperatorID operatorID = newOperatorIDs.get(operatorIndex);
			int oldParallelism = oldOperatorStates.get(operatorIndex).getParallelism();
			newManagedOperatorStates.putAll(applyRepartitioner(
				operatorID,
				opStateRepartitioner,
				oldManagedOperatorStates.get(operatorIndex),
				oldParallelism,
				newParallelism));
			newRawOperatorStates.putAll(applyRepartitioner(
				operatorID,
				opStateRepartitioner,
				oldRawOperatorStates.get(operatorIndex),
				oldParallelism,
				newParallelism));
		}
	}

	private void collectPartionableStates(
			List<OperatorState> operatorStates,
			List<List<OperatorStateHandle>> managedOperatorStates,
			List<List<OperatorStateHandle>> rawOperatorStates) {

		for (OperatorState operatorState : operatorStates) {

			final int parallelism = operatorState.getParallelism();

			List<OperatorStateHandle> managedOperatorState = null;
			List<OperatorStateHandle> rawOperatorState = null;

			for (int i = 0; i < parallelism; i++) {
				OperatorSubtaskState operatorSubtaskState = operatorState.getState(i);
				if (operatorSubtaskState != null) {

					StateObjectCollection<OperatorStateHandle> managed = operatorSubtaskState.getManagedOperatorState();
					StateObjectCollection<OperatorStateHandle> raw = operatorSubtaskState.getRawOperatorState();

					if (managedOperatorState == null) {
						managedOperatorState = new ArrayList<>(parallelism * managed.size());
					}
					managedOperatorState.addAll(managed);

					if (rawOperatorState == null) {
						rawOperatorState = new ArrayList<>(parallelism * raw.size());
					}
					rawOperatorState.addAll(raw);
				}
			}
			managedOperatorStates.add(managedOperatorState);
			rawOperatorStates.add(rawOperatorState);
		}
	}

	/**
	 * Collect {@link KeyGroupsStateHandle  managedKeyedStateHandles} which have intersection with given
	 * {@link KeyGroupRange} from {@link TaskState operatorState}
	 *
	 * @param operatorState        all state handles of a operator
	 * @param subtaskKeyGroupRange the KeyGroupRange of a subtask
	 * @return all managedKeyedStateHandles which have intersection with given KeyGroupRange
	 */
	public static List<KeyedStateHandle> getManagedKeyedStateHandles(
		OperatorState operatorState,
		KeyGroupRange subtaskKeyGroupRange) {

		final int parallelism = operatorState.getParallelism();

		List<KeyedStateHandle> subtaskKeyedStateHandles = null;

		for (int i = 0; i < parallelism; i++) {
			if (operatorState.getState(i) != null) {

				Collection<KeyedStateHandle> keyedStateHandles = operatorState.getState(i).getManagedKeyedState();

				if (subtaskKeyedStateHandles == null) {
					subtaskKeyedStateHandles = new ArrayList<>(parallelism * keyedStateHandles.size());
				}

				extractIntersectingState(
					keyedStateHandles,
					subtaskKeyGroupRange,
					subtaskKeyedStateHandles);
			}
		}

		return subtaskKeyedStateHandles;
	}

	public static List<KeyedStateHandle>[] reDistributeLocalKeyedStates(
			OperatorState operatorState,
			int newParallelism) {
		if (!hasLocalKeyedState(operatorState)) {
			return new List[0];
		}

		List<KeyedStateHandle>[] reDistributed = new List[newParallelism];

		List<LocalKeyedStateHandle> allLocalKeyedStateHandles = new ArrayList<>();
		final int oldParallelism = operatorState.getParallelism();
		for (int i = 0; i < oldParallelism; i++) {
			if (operatorState.getState(i) != null) {
				for (KeyedStateHandle keyedStateHandle : operatorState.getState(i).getManagedKeyedState()) {
					Preconditions.checkArgument(keyedStateHandle instanceof LocalKeyedStateHandle,
						"Unexpected state handle type, " +
							"expected: " + LocalKeyedStateHandle.class +
							", but found: " + keyedStateHandle.getClass());
					allLocalKeyedStateHandles.add((LocalKeyedStateHandle) keyedStateHandle);
				}
			}
		}

		if (!allLocalKeyedStateHandles.isEmpty()) {
			final int numKeyGroupsPerHandle =
				allLocalKeyedStateHandles.get(0).getKeyGroupRange().getNumberOfKeyGroups();
			final int totalNumOfKeyGroups = numKeyGroupsPerHandle * allLocalKeyedStateHandles.size();

			final int numKeyGroupsPerSubTask = totalNumOfKeyGroups / newParallelism;

			for (int subTaskId = 0, firstKGIdxInAllHandles = 0; subTaskId < newParallelism; subTaskId++) {
				reDistributed[subTaskId] = new ArrayList<>(numKeyGroupsPerSubTask);
				boolean lastSubTask = subTaskId == newParallelism - 1;

				int lastKGIdxInAllHandles = lastSubTask ?
					totalNumOfKeyGroups - 1 :
					firstKGIdxInAllHandles + numKeyGroupsPerSubTask - 1;

				int firstHandleIdx = firstKGIdxInAllHandles / numKeyGroupsPerHandle;
				int lastHandleIdx = lastKGIdxInAllHandles / numKeyGroupsPerHandle;

				int firstKGIdxInHandle = firstKGIdxInAllHandles % numKeyGroupsPerHandle;
				int lastKGIdxInHandle = lastKGIdxInAllHandles % numKeyGroupsPerHandle;

				if (firstHandleIdx == lastHandleIdx) {
					// only one state handle
					KeyGroupRange kgRange = new KeyGroupRange(firstKGIdxInHandle, lastKGIdxInHandle);
					reDistributed[subTaskId].add(
						allLocalKeyedStateHandles.get(firstHandleIdx).getIntersection(kgRange));
				} else {
					// multi state handles
					for (int handleIdx = firstHandleIdx; handleIdx <= lastHandleIdx; handleIdx++) {
						boolean firstHandle = handleIdx == firstHandleIdx;
						boolean lastHandle = handleIdx == lastHandleIdx;
						if (firstHandle) {
							KeyGroupRange kgRange = new KeyGroupRange(firstKGIdxInHandle,
								allLocalKeyedStateHandles.get(handleIdx).getKeyGroupRange().getEndKeyGroup());
							reDistributed[subTaskId].add(
								allLocalKeyedStateHandles.get(handleIdx).getIntersection(kgRange));
						} else if (lastHandle) {
							KeyGroupRange kgRange = new KeyGroupRange(0, lastKGIdxInHandle);
							reDistributed[subTaskId].add(
								allLocalKeyedStateHandles.get(handleIdx).getIntersection(kgRange));
						} else {
							reDistributed[subTaskId].add(allLocalKeyedStateHandles.get(handleIdx));
						}
					}

				}

				firstKGIdxInAllHandles += numKeyGroupsPerSubTask;
			}
		}

		return reDistributed;
	}

	private static boolean hasLocalKeyedState(OperatorState operatorState) {
		return operatorState != null && operatorState.getState(0) != null
			&& operatorState.getState(0).getManagedKeyedState().hasState()
			&& operatorState.getState(0).getManagedKeyedState().asList().get(0)
				instanceof LocalKeyedStateHandle;
	}

	/**
	 * Collect {@link KeyGroupsStateHandle  rawKeyedStateHandles} which have intersection with given
	 * {@link KeyGroupRange} from {@link TaskState operatorState}
	 *
	 * @param operatorState        all state handles of a operator
	 * @param subtaskKeyGroupRange the KeyGroupRange of a subtask
	 * @return all rawKeyedStateHandles which have intersection with given KeyGroupRange
	 */
	public static List<KeyedStateHandle> getRawKeyedStateHandles(
		OperatorState operatorState,
		KeyGroupRange subtaskKeyGroupRange) {

		final int parallelism = operatorState.getParallelism();

		List<KeyedStateHandle> extractedKeyedStateHandles = null;

		for (int i = 0; i < parallelism; i++) {
			if (operatorState.getState(i) != null) {

				Collection<KeyedStateHandle> rawKeyedState = operatorState.getState(i).getRawKeyedState();

				if (extractedKeyedStateHandles == null) {
					extractedKeyedStateHandles = new ArrayList<>(parallelism * rawKeyedState.size());
				}

				extractIntersectingState(
					rawKeyedState,
					subtaskKeyGroupRange,
					extractedKeyedStateHandles);
			}
		}

		return extractedKeyedStateHandles;
	}

	/**
	 * Extracts certain key group ranges from the given state handles and adds them to the collector.
	 */
	private static void extractIntersectingState(
		Collection<KeyedStateHandle> originalSubtaskStateHandles,
		KeyGroupRange rangeToExtract,
		List<KeyedStateHandle> extractedStateCollector) {

		for (KeyedStateHandle keyedStateHandle : originalSubtaskStateHandles) {

			if (keyedStateHandle != null) {

				KeyedStateHandle intersectedKeyedStateHandle = keyedStateHandle.getIntersection(rangeToExtract);

				if (intersectedKeyedStateHandle != null) {
					extractedStateCollector.add(intersectedKeyedStateHandle);
				}
			}
		}
	}

	/**
	 * Groups the available set of key groups into key group partitions. A key group partition is
	 * the set of key groups which is assigned to the same task. Each set of the returned list
	 * constitutes a key group partition.
	 * <p>
	 * <b>IMPORTANT</b>: The assignment of key groups to partitions has to be in sync with the
	 * KeyGroupStreamPartitioner.
	 *
	 * @param numberKeyGroups Number of available key groups (indexed from 0 to numberKeyGroups - 1)
	 * @param parallelism     Parallelism to generate the key group partitioning for
	 * @return List of key group partitions
	 */
	public static List<KeyGroupRange> createKeyGroupPartitions(int numberKeyGroups, int parallelism) {
		Preconditions.checkArgument(numberKeyGroups >= parallelism);
		List<KeyGroupRange> result = new ArrayList<>(parallelism);

		for (int i = 0; i < parallelism; ++i) {
			result.add(KeyGroupRangeAssignment.computeKeyGroupRangeForOperatorIndex(numberKeyGroups, parallelism, i));
		}
		return result;
	}

	/**
	 * Verifies conditions in regards to parallelism and maxParallelism that must be met when restoring state.
	 *
	 * @param operatorState      state to restore
	 * @param executionJobVertex task for which the state should be restored
	 */
	private static void checkParallelismPreconditions(OperatorState operatorState, ExecutionJobVertex executionJobVertex) {
		//----------------------------------------max parallelism preconditions-------------------------------------

		if (operatorState.getMaxParallelism() < executionJobVertex.getParallelism()) {
			throw new IllegalStateException("The state for task " + executionJobVertex.getJobVertexId() +
				" can not be restored. The maximum parallelism (" + operatorState.getMaxParallelism() +
				") of the restored state is lower than the configured parallelism (" + executionJobVertex.getParallelism() +
				"). Please reduce the parallelism of the task to be lower or equal to the maximum parallelism."
			);
		}

		// check that the number of key groups have not changed or if we need to override it to satisfy the restored state
		if (operatorState.getMaxParallelism() != executionJobVertex.getMaxParallelism()) {

			if (!executionJobVertex.isMaxParallelismConfigured()) {
				// if the max parallelism was not explicitly specified by the user, we derive it from the state

				LOG.debug("Overriding maximum parallelism for JobVertex {} from {} to {}",
					executionJobVertex.getJobVertexId(), executionJobVertex.getMaxParallelism(), operatorState.getMaxParallelism());

				executionJobVertex.setMaxParallelism(operatorState.getMaxParallelism());
			} else {
				// if the max parallelism was explicitly specified, we complain on mismatch
				throw new IllegalStateException("The maximum parallelism (" +
					operatorState.getMaxParallelism() + ") with which the latest " +
					"checkpoint of the execution job vertex " + executionJobVertex +
					" has been taken and the current maximum parallelism (" +
					executionJobVertex.getMaxParallelism() + ") changed. This " +
					"is currently not supported.");
			}
		}
	}

	/**
	 * Verifies that all operator states can be mapped to an execution job vertex.
	 *
	 * @param allowNonRestoredState if false an exception will be thrown if a state could not be mapped
	 * @param operatorStates operator states to map
	 * @param tasks task to map to
	 */
	private static void checkStateMappingCompleteness(
			boolean allowNonRestoredState,
			Map<OperatorID, OperatorState> operatorStates,
			Map<JobVertexID, ExecutionJobVertex> tasks) {

		Set<OperatorID> allOperatorIDs = new HashSet<>();
		for (ExecutionJobVertex executionJobVertex : tasks.values()) {
			allOperatorIDs.addAll(executionJobVertex.getOperatorIDs());
		}
		for (Map.Entry<OperatorID, OperatorState> operatorGroupStateEntry : operatorStates.entrySet()) {
			OperatorState operatorState = operatorGroupStateEntry.getValue();
			//----------------------------------------find operator for state---------------------------------------------

			if (!allOperatorIDs.contains(operatorGroupStateEntry.getKey())) {
				if (allowNonRestoredState) {
					LOG.info("Skipped checkpoint state for operator {}.", operatorState.getOperatorID());
				} else {
					throw new IllegalStateException("There is no operator for the state " + operatorState.getOperatorID());
				}
			}
		}
	}

	public static Map<OperatorInstanceID, List<OperatorStateHandle>> applyRepartitioner(
			OperatorID operatorID,
			OperatorStateRepartitioner opStateRepartitioner,
			List<OperatorStateHandle> chainOpParallelStates,
			int oldParallelism,
			int newParallelism) {

		List<List<OperatorStateHandle>> states = applyRepartitioner(
			opStateRepartitioner,
			chainOpParallelStates,
			oldParallelism,
			newParallelism);

		Map<OperatorInstanceID, List<OperatorStateHandle>> result = new HashMap<>(states.size());

		for (int subtaskIndex = 0; subtaskIndex < states.size(); subtaskIndex++) {
			checkNotNull(states.get(subtaskIndex) != null, "states.get(subtaskIndex) is null");
			result.put(OperatorInstanceID.of(subtaskIndex, operatorID), states.get(subtaskIndex));
		}

		return result;
	}

	/**
	 * Repartitions the given operator state using the given {@link OperatorStateRepartitioner} with respect to the new
	 * parallelism.
	 *
	 * @param opStateRepartitioner  partitioner to use
	 * @param chainOpParallelStates state to repartition
	 * @param oldParallelism        parallelism with which the state is currently partitioned
	 * @param newParallelism        parallelism with which the state should be partitioned
	 * @return repartitioned state
	 */
	// TODO rewrite based on operator id
	public static List<List<OperatorStateHandle>> applyRepartitioner(
			OperatorStateRepartitioner opStateRepartitioner,
			List<OperatorStateHandle> chainOpParallelStates,
			int oldParallelism,
			int newParallelism) {

		if (chainOpParallelStates == null) {
			return Collections.emptyList();
		}

		//We only redistribute if the parallelism of the operator changed from previous executions
		if (newParallelism != oldParallelism) {

			return opStateRepartitioner.repartitionState(
					chainOpParallelStates,
					newParallelism);
		} else {
			List<List<OperatorStateHandle>> repackStream = new ArrayList<>(newParallelism);
			for (OperatorStateHandle operatorStateHandle : chainOpParallelStates) {

				if (operatorStateHandle != null) {
					Map<String, OperatorStateHandle.StateMetaInfo> partitionOffsets =
						operatorStateHandle.getStateNameToPartitionOffsets();

					for (OperatorStateHandle.StateMetaInfo metaInfo : partitionOffsets.values()) {

						// if we find any broadcast state, we cannot take the shortcut and need to go through repartitioning
						if (OperatorStateHandle.Mode.UNION.equals(metaInfo.getDistributionMode())) {
							return opStateRepartitioner.repartitionState(
								chainOpParallelStates,
								newParallelism);
						}
					}

					repackStream.add(Collections.singletonList(operatorStateHandle));
				}
			}
			return repackStream;
		}
	}

	/**
	 * Determine the subset of {@link KeyGroupsStateHandle KeyGroupsStateHandles} with correct
	 * key group index for the given subtask {@link KeyGroupRange}.
	 *
	 * <p>This is publicly visible to be used in tests.
	 */
	public static List<KeyedStateHandle> getKeyedStateHandles(
		Collection<? extends KeyedStateHandle> keyedStateHandles,
		KeyGroupRange subtaskKeyGroupRange) {

		List<KeyedStateHandle> subtaskKeyedStateHandles = new ArrayList<>(keyedStateHandles.size());

		for (KeyedStateHandle keyedStateHandle : keyedStateHandles) {
			KeyedStateHandle intersectedKeyedStateHandle = keyedStateHandle.getIntersection(subtaskKeyGroupRange);

			if (intersectedKeyedStateHandle != null) {
				subtaskKeyedStateHandles.add(intersectedKeyedStateHandle);
			}
		}

		return subtaskKeyedStateHandles;
	}
}
