/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.runners.worker;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.dataflow.model.SourceOperationResponse;
import com.google.api.services.dataflow.model.SourceSplitResponse;
import com.google.api.services.dataflow.model.SourceSplitShard;
import com.google.api.services.dataflow.model.WorkItem;
import com.google.api.services.dataflow.model.WorkItemStatus;
import com.google.cloud.dataflow.sdk.options.DataflowWorkerHarnessOptions;
import com.google.cloud.dataflow.sdk.testing.FastNanoClockAndSleeper;
import com.google.cloud.dataflow.sdk.util.common.worker.StateSampler;
import com.google.cloud.dataflow.sdk.util.common.worker.WorkExecutor;
import com.google.common.collect.ImmutableList;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

/** Unit tests for {@link DataflowWorker}. */
@RunWith(JUnit4.class)
public class DataflowWorkerTest {

  private class WorkerException extends Exception { }

  @Rule
  public FastNanoClockAndSleeper clockAndSleeper = new FastNanoClockAndSleeper();

  @Mock
  DataflowWorker.WorkUnitClient mockWorkUnitClient;

  @Mock
  DataflowWorkerHarnessOptions options;

  @Mock
  DataflowWorkProgressUpdater mockProgressUpdater;

  @Mock
  WorkExecutor mockWorkExecutor;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void testWhenNoWorkThatWeReturnFalse() throws Exception {
    DataflowWorker worker = new DataflowWorker(mockWorkUnitClient, options);
    when(mockWorkUnitClient.getWorkItem()).thenReturn(null);

    assertFalse(worker.getAndPerformWork());
  }

  @Test
  public void testWhenProcessingWorkUnitFailsWeReportStatus() throws Exception {
    DataflowWorker worker = new DataflowWorker(mockWorkUnitClient, options);
    // In practice this value is always 1, but for the sake of testing send a different value.
    long initialReportIndex = 4L;
    WorkItem workItem = new WorkItem()
        .setId(1L).setJobId("Expected to fail the job").setInitialReportIndex(initialReportIndex);
    when(mockWorkUnitClient.getWorkItem()).thenReturn(workItem).thenReturn(null);

    assertFalse(worker.getAndPerformWork());
    verify(mockWorkUnitClient)
        .reportWorkItemStatus(argThat(cloudWorkHasErrors(initialReportIndex)));
  }

  @Test
  public void testStartAndStopProgressReport() throws Exception {
    DataflowWorker worker = new DataflowWorker(mockWorkUnitClient, options);
    worker.executeWork(mockWorkExecutor, mockProgressUpdater);
    verify(mockProgressUpdater, times(1)).startReportingProgress();
    verify(mockProgressUpdater, times(1)).stopReportingProgress();
  }

  @Test
  public void testStopProgressReportInCaseOfFailure() throws Exception {
    doThrow(new WorkerException()).when(mockWorkExecutor).execute();
    DataflowWorker worker = new DataflowWorker(mockWorkUnitClient, options);
    try {
      worker.executeWork(mockWorkExecutor, mockProgressUpdater);
    } catch (WorkerException e) { /* Expected - ignore. */ }
      verify(mockProgressUpdater, times(1)).stopReportingProgress();
  }

  @Test
  public void testHealthzHandler() throws Exception {
    String response = testStatusServer(
        "GET /healthz HTTP/1.1\nhost: localhost\n\n");
    assertThat(response, containsString("HTTP/1.1 200 OK"));
    assertThat(response, containsString("ok"));
  }

  @Test
  public void testThreadzHandler() throws Exception {
    String response = testStatusServer(
        "GET /threadz HTTP/1.1\nhost: localhost\n\n");
    assertThat(response, containsString("HTTP/1.1 200 OK"));
    assertThat(response, containsString("--- Thread: "));
    // testThreadzHandler should be somewhere in the stack trace of one of the threads.
    assertThat(response, containsString("testThreadzHandler"));
  }

  @Test
  public void testUnknownHandler() throws Exception {
    String response = testStatusServer(
        "GET /missinghandlerz HTTP/1.1\nhost: localhost\n\n");
    assertThat(response, containsString("HTTP/1.1 404 Not Found"));
  }

  @Test
  public void testIsSplitResponseTooLarge() {
    SourceSplitResponse splitResponse = new SourceSplitResponse();
    splitResponse.setShards(
        ImmutableList.<SourceSplitShard>of(new SourceSplitShard(), new SourceSplitShard()));
    assertTrue(
        SourceOperationExecutor.determineSplitResponseSize(
            new SourceOperationResponse().setSplit(splitResponse)) > 0);
  }

  @Test
  public void testWorkItemStatusWithStateSamplerInfo() throws Exception {
    WorkItem workItem = new WorkItem()
        .setId(1L).setJobId("jobid").setInitialReportIndex(4L);
    WorkItemStatus status = DataflowWorker.buildStatus(workItem, false,
        null, null, options, null, null, null, null, 0,
        new StateSampler.StateSamplerInfo("state", 101L, null));
    assertEquals(1, status.getMetricUpdates().size());
    assertEquals("internal", status.getMetricUpdates().get(0).getKind());
    assertEquals("state-sampler", status.getMetricUpdates().get(0).getName().getName());
    @SuppressWarnings("unchecked")
    Map<String, Object> metric =
        (Map<String, Object>) status.getMetricUpdates().get(0).getInternal();
    assertEquals("state", metric.get("last-state-name"));
    assertEquals(101L, metric.get("num-transitions"));
  }

  private String testStatusServer(String request) throws Exception {
    Server server = new Server();
    LocalConnector connector = new LocalConnector(server);
    try {
      DataflowWorker worker = new DataflowWorker(mockWorkUnitClient, options);
      worker.runStatusServer(server);
      connector.start();
      return connector.getResponses(request);
    } finally {
      connector.stop();
      connector.join();
      server.stop();
      server.join();
    }
  }

  private Matcher<WorkItemStatus> cloudWorkHasErrors(final long expectedReportIndex) {
    return new TypeSafeMatcher<WorkItemStatus>() {
      @Override
      public void describeTo(Description description) {
        description.appendText("WorkItemStatus expected to have errors");
      }

      @Override
      protected boolean matchesSafely(WorkItemStatus status) {
        assertEquals(expectedReportIndex, (long) status.getReportIndex());
        boolean returnValue = status.getCompleted() && !status.getErrors().isEmpty();
        if (returnValue) {
          assertThat(status.getErrors().get(0).getMessage(),
              CoreMatchers.containsString("java.lang.RuntimeException: Unknown kind of work"));
        }
        return returnValue;
      }
    };
  }
}
