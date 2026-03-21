# TitanMQ Architecture Design Document

## 1. Overview

TitanMQ is a next-generation message queue designed to unify the strengths of existing MQ systems while addressing their individual weaknesses:

| System | Strengths Adopted | Weaknesses Addressed |
|--------|-------------------|---------------------|
| Kafka | Append-only log, high throughput, consumer groups | Complex ops, no flexible routing, high tail latency |
| RabbitMQ | Flexible routing (exchanges), protocol support | Low throughput, no log-based storage, poor horizontal scaling |
| ZeroMQ | Ultra-low latency, zero-copy, brokerless mode | No persistence, no routing, no consumer groups |

## 2. Core Design Principles

### 2.1 Append-Only Commit Log (from Kafka)
All messages are written to an immutable, append-only log. This provides:
- Sequential disk I/O for maximum write throughput
- Natural message ordering within partitions
- Replay capability from any offset
- Efficient compaction and retention policies

### 2.2 Flexible Exchange Routing (from RabbitMQ)
TitanMQ supports four exchange types layered on top of the commit log:
- **Direct**: Exact routing key match
- **Topic**: Wildcard pattern matching (`*` and `#`)
- **Fanout**: Broadcast to all bound destinations
- **Content-Based**: Route by message headers/content (beyond RabbitMQ)

### 2.3 Zero-Copy & Lock-Free (from ZeroMQ)
- Memory-mapped file I/O for zero-copy reads
- Lock-free ring buffers for inter-thread communication
- Direct ByteBuffer serialization to avoid GC pressure

### 2.4 Adaptive Back-Pressure
Unlike Kafka (which has no built-in back-pressure) and RabbitMQ (which uses basic flow control):
- High/low watermark system with gradual throttling
- Producer-side back-pressure signals
- Prevents OOM and cascading failures

## 3. Architecture Layers

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
│  ┌─────────────┐ ┌──────────────┐ ┌──────────────┐ │
│  │  Commit Log  │ │  Log Segment │ │  Offset      │ │
│  │  (per-       │ │  (mmap file, │ │  Store       │ │
│  │   partition) │ │   sparse idx)│ │              │ │
│  └─────────────┘ └──────────────┘ └──────────────┘ │
├─────────────────────────────────────────────────────┤
│              Cluster & Replication                    │
│        (Embedded Raft — no ZooKeeper needed)         │
└─────────────────────────────────────────────────────┘
```

## 4. Storage Design

### 4.1 Commit Log
Each topic-partition has its own commit log, composed of segments:

```
topic-orders/partition-0/
├── 00000000000000000000.log    (segment 0: offsets 0-999)
├── 00000000000000001000.log    (segment 1: offsets 1000-1999)
└── 00000000000000002000.log    (segment 2: active)
```

### 4.2 Segment Format
```
┌──────────┬──────────────┬─────────────────────┐
│ 8 bytes  │ 4 bytes      │ N bytes             │
│ offset   │ message size │ serialized message  │
├──────────┼──────────────┼─────────────────────┤
│ offset   │ message size │ serialized message  │
└──────────┴──────────────┴─────────────────────┘
```

### 4.3 Index
Sparse in-memory index mapping offset → file position. Binary search for O(log n) lookups.

## 5. Wire Protocol

Custom binary protocol optimized for throughput:

```
Frame: [4B length][1B command type][4B correlation ID][NB payload]
```

Command types: PRODUCE, PRODUCE_ACK, FETCH, FETCH_RESPONSE, COMMIT_OFFSET, etc.

## 6. Cluster Consensus

Embedded Raft implementation (no external ZooKeeper/etcd dependency):
- Leader election with randomized timeouts
- Log replication for metadata
- Partition leadership assignment
- Sub-second failover

## 7. Delivery Semantics

| Mode | Mechanism |
|------|-----------|
| At-most-once | Fire and forget, no ACK wait |
| At-least-once | Producer retries + consumer ACK before commit |
| Exactly-once | Idempotent producer (dedup by message ID) + transactional consumer |

## 8. Performance Targets

| Metric | Target |
|--------|--------|
| Write throughput | > 1M msgs/sec (1KB messages, 8 partitions) |
| Read throughput | > 2M msgs/sec |
| P99 latency (produce) | < 5ms |
| P99 latency (end-to-end) | < 10ms |
| Max message size | 10MB |
| Max partitions per topic | 1024 |

## 9. Module Structure

| Module | Responsibility |
|--------|---------------|
| titanmq-common | Shared types, config, serialization |
| titanmq-protocol | Wire protocol, Netty codecs |
| titanmq-store | Commit log, segments, offset management |
| titanmq-core | Topic management, consumer groups, back-pressure |
| titanmq-routing | Exchange types, routing engine |
| titanmq-cluster | Raft consensus, replication |
| titanmq-client | Producer/Consumer SDK |
| titanmq-server | Broker bootstrap, network server |
| titanmq-benchmark | JMH and throughput benchmarks |
