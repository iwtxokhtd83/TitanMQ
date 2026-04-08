# Benchmarking

TitanMQ includes two types of benchmarks for measuring performance.

## End-to-End Throughput Benchmark

Simulates realistic producer/consumer workloads:

```bash
mvn -pl titanmq-benchmark exec:java \
  -Dexec.mainClass="com.titanmq.benchmark.ThroughputBenchmark"
```

Default parameters:
- 1,000,000 messages
- 1 KB message size
- 4 producer threads
- 8 partitions

Output:

```
=== TitanMQ Throughput Benchmark ===
--- Producer Throughput ---
  Total messages: 1,000,000
  Time: X.XX seconds
  Throughput: XXX,XXX msgs/sec
  Bandwidth: XX.XX MB/sec
  Avg latency: X.XX µs/msg

--- Consumer Throughput ---
  Total messages read: 1,000,000
  Throughput: XXX,XXX msgs/sec
  Bandwidth: XX.XX MB/sec
```

## JMH Microbenchmarks

Precise measurement of the commit log append path:

```bash
mvn -pl titanmq-benchmark exec:java \
  -Dexec.mainClass="com.titanmq.benchmark.CommitLogBenchmark"
```

Tests message sizes: 128B, 1KB, 4KB, 16KB

JMH configuration:
- 3 warmup iterations (3s each)
- 5 measurement iterations (5s each)
- 1 fork
- Reports: throughput (ops/µs) and average time (µs/op)

## Performance Targets

| Metric | Target |
|---|---|
| Write throughput | > 1M msgs/sec (1KB, 8 partitions) |
| Read throughput | > 2M msgs/sec |
| P99 produce latency | < 5ms |
| P99 end-to-end latency | < 10ms |

## Tips for Accurate Benchmarks

- Use SSDs for the data directory
- Disable swap: `sudo swapoff -a`
- Pin JVM to specific cores: `taskset -c 0-7 java ...`
- Use JVM flags: `-XX:+UseZGC -Xmx4g -Xms4g`
- Run multiple iterations and take the median
- Ensure the benchmark machine is otherwise idle
