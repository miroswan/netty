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
import java.io.InputStreamReader;

record EnvironmentInfo(String jvmVersion, String jvmVendor,
                              String osName, String osVersion, String osArch,
                              String cpuModel, int cpuCores, long availableMemoryMB,
                              String gitCommit, String gitBranch) {

    public static EnvironmentInfo capture() {
        return new EnvironmentInfo(
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"),
                getCpuModel(),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024),
                runCommand("git", "rev-parse", "HEAD"),
                runCommand("git", "rev-parse", "--abbrev-ref", "HEAD")
        );
    }

    private static String getCpuModel() {
        final String os = System.getProperty("os.name", "");
        if (os.contains("Mac")) {
            return runCommand("sysctl", "-n", "machdep.cpu.brand_string");
        } else if (os.contains("Linux")) {
            return runCommand("sh", "-c", "grep 'model name' /proc/cpuinfo | head -1 | cut -d: -f2");
        }
        return "unknown";
    }

    private static String runCommand(final String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                final String line = reader.readLine();
                process.waitFor();
                return line != null ? line.trim() : "unknown";
            }
        } catch (final Exception e) {
            return "unknown";
        }
    }
}
