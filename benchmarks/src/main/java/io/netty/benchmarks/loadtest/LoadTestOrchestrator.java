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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class LoadTestOrchestrator {

    private LoadTestOrchestrator() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: LoadTestOrchestrator <suite|compare>");
            System.exit(1);
        }

        final String mode = args[0];
        if ("suite".equals(mode)) {
            runSuite();
        } else if ("compare".equals(mode)) {
            runCompare();
        } else {
            System.err.println("Unknown mode: " + mode + ". Use 'suite' or 'compare'.");
            System.exit(1);
        }
    }

    private static void runSuite() throws Exception {
        final String label = System.getProperty("benchmark.label", "unnamed");
        final int iterations = Integer.getInteger("benchmark.iterations", 5);
        final String serverClasspath = buildClasspath();
        final File buildDir = new File("benchmarks/target");
        final File reportDir = new File(buildDir, "reports");
        final Path resultsDir = Path.of("benchmarks/results");

        System.err.println("=== Benchmark Suite: " + label + " ===");
        System.err.println("Iterations: " + iterations);

        final EnvironmentInfo env = EnvironmentInfo.capture();
        System.err.println("Environment: " + env.jvmVersion() + " / " + env.osName() +
                " / " + env.cpuModel());

        final ScenarioRunner runner = new ScenarioRunner(buildDir, reportDir, serverClasspath);
        final List<BenchmarkScenario> scenarios = BenchmarkScenario.defaultSuite();
        final List<ScenarioResult> results = new ArrayList<>();

        for (final BenchmarkScenario scenario : scenarios) {
            final ScenarioResult result = runner.run(scenario, iterations);
            results.add(result);
            printScenarioSummary(result);
        }

        final SuiteResult suiteResult = new SuiteResult(
                env, results, Instant.now(), env.gitCommit(), label);

        final ResultStore store = new ResultStore(resultsDir);
        store.save(suiteResult, label);

        if (!"baseline".equals(label) && store.hasLabel("baseline")) {
            final SuiteResult baseline = store.loadBaseline();
            final String deltaReport = DeltaReportGenerator.generateConsoleReport(baseline, suiteResult);
            System.err.println(deltaReport);

            final File htmlFile = new File(reportDir, label + "-vs-baseline.html");
            DeltaReportGenerator.generateHtmlReport(htmlFile, baseline, suiteResult);
            System.err.println("HTML report: " + htmlFile);

            final RegressionDetector detector = new RegressionDetector();
            final RegressionDetector.Verdict verdict = detector.evaluate(baseline, suiteResult);
            System.err.println(verdict.formatSummary());

            if (!verdict.passed()) {
                System.exit(1);
            }
        }

        System.err.println("\nBenchmark suite completed: " + label);
    }

    private static void runCompare() throws Exception {
        final String baselineLabel = System.getProperty("baseline.label");
        final String candidateLabel = System.getProperty("candidate.label");

        if (baselineLabel == null || candidateLabel == null) {
            System.err.println("Both -Dbaseline.label and -Dcandidate.label are required");
            System.exit(1);
        }

        final Path resultsDir = Path.of("benchmarks/results");
        final ResultStore store = new ResultStore(resultsDir);

        final SuiteResult baseline = store.load(baselineLabel);
        final SuiteResult candidate = store.load(candidateLabel);

        final String deltaReport = DeltaReportGenerator.generateConsoleReport(baseline, candidate);
        System.err.println(deltaReport);

        final File reportDir = new File("benchmarks/target/reports");
        reportDir.mkdirs();
        final File htmlFile = new File(reportDir, candidateLabel + "-vs-" + baselineLabel + ".html");
        DeltaReportGenerator.generateHtmlReport(htmlFile, baseline, candidate);
        System.err.println("HTML report: " + htmlFile);

        final RegressionDetector detector = new RegressionDetector();
        final RegressionDetector.Verdict verdict = detector.evaluate(baseline, candidate);
        System.err.println(verdict.formatSummary());

        if (!verdict.passed()) {
            System.exit(1);
        }
    }

    private static void printScenarioSummary(final ScenarioResult result) {
        System.err.println(String.format(
                "\n  %s: QPS=%.0f (stddev=%.0f, 95%%CI=+/-%.0f)  P50=%.2fms  P99=%.2fms  P99.9=%.2fms",
                result.scenario().name(),
                result.meanQPS(), result.stddevQPS(), result.ci95QPS(),
                result.meanP50(), result.meanP99(), result.meanP999()));
    }

    private static String buildClasspath() {
        final ClassLoader cl = LoadTestOrchestrator.class.getClassLoader();
        if (cl instanceof URLClassLoader) {
            final URL[] urls = ((URLClassLoader) cl).getURLs();
            return java.util.Arrays.stream(urls)
                    .map(URL::getPath)
                    .collect(Collectors.joining(File.pathSeparator));
        }
        return System.getProperty("java.class.path");
    }
}
