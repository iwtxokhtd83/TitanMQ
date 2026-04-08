# Design Decisions

Key architectural choices and the reasoning behind them.

## 1. Append-Only Commit Log (from Kafka)

**Decision**: All messages are written to an immutable, append-only log.

**Why**: Sequential disk writes are 100x faster than random writes on SSDs. An append-only log maximizes write throughput, provides natural ordering, and enables replay from any offset.

**Trade-off**: No in-place updates. Deleting individual messages requires compaction (planned).

## 2. Exchange Routing (from RabbitMQ)

**Decision**: Layer a flexible routing engine on top of the commit log.

**Why**: Kafka's topic-only routing forces either topic proliferation or client-side filtering. RabbitMQ's exchange model (direct, topic, fanout, headers) solves this elegantly. We added content-based routing with arbitrary predicates, which goes beyond RabbitMQ.

**Trade-off**: Routing adds a small amount of latency per message. For pure topic-partition workloads, the routing layer is bypassed.

## 3. Custom Binary Protocol (not gRPC/HTTP2)

**Decision**: Design a minimal binary protocol with 9-byte frame overhead.

**Why**: Full control over every byte on the wire. No protobuf encoding overhead, no HTTP/2 stream management complexity. Direct ByteBuffer integration for zero-copy paths.

**Trade-off**: No built-in cross-language support (would need to implement codecs per language). gRPC would give us that for free.

## 4. Embedded Raft (not ZooKeeper/etcd)

**Decision**: Implement Raft consensus directly in the broker.

**Why**: ZooKeeper is a separate service to deploy and monitor. KRaft (Kafka's replacement) took years to stabilize. Embedding Raft means zero external dependencies and simpler operations.

**Trade-off**: We own the consensus implementation — more code to maintain and get right. The Raft paper is well-specified, which helps.

## 5. Dual-Watermark Back-Pressure

**Decision**: Use high/low watermarks with a continuous throttle ratio.

**Why**: Binary on/off throttling causes oscillation. The hysteresis gap between watermarks creates stability. The continuous 0.0–1.0 signal lets producers implement their own slowdown curves.

**Trade-off**: Slightly more complex than a simple threshold. Worth it for production stability.

## 6. Java 21 with Records and Virtual Threads

**Decision**: Target Java 21 as the minimum version.

**Why**: Records for value types (`TopicPartition`, `Command`, `LogEntry`), pattern matching in switch, and virtual threads (future) for high-concurrency consumer handling.

**Trade-off**: Excludes users on Java 17 or earlier. Java 21 is LTS, so this is reasonable.

## 7. Netty for Networking

**Decision**: Use Netty instead of Java NIO directly or gRPC.

**Why**: Netty provides non-blocking I/O with an event loop model, built-in frame decoders, zero-copy ByteBuf, and battle-tested production reliability. Writing raw NIO is error-prone; gRPC adds too much abstraction.

**Trade-off**: Netty is a large dependency. Acceptable for a broker.

## 8. MurmurHash3 for Partitioning

**Decision**: Use MurmurHash3 for key-based partition assignment.

**Why**: Excellent distribution, fast (no crypto overhead), and the same algorithm Kafka uses — making migration easier.

**Trade-off**: Not cryptographically secure (irrelevant for partitioning).

## 9. Tiered Storage

**Decision**: Three-tier model (HOT → WARM → COLD) with pluggable backends.

**Why**: Kafka stores everything on one tier. For 30+ day retention, this means expensive SSD storage for rarely-accessed data. Tiered storage can reduce costs by 80%+ by moving old data to object storage.

**Trade-off**: Cold reads are slower (remote fetch). Mitigated by local caching.

## 10. No-Op on Leader Election

**Decision**: New leaders append a no-op entry immediately.

**Why**: Raft §5.4.2 says leaders can only commit entries from their own term. Without a no-op, a new leader with only previous-term entries has a liveness bug — it can never advance the commit index until a client command arrives.

**Trade-off**: One extra log entry per election. Negligible cost for correctness.
