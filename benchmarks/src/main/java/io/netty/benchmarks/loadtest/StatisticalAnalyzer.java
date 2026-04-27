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

import org.apache.commons.math3.distribution.TDistribution;

import java.util.Arrays;

final class StatisticalAnalyzer {

    private StatisticalAnalyzer() {
    }

    record DescriptiveStats(double mean, double stddev, double min, double max, double median) {
    }

    record ConfidenceInterval(double lower, double upper, double margin) {
    }

    record TTestResult(double tStatistic, double pValue, double degreesOfFreedom,
                       boolean significant) {
    }

    static DescriptiveStats compute(final double[] values) {
        final double mean = Arrays.stream(values).average().orElse(0);
        final double variance = Arrays.stream(values)
                .map(v -> (v - mean) * (v - mean))
                .sum() / (values.length - 1);
        final double stddev = Math.sqrt(variance);

        final double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        final double min = sorted[0];
        final double max = sorted[sorted.length - 1];
        final double median = sorted.length % 2 == 0
                ? (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0
                : sorted[sorted.length / 2];

        return new DescriptiveStats(mean, stddev, min, max, median);
    }

    static ConfidenceInterval confidenceInterval(final double[] values, final double confidence) {
        final DescriptiveStats stats = compute(values);
        final int df = values.length - 1;
        final TDistribution tDist = new TDistribution(df);
        final double alpha = 1.0 - confidence;
        final double tCritical = tDist.inverseCumulativeProbability(1.0 - alpha / 2.0);
        final double margin = tCritical * stats.stddev() / Math.sqrt(values.length);

        return new ConfidenceInterval(stats.mean() - margin, stats.mean() + margin, margin);
    }

    static TTestResult welchTTest(final double[] baseline, final double[] candidate) {
        final DescriptiveStats baseStats = compute(baseline);
        final DescriptiveStats candStats = compute(candidate);

        final double n1 = baseline.length;
        final double n2 = candidate.length;
        final double s1sq = baseStats.stddev() * baseStats.stddev();
        final double s2sq = candStats.stddev() * candStats.stddev();

        final double t = (baseStats.mean() - candStats.mean())
                / Math.sqrt(s1sq / n1 + s2sq / n2);

        final double numerator = Math.pow(s1sq / n1 + s2sq / n2, 2);
        final double denominator = Math.pow(s1sq / n1, 2) / (n1 - 1)
                + Math.pow(s2sq / n2, 2) / (n2 - 1);
        final double df = numerator / denominator;

        final TDistribution tDist = new TDistribution(df);
        final double pValue = 2.0 * (1.0 - tDist.cumulativeProbability(Math.abs(t)));

        return new TTestResult(t, pValue, df, pValue < 0.05);
    }
}
