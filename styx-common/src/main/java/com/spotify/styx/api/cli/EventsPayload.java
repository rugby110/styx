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

package com.spotify.styx.api.cli;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;
import com.spotify.styx.model.EventSerializer.PersistentEvent;
import java.util.List;

/**
 * convert Event to EventsPayload (with associated timestamps)
 */
@AutoValue
public abstract class EventsPayload {

  @JsonProperty
  public abstract List<TimestampedPersistentEvent> events();

  @AutoValue
  public abstract static class TimestampedPersistentEvent {

    @JsonProperty
    public abstract PersistentEvent event();

    @JsonProperty
    public abstract long timestamp();

    @JsonCreator
    public static EventsPayload.TimestampedPersistentEvent create(
        @JsonProperty("event") PersistentEvent persistentEvent,
        @JsonProperty("timestamp") long timestamp) {
      return new AutoValue_EventsPayload_TimestampedPersistentEvent(
          persistentEvent,
          timestamp);
    }
  }

  @JsonCreator
  public static EventsPayload create(
      @JsonProperty("events") List<TimestampedPersistentEvent> events) {
    return new AutoValue_EventsPayload(events);
  }
}
