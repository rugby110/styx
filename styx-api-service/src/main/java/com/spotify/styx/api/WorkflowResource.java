/*-
 * -\-\-
 * Spotify Styx API Service
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

package com.spotify.styx.api;

import static com.spotify.styx.api.Middlewares.json;
import static com.spotify.styx.util.StreamUtil.cat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.spotify.apollo.Request;
import com.spotify.apollo.RequestContext;
import com.spotify.apollo.Response;
import com.spotify.apollo.Status;
import com.spotify.apollo.route.AsyncHandler;
import com.spotify.apollo.route.Route;
import com.spotify.styx.model.Workflow;
import com.spotify.styx.model.WorkflowId;
import com.spotify.styx.model.WorkflowInstance;
import com.spotify.styx.model.WorkflowInstanceExecutionData;
import com.spotify.styx.model.WorkflowState;
import com.spotify.styx.storage.Storage;
import com.spotify.styx.util.Json;
import com.spotify.styx.util.ResourceNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import okio.ByteString;

public final class WorkflowResource {

  public static final String BASE = "/workflows";
  public static final ObjectMapper OBJECT_MAPPER = Json.OBJECT_MAPPER;

  private final Storage storage;

  public WorkflowResource(Storage storage) {
    this.storage = Objects.requireNonNull(storage);
  }

  public Stream<? extends Route<? extends AsyncHandler<? extends Response<ByteString>>>> routes() {
    final List<Route<AsyncHandler<Response<ByteString>>>> v0 = Arrays.asList(
        Route.with(
            json(), "GET", BASE + "/<cid>/<eid>",
            rc -> workflow(arg("cid", rc), arg("eid", rc))),
        Route.with(
            json(), "GET", BASE + "/<cid>/<eid>/instances",
            rc -> Response.forStatus(Status.NOT_FOUND.withReasonPhrase("Use v1 api"))),
        Route.with(
            json(), "GET", BASE + "/<cid>/<eid>/instances/<iid>",
            rc -> Response.forStatus(Status.NOT_FOUND.withReasonPhrase("Use v1 api"))),
        Route.with(
            json(), "GET", BASE + "/<cid>/<eid>/state",
            rc -> state(arg("cid", rc), arg("eid", rc))),
        Route.with(
            json(), "PATCH", BASE + "/<cid>/<eid>/state",
            rc -> patchState(arg("cid", rc), arg("eid", rc), rc.request())),
        Route.with(
            json(), "PATCH", BASE + "/<cid>/state",
            rc -> patchState(arg("cid", rc), rc.request()))
    );

    final List<Route<AsyncHandler<Response<ByteString>>>> v1 = Arrays.asList(
        Route.with(
            json(), "GET", BASE + "/<cid>/<eid>/instances",
            rc -> instances(arg("cid", rc), arg("eid", rc))),
        Route.with(
            json(), "GET", BASE + "/<cid>/<eid>/instances/<iid>",
            rc -> instance(arg("cid", rc), arg("eid", rc), arg("iid", rc)))
    );

    return cat(
        v0.stream().map(r -> r.withPrefix(Api.Version.V0.prefix())),
        v0.stream().map(r -> r.withPrefix(Api.Version.V1.prefix())),
        v1.stream().map(r -> r.withPrefix(Api.Version.V1.prefix()))
    );
  }

  private Response<WorkflowState> patchState(String componentId, String endpointId, Request request) {
    final Optional<ByteString> payload = request.payload();
    if (!payload.isPresent()) {
      return Response.forStatus(Status.BAD_REQUEST.withReasonPhrase("Missing payload."));
    }

    WorkflowId workflowId = WorkflowId.create(componentId, endpointId);
    WorkflowState patchState;
    try {
      patchState = OBJECT_MAPPER.readValue(payload.get().toByteArray(), WorkflowState.class);
    } catch (IOException e) {
      return Response.forStatus(Status.BAD_REQUEST.withReasonPhrase("Invalid payload."));
    }

    if (patchState.commitSha().isPresent()) {
      if (!isValidSHA1(patchState.commitSha().get())) {
        return Response.forStatus(Status.BAD_REQUEST.withReasonPhrase("Invalid SHA-1."));
      }
    }

    try {
      storage.patchState(workflowId, patchState);
    } catch (ResourceNotFoundException e) {
      return Response
          .forStatus(Status.NOT_FOUND.withReasonPhrase(e.getMessage()));
    } catch (IOException e) {
      return Response
          .forStatus(
              Status.INTERNAL_SERVER_ERROR.withReasonPhrase("Failed to update the state."));
    }

    return state(componentId, endpointId);
  }

  private Response<WorkflowState> patchState(String componentId, Request request) {
    final Optional<ByteString> payload = request.payload();
    if (!payload.isPresent()) {
      return Response.forStatus(Status.BAD_REQUEST.withReasonPhrase("Missing payload."));
    }

    WorkflowState patchState;
    try {
      patchState = OBJECT_MAPPER.readValue(payload.get().toByteArray(), WorkflowState.class);
    } catch (IOException e) {
      return Response.forStatus(Status.BAD_REQUEST.withReasonPhrase("Invalid payload."));
    }

    if (patchState.enabled().isPresent()) {
      return Response.forStatus(Status.BAD_REQUEST.withReasonPhrase("Enabled flag not supported "
                                                                    + "for components."));
    }

    if (patchState.commitSha().isPresent()) {
      if (!isValidSHA1(patchState.commitSha().get())) {
        return Response.forStatus(Status.BAD_REQUEST.withReasonPhrase("Invalid SHA-1."));
      }
    }

    try {
      storage.patchState(componentId, patchState);
    } catch (ResourceNotFoundException e) {
      return Response
          .forStatus(Status.NOT_FOUND.withReasonPhrase(e.getMessage()));
    } catch (IOException e) {
      return Response
          .forStatus(
              Status.INTERNAL_SERVER_ERROR.withReasonPhrase("Failed to update the state."));
    }

    return Response.forPayload(patchState);
  }

  private Response<Workflow> workflow(String componentId, String endpointId) {
    final WorkflowId workflowId = WorkflowId.create(componentId, endpointId);
    final Optional<Workflow> workflowOpt;
    try {
      workflowOpt = storage.workflow(workflowId);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }

    if (!workflowOpt.isPresent()) {
      return Response.forStatus(Status.NOT_FOUND);
    }

    return Response.forPayload(workflowOpt.get());
  }

  private Response<WorkflowState> state(String componentId, String endpointId) {
    final WorkflowId workflowId = WorkflowId.create(componentId, endpointId);
    WorkflowState workflowState;
    try {
      workflowState = storage.workflowState(workflowId);
    } catch (IOException e) {
      return Response
          .forStatus(Status.INTERNAL_SERVER_ERROR.withReasonPhrase("Couldn't fetch state."));
    }
    return Response.forPayload(workflowState);
  }

  private Response<List<WorkflowInstanceExecutionData>> instances(String componentId, String endpointId) {
    final WorkflowId workflowId = WorkflowId.create(componentId, endpointId);
    final List<WorkflowInstanceExecutionData> data;

    try {
      data = storage.executionData(workflowId);
    } catch (IOException e) {
      return Response.forStatus(
          Status.INTERNAL_SERVER_ERROR.withReasonPhrase("Couldn't fetch execution info."));
    }
    return Response.forPayload(data);
  }

  private Response<WorkflowInstanceExecutionData> instance(
      String componentId,
      String endpointId,
      String instanceId) {
    final WorkflowId workflowId = WorkflowId.create(componentId, endpointId);
    final WorkflowInstance workflowInstance = WorkflowInstance.create(workflowId, instanceId);

    try {
      final WorkflowInstanceExecutionData  workflowInstanceExecutionData =
          storage.executionData(workflowInstance);

      return Response.forPayload(workflowInstanceExecutionData);
    } catch (IOException e) {
      return Response.forStatus(
          Status.INTERNAL_SERVER_ERROR.withReasonPhrase("Couldn't fetch execution info."));
    }
  }

  private static boolean isValidSHA1(String s) {
    return s.matches("[a-fA-F0-9]{40}");
  }

  private static String arg(String name, RequestContext rc) {
    return rc.pathArgs().get(name);
  }
}
