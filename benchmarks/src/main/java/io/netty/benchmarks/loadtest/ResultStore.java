/*
 * Copyright 2026 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.benchmarks.loadtest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

final class ResultStore {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneId.systemDefault());
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
                @Override
                public void write(final JsonWriter out, final Instant value) throws IOException {
                    out.value(value == null ? null : value.toString());
                }

                @Override
                public Instant read(final JsonReader in) throws IOException {
                    return Instant.parse(in.nextString());
                }
            })
            .serializeSpecialFloatingPointValues()
            .create();

    private final Path resultsDir;

    ResultStore(final Path resultsDir) {
        this.resultsDir = resultsDir;
    }

    void save(final SuiteResult result, final String label) throws IOException {
        final Path labelDir = resultsDir.resolve(label);
        Files.createDirectories(labelDir);

        final String timestamp = TIMESTAMP_FORMAT.format(result.timestamp());
        final String gitShort = result.gitCommit().length() > 7
                ? result.gitCommit().substring(0, 7)
                : result.gitCommit();
        final String filename = timestamp + "_" + gitShort + ".json";

        final Path file = labelDir.resolve(filename);
        Files.writeString(file, GSON.toJson(result));
        System.err.println("Results saved to: " + file);
    }

    SuiteResult load(final String label) throws IOException {
        final Path labelDir = resultsDir.resolve(label);
        if (!Files.isDirectory(labelDir)) {
            throw new IOException("No results found for label: " + label);
        }

        try (Stream<Path> files = Files.list(labelDir)) {
            final Path mostRecent = files
                    .filter(p -> p.toString().endsWith(".json"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElseThrow(() -> new IOException("No result files in: " + labelDir));

            final String json = Files.readString(mostRecent);
            return GSON.fromJson(json, SuiteResult.class);
        }
    }

    SuiteResult loadBaseline() throws IOException {
        return load("baseline");
    }

    boolean hasLabel(final String label) {
        final Path labelDir = resultsDir.resolve(label);
        if (!Files.isDirectory(labelDir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(labelDir)) {
            return files.anyMatch(p -> p.toString().endsWith(".json"));
        } catch (final IOException e) {
            return false;
        }
    }
}
