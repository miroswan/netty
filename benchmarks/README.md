# Netty Benchmarks

End-to-end load testing framework for Netty's transport and HTTP stacks. Launches
real server processes, drives them with [Fortio](https://fortio.org/), and
collects throughput, latency percentiles, JVM memory, and native memory metrics
across multiple iterations with statistical analysis.

## Prerequisites

- JDK 25+
- [Fortio](https://fortio.org/) on `PATH`

```bash
# macOS
brew install fortio

# Linux
go install fortio.org/fortio@latest
```

Or use the bundled script:

```bash
./benchmarks/scripts/install-fortio.sh
```

## Environment Setup

The `.local/env.sh` file at the repo root sets up the build environment. Source
it before building or running benchmarks:

```bash
source .local/env.sh
```

It configures:

- **`JAVA_HOME`** -- Points to JDK 25, which this branch requires (the parent
  pom activates a `java25` profile that sets `source`/`target`/`release` to 25).
- **`$JAVA_HOME/bin` on `PATH`** -- Ensures `java`, `javac`, and `jcmd` resolve
  to JDK 25 instead of whatever JDK your system defaults to.
- **`/opt/homebrew/bin` on `PATH`** -- Gives Maven access to `cmake`, `ninja`,
  and `go`, needed by the `codec-native-quic` module to build BoringSSL and
  Quiche from source.
- **`$HOME/.cargo/bin` on `PATH`** -- Gives Maven access to `cargo`/`rustc`,
  also needed by `codec-native-quic` to build the Quiche Rust library.

Without this file sourced, the build fails at various points because tools are
missing from `PATH` or the wrong JDK is used.

## Quick Start

```bash
source .local/env.sh

# Build (from repo root)
mvn install -DskipTests -Dskip.bundle.plugin=true

# Quick validation (1 iteration, 30s per scenario)
mvn exec:java -pl benchmarks -Pbenchmark-quick -Dbenchmark.label=my-run

# Full suite (5 iterations, 60s each)
mvn exec:java -pl benchmarks -Pbenchmark-suite -Dbenchmark.label=my-run

# Compare two labeled runs
mvn exec:java -pl benchmarks -Pbenchmark-compare \
    -Dbaseline.label=baseline -Dcandidate.label=my-run
```

## Default Scenarios

The suite runs six scenarios covering both transports and protocols:

| Scenario | Transport | Protocol | Connections | Payload |
|---|---|---|---|---|
| kqueue-echo-1kb-100conn | kQueue | TCP | 100 | 1 KB |
| kqueue-echo-64kb-100conn | kQueue | TCP | 100 | 64 KB |
| kqueue-echo-1kb-1000conn | kQueue | TCP | 1000 | 1 KB |
| kqueue-http-100conn | kQueue | HTTP | 100 | - |
| nio-echo-1kb-100conn | NIO | TCP | 100 | 1 KB |
| nio-http-100conn | NIO | HTTP | 100 | - |

## Configuration

All properties can be overridden via `-D` flags:

| Property | Default | Description |
|---|---|---|
| `benchmark.label` | `unnamed` | Label for result storage |
| `benchmark.iterations` | `5` | Measured iterations (warmup iteration is always added) |
| `benchmark.connections` | `100` | Concurrent connections |
| `benchmark.duration` | `60s` | Load duration per iteration |
| `benchmark.warmupDuration` | `2000` | JVM warmup before measurement (ms) |
| `benchmark.qps` | `0` | QPS rate limit (0 = unlimited) |
| `benchmark.payload` | `hello` | Request payload |

## How It Works

1. **Server launch** -- Each scenario spawns a Netty server in a subprocess with
   JFR profiling, JMX monitoring, and native memory tracking enabled.

2. **Warmup** -- The first iteration is always discarded. An additional
   `warmupDuration` pause lets the JVM warm up before Fortio starts.

3. **Load generation** -- Fortio drives the server at the configured connection
   count, duration, and QPS. Results are parsed from Fortio's JSON output.

4. **Memory collection** -- A JMX client samples heap, direct memory, GC events,
   and allocation rates at 250ms intervals during the load phase.

5. **Leak detection** -- `jcmd VM.native_memory` snapshots before and after the
   load phase detect native memory growth > 10 KB.

6. **Aggregation** -- Per-scenario results are aggregated with mean, standard
   deviation, and 95% confidence intervals (Welch's t-distribution).

7. **Persistence** -- Results are saved as JSON under
   `benchmarks/results/<label>/<timestamp>_<git-short>.json`.

8. **Comparison** -- If a `baseline` label exists, a delta report is
   auto-generated showing QPS and latency changes with statistical significance
   (p < 0.05). An HTML report is written to `benchmarks/target/reports/`.

## Regression Detection

When comparing against a baseline, quality gates are applied:

| Metric | Warning | Failure |
|---|---|---|
| QPS drop | > 3% | > 5% |
| P99 latency increase | > 5% | > 10% |
| Native memory leak | - | Any detected |

Thresholds are configurable via system properties (`regression.qps.fail`,
`regression.p99.fail`, etc.). The process exits with code 1 on failure.

## Output Files

| Path | Description |
|---|---|
| `benchmarks/results/<label>/*.json` | Persisted suite results |
| `benchmarks/target/reports/*-server.log` | Server stdout/stderr |
| `benchmarks/target/reports/*-alloc.jfr` | JFR flight recordings |
| `benchmarks/target/reports/*-vs-baseline.html` | HTML comparison report |

## Architecture

```
LoadTestOrchestrator          -- entry point, suite/compare modes
  BenchmarkScenario           -- scenario definitions (transport, protocol, payload)
  ScenarioRunner              -- iteration loop, aggregation
    ServerLauncher            -- subprocess management, JMX/JFR setup
    FortioRunner              -- Fortio CLI wrapper, JSON parsing
    MemoryStatsCollector      -- JMX-based heap/GC/allocation monitoring
    NativeMemoryTracker       -- jcmd-based native memory leak detection
  StatisticalAnalyzer         -- descriptive stats, confidence intervals, Welch t-test
  RegressionDetector          -- quality gate evaluation
  DeltaReportGenerator        -- console and HTML comparison reports
  ResultStore                 -- JSON persistence and retrieval
  EnvironmentInfo             -- JVM/OS/CPU/git metadata capture
```
