/*-
 * -\-\-
 * Spotify Styx Scheduler Service
 * --
 * Copyright (C) 2016 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.styx.state;

import com.google.common.collect.Maps;
import com.spotify.styx.model.Event;
import com.spotify.styx.model.WorkflowId;
import com.spotify.styx.model.WorkflowInstance;
import java.util.Map;

/**
 * An implementation of {@link StateManager} that process all events synchronously on the thread
 * that calls {@link #receive(Event)}.
 *
 * This class is not thread safe.
 */
public class SyncStateManager implements StateManager {

  private final Map<WorkflowInstance, RunState> states = Maps.newHashMap();

  @Override
  public void initialize(RunState runState) {
    states.put(runState.workflowInstance(), runState);
  }

  @Override
  public void restore(RunState runState, long count) {
    initialize(runState);
  }

  @Override
  public void receive(Event event) {
    WorkflowInstance key = event.workflowInstance();
    RunState currentState = states.get(key);

    RunState nextState = currentState.transition(event);
    states.put(key, nextState);

    nextState.outputHandler().transitionInto(nextState);
  }

  @Override
  public long getActiveStatesCount() {
    return states.size();
  }

  @Override
  public long getQueuedEventsCount() {
    return 0; // synchronous event handling, no queue
  }

  @Override
  public long getActiveStatesCount(WorkflowId workflowId) {
    return states
        .keySet()
        .stream()
        .filter(workflowInstance -> workflowInstance.workflowId().equals(workflowId))
        .count();
  }

  @Override
  public boolean isActiveWorkflowInstance(WorkflowInstance workflowInstance) {
    return states.containsKey(workflowInstance);
  }

  @Override
  public RunState get(WorkflowInstance workflowInstance) {
    return states.get(workflowInstance);
  }

  public int activeStatesSize() {
    return states.size();
  }

  @Override
  public void close() {
  }
}
