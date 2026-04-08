# Installation

## System Requirements

| Requirement | Minimum | Recommended |
|---|---|---|
| Java | 21 | 21+ (with preview features) |
| Maven | 3.9 | 3.9+ |
| RAM | 512 MB | 4 GB+ |
| Disk | 1 GB | SSD recommended |
| OS | Linux, macOS, Windows | Linux (for production) |

## Build from Source

```bash
git clone https://github.com/iwtxokhtd83/TitanMQ.git
cd TitanMQ
mvn clean install
```

This builds all 9 modules and runs the test suite.

To skip tests:

```bash
mvn clean install -DskipTests
```

## Module Artifacts

After building, the key artifacts are:

| Module | Artifact | Purpose |
|---|---|---|
| `titanmq-server` | `titanmq-server-1.0.0-SNAPSHOT.jar` | Broker server |
| `titanmq-client` | `titanmq-client-1.0.0-SNAPSHOT.jar` | Producer/Consumer SDK |
| `titanmq-core` | `titanmq-core-1.0.0-SNAPSHOT.jar` | Core engine + Embedded mode |
| `titanmq-common` | `titanmq-common-1.0.0-SNAPSHOT.jar` | Shared types (needed by all) |

## Maven Dependency

To use TitanMQ as a library in your project:

```xml
<!-- For networked client -->
<dependency>
    <groupId>com.titanmq</groupId>
    <artifactId>titanmq-client</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- For embedded/brokerless mode -->
<dependency>
    <groupId>com.titanmq</groupId>
    <artifactId>titanmq-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Verify Installation

Run the throughput benchmark to verify everything works:

```bash
mvn -pl titanmq-benchmark exec:java \
  -Dexec.mainClass="com.titanmq.benchmark.ThroughputBenchmark"
```

You should see output like:

```
=== TitanMQ Throughput Benchmark ===
Messages: 1,000,000
Message size: 1024 bytes
Producers: 4
Partitions: 8

--- Producer Throughput ---
  Throughput: XXX,XXX msgs/sec
  Bandwidth: XX.XX MB/sec
```
