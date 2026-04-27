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

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

final class MemoryStatsCollector {

    private final int jmxPort;
    private final long samplingIntervalMs;
    private JMXConnector connector;
    private MBeanServerConnection connection;
    private final List<MemorySample> samples = new ArrayList<>();
    private Thread collectorThread;
    private volatile boolean running;

    private long lastTotalAllocatedBytes;
    private long lastSampleTime;

    private final Map<String, GCInfo> lastGCInfo = new HashMap<>();
    private final List<GCEvent> gcEvents = new ArrayList<>();

    MemoryStatsCollector(final int jmxPort) {
        this.jmxPort = jmxPort;
        this.samplingIntervalMs = 250;
    }

    void connect() throws Exception {
        final JMXServiceURL serviceUrl = new JMXServiceURL(
                "service:jmx:rmi:///jndi/rmi://localhost:" + jmxPort + "/jmxrmi");
        connector = JMXConnectorFactory.connect(serviceUrl);
        connection = connector.getMBeanServerConnection();
    }

    void startCollecting() {
        if (running) {
            return;
        }
        running = true;

        collectorThread = new Thread(() -> {
            try {
                lastTotalAllocatedBytes = getTotalAllocatedBytes();
                lastSampleTime = System.nanoTime();

                while (running) {
                    final MemorySample sample = collectSample();
                    if (sample != null) {
                        synchronized (samples) {
                            samples.add(sample);
                        }
                    }
                    Thread.sleep(samplingIntervalMs);
                }
            } catch (final InterruptedException e) {
                // Expected during shutdown
            } catch (final Exception e) {
                System.err.println("Memory collector failed: " + e.getMessage());
            }
        }, "MemoryStatsCollector");
        collectorThread.setDaemon(true);
        collectorThread.start();
    }

