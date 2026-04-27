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
        final Path stagingDir = resultsDir.resolve("staging").resolve(label);
        Files.createDirectories(stagingDir);

        final String timestamp = TIMESTAMP_FORMAT.format(result.timestamp());
        final String filename = timestamp + ".json";

        final Path file = stagingDir.resolve(filename);
        Files.writeString(file, GSON.toJson(result));
        System.err.println("Results saved to: " + file);
    }

    SuiteResult load(final String label) throws IOException {
        final Path mostRecent = findMostRecentResult(label);
        final String json = Files.readString(mostRecent);
        return GSON.fromJson(json, SuiteResult.class);
    }

    SuiteResult load(final String sha, final String label) throws IOException {
        final Path shaLabelDir = resultsDir.resolve(sha).resolve(label);
        if (!Files.isDirectory(shaLabelDir)) {
            throw new IOException("No results found for sha=" + sha + ", label=" + label);
        }

        try (Stream<Path> files = Files.list(shaLabelDir)) {
            final Path mostRecent = files
                    .filter(p -> p.toString().endsWith(".json"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElseThrow(() -> new IOException("No result files in: " + shaLabelDir));

            final String json = Files.readString(mostRecent);
            return GSON.fromJson(json, SuiteResult.class);
        }
    }

    boolean hasLabel(final String label) {
        try {
            findMostRecentResult(label);
            return true;
        } catch (final IOException e) {
            return false;
        }
    }

    SuiteResult loadLatestPromoted() throws IOException {
        if (!Files.isDirectory(resultsDir)) {
            throw new IOException("No results directory: " + resultsDir);
        }

        Path mostRecent = null;
        try (Stream<Path> shaDirs = Files.list(resultsDir)) {
            for (final Path shaDir : shaDirs.filter(Files::isDirectory).toList()) {
                if ("staging".equals(shaDir.getFileName().toString())) {
                    continue;
                }
                try (Stream<Path> labelDirs = Files.list(shaDir)) {
                    for (final Path labelDir : labelDirs.filter(Files::isDirectory).toList()) {
                        try (Stream<Path> files = Files.list(labelDir)) {
                            final Path candidate = files
                                    .filter(p -> p.toString().endsWith(".json"))
                                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                                    .orElse(null);
                            if (candidate != null && (mostRecent == null
                                    || candidate.getFileName().toString()
                                            .compareTo(mostRecent.getFileName().toString()) > 0)) {
                                mostRecent = candidate;
                            }
                        }
                    }
                }
            }
        }

        if (mostRecent == null) {
            throw new IOException("No promoted results found");
        }
        final String json = Files.readString(mostRecent);
        return GSON.fromJson(json, SuiteResult.class);
    }

    boolean hasPromotedResults() {
        try {
            loadLatestPromoted();
            return true;
        } catch (final IOException e) {
            return false;
        }
    }

    private Path findMostRecentResult(final String label) throws IOException {
        if (!Files.isDirectory(resultsDir)) {
            throw new IOException("No results directory: " + resultsDir);
        }

        Path mostRecent = null;
        try (Stream<Path> shaDirs = Files.list(resultsDir)) {
            for (final Path shaDir : shaDirs.filter(Files::isDirectory).toList()) {
                final Path labelDir = shaDir.resolve(label);
                if (!Files.isDirectory(labelDir)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(labelDir)) {
                    final Path candidate = files
                            .filter(p -> p.toString().endsWith(".json"))
                            .max(Comparator.comparing(p -> p.getFileName().toString()))
                            .orElse(null);
                    if (candidate != null && (mostRecent == null
                            || candidate.getFileName().toString()
                                    .compareTo(mostRecent.getFileName().toString()) > 0)) {
                        mostRecent = candidate;
                    }
                }
            }
        }

        if (mostRecent == null) {
            throw new IOException("No results found for label: " + label);
        }
        return mostRecent;
    }
}
