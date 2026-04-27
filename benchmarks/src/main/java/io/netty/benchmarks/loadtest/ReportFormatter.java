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

final class ReportFormatter {

    private ReportFormatter() {
    }

    static String getLatencyClass(final double latencyMs) {
        if (latencyMs < 5.0) {
            return "good";
        } else if (latencyMs < 20.0) {
            return "warning";
        } else {
            return "error";
        }
    }

    static String getGCPauseClass(final double pauseMs) {
        if (pauseMs < 50.0) {
            return "good";
        } else if (pauseMs < 100.0) {
            return "warning";
        } else {
            return "error";
        }
    }

    static String formatBytesKB(final long kb) {
        if (kb < 1024) {
            return kb + " KB";
        } else if (kb < 1024 * 1024) {
            return String.format("%.1f MB", kb / 1024.0);
        } else {
            return String.format("%.2f GB", kb / (1024.0 * 1024.0));
        }
    }

    static String formatDeltaKB(final long kb) {
        if (kb >= 0) {
            return "+" + formatBytesKB(kb);
        } else {
            return formatBytesKB(kb);
        }
    }

    static void appendMemorySection(final StringBuilder sb,
                                    final MemoryStatsCollector.MemoryStats stats) {
        sb.append("\n=== Memory Stats ===\n");
        sb.append(String.format("  Heap Used:     min=%s  max=%s  live=%s%n",
                stats.formatBytes(stats.heapUsedMin()),
                stats.formatBytes(stats.heapUsedMax()),
                stats.formatBytes(stats.liveSetSize())));
        sb.append(String.format("  Direct Memory: max=%s  avg=%s%n",
                stats.formatBytes(stats.directMemMax()),
                stats.formatBytes(stats.directMemAvg())));
        sb.append(String.format("  Alloc Rate:    %.1f MB/s%n", stats.allocRateAvgMBps()));
        sb.append(String.format("  GC Count:      %d (young=%d, full=%d)%n",
                stats.gcCount(), stats.youngGCCount(), stats.fullGCCount()));
        sb.append(String.format("  GC Time:       %d ms (%.1f%% of test)%n",
                stats.gcTimeMs(), stats.timeInGCPercent()));
        sb.append(String.format("  GC Pauses:     P50=%.1fms  P95=%.1fms  P99=%.1fms%n",
                stats.gcPauseP50Ms(), stats.gcPauseP95Ms(), stats.gcPauseP99Ms()));
        sb.append(String.format("  Heap Pressure: util=%.1f%%  peak/live=%.1fx%n",
                stats.heapUtilizationPercent(), stats.peakToLiveSetRatio()));
        sb.append(String.format("  Promotion:     %.2f MB/s%n", stats.promotionRateMBps()));
        sb.append(String.format("  Samples:       %d (over %d ms)%n",
                stats.sampleCount(), stats.testDurationMs()));
    }

    static void appendNativeMemorySection(final StringBuilder sb,
                                          final NativeMemoryTracker.LeakDetectionResult result) {
        sb.append("\n=== Native Memory ===\n");
        sb.append("  ").append(result.formatSummary());
    }

    static void appendHtmlMemorySection(final StringBuilder sb,
                                        final MemoryStatsCollector.MemoryStats stats) {
        sb.append("<h3>Memory Statistics</h3>\n");
        sb.append("<table><tr><th>Metric</th><th>Value</th></tr>\n");
        appendHtmlRow(sb, "Heap Min", stats.formatBytes(stats.heapUsedMin()));
        appendHtmlRow(sb, "Heap Max", stats.formatBytes(stats.heapUsedMax()));
        appendHtmlRow(sb, "Live Set", stats.formatBytes(stats.liveSetSize()));
        appendHtmlRow(sb, "Direct Memory Max", stats.formatBytes(stats.directMemMax()));
        appendHtmlRow(sb, "Alloc Rate", String.format("%.1f MB/s", stats.allocRateAvgMBps()));
        appendHtmlRow(sb, "GC Count", String.valueOf(stats.gcCount()));
        appendHtmlRow(sb, "GC Time", stats.gcTimeMs() + " ms");
        appendHtmlRow(sb, "GC Pause P50",
                String.format("%.1f ms", stats.gcPauseP50Ms()), getGCPauseClass(stats.gcPauseP50Ms()));
        appendHtmlRow(sb, "GC Pause P99",
                String.format("%.1f ms", stats.gcPauseP99Ms()), getGCPauseClass(stats.gcPauseP99Ms()));
        appendHtmlRow(sb, "Time in GC", String.format("%.1f%%", stats.timeInGCPercent()));
        appendHtmlRow(sb, "Promotion Rate", String.format("%.2f MB/s", stats.promotionRateMBps()));
        sb.append("</table>\n");
    }

    static void appendHtmlNativeMemorySection(final StringBuilder sb,
                                              final NativeMemoryTracker.LeakDetectionResult result) {
        sb.append("<h3>Native Memory</h3>\n");
        final String cssClass = result.leakDetected() ? "error" : "good";
        sb.append("<p class=\"").append(cssClass).append("\">");
        sb.append(result.leakDetected() ? "LEAK DETECTED" : "No leak detected");
        sb.append("</p>\n");
        sb.append("<table><tr><th>Metric</th><th>Value</th></tr>\n");
        appendHtmlRow(sb, "Committed Growth", formatDeltaKB(result.committedGrowth()));
        appendHtmlRow(sb, "Reserved Growth", formatDeltaKB(result.reservedGrowth()));
        sb.append("</table>\n");
    }

    private static void appendHtmlRow(final StringBuilder sb,
                                      final String label, final String value) {
        sb.append("<tr><td>").append(label).append("</td><td>").append(value).append("</td></tr>\n");
    }

    private static void appendHtmlRow(final StringBuilder sb,
                                      final String label, final String value, final String cssClass) {
        sb.append("<tr><td>").append(label).append("</td><td class=\"").append(cssClass)
                .append("\">").append(value).append("</td></tr>\n");
    }
}
