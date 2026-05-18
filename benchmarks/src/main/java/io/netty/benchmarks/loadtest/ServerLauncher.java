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
import java.util.ArrayList;
import java.util.List;

final class ServerLauncher {

    private final Config config;

    ServerLauncher(final Config config) {
        this.config = config;
    }

    Process startServer(final File portFile) throws IOException {
        final List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");

        command.add("-XX:NativeMemoryTracking=summary");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("--add-opens");
        command.add("java.base/sun.nio.ch=ALL-UNNAMED");
        command.add("--add-opens");
        command.add("java.base/java.io=ALL-UNNAMED");

        final long warmupDelaySeconds = config.warmupTimeMs() / 1000;
        command.add("-XX:StartFlightRecording=delay=" + warmupDelaySeconds +
                "s,disk=true,dumponexit=true,filename=" + getJfrFile().getAbsolutePath() +
                ",settings=profile");

        command.add("-cp");
        command.add(config.serverClasspath());
        command.add(config.serverClass());
        command.add("0");
        command.add("10000");
        command.add(portFile.getAbsolutePath());

        final File serverLog = new File(config.reportDir(), config.name() + "-server.log");
        config.reportDir().mkdirs();

        System.err.println("Starting server: " + config.serverClass());

        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectOutput(ProcessBuilder.Redirect.to(serverLog));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(serverLog));

        return pb.start();
    }

    File getJfrFile() {
        return new File(config.reportDir(), config.name() + "-alloc.jfr");
    }

    int readServerPort(final File portFile) throws Exception {
        final long startTime = System.currentTimeMillis();
        final long timeout = 5000;

        while (System.currentTimeMillis() - startTime < timeout) {
            if (portFile.exists()) {
                final String portText = Files.readString(portFile.toPath()).trim();
                return Integer.parseInt(portText);
            }
            Thread.sleep(100);
        }

        throw new RuntimeException("Server did not write port file within " + timeout + "ms");
    }

    void captureHeapDump(final Process serverProcess, final File reportDir, final String name) {
        try {
            final long pid = serverProcess.pid();
            final File heapDumpFile = new File(reportDir, name + "-heap.hprof");

            System.err.println("Capturing heap dump from PID " + pid + "...");

            final ProcessBuilder pb = new ProcessBuilder(
                    System.getProperty("java.home") + "/bin/jcmd",
                    String.valueOf(pid),
                    "GC.heap_dump",
                    heapDumpFile.getAbsolutePath()
            );
            pb.inheritIO();
            final Process jcmdProcess = pb.start();
            final int exitCode = jcmdProcess.waitFor();

            if (exitCode == 0) {
                System.err.println("Heap dump saved to: " + heapDumpFile.getAbsolutePath());
            } else {
                System.err.println("Warning: jcmd failed with exit code " + exitCode);
            }
        } catch (final Exception e) {
            System.err.println("Warning: Failed to capture heap dump: " + e.getMessage());
        }
    }

    static void triggerGC(final long pid) throws IOException, InterruptedException {
        final ProcessBuilder pb = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/jcmd",
                String.valueOf(pid),
                "GC.run"
        );
        pb.inheritIO();
        final Process process = pb.start();
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.err.println("Warning: jcmd GC.run failed with exit code " + exitCode);
        }
    }

    record Config(String name, File reportDir, String serverClasspath,
                  String serverClass, long warmupTimeMs) {
    }
}
