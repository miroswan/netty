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

import java.util.List;

record BenchmarkScenario(String name, String transport, String protocol,
                         String serverClass, int connections, String duration,
                         int qps, String payload, long warmupTimeMs) {

    static List<BenchmarkScenario> defaultSuite() {
        final int connections = Integer.getInteger("benchmark.connections", 100);
        final String duration = System.getProperty("benchmark.duration", "60s");
        final int qps = Integer.getInteger("benchmark.qps", 0);
        final long warmup = Long.getLong("benchmark.warmupDuration", 2000L);

        return List.of(
                new BenchmarkScenario("kqueue-echo-1kb-100conn", "kqueue", "tcp",
                        "io.netty.benchmarks.server.KQueueEchoServer",
                        connections, duration, qps, "hello", warmup),
                new BenchmarkScenario("kqueue-echo-64kb-100conn", "kqueue", "tcp",
                        "io.netty.benchmarks.server.KQueueEchoServer",
                        connections, duration, qps, "A".repeat(65536), warmup),
                new BenchmarkScenario("kqueue-echo-1kb-1000conn", "kqueue", "tcp",
                        "io.netty.benchmarks.server.KQueueEchoServer",
                        1000, duration, qps, "hello", warmup),
                new BenchmarkScenario("kqueue-http-100conn", "kqueue", "http",
                        "io.netty.benchmarks.server.KQueueHttpServer",
                        connections, duration, qps, null, warmup),
                new BenchmarkScenario("ffm-kqueue-echo-1kb-100conn", "ffm-kqueue", "tcp",
                        "io.netty.benchmarks.server.FfmKQueueEchoServer",
                        connections, duration, qps, "hello", warmup),
                new BenchmarkScenario("ffm-kqueue-echo-64kb-100conn", "ffm-kqueue", "tcp",
                        "io.netty.benchmarks.server.FfmKQueueEchoServer",
                        connections, duration, qps, "A".repeat(65536), warmup),
                new BenchmarkScenario("ffm-kqueue-echo-1kb-1000conn", "ffm-kqueue", "tcp",
                        "io.netty.benchmarks.server.FfmKQueueEchoServer",
                        1000, duration, qps, "hello", warmup),
                new BenchmarkScenario("ffm-kqueue-http-100conn", "ffm-kqueue", "http",
                        "io.netty.benchmarks.server.FfmKQueueHttpServer",
                        connections, duration, qps, null, warmup),
                new BenchmarkScenario("nio-echo-1kb-100conn", "nio", "tcp",
                        "io.netty.benchmarks.server.NioEchoServer",
                        connections, duration, qps, "hello", warmup),
                new BenchmarkScenario("nio-http-100conn", "nio", "http",
                        "io.netty.benchmarks.server.NioHttpServer",
                        connections, duration, qps, null, warmup)
        );
    }
}
