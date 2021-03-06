/*-
 * -\-\-
 * Spotify Styx CLI
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

package com.spotify.styx.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.base.Throwables;
import com.spotify.apollo.Client;
import com.spotify.apollo.Request;
import com.spotify.apollo.core.Service;
import com.spotify.apollo.core.Services;
import com.spotify.apollo.environment.ApolloEnvironmentModule;
import com.spotify.apollo.http.client.HttpClientModule;
import com.spotify.styx.api.cli.ActiveStatesPayload;
import com.spotify.styx.api.cli.EventsPayload;
import com.spotify.styx.model.Event;
import com.spotify.styx.model.EventSerializer;
import com.spotify.styx.model.WorkflowId;
import com.spotify.styx.model.WorkflowInstance;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import net.sourceforge.argparse4j.inf.Subparsers;
import net.sourceforge.argparse4j.internal.HelpScreenException;
import okio.ByteString;

public final class Main {

  private static final String UTF_8 = "UTF-8";
  private static final String ENV_VAR_PREFIX = "STYX_CLI";
  private static final String STYX_CLI_API_ENDPOINT = "/api/v1/cli";
  private static final int TTL_REQUEST = 90;

  private static final String COMMAND_DEST = "command";
  private static final String COMPONENT_DEST = "component";
  private static final String WORKFLOW_DEST = "workflow";
  private static final String PARAMETER_DEST = "parameter";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
      .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
      .registerModule(new Jdk8Module());

  private static final int EXIT_CODE_SUCCESS = 0;
  private static final int EXIT_CODE_API_ERROR = 1;
  private static final int EXIT_CODE_ARGUMENT_ERROR = 2;

  private final StyxCliParser parser;
  private final Namespace namespace;
  private final String apiHost;
  private final Service cliService;
  private final CliOutput cliOutput;

  private BiConsumer<Request, Consumer<byte[]>> client;

  private Main(
      StyxCliParser parser,
      Namespace namespace,
      String apiHost,
      Service cliService,
      CliOutput cliOutput) {
    this.parser = Objects.requireNonNull(parser);
    this.namespace = Objects.requireNonNull(namespace);
    this.apiHost = Objects.requireNonNull(apiHost);
    this.cliService = Objects.requireNonNull(cliService);
    this.cliOutput = Objects.requireNonNull(cliOutput);
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    final StyxCliParser parser = new StyxCliParser();
    final Namespace namespace;
    final String apiHost;

    try {
      namespace = parser.parser.parseArgs(args);
      apiHost = namespace.getString(parser.host.getDest());
      if (apiHost == null) {
        throw new ArgumentParserException("Styx API host not set", parser.parser);
      }
    } catch (HelpScreenException e) {
      System.exit(EXIT_CODE_SUCCESS);
      return;
    } catch (ArgumentParserException e) {
      parser.parser.handleError(e);
      System.exit(EXIT_CODE_ARGUMENT_ERROR);
      return;
    }

    final Service cliService = Services.usingName("styx-cli")
        .withEnvVarPrefix(ENV_VAR_PREFIX)
        .withModule(ApolloEnvironmentModule.create())
        .withModule(HttpClientModule.create())
        .build();

    final boolean plainOutput = namespace.getBoolean(parser.plain.getDest());
    final CliOutput cliOutput = plainOutput ? new PlainCliOutput() : new PrettyCliOutput();

    new Main(parser, namespace, apiHost, cliService, cliOutput).run();
  }

  private void run() throws IOException, InterruptedException {
    final Command command = namespace.get(COMMAND_DEST);

    try (Service.Instance instance = cliService.start()) {
      final Service.Signaller signaller = instance.getSignaller();

      client = errorHandlingClient(
          ApolloEnvironmentModule.environment(instance).environment().client(), signaller);

      switch (command) {
        case LIST:
          activeStates();
          break;

        case EVENTS:
          eventsForWorkflowInstance();
          break;

        case TRIGGER:
          triggerWorkflowInstance();
          break;

        case HALT:
          haltWorkflowInstance();
          break;

        case RETRY:
          retryWorkflowInstance();
          break;

        default:
          // parsing unknown command will fail so this should never happen...
          throw new RuntimeException("Unrecognized command: " + command);
      }

      instance.waitForShutdown();
    }
  }

  private String apiUrl(CharSequence... parts) {
    return "http://" + apiHost + STYX_CLI_API_ENDPOINT + "/" + String.join("/", parts);
  }

  private void activeStates()
      throws UnsupportedEncodingException {

    String uri = apiUrl("activeStates");
    String component = namespace.getString(parser.listComponent.getDest());
    if (component != null) {
      uri += "?component=" + URLEncoder.encode(component, UTF_8);
    }

    client.accept(
        Request.forUri(uri).withTtl(Duration.ofSeconds(TTL_REQUEST)),
        bytes -> {
          try {
            cliOutput.printActiveStates(OBJECT_MAPPER.readValue(bytes, ActiveStatesPayload.class));
          } catch (IOException e) {
            throw Throwables.propagate(e);
          }
        });
  }

  private void eventsForWorkflowInstance() {
    WorkflowInstance workflowInstance = getWorkflowInstance(namespace);
    String component = workflowInstance.workflowId().componentId();
    String workflow = workflowInstance.workflowId().endpointId();
    String parameter = workflowInstance.parameter();

    client.accept(
        Request.forUri(apiUrl("events", component, workflow, parameter))
            .withTtl(Duration.ofSeconds(TTL_REQUEST)),
        bytes -> {
          try {
            cliOutput.printEvents(OBJECT_MAPPER.readValue(bytes, EventsPayload.class));
          } catch (IOException e) {
            throw Throwables.propagate(e);
          }
        });
  }

  private void triggerWorkflowInstance() {
    WorkflowInstance workflowInstance = getWorkflowInstance(namespace);

    final ByteString payload;
    try {
      payload = ByteString.of(OBJECT_MAPPER.writeValueAsBytes(workflowInstance));
    } catch (JsonProcessingException e) {
      throw Throwables.propagate(e);
    }
    Request request = Request.forUri(apiUrl("trigger"), "POST")
        .withPayload(payload);
    client.accept(request, null);
  }

  private void haltWorkflowInstance() {
    WorkflowInstance workflowInstance = getWorkflowInstance(namespace);

    Event halt = Event.halt(workflowInstance);
    EventSerializer.PersistentEvent persistentEvent =
        EventSerializer.convertEventToPersistentEvent(halt);
    final ByteString payload;
    try {
      payload = ByteString.of(OBJECT_MAPPER.writeValueAsBytes(persistentEvent));
    } catch (JsonProcessingException e) {
      throw Throwables.propagate(e);
    }
    Request request = Request.forUri(apiUrl("events"), "POST")
        .withPayload(payload);
    client.accept(request, null);
  }

  private void retryWorkflowInstance() {
    WorkflowInstance workflowInstance = getWorkflowInstance(namespace);

    Event retry = Event.retry(workflowInstance);
    EventSerializer.PersistentEvent persistentEvent =
        EventSerializer.convertEventToPersistentEvent(retry);
    final ByteString payload;
    try {
      payload = ByteString.of(OBJECT_MAPPER.writeValueAsBytes(persistentEvent));
    } catch (JsonProcessingException e) {
      throw Throwables.propagate(e);
    }
    Request request = Request.forUri(apiUrl("events"), "POST")
        .withPayload(payload);
    client.accept(request, null);
  }

  private static WorkflowInstance getWorkflowInstance(Namespace namespace) {
    return WorkflowInstance.create(
        WorkflowId.create(
            namespace.getString(COMPONENT_DEST),
            namespace.getString(WORKFLOW_DEST)),
        namespace.getString(PARAMETER_DEST));
  }

  private static BiConsumer<Request, Consumer<byte[]>> errorHandlingClient(
      Client client, Service.Signaller signaller) {
    return (request, consumer) ->
      client.send(request)
          .handle((response, throwable) -> {
            if (throwable != null) {
              throw Throwables.propagate(throwable);
            }
            switch (response.status().family()) {
              case SUCCESSFUL:
              case REDIRECTION:
                return response.payload().orElse(ByteString.EMPTY).toByteArray();
              default:
                throw new RuntimeException(
                    response.status().code() + " " + response.status().reasonPhrase());
            }
          })
          .thenAccept(consumer != null ? consumer : bytes -> { })
          .whenComplete((ignored, throwable) -> {
            if (throwable != null) {
              System.err.println("An API error occurred: "
                                 + Throwables.getRootCause(throwable).getMessage());
              System.exit(EXIT_CODE_API_ERROR);
            }
            signaller.signalShutdown();
          });
  }

  private static class StyxCliParser {

    final ArgumentParser parser = ArgumentParsers.newArgumentParser("styx")
        .description("Styx CLI");

    final Subparsers subCommands = parser.addSubparsers()
        .title("commands")
        .metavar(" ");

    final Subparser list = Command.LIST.parser(subCommands);
    final Argument listComponent = list.addArgument("-c", "--component")
        .help("only show instances for COMPONENT");

    final Subparser events = addWorkflowInstanceArguments(Command.EVENTS.parser(subCommands));
    final Subparser trigger = addWorkflowInstanceArguments(Command.TRIGGER.parser(subCommands));
    final Subparser halt = addWorkflowInstanceArguments(Command.HALT.parser(subCommands));
    final Subparser retry = addWorkflowInstanceArguments(Command.RETRY.parser(subCommands));

    final Argument host = parser.addArgument("-H", "--host")
        .help("Styx API host (can also be set with environment variable " + ENV_VAR_PREFIX + "_HOST)")
        .setDefault(System.getenv(ENV_VAR_PREFIX + "_HOST"))
        .action(Arguments.store());

    final Argument plain = parser.addArgument("-p", "--plain")
        .help("plain output")
        .setDefault(false)
        .action(Arguments.storeTrue());

    private static Subparser addWorkflowInstanceArguments(Subparser subparser) {
      subparser.addArgument(COMPONENT_DEST)
          .help("Component id");
      subparser.addArgument(WORKFLOW_DEST)
          .help("Workflow id (legacy Endpoint)");
      subparser.addArgument(PARAMETER_DEST)
          .help("Parameter identifying the workflow instance, e.g. '2016-09-14' or '2016-09-14T17'");
      return subparser;
    }
  }

  private enum Command {
    LIST("ls", "List active workflow instances"),
    EVENTS("e", "List events for a workflow instance"),
    HALT("h", "Halt a workflow instance"),
    TRIGGER("t", "Trigger a completed workflow instance"),
    RETRY("r", "Retry a workflow instance that is in a waiting state");

    private final String alias;
    private final String description;

    Command(String alias, String description) {
      this.alias = alias;
      this.description = description;
    }

    public Subparser parser(Subparsers subCommands) {
      return subCommands
          .addParser(name().toLowerCase())
          .aliases(alias)
          .setDefault(COMMAND_DEST, this)
          .description(description)
          .help(description);
    }
  }
}