    void stopCollecting() {
        running = false;
        if (collectorThread != null) {
            collectorThread.interrupt();
            try {
                collectorThread.join(1000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void disconnect() {
        if (connector != null) {
            try {
                connector.close();
            } catch (final Exception e) {
                // Ignore
            }
        }
    }

    MemoryStats getStats() {
        final List<MemorySample> samplesCopy;
        final List<GCEvent> gcEventsCopy;

        synchronized (samples) {
            samplesCopy = new ArrayList<>(samples);
        }
        synchronized (gcEvents) {
            gcEventsCopy = new ArrayList<>(gcEvents);
        }

        if (samplesCopy.isEmpty()) {
            return MemoryStats.EMPTY;
        }

        long heapUsedMin = Long.MAX_VALUE;
        long heapUsedMax = Long.MIN_VALUE;
        long heapUsedSum = 0;
        long heapCommittedSum = 0;
        long directMemMax = 0;
        long directMemSum = 0;
        double allocRateSum = 0;

        long edenUsedMax = 0;
        long survivorUsedMax = 0;
        long oldGenUsedMax = 0;

        long oldGenFirst = -1;
        long oldGenLast = -1;

        for (final MemorySample sample : samplesCopy) {
            heapUsedMin = Math.min(heapUsedMin, sample.heapUsed);
            heapUsedMax = Math.max(heapUsedMax, sample.heapUsed);
            heapUsedSum += sample.heapUsed;
            heapCommittedSum += sample.heapCommitted;
            directMemMax = Math.max(directMemMax, sample.directMemoryUsed);
            directMemSum += sample.directMemoryUsed;
            allocRateSum += sample.allocationRateMBps;

            edenUsedMax = Math.max(edenUsedMax, sample.edenUsed);
            survivorUsedMax = Math.max(survivorUsedMax, sample.survivorUsed);
            oldGenUsedMax = Math.max(oldGenUsedMax, sample.oldGenUsed);

            if (oldGenFirst == -1 && sample.oldGenUsed > 0) {
                oldGenFirst = sample.oldGenUsed;
            }
            if (sample.oldGenUsed > 0) {
                oldGenLast = sample.oldGenUsed;
            }
        }

        final long liveSetApprox = (long) samplesCopy.stream()
                .mapToLong(s -> s.heapUsed)
                .sorted()
                .limit(Math.max(1, samplesCopy.size() / 10))
                .average()
                .orElse(0);

        final long gcCount = samplesCopy.getLast().gcCount - samplesCopy.getFirst().gcCount;
        final long gcTimeMs = samplesCopy.getLast().gcTimeMs - samplesCopy.getFirst().gcTimeMs;

        final double gcPauseP50Ms;
        final double gcPauseP95Ms;
        final double gcPauseP99Ms;
        long youngGCCount = 0;
        long fullGCCount = 0;
        double avgTimeBetweenGCsMs = 0;

        if (!gcEventsCopy.isEmpty()) {
            final List<Long> pauseDurations = new ArrayList<>();
            final List<Long> gcTimestamps = new ArrayList<>();

            for (final GCEvent event : gcEventsCopy) {
                pauseDurations.add(event.durationMs);
                gcTimestamps.add(event.timestampMs);
                if (event.isYoungGC) {
                    youngGCCount++;
                } else {
                    fullGCCount++;
                }
            }

            pauseDurations.sort(Long::compare);

            gcPauseP50Ms = percentile(pauseDurations, 50);
            gcPauseP95Ms = percentile(pauseDurations, 95);
            gcPauseP99Ms = percentile(pauseDurations, 99);

            if (gcTimestamps.size() > 1) {
                gcTimestamps.sort(Long::compare);
                long totalGaps = 0;
                for (int i = 1; i < gcTimestamps.size(); i++) {
                    totalGaps += gcTimestamps.get(i) - gcTimestamps.get(i - 1);
                }
                avgTimeBetweenGCsMs = (double) totalGaps / (gcTimestamps.size() - 1);
            }
        } else {
            gcPauseP50Ms = 0;
            gcPauseP95Ms = 0;
            gcPauseP99Ms = 0;
        }

        final double avgHeapCommitted = heapCommittedSum / (double) samplesCopy.size();
        final double heapUtilizationPercent = (heapUsedMax / avgHeapCommitted) * 100.0;
        final double peakToLiveSetRatio = liveSetApprox > 0 ? (double) heapUsedMax / liveSetApprox : 0;

        final long testDurationMs = samplesCopy.size() * samplingIntervalMs;
        final double timeInGCPercent = testDurationMs > 0
                ? (gcTimeMs / (double) testDurationMs) * 100.0 : 0;

        double promotionRateMBps = 0;
        if (oldGenFirst >= 0 && oldGenLast > oldGenFirst && testDurationMs > 0) {
            final double oldGenGrowthMB = (oldGenLast - oldGenFirst) / (1024.0 * 1024.0);
            final double testDurationSec = testDurationMs / 1000.0;
            promotionRateMBps = oldGenGrowthMB / testDurationSec;
        }

        return new MemoryStats(
                heapUsedMin, heapUsedMax, liveSetApprox, directMemMax,
                directMemSum / samplesCopy.size(), allocRateSum / samplesCopy.size(),
                gcCount, gcTimeMs, samplesCopy.size(),
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

    private MemorySample collectSample() throws Exception {
        if (connection == null) {
            return null;
        }

        final MemoryMXBean memoryBean = ManagementFactory.newPlatformMXBeanProxy(
                connection, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
        final MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();

        long edenUsed = 0;
        long survivorUsed = 0;
        long oldGenUsed = 0;
        try {
            final ObjectName poolPattern = new ObjectName("java.lang:type=MemoryPool,*");
            for (final ObjectName name : connection.queryNames(poolPattern, null)) {
                final MemoryPoolMXBean pool = ManagementFactory.newPlatformMXBeanProxy(
                        connection, name.toString(), MemoryPoolMXBean.class);
                final String poolName = pool.getName();
                final MemoryUsage usage = pool.getUsage();

                if (poolName.contains("Eden")) {
                    edenUsed = usage.getUsed();
                } else if (poolName.contains("Survivor")) {
                    survivorUsed += usage.getUsed();
                } else if (poolName.contains("Old") || poolName.contains("Tenured")) {
                    oldGenUsed = usage.getUsed();
                }
            }
        } catch (final Exception e) {
            // Generational stats may not be available for all GCs
        }

        long directMemoryUsed = 0;
        try {
            final ObjectName directPool = new ObjectName("java.nio:type=BufferPool,name=direct");
            for (final ObjectName name : connection.queryNames(directPool, null)) {
                final BufferPoolMXBean pool = ManagementFactory.newPlatformMXBeanProxy(
                        connection, name.toString(), BufferPoolMXBean.class);
                directMemoryUsed += pool.getMemoryUsed();
            }
        } catch (final Exception e) {
            // Direct buffer pool may not exist
        }

        long totalGcCount = 0;
        long totalGcTime = 0;
        final long currentTimeMs = System.currentTimeMillis();

        final ObjectName gcPattern = new ObjectName("java.lang:type=GarbageCollector,*");
        for (final ObjectName name : connection.queryNames(gcPattern, null)) {
            final GarbageCollectorMXBean gcBean = ManagementFactory.newPlatformMXBeanProxy(
                    connection, name.toString(), GarbageCollectorMXBean.class);

            final String gcName = gcBean.getName();
            final long count = gcBean.getCollectionCount();
            final long time = gcBean.getCollectionTime();

            totalGcCount += count;
            totalGcTime += time;

            final GCInfo lastInfo = lastGCInfo.get(gcName);
            if (lastInfo != null) {
                final long newCollections = count - lastInfo.count;
                final long newTimeMs = time - lastInfo.timeMs;

                if (newCollections > 0) {
                    final long avgPauseMs = newTimeMs / newCollections;
                    final boolean isYoungGC = gcName.contains("Scavenge") || gcName.contains("Young")
                            || gcName.contains("Copy") || gcName.contains("ParNew");

                    for (long i = 0; i < newCollections; i++) {
                        synchronized (gcEvents) {
                            gcEvents.add(new GCEvent(currentTimeMs, avgPauseMs, gcName, isYoungGC));
                        }
                    }
                }
            }

            lastGCInfo.put(gcName, new GCInfo(count, time));
        }

        final long currentTotalAllocated = getTotalAllocatedBytes();
        final long currentTime = System.nanoTime();

        final long bytesAllocated = currentTotalAllocated - lastTotalAllocatedBytes;
        final long timeDeltaNs = currentTime - lastSampleTime;

        double allocationRateMBps = 0;
        if (timeDeltaNs > 0 && bytesAllocated >= 0) {
            final double seconds = timeDeltaNs / 1_000_000_000.0;
            final double mb = bytesAllocated / (1024.0 * 1024.0);
            allocationRateMBps = mb / seconds;
        }

        lastTotalAllocatedBytes = currentTotalAllocated;
        lastSampleTime = currentTime;

        return new MemorySample(
                heapUsage.getUsed(), heapUsage.getCommitted(), directMemoryUsed,
                allocationRateMBps, totalGcCount, totalGcTime,
                edenUsed, survivorUsed, oldGenUsed
        );
    }

    private long getTotalAllocatedBytes() throws Exception {
        try {
            final ObjectName threadBeanName = new ObjectName("java.lang:type=Threading");
            final com.sun.management.ThreadMXBean threadBean = ManagementFactory.newPlatformMXBeanProxy(
                    connection, threadBeanName.toString(), com.sun.management.ThreadMXBean.class);

            long total = 0;
            for (final long id : threadBean.getAllThreadIds()) {
                final long allocated = threadBean.getThreadAllocatedBytes(id);
                if (allocated != -1) {
                    total += allocated;
                }
            }
            return total;
        } catch (final Exception e) {
            return lastTotalAllocatedBytes;
        }
    }

    private static final class GCInfo {
        final long count;
        final long timeMs;

        GCInfo(final long count, final long timeMs) {
            this.count = count;
            this.timeMs = timeMs;
        }
    }

    private record GCEvent(long timestampMs, long durationMs, String gcName, boolean isYoungGC) {
    }

    private record MemorySample(long heapUsed, long heapCommitted, long directMemoryUsed,
                                double allocationRateMBps, long gcCount, long gcTimeMs,
                                long edenUsed, long survivorUsed, long oldGenUsed) {
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
