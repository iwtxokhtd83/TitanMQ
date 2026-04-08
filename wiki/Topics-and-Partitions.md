# Topics and Partitions

## Concepts

A **topic** is a named stream of messages. Each topic is divided into **partitions** for parallelism and scalability.

```
Topic: "orders"
├── Partition 0: [msg0, msg1, msg4, msg7, ...]
├── Partition 1: [msg2, msg5, msg8, ...]
├── Partition 2: [msg3, msg6, msg9, ...]
└── Partition 3: [msg10, msg11, ...]
```

Each partition is an independent, ordered, append-only commit log. Messages within a partition are assigned monotonically increasing offsets.

## Storage Layout

On disk, each partition is stored as a series of segment files:

```
data/titanmq/
└── orders/
    ├── partition-0/
    │   ├── 00000000000000000000.log    (offsets 0–999)
    │   ├── 00000000000000001000.log    (offsets 1000–1999)
    │   └── 00000000000000002000.log    (active)
    ├── partition-1/
    │   └── 00000000000000000000.log
    └── ...
```

Segments roll when they exceed `segmentSizeBytes` (default: 1 GB).

## Creating Topics

Topics are auto-created on first publish with the default partition count. To create explicitly:

```java
// Programmatic
topicManager.createTopic("orders", 16);

// Via wire protocol (CREATE_TOPIC command)
```

## Partition Count Guidelines

| Workload | Recommended Partitions |
|---|---|
| Low throughput (< 10K msg/s) | 4–8 |
| Medium throughput (10K–100K msg/s) | 8–32 |
| High throughput (> 100K msg/s) | 32–128 |
| Maximum parallelism | 1 partition per consumer thread |

More partitions = more parallelism, but also more file handles and memory overhead.

## Ordering Guarantees

- **Within a partition**: Strict ordering. Messages are consumed in the order they were produced.
- **Across partitions**: No ordering guarantee. Use message keys to ensure related messages go to the same partition.

```java
// All orders for customer-123 go to the same partition
producer.send("orders", "customer-123", orderPayload);
```
