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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FortioRunner {

    private final Config config;

    FortioRunner(final Config config) {
        this.config = config;
    }

    FortioResult run(final int port) throws Exception {
        final File jsonFile = new File(config.reportDir(), config.name() + "-result.json");
        config.reportDir().mkdirs();

        final List<String> command = new ArrayList<>();
        command.add(findFortio());
        command.add("load");
        command.add("-c");
        command.add(String.valueOf(config.connections()));
        command.add("-t");
        command.add(config.duration());
        command.add("-qps");
        command.add(String.valueOf(config.qps()));

        final String target;
        if ("http".equals(config.protocol())) {
            final String path = System.getProperty("test.http.path", "/");
            target = "http://" + config.serverHost() + ":" + port + path;
        } else {
            command.add("-payload");
            command.add(config.payload());
            target = "tcp://" + config.serverHost() + ":" + port;
        }

        command.add("-json");
        command.add(jsonFile.getAbsolutePath());
        command.add(target);

        System.err.println("Running Fortio: " + String.join(" ", command));

        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        final Process process = pb.start();
        final int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Fortio exited with code " + exitCode);
        }

        return parseResults(jsonFile);
    }

    private String findFortio() throws IOException, InterruptedException {
        final ProcessBuilder pb = new ProcessBuilder("which", "fortio");
        final Process process = pb.start();
        process.waitFor();

        if (process.exitValue() == 0) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                return reader.readLine().trim();
            }
        }

        throw new RuntimeException(
                "Fortio not found in PATH. Install via: benchmarks/scripts/install-fortio.sh");
    }

    private FortioResult parseResults(final File jsonFile) throws IOException {
        final Gson gson = new Gson();
        final JsonObject json = gson.fromJson(Files.readString(jsonFile.toPath()), JsonObject.class);

        final double actualQPS = json.get("ActualQPS").getAsDouble();
        final double actualDuration = json.get("ActualDuration").getAsDouble() / 1_000_000_000.0;

        final JsonObject histogram = json.getAsJsonObject("DurationHistogram");
        final JsonArray percentiles = histogram.getAsJsonArray("Percentiles");

        final LatencyStats latency = new LatencyStats(
                histogram.get("Min").getAsDouble() * 1000,
                histogram.get("Max").getAsDouble() * 1000,
                histogram.get("Avg").getAsDouble() * 1000,
                getPercentile(percentiles, 50.0),
                getPercentile(percentiles, 75.0),
                getPercentile(percentiles, 90.0),
                getPercentile(percentiles, 99.0),
                getPercentile(percentiles, 99.9)
        );

        final long socketCount = json.has("SocketCount") ? json.get("SocketCount").getAsLong() : 0L;

        final Map<String, Long> httpStatusCodes = new HashMap<>();
        if (json.has("RetCodes")) {
            final JsonObject retCodes = json.getAsJsonObject("RetCodes");
            for (final String key : retCodes.keySet()) {
                httpStatusCodes.put(key, retCodes.get(key).getAsLong());
            }
        }

        return new FortioResult(actualQPS, actualDuration, latency, socketCount, httpStatusCodes);
    }

    private double getPercentile(final JsonArray percentiles, final double target) {
        for (final JsonElement element : percentiles) {
            final JsonObject obj = element.getAsJsonObject();
            if (Math.abs(obj.get("Percentile").getAsDouble() - target) < 0.01) {
                return obj.get("Value").getAsDouble() * 1000;
            }
        }
        return 0.0;
    }

    record Config(String name, File reportDir, String protocol, int connections,
                  String duration, int qps, String payload, String serverHost) {
    }

    record LatencyStats(double min, double max, double avg,
                        double p50, double p75, double p90, double p99, double p999) {
    }

    record FortioResult(double actualQPS, double actualDuration, LatencyStats latency,
                        long socketCount, Map<String, Long> httpStatusCodes) {

        boolean isHttpTest() {
            return httpStatusCodes != null && !httpStatusCodes.isEmpty();
        }
    }
}
