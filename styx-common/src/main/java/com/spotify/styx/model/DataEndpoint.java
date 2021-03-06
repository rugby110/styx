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

package com.spotify.styx.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.value.AutoValue;
import java.util.List;
import java.util.Optional;

/**
 * A specification of a scheduled data endpoint
 */
@AutoValue
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class DataEndpoint {

  @JsonProperty
  public abstract String id();

  @JsonProperty
  public abstract Partitioning partitioning();

  @JsonProperty
  public abstract Optional<String> dockerImage();

  @JsonProperty
  public abstract Optional<List<String>> dockerArgs();

  @JsonProperty
  public abstract Optional<Secret> secret();

  @JsonCreator
  public static DataEndpoint create(
      @JsonProperty("id") String id,
      @JsonProperty("partitioning") Partitioning partitioning,
      @JsonProperty("docker_image") Optional<String> dockerImage,
      @JsonProperty("docker_args") Optional<List<String>> dockerArgs,
      @JsonProperty("secret") Optional<Secret> secret) {

    return new AutoValue_DataEndpoint(id, partitioning, dockerImage, dockerArgs, secret);
  }

  @AutoValue
  @JsonIgnoreProperties(ignoreUnknown = true)
  public abstract static class Secret {

    @JsonProperty
    public abstract String name();

    @JsonProperty
    public abstract String mountPath();

    @JsonCreator
    public static Secret create(
        @JsonProperty("name") String name,
        @JsonProperty("mount_path") String mountPath) {
      return new AutoValue_DataEndpoint_Secret(name, mountPath);
    }
  }

}
