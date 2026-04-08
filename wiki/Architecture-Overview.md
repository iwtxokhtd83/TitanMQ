# Architecture Overview

TitanMQ is built on a modular, layered architecture designed to combine the best of Kafka (throughput), RabbitMQ (routing flexibility), and ZeroMQ (low latency).

## System Architecture

```
┌─────────────────────────────────────────────────────┐
│                   Client SDK Layer                   │
│         (Producer / Consumer / Admin APIs)           │
├─────────────────────────────────────────────────────┤
│                  Protocol Layer                      │
│        (Custom binary protocol over TCP/Netty)       │
├─────────────────────────────────────────────────────┤
│                  Routing Engine                       │
│    (Direct / Topic / Fanout / Content-Based)         │
├─────────────────────────────────────────────────────┤
│                  Core Broker Engine                   │
│  ┌─────────────┐ ┌──────────────┐ ┌──────────────┐ │
│  │  Topic Mgr   │ │  Consumer    │ │  Back-       │ │
│  │  (Partition   │ │  Group Mgr   │ │  Pressure    │ │
│  │   routing)   │ │  (Rebalance) │ │  Controller  │ │
│  └─────────────┘ └──────────────┘ └──────────────┘ │
├─────────────────────────────────────────────────────┤
│                  Storage Layer                        │
│     (Append-only commit log, tiered storage)         │
├─────────────────────────────────────────────────────┤
│              Cluster Coordination                     │
│        (Embedded Raft — no ZooKeeper needed)         │
└─────────────────────────────────────────────────────┘
```

## Module Structure

| Module | Dependency | Responsibility |
|---|---|---|
| `titanmq-common` | — | Shared types: `TitanMessage`, `TopicPartition`, `BrokerConfig`, serialization |
| `titanmq-protocol` | common | Wire protocol: `Command`, `CommandType`, Netty codecs |
| `titanmq-store` | common | Storage: `CommitLog`, `LogSegment`, `OffsetStore`, tiered storage |
| `titanmq-core` | common, store | Broker engine: `TopicManager`, `ConsumerGroupManager`, `BackPressureController`, embedded mode |
| `titanmq-routing` | common | Exchange routing: Direct, Topic, Fanout, Content-Based |
| `titanmq-cluster` | common, protocol | Raft consensus: `RaftNode`, `RaftLog`, RPC transport |
| `titanmq-client` | common, protocol | Client SDK: `TitanProducer`, `TitanConsumer`, `TitanMQ` |
| `titanmq-server` | all above | Broker bootstrap: `TitanBroker`, `BrokerNetworkServer` |
| `titanmq-benchmark` | common, store, core | JMH benchmarks and throughput tests |

## Dependency Graph

```
titanmq-common
    ├── titanmq-protocol
    ├── titanmq-store
    │     └── titanmq-core
    │           └── titanmq-server
    ├── titanmq-routing
    │     └── titanmq-server
    ├── titanmq-cluster
    │     └── titanmq-server
    └── titanmq-client
```

## Design Principles

1. **Append-only commit log** — All writes are sequential for maximum disk throughput
2. **Flexible routing** — Exchange model layered on top of the commit log
3. **Zero-copy I/O** — Memory-mapped files and direct ByteBuffer serialization
4. **Adaptive back-pressure** — Dual watermark system with continuous throttle signal
5. **Embedded Raft** — No external coordination service needed
6. **Dual mode** — Run as a broker cluster or embed as an in-process library
