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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NativeMemoryTracker {

    private static final Pattern TOTAL_PATTERN = Pattern.compile(
            "^Total: reserved=(\\d+)KB, committed=(\\d+)KB.*");

    private static final Pattern CATEGORY_PATTERN = Pattern.compile(
            "^-\\s+(\\w+)\\s+\\(reserved=(\\d+)KB, committed=(\\d+)KB\\)");

    private final long pid;
    private NativeMemorySnapshot baseline;

    NativeMemoryTracker(final long pid) {
        this.pid = pid;
    }

    void captureBaseline() throws IOException, InterruptedException {
        this.baseline = captureSnapshot();
        System.err.println("Native memory baseline captured: " +
                formatBytes(baseline.totalReserved()) + " reserved, " +
                formatBytes(baseline.totalCommitted()) + " committed");
    }

    LeakDetectionResult detectLeaks(final String phase) throws IOException, InterruptedException {
        if (baseline == null) {
            throw new IllegalStateException("Must call captureBaseline() first");
        }

        final NativeMemorySnapshot current = captureSnapshot();

        final long reservedGrowth = current.totalReserved() - baseline.totalReserved();
        final long committedGrowth = current.totalCommitted() - baseline.totalCommitted();

        System.err.println("\nNative Memory Analysis (" + phase + "):");
        System.err.println("  Reserved:  " + formatBytes(current.totalReserved()) +
                " (delta " + formatDelta(reservedGrowth) + ")");
        System.err.println("  Committed: " + formatBytes(current.totalCommitted()) +
                " (delta " + formatDelta(committedGrowth) + ")");

        final Map<String, Long> categoryGrowth = new HashMap<>();
        for (final Map.Entry<String, Long> entry : current.categoryCommitted().entrySet()) {
            final String category = entry.getKey();
            final long currentCommitted = entry.getValue();
            final long baselineCommitted = baseline.categoryCommitted().getOrDefault(category, 0L);
            final long growth = currentCommitted - baselineCommitted;

            if (growth > 0) {
                categoryGrowth.put(category, growth);
                System.err.println("  " + category + ": +" + formatBytes(growth));
            }
        }

        final boolean leakDetected = committedGrowth > 10 * 1024;

        return new LeakDetectionResult(
                phase, baseline, current, reservedGrowth, committedGrowth,
                categoryGrowth, leakDetected);
    }

    private NativeMemorySnapshot captureSnapshot() throws IOException, InterruptedException {
        final ProcessBuilder pb = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/jcmd",
                String.valueOf(pid),
                "VM.native_memory",
                "summary",
                "scale=KB"
        );

        final Process process = pb.start();
        final int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException("jcmd VM.native_memory failed with exit code " + exitCode);
        }

        return parseNativeMemoryOutput(process);
    }

    private NativeMemorySnapshot parseNativeMemoryOutput(final Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {

            long totalReserved = 0;
            long totalCommitted = 0;
            final Map<String, Long> categoryCommitted = new HashMap<>();

            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = TOTAL_PATTERN.matcher(line);
                if (matcher.matches()) {
                    totalReserved = Long.parseLong(matcher.group(1));
                    totalCommitted = Long.parseLong(matcher.group(2));
                    continue;
                }

                matcher = CATEGORY_PATTERN.matcher(line);
                if (matcher.matches()) {
                    final String category = matcher.group(1);
                    final long committed = Long.parseLong(matcher.group(3));
                    categoryCommitted.put(category, committed);
                }
            }

            return new NativeMemorySnapshot(totalReserved, totalCommitted, categoryCommitted);
        }
    }

    private String formatBytes(final long kb) {
        if (kb < 1024) {
            return kb + " KB";
        } else if (kb < 1024 * 1024) {
            return String.format("%.1f MB", kb / 1024.0);
        } else {
            return String.format("%.2f GB", kb / (1024.0 * 1024.0));
        }
    }

    private String formatDelta(final long kb) {
        if (kb >= 0) {
            return "+" + formatBytes(kb);
        } else {
            return formatBytes(kb);
        }
    }

    record NativeMemorySnapshot(long totalReserved, long totalCommitted,
                                Map<String, Long> categoryCommitted) {
    }

    record LeakDetectionResult(String phase, NativeMemorySnapshot baseline,
                               NativeMemorySnapshot current, long reservedGrowth,
                               long committedGrowth, Map<String, Long> categoryGrowth,
                               boolean leakDetected) {

        String formatSummary() {
            final StringBuilder sb = new StringBuilder();
            sb.append("Leak Detection - ").append(phase).append(":\n");
            sb.append("  Committed Growth: ");
            if (committedGrowth >= 0) {
                sb.append("+");
            }
            sb.append(committedGrowth).append(" KB");

            if (leakDetected) {
                sb.append(" LEAK DETECTED\n");
                sb.append("  Top Growing Categories:\n");
                categoryGrowth.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                        .limit(3)
                        .forEach(e -> sb.append("  - ")
                                .append(e.getKey())
                                .append(": +")
                                .append(e.getValue())
                                .append(" KB\n"));
            } else {
                sb.append(" OK\n");
            }

            return sb.toString();
        }
    }
}
