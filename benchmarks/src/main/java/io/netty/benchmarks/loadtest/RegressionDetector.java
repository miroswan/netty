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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class RegressionDetector {

    private final double qpsWarningPct;
    private final double qpsRegressionPct;
    private final double p99WarningPct;
    private final double p99RegressionPct;
    private final double allocRateWarningPct;
    private final double allocRateRegressionPct;

    RegressionDetector() {
        this.qpsWarningPct = Double.parseDouble(System.getProperty("regression.qps.warning", "3"));
        this.qpsRegressionPct = Double.parseDouble(System.getProperty("regression.qps.fail", "5"));
        this.p99WarningPct = Double.parseDouble(System.getProperty("regression.p99.warning", "5"));
        this.p99RegressionPct = Double.parseDouble(System.getProperty("regression.p99.fail", "10"));
        this.allocRateWarningPct = Double.parseDouble(System.getProperty("regression.alloc.warning", "10"));
        this.allocRateRegressionPct = Double.parseDouble(System.getProperty("regression.alloc.fail", "25"));
    }

    record Verdict(boolean passed, List<String> warnings, List<String> errors) {

        String formatSummary() {
            final StringBuilder sb = new StringBuilder();
            sb.append("\n========== REGRESSION VERDICT ==========\n");
            sb.append("Result: ").append(passed ? "PASS" : "FAIL").append("\n");

            if (!errors.isEmpty()) {
                sb.append("\nErrors:\n");
                for (final String error : errors) {
                    sb.append("  [FAIL] ").append(error).append("\n");
                }
            }
            if (!warnings.isEmpty()) {
                sb.append("\nWarnings:\n");
                for (final String warning : warnings) {
                    sb.append("  [WARN] ").append(warning).append("\n");
                }
            }

            return sb.toString();
        }
    }

    Verdict evaluate(final SuiteResult baseline, final SuiteResult candidate) {
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        final var baselineByName = baseline.scenarios().stream()
                .collect(Collectors.toMap(s -> s.scenario().name(), s -> s));

        for (final ScenarioResult candScenario : candidate.scenarios()) {
            final ScenarioResult baseScenario = baselineByName.get(candScenario.scenario().name());
            if (baseScenario == null) {
                continue;
            }

            final String name = candScenario.scenario().name();

            checkQPS(name, baseScenario.meanQPS(), candScenario.meanQPS(), warnings, errors);
            checkP99(name, baseScenario.meanP99(), candScenario.meanP99(), warnings, errors);
            checkLeaks(name, candScenario, warnings, errors);
        }

        return new Verdict(errors.isEmpty(), warnings, errors);
    }

    private void checkQPS(final String scenario, final double baseQPS, final double candQPS,
                          final List<String> warnings, final List<String> errors) {
        if (baseQPS <= 0) {
            return;
        }
        final double dropPct = ((baseQPS - candQPS) / baseQPS) * 100.0;
        if (dropPct > qpsRegressionPct) {
            errors.add(String.format("%s: QPS dropped %.1f%% (%.0f -> %.0f)",
                    scenario, dropPct, baseQPS, candQPS));
        } else if (dropPct > qpsWarningPct) {
            warnings.add(String.format("%s: QPS dropped %.1f%% (%.0f -> %.0f)",
                    scenario, dropPct, baseQPS, candQPS));
        }
    }

    private void checkP99(final String scenario, final double baseP99, final double candP99,
                          final List<String> warnings, final List<String> errors) {
        if (baseP99 <= 0) {
            return;
        }
        final double increasePct = ((candP99 - baseP99) / baseP99) * 100.0;
        if (increasePct > p99RegressionPct) {
            errors.add(String.format("%s: P99 latency increased %.1f%% (%.2fms -> %.2fms)",
                    scenario, increasePct, baseP99, candP99));
        } else if (increasePct > p99WarningPct) {
            warnings.add(String.format("%s: P99 latency increased %.1f%% (%.2fms -> %.2fms)",
                    scenario, increasePct, baseP99, candP99));
        }
    }

    private void checkLeaks(final String scenario, final ScenarioResult candScenario,
                            final List<String> warnings, final List<String> errors) {
        for (final BenchmarkResult iteration : candScenario.iterations()) {
            if (iteration.leakResult() != null && iteration.leakResult().leakDetected()) {
                errors.add(String.format("%s: Native memory leak detected in iteration %d",
                        scenario, iteration.iteration()));
                return;
            }
        }
    }
}
