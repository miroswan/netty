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
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.stream.Collectors;

final class DeltaReportGenerator {

    private DeltaReportGenerator() {
    }

    static String generateConsoleReport(final SuiteResult baseline, final SuiteResult candidate) {
        final StringBuilder sb = new StringBuilder();
        sb.append("\n========== DELTA REPORT ==========\n");
        sb.append("Baseline: ").append(baseline.label()).append(" (").append(baseline.gitCommit()).append(")\n");
        sb.append("Candidate: ").append(candidate.label()).append(" (").append(candidate.gitCommit()).append(")\n\n");

        final Map<String, ScenarioResult> baselineByName = baseline.scenarios().stream()
                .collect(Collectors.toMap(s -> s.scenario().name(), s -> s));

        for (final ScenarioResult candScenario : candidate.scenarios()) {
            final ScenarioResult baseScenario = baselineByName.get(candScenario.scenario().name());
            if (baseScenario == null) {
                sb.append("=== ").append(candScenario.scenario().name()).append(" === (no baseline)\n");
                continue;
            }

            sb.append("=== ").append(candScenario.scenario().name()).append(" ===\n");

            appendMetricDelta(sb, "QPS", baseScenario.meanQPS(), candScenario.meanQPS(),
                    extractQPSValues(baseScenario), extractQPSValues(candScenario), false);
            appendMetricDelta(sb, "P50", baseScenario.meanP50(), candScenario.meanP50(),
                    extractP50Values(baseScenario), extractP50Values(candScenario), true);
            appendMetricDelta(sb, "P99", baseScenario.meanP99(), candScenario.meanP99(),
                    extractP99Values(baseScenario), extractP99Values(candScenario), true);
            appendMetricDelta(sb, "P99.9", baseScenario.meanP999(), candScenario.meanP999(),
                    extractP999Values(baseScenario), extractP999Values(candScenario), true);
            sb.append("\n");
        }

        return sb.toString();
    }

    static void generateHtmlReport(final File outputFile, final SuiteResult baseline,
                                   final SuiteResult candidate) throws IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><title>Delta Report</title>\n");
        sb.append("<style>\n");
        sb.append("body { font-family: monospace; margin: 20px; }\n");
        sb.append("table { border-collapse: collapse; margin: 10px 0; }\n");
        sb.append("th, td { border: 1px solid #ccc; padding: 6px 12px; text-align: right; }\n");
        sb.append("th { background: #f0f0f0; text-align: left; }\n");
        sb.append(".good { color: green; }\n");
        sb.append(".error { color: red; }\n");
        sb.append(".neutral { color: gray; }\n");
        sb.append("</style></head><body>\n");
        sb.append("<h1>Delta Report</h1>\n");
        sb.append("<p>Baseline: ").append(baseline.label()).append(" | Candidate: ")
                .append(candidate.label()).append("</p>\n");

        final Map<String, ScenarioResult> baselineByName = baseline.scenarios().stream()
                .collect(Collectors.toMap(s -> s.scenario().name(), s -> s));

        for (final ScenarioResult candScenario : candidate.scenarios()) {
            final ScenarioResult baseScenario = baselineByName.get(candScenario.scenario().name());
            if (baseScenario == null) {
                continue;
            }

            sb.append("<h2>").append(candScenario.scenario().name()).append("</h2>\n");
            sb.append("<table>\n");
            sb.append("<tr><th>Metric</th><th>Baseline</th><th>Candidate</th><th>Delta</th><th>p-value</th></tr>\n");

            appendHtmlMetricRow(sb, "QPS", baseScenario.meanQPS(), candScenario.meanQPS(),
                    extractQPSValues(baseScenario), extractQPSValues(candScenario), false);
            appendHtmlMetricRow(sb, "P50 (ms)", baseScenario.meanP50(), candScenario.meanP50(),
                    extractP50Values(baseScenario), extractP50Values(candScenario), true);
            appendHtmlMetricRow(sb, "P99 (ms)", baseScenario.meanP99(), candScenario.meanP99(),
                    extractP99Values(baseScenario), extractP99Values(candScenario), true);
            appendHtmlMetricRow(sb, "P99.9 (ms)", baseScenario.meanP999(), candScenario.meanP999(),
                    extractP999Values(baseScenario), extractP999Values(candScenario), true);

            sb.append("</table>\n");
        }

        sb.append("</body></html>");
        Files.writeString(outputFile.toPath(), sb.toString());
    }

    private static void appendMetricDelta(final StringBuilder sb, final String name,
                                          final double baseVal, final double candVal,
                                          final double[] baseValues, final double[] candValues,
                                          final boolean lowerIsBetter) {
        final double delta = candVal - baseVal;
        final double pctChange = baseVal != 0 ? (delta / baseVal) * 100.0 : 0;

        final String significance;
        if (baseValues.length >= 2 && candValues.length >= 2) {
            final StatisticalAnalyzer.TTestResult tTest =
                    StatisticalAnalyzer.welchTTest(baseValues, candValues);
            significance = String.format("[p=%.3f, %s]",
                    tTest.pValue(), tTest.significant() ? "significant" : "not significant");
        } else {
            significance = "[insufficient data]";
        }

        final String direction = (lowerIsBetter ? delta < 0 : delta > 0) ? "+" : "";
        sb.append(String.format("  %-8s %,.2f -> %,.2f  (%s%.1f%%)  %s%n",
                name + ":", baseVal, candVal, direction, pctChange, significance));
    }

    private static void appendHtmlMetricRow(final StringBuilder sb, final String name,
                                            final double baseVal, final double candVal,
                                            final double[] baseValues, final double[] candValues,
                                            final boolean lowerIsBetter) {
        final double delta = candVal - baseVal;
        final double pctChange = baseVal != 0 ? (delta / baseVal) * 100.0 : 0;

        final boolean improved = lowerIsBetter ? delta < 0 : delta > 0;
        final String cssClass;
        final String pValueStr;

        if (baseValues.length >= 2 && candValues.length >= 2) {
            final StatisticalAnalyzer.TTestResult tTest =
                    StatisticalAnalyzer.welchTTest(baseValues, candValues);
            pValueStr = String.format("%.3f", tTest.pValue());
            cssClass = tTest.significant() ? (improved ? "good" : "error") : "neutral";
        } else {
            pValueStr = "N/A";
            cssClass = "neutral";
        }

        sb.append(String.format("<tr><td>%s</td><td>%,.2f</td><td>%,.2f</td>" +
                        "<td class=\"%s\">%+.1f%%</td><td>%s</td></tr>%n",
                name, baseVal, candVal, cssClass, pctChange, pValueStr));
    }

    private static double[] extractQPSValues(final ScenarioResult result) {
        return result.iterations().stream()
                .mapToDouble(r -> r.loadResult().actualQPS())
                .toArray();
    }

    private static double[] extractP50Values(final ScenarioResult result) {
        return result.iterations().stream()
                .mapToDouble(r -> r.loadResult().latency().p50())
                .toArray();
    }

    private static double[] extractP99Values(final ScenarioResult result) {
        return result.iterations().stream()
                .mapToDouble(r -> r.loadResult().latency().p99())
                .toArray();
    }

    private static double[] extractP999Values(final ScenarioResult result) {
        return result.iterations().stream()
                .mapToDouble(r -> r.loadResult().latency().p999())
                .toArray();
    }
}
