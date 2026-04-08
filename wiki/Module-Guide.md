# Module Guide

TitanMQ is organized into 9 Maven modules with clear dependency boundaries.

## Module Map

```
titanmq-parent (root POM)
├── titanmq-common          Zero dependencies (shared types)
├── titanmq-protocol        → common (wire protocol)
├── titanmq-store           → common (storage engine)
├── titanmq-core            → common, store (broker engine)
├── titanmq-routing         → common (exchange routing)
├── titanmq-cluster         → common, protocol (Raft consensus)
├── titanmq-client          → common, protocol (SDK)
├── titanmq-server          → all modules (broker bootstrap)
└── titanmq-benchmark       → common, store, core (perf tests)
```

## titanmq-common

Foundation types shared by all modules.

| Class | Purpose |
|---|---|
| `TitanMessage` | Core message abstraction with builder, serialize/deserialize |
| `TopicPartition` | Record identifying a specific partition |
| `BrokerConfig` | All broker configuration with fluent API |
| `Serializer<T>` | Interface for object → byte[] conversion |
| `Deserializer<T>` | Interface for byte[] → object conversion |
| `StringSerializer` | Built-in UTF-8 string serializer |
| `StringDeserializer` | Built-in UTF-8 string deserializer |

## titanmq-protocol

Wire protocol definitions and Netty codecs.

| Class | Purpose |
|---|---|
| `Command` | Wire frame: type + correlationId + payload |
| `CommandType` | Enum of all protocol commands (PRODUCE, FETCH, etc.) |
| `TitanMessageEncoder` | Netty encoder: Command → ByteBuf |
| `TitanMessageDecoder` | Netty decoder: ByteBuf → Command (length-field framing) |

## titanmq-store

Persistent storage engine.

| Class | Purpose |
|---|---|
| `CommitLog` | Per-partition append-only log with segment management |
| `LogSegment` | Single segment file with sparse index |
| `OffsetStore` | Consumer group offset tracking |
| `tier/StorageTier` | HOT, WARM, COLD tier enum |
| `tier/TierConfig` | Tiered storage configuration |
| `tier/TieredSegment` | Segment wrapper with tier-aware reads |
| `tier/TieredStorageManager` | Background tier promotion/demotion |
| `tier/RemoteStorageBackend` | Interface for cold storage (S3, etc.) |
| `tier/LocalFileStorageBackend` | Filesystem-based cold storage |

## titanmq-core

Broker engine and embedded mode.

| Class | Purpose |
|---|---|
| `TopicManager` | Topic/partition management, key-based routing |
| `ConsumerGroupManager` | Consumer group membership and rebalancing |
| `BackPressureController` | Dual-watermark flow control |
| `embedded/EmbeddedBroker` | In-process broker (no network) |
| `embedded/EmbeddedProducer` | In-process producer |
| `embedded/EmbeddedConsumer` | In-process consumer with poll/subscribe |

## titanmq-routing

Exchange-based message routing.

| Class | Purpose |
|---|---|
| `Exchange` | Interface for all exchange types |
| `ExchangeType` | DIRECT, TOPIC, FANOUT, CONTENT_BASED |
| `DirectExchange` | Exact routing key match |
| `TopicExchange` | Wildcard pattern matching (* and #) |
| `FanoutExchange` | Broadcast to all bindings |
| `ContentBasedExchange` | Header/content predicate matching |

## titanmq-cluster

Raft consensus and cluster coordination.

| Class | Purpose |
|---|---|
| `RaftNode` | Full Raft implementation (election, replication, commit) |
| `RaftLog` | Replicated log with conflict resolution |
| `RaftState` | FOLLOWER, CANDIDATE, LEADER |
| `RaftRpcMessages` | VoteRequest, AppendEntries, responses |
| `RaftTransport` | Transport abstraction for RPCs |
| `InMemoryRaftTransport` | In-process transport (testing) |
| `NettyRaftTransport` | TCP transport (production) |

## titanmq-client

Producer and consumer SDK.

| Class | Purpose |
|---|---|
| `TitanMQ` | Entry point: `newProducer()`, `newConsumer()` |
| `TitanProducer` | Async/sync message sending |
| `TitanConsumer` | Poll/subscribe message consumption |
| `TitanConnection` | Netty client connection management |

## titanmq-server

Broker server bootstrap.

| Class | Purpose |
|---|---|
| `TitanBroker` | Main server: orchestrates all subsystems |
| `BrokerNetworkServer` | Netty TCP server (boss/worker threads) |
| `BrokerRequestHandler` | Dispatches incoming commands |
