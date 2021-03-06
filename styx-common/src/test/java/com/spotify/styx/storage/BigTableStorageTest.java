/*-
 * -\-\-
 * Spotify Styx Common
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

package com.spotify.styx.storage;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

import com.spotify.styx.model.Event;
import com.spotify.styx.model.ExecStatus;
import com.spotify.styx.model.SequenceEvent;
import com.spotify.styx.model.WorkflowId;
import com.spotify.styx.model.WorkflowInstance;
import com.spotify.styx.model.WorkflowInstanceExecutionData;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.hadoop.hbase.client.Connection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BigTableStorageTest {

  private static final String PARAMETER1 = "2016-01-01";
  private static final String PARAMETER2 = "2016-01-02";

  private static final WorkflowId WORKFLOW_ID1 = WorkflowId.create("component", "endpoint1");
  private static final WorkflowInstance WFI1 = WorkflowInstance.create(WORKFLOW_ID1, PARAMETER1);
  private static final WorkflowInstance WFI2 = WorkflowInstance.create(WORKFLOW_ID1, PARAMETER2);

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private BigtableStorage storage;

  public void setUp(int numFailures) throws Exception {
    Connection bigtable = setupBigTableMockTable(numFailures);
    storage = new BigtableStorage(bigtable, Duration.ZERO);
  }

  private Connection setupBigTableMockTable(int numFailures) throws IOException {
    Connection bigtable = mock(Connection.class);
    new BigtableMocker(bigtable)
        .setNumFailures(numFailures)
        .setupTable(BigtableStorage.EVENTS_TABLE_NAME)
        .finalizeMocking();
    return bigtable;
  }

  @Test
  public void shouldReturnExecutionDataForWorkflowInstance() throws Exception {
    setUp(0);
    storage.writeEvent(SequenceEvent.create(Event.triggerExecution(WFI1, "triggerId"), 0L, 0L));
    storage.writeEvent(SequenceEvent.create(Event.created(WFI1, "execId", "img"), 1L, 1L));
    storage.writeEvent(SequenceEvent.create(Event.started(WFI1), 2L, 2L));

    WorkflowInstanceExecutionData workflowInstanceExecutionData = storage.executionData(WFI1);
    assertThat(workflowInstanceExecutionData.triggers().get(0).triggerId(), is("triggerId"));
    assertThat(workflowInstanceExecutionData.triggers().get(0).executions().get(0).executionId(), is("execId"));
    assertThat(workflowInstanceExecutionData.triggers().get(0).executions().get(0).dockerImage(), is("img"));
    assertThat(workflowInstanceExecutionData.triggers().get(0).executions().get(0).statuses().get(0), is(
        ExecStatus.create(Instant.ofEpochMilli(1L), "SUBMITTED")));
    assertThat(workflowInstanceExecutionData.triggers().get(0).executions().get(0).statuses().get(1), is(
        ExecStatus.create(Instant.ofEpochMilli(2L), "STARTED")));
  }

  @Test
  public void shouldReturnExecutionDataForWorkflow() throws Exception {
    setUp(0);
    storage.writeEvent(SequenceEvent.create(Event.triggerExecution(WFI1, "triggerId1"), 0L, 0L));
    storage.writeEvent(SequenceEvent.create(Event.created(WFI1, "execId1", "img1"), 1L, 1L));
    storage.writeEvent(SequenceEvent.create(Event.started(WFI1), 2L, 2L));

    storage.writeEvent(SequenceEvent.create(Event.triggerExecution(WFI2, "triggerId2"), 0L, 3L));
    storage.writeEvent(SequenceEvent.create(Event.created(WFI2, "execId2", "img2"), 1L, 4L));
    storage.writeEvent(SequenceEvent.create(Event.started(WFI2), 2L, 5L));

    List<WorkflowInstanceExecutionData> workflowInstanceExecutionData = storage.executionData(WORKFLOW_ID1);
    assertThat(workflowInstanceExecutionData.size(), is(2));

    assertThat(workflowInstanceExecutionData.get(0).triggers().get(0).triggerId(), is("triggerId1"));
    assertThat(workflowInstanceExecutionData.get(0).triggers().get(0).executions().get(0).executionId(), is("execId1"));
    assertThat(workflowInstanceExecutionData.get(0).triggers().get(0).executions().get(0).dockerImage(), is("img1"));
    assertThat(workflowInstanceExecutionData.get(0).triggers().get(0).executions().get(0).statuses()
                   .get(0), is(ExecStatus.create(Instant.ofEpochMilli(1L), "SUBMITTED")));
    assertThat(workflowInstanceExecutionData.get(0).triggers().get(0).executions().get(0).statuses()
                   .get(1), is(ExecStatus.create(Instant.ofEpochMilli(2L), "STARTED")));
    assertThat(workflowInstanceExecutionData.get(1).triggers().get(0).triggerId(), is("triggerId2"));
    assertThat(workflowInstanceExecutionData.get(1).triggers().get(0).executions().get(0).executionId(), is("execId2"));
    assertThat(workflowInstanceExecutionData.get(1).triggers().get(0).executions().get(0).dockerImage(), is("img2"));
    assertThat(workflowInstanceExecutionData.get(1).triggers().get(0).executions().get(0).statuses()
                   .get(0), is(ExecStatus.create(Instant.ofEpochMilli(4L), "SUBMITTED")));
    assertThat(workflowInstanceExecutionData.get(1).triggers().get(0).executions().get(0).statuses()
                   .get(1), is(ExecStatus.create(Instant.ofEpochMilli(5L), "STARTED")));

  }

  @Test
  public void shouldProduceIOExceptionIfTooManyPutRetries() throws Exception {
    setUp(BigtableStorage.MAX_BIGTABLE_RETRIES);

    thrown.expect(IOException.class);
    thrown.expectMessage(containsString("Something went wrong in performing put operation"));

    storage.writeEvent(SequenceEvent.create(Event.success(WFI1), 1, 0));
  }

  @Test
  public void shouldNotProduceIOExceptionIfPutRetrySucceeds() throws Exception {
    setUp(BigtableStorage.MAX_BIGTABLE_RETRIES - 1);

    storage.writeEvent(SequenceEvent.create(Event.success(WFI1), 1, 0));
  }
}
