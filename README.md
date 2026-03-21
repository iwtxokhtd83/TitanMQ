# TitanMQ

> A next-generation message queue that unifies the strengths of Kafka, RabbitMQ, and ZeroMQ while addressing their limitations.

## Why TitanMQ?

| Feature | Kafka | RabbitMQ | ZeroMQ | TitanMQ |
|---|---|---|---|---|
| High Throughput | ✅ | ❌ | ✅ | ✅ |
| Low Latency | ❌ | ✅ | ✅ | ✅ |
| Flexible Routing | ❌ | ✅ | ❌ | ✅ |
| Persistent Storage | ✅ | ⚠️ | ❌ | ✅ |
| Exactly-Once Delivery | ⚠️ | ❌ | ❌ | ✅ |
| Brokerless Mode | ❌ | ❌ | ✅ | ✅ |
| Back-pressure | ❌ | ⚠️ | ✅ | ✅ |
| Multi-tenancy | ❌ | ⚠️ | ❌ | ✅ |
| Zero-copy Transfer | ❌ | ❌ | ✅ | ✅ |
| Built-in Observability | ❌ | ⚠️ | ❌ | ✅ |

## Architecture

TitanMQ is built on a modular, layered architecture:

```
┌─────────────────────────────────────────────────────┐
│                   Client SDK Layer                   │
│         (Producer / Consumer / Admin APIs)           │
├─────────────────────────────────────────────────────┤
│                  Protocol Layer                      │
│        (TCP / gRPC / WebSocket / In-Process)         │
├─────────────────────────────────────────────────────┤
│                  Routing Engine                       │
│    (Direct / Topic / Fanout / Content-Based)         │
├─────────────────────────────────────────────────────┤
│                  Core Broker Engine                   │
│  ┌─────────────┐ ┌──────────────┐ ┌──────────────┐ │
│  │  Commit Log  │ │  Index Engine │ │  Consumer    │ │
│  │  (Append-    │ │  (Offset +   │ │  Group Mgr   │ │
│  │   only WAL)  │ │   Time-based)│ │              │ │
│  └─────────────┘ └──────────────┘ └──────────────┘ │
├─────────────────────────────────────────────────────┤
│                  Storage Layer                        │
│     (Memory-Mapped Files / Tiered Storage)           │
├─────────────────────────────────────────────────────┤
│              Cluster & Replication                    │
│        (Raft Consensus / Partition Mgmt)             │
└─────────────────────────────────────────────────────┘
```

## Key Design Decisions

1. **Append-Only Commit Log** (from Kafka): All messages are written to an immutable, append-only log for maximum write throughput and durability.

2. **Flexible Exchange Routing** (from RabbitMQ): Support direct, topic, fanout, and content-based routing patterns beyond simple topic-partition.

3. **Zero-Copy & Lock-Free Structures** (from ZeroMQ): Memory-mapped I/O and lock-free ring buffers for ultra-low latency paths.

4. **Hybrid Delivery Semantics**: Support at-most-once, at-least-once, and exactly-once delivery via idempotent producers and transactional consumers.

5. **Adaptive Back-Pressure**: Built-in flow control that dynamically adjusts based on consumer lag, preventing OOM and cascading failures.

6. **Dual Mode**: Run as a centralized broker cluster OR embed as an in-process library (brokerless mode like ZeroMQ).

## Modules

```
titanmq/
├── titanmq-core/          # Core broker engine, commit log, indexing
├── titanmq-routing/       # Exchange and routing engine
├── titanmq-store/         # Storage layer (mmap, tiered storage)
├── titanmq-cluster/       # Raft consensus, replication, partitioning
├── titanmq-client/        # Producer & Consumer SDK
├── titanmq-protocol/      # Wire protocol definitions
├── titanmq-server/        # Broker server bootstrap
├── titanmq-benchmark/     # Performance benchmarks
└── titanmq-common/        # Shared utilities
```

## Quick Start

### Networked Mode (Kafka-style)

```java
// Producer
TitanProducer producer = TitanMQ.newProducer()
    .brokers("localhost:9500")
    .serializer(new StringSerializer())
    .build();

producer.send("orders", "order-123", orderPayload);

// Consumer
TitanConsumer consumer = TitanMQ.newConsumer()
    .brokers("localhost:9500")
    .group("order-service")
    .topics("orders")
    .deserializer(new StringDeserializer())
    .build();

consumer.subscribe(message -> {
    process(message);
    message.ack();
});
```

### Embedded/Brokerless Mode (ZeroMQ-style)

```java
// No separate broker process needed — runs entirely in-process
EmbeddedBroker broker = EmbeddedBroker.builder()
    .dataDir(Path.of("/tmp/titanmq"))
    .numPartitions(4)
    .build()
    .start();

EmbeddedProducer producer = broker.createProducer();
producer.send("events", "key-1", "payload".getBytes());

EmbeddedConsumer consumer = broker.createConsumer("my-group", "events");
consumer.subscribe(msg -> System.out.println(new String(msg.payload())));
```

### Cluster Mode (with Raft consensus)

```bash
# Node 1
java -jar titanmq-server.jar --node-id broker-1 --port 9500 --peers broker-2,broker-3

# Node 2
java -jar titanmq-server.jar --node-id broker-2 --port 9501 --peers broker-1,broker-3

# Node 3
java -jar titanmq-server.jar --node-id broker-3 --port 9502 --peers broker-1,broker-2
```

## Building

```bash
mvn clean install
```

## Running Benchmarks

```bash
mvn -pl titanmq-benchmark exec:java -Dexec.mainClass="com.titanmq.benchmark.ThroughputBenchmark"
```

## License

Apache License 2.0
