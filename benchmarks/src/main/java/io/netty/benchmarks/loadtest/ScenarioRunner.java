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

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ScenarioRunner {

    private final File buildDir;
    private final File reportDir;
    private final String serverClasspath;

    ScenarioRunner(final File buildDir, final File reportDir, final String serverClasspath) {
        this.buildDir = buildDir;
        this.reportDir = reportDir;
        this.serverClasspath = serverClasspath;
    }

    ScenarioResult run(final BenchmarkScenario scenario, final int iterations) throws Exception {
        System.err.println("\n--- Running scenario: " + scenario.name() +
                " (" + (iterations + 1) + " iterations, first discarded) ---");

        final List<BenchmarkResult> results = new ArrayList<>();

        for (int i = 0; i <= iterations; i++) {
            final boolean isWarmup = i == 0;
            System.err.println("\nIteration " + i + (isWarmup ? " (warmup, discarded)" : "") + ":");

            final BenchmarkResult result = runSingleIteration(scenario, i);

            if (!isWarmup) {
                results.add(result);
            }
        }

        return aggregate(scenario, results);
    }

    private BenchmarkResult runSingleIteration(final BenchmarkScenario scenario,
                                               final int iteration) throws Exception {
        final ServerLauncher.Config serverConfig = new ServerLauncher.Config(
                scenario.name() + "-iter" + iteration, reportDir,
                serverClasspath, scenario.serverClass(), scenario.warmupTimeMs());
        final ServerLauncher launcher = new ServerLauncher(serverConfig);

        final File portFile = new File(buildDir, "tmp/load-test/" + scenario.name() + "-port.txt");
        portFile.getParentFile().mkdirs();
        portFile.delete();

        final int jmxPort = launcher.findFreePort();
        final Process serverProcess = launcher.startServer(portFile, jmxPort);

        MemoryStatsCollector memoryCollector = null;
        NativeMemoryTracker nativeTracker = null;

        try {
            final int actualPort = launcher.readServerPort(portFile);
            System.err.println("Server started on port " + actualPort +
                    " (JMX on " + jmxPort + ", PID " + serverProcess.pid() + ")");

            Thread.sleep(scenario.warmupTimeMs());

            memoryCollector = new MemoryStatsCollector(jmxPort);
            try {
                memoryCollector.connect();
                memoryCollector.startCollecting();
            } catch (final Exception e) {
                System.err.println("Warning: JMX monitoring unavailable: " + e.getMessage());
                memoryCollector = null;
            }

            nativeTracker = new NativeMemoryTracker(serverProcess.pid());
            try {
                nativeTracker.captureBaseline();
            } catch (final Exception e) {
                System.err.println("Warning: NMT unavailable: " + e.getMessage());
                nativeTracker = null;
            }

            final FortioRunner.Config fortioConfig = new FortioRunner.Config(
                    scenario.name() + "-iter" + iteration, reportDir, scenario.protocol(),
                    scenario.connections(), scenario.duration(), scenario.qps(),
                    scenario.payload(), "localhost");
            final FortioRunner fortio = new FortioRunner(fortioConfig);
            final FortioRunner.FortioResult loadResult = fortio.run(actualPort);

            final MemoryStatsCollector.MemoryStats memoryStats;
            if (memoryCollector != null) {
                memoryCollector.stopCollecting();
                memoryStats = memoryCollector.getStats();
            } else {
                memoryStats = MemoryStatsCollector.MemoryStats.EMPTY;
            }

            NativeMemoryTracker.LeakDetectionResult leakResult = null;
            if (nativeTracker != null) {
                try {
                    Thread.sleep(5000);
                    System.gc();
                    Thread.sleep(1000);
                    leakResult = nativeTracker.detectLeaks("after cooldown");
                    System.err.println(leakResult.formatSummary());
                } catch (final Exception e) {
                    System.err.println("Warning: Leak detection failed: " + e.getMessage());
                }
            }

            return new BenchmarkResult(iteration, Instant.now(), loadResult, memoryStats, leakResult);
        } finally {
            if (memoryCollector != null) {
                memoryCollector.disconnect();
            }
            serverProcess.destroy();
            serverProcess.waitFor(5, TimeUnit.SECONDS);
            if (serverProcess.isAlive()) {
                serverProcess.destroyForcibly();
            }
            portFile.delete();
        }
    }

    private ScenarioResult aggregate(final BenchmarkScenario scenario,
                                     final List<BenchmarkResult> results) {
        final double[] qpsValues = results.stream()
                .mapToDouble(r -> r.loadResult().actualQPS()).toArray();
        final double[] p50Values = results.stream()
                .mapToDouble(r -> r.loadResult().latency().p50()).toArray();
        final double[] p99Values = results.stream()
                .mapToDouble(r -> r.loadResult().latency().p99()).toArray();
        final double[] p999Values = results.stream()
                .mapToDouble(r -> r.loadResult().latency().p999()).toArray();

        final StatisticalAnalyzer.DescriptiveStats qpsStats = StatisticalAnalyzer.compute(qpsValues);
        final StatisticalAnalyzer.ConfidenceInterval qpsCI =
                qpsValues.length >= 2 ? StatisticalAnalyzer.confidenceInterval(qpsValues, 0.95) : null;

        final double meanP50 = p50Values.length > 0
                ? Arrays.stream(p50Values).average().orElse(0) : 0;
        final double meanP99 = p99Values.length > 0
                ? Arrays.stream(p99Values).average().orElse(0) : 0;
        final double meanP999 = p999Values.length > 0
                ? Arrays.stream(p999Values).average().orElse(0) : 0;

        return new ScenarioResult(scenario, results,
                qpsStats.mean(), qpsStats.stddev(),
                qpsCI != null ? qpsCI.margin() : 0,
                meanP50, meanP99, meanP999);
    }
}
