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

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class MemoryStatsCollector {

    private final Path jfrFile;

    MemoryStatsCollector(final Path jfrFile) {
        this.jfrFile = jfrFile;
    }

    MemoryStats parseStats() throws IOException {
        long heapUsedMin = Long.MAX_VALUE;
        long heapUsedMax = Long.MIN_VALUE;
        long heapUsedSum = 0;
        long heapCommittedSum = 0;
        int heapSampleCount = 0;

        long edenUsedMax = 0;
        long survivorUsedMax = 0;
        long oldGenUsedMax = 0;
        long oldGenFirst = -1;
        long oldGenLast = -1;

        long totalAllocBytes = 0;
        long gcCount = 0;
        long gcTimeMs = 0;
        long youngGCCount = 0;
        long fullGCCount = 0;

        long directMemMax = 0;
        long directMemSum = 0;
        int directMemSamples = 0;

        final List<Long> pauseDurationsMs = new ArrayList<>();
        final List<Long> gcTimestampsMs = new ArrayList<>();

        Instant recordingStart = null;
        Instant recordingEnd = null;

        try (RecordingFile recording = new RecordingFile(jfrFile)) {
            while (recording.hasMoreEvents()) {
                final RecordedEvent event = recording.readEvent();
                final String eventName = event.getEventType().getName();

                if (recordingStart == null || event.getStartTime().isBefore(recordingStart)) {
                    recordingStart = event.getStartTime();
                }
                if (recordingEnd == null || event.getEndTime().isAfter(recordingEnd)) {
                    recordingEnd = event.getEndTime();
                }

                switch (eventName) {
                    case "jdk.GCHeapSummary" -> {
                        final long heapUsed = event.getLong("heapUsed");
                        final long heapCommitted = event.getValue("heapSpace") != null
                                ? event.getValue("heapSpace.committedSize") instanceof Long v ? v : 0
                                : 0;

                        heapUsedMin = Math.min(heapUsedMin, heapUsed);
                        heapUsedMax = Math.max(heapUsedMax, heapUsed);
                        heapUsedSum += heapUsed;
                        heapCommittedSum += heapCommitted;
                        heapSampleCount++;
                    }
                    case "jdk.G1HeapSummary" -> {
                        final long eden = event.getLong("edenUsedSize");
                        final long survivor = event.getLong("survivorUsedSize");
                        final long oldGen = event.getLong("oldGenUsedSize");

                        edenUsedMax = Math.max(edenUsedMax, eden);
                        survivorUsedMax = Math.max(survivorUsedMax, survivor);
                        oldGenUsedMax = Math.max(oldGenUsedMax, oldGen);

                        if (oldGenFirst == -1 && oldGen > 0) {
                            oldGenFirst = oldGen;
                        }
                        if (oldGen > 0) {
                            oldGenLast = oldGen;
                        }
                    }
                    case "jdk.PSHeapSummary" -> {
                        final long eden = event.getValue("edenSpace.used") instanceof Long v ? v : 0;
                        final long survivor = event.getValue("fromSpace.used") instanceof Long v ? v : 0;
                        final long oldGen = event.getValue("oldSpace.used") instanceof Long v ? v : 0;

                        edenUsedMax = Math.max(edenUsedMax, eden);
                        survivorUsedMax = Math.max(survivorUsedMax, survivor);
                        oldGenUsedMax = Math.max(oldGenUsedMax, oldGen);

                        if (oldGenFirst == -1 && oldGen > 0) {
                            oldGenFirst = oldGen;
                        }
                        if (oldGen > 0) {
                            oldGenLast = oldGen;
                        }
                    }
                    case "jdk.GCPhasePause" -> {
                        final Duration duration = event.getDuration();
                        pauseDurationsMs.add(duration.toMillis());
                    }
                    case "jdk.GarbageCollection" -> {
                        gcCount++;
                        final Duration duration = event.getDuration();
                        gcTimeMs += duration.toMillis();
                        gcTimestampsMs.add(event.getStartTime().toEpochMilli());

                        final String gcName = event.getString("name");
                        if (gcName.contains("Young") || gcName.contains("Scavenge")
                                || gcName.contains("Copy") || gcName.contains("ParNew")) {
                            youngGCCount++;
                        } else {
                            fullGCCount++;
                        }
                    }
                    case "jdk.ObjectAllocationInNewTLAB" -> {
                        totalAllocBytes += event.getLong("tlabSize");
                    }
                    case "jdk.ObjectAllocationOutsideTLAB" -> {
                        totalAllocBytes += event.getLong("allocationSize");
                    }
                    case "jdk.DirectBufferStatistics" -> {
                        final long memUsed = event.getLong("memoryUsed");
                        directMemMax = Math.max(directMemMax, memUsed);
                        directMemSum += memUsed;
                        directMemSamples++;
                    }
                    default -> { }
                }
            }
        }

        if (heapSampleCount == 0) {
            return MemoryStats.EMPTY;
        }

        final long liveSetApprox = heapUsedMin == Long.MAX_VALUE ? 0 : heapUsedMin;

        final double gcPauseP50Ms;
        final double gcPauseP95Ms;
        final double gcPauseP99Ms;
        double avgTimeBetweenGCsMs = 0;

        if (!pauseDurationsMs.isEmpty()) {
            pauseDurationsMs.sort(Long::compare);
            gcPauseP50Ms = percentile(pauseDurationsMs, 50);
            gcPauseP95Ms = percentile(pauseDurationsMs, 95);
            gcPauseP99Ms = percentile(pauseDurationsMs, 99);
        } else {
            gcPauseP50Ms = 0;
            gcPauseP95Ms = 0;
            gcPauseP99Ms = 0;
        }

        if (gcTimestampsMs.size() > 1) {
            gcTimestampsMs.sort(Long::compare);
            long totalGaps = 0;
            for (int i = 1; i < gcTimestampsMs.size(); i++) {
                totalGaps += gcTimestampsMs.get(i) - gcTimestampsMs.get(i - 1);
            }
            avgTimeBetweenGCsMs = (double) totalGaps / (gcTimestampsMs.size() - 1);
        }

        final double avgHeapCommitted = heapCommittedSum / (double) heapSampleCount;
        final double heapUtilizationPercent = avgHeapCommitted > 0
                ? (heapUsedMax / avgHeapCommitted) * 100.0 : 0;
        final double peakToLiveSetRatio = liveSetApprox > 0
                ? (double) heapUsedMax / liveSetApprox : 0;

        final long testDurationMs = recordingStart != null && recordingEnd != null
                ? Duration.between(recordingStart, recordingEnd).toMillis() : 0;
        final double timeInGCPercent = testDurationMs > 0
                ? (gcTimeMs / (double) testDurationMs) * 100.0 : 0;

        double promotionRateMBps = 0;
        if (oldGenFirst >= 0 && oldGenLast > oldGenFirst && testDurationMs > 0) {
            final double oldGenGrowthMB = (oldGenLast - oldGenFirst) / (1024.0 * 1024.0);
            final double testDurationSec = testDurationMs / 1000.0;
            promotionRateMBps = oldGenGrowthMB / testDurationSec;
        }

        final double allocRateAvgMBps = testDurationMs > 0
                ? (totalAllocBytes / (1024.0 * 1024.0)) / (testDurationMs / 1000.0) : 0;

        final long directMemAvg = directMemSamples > 0 ? directMemSum / directMemSamples : 0;

        return new MemoryStats(
                heapUsedMin, heapUsedMax, liveSetApprox, directMemMax,
                directMemAvg, allocRateAvgMBps,
                gcCount, gcTimeMs, heapSampleCount,
                edenUsedMax, survivorUsedMax, oldGenUsedMax,
                gcPauseP50Ms, gcPauseP95Ms, gcPauseP99Ms,
                youngGCCount, fullGCCount, avgTimeBetweenGCsMs,
                heapUtilizationPercent, peakToLiveSetRatio,
                timeInGCPercent, promotionRateMBps, testDurationMs
        );
    }

    private double percentile(final List<Long> sortedValues, final double percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        final int index = (int) Math.ceil((percentile / 100.0) * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    record MemoryStats(long heapUsedMin, long heapUsedMax, long liveSetSize,
                       long directMemMax, long directMemAvg, double allocRateAvgMBps,
                       long gcCount, long gcTimeMs, int sampleCount,
                       long edenUsedMax, long survivorUsedMax, long oldGenUsedMax,
                       double gcPauseP50Ms, double gcPauseP95Ms, double gcPauseP99Ms,
                       long youngGCCount, long fullGCCount, double avgTimeBetweenGCsMs,
                       double heapUtilizationPercent, double peakToLiveSetRatio,
                       double timeInGCPercent, double promotionRateMBps, long testDurationMs) {

        static final MemoryStats EMPTY = new MemoryStats(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        String formatBytes(final long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            } else if (bytes < 1024 * 1024) {
                return (bytes / 1024) + " KB";
            } else if (bytes < 1024L * 1024 * 1024) {
                return (bytes / (1024 * 1024)) + " MB";
            } else {
                return (bytes / (1024L * 1024 * 1024)) + " GB";
            }
        }
    }
}
