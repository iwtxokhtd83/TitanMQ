# Consumer API

## Networked Consumer

```java
TitanConsumer<String, String> consumer = TitanMQ.<String, String>newConsumer()
    .brokers("localhost:9500")
    .group("order-service")
    .topics("orders", "payments")
    .keyDeserializer(new StringDeserializer())
    .valueDeserializer(new StringDeserializer())
    .autoCommit(true)
    .build()
    .connect();
```

### Push-based (Subscribe)

```java
consumer.subscribe(message -> {
    System.out.printf("Topic=%s, Key=%s, Value=%s%n",
        message.topic(), message.key(), message.value());
    message.ack();  // Manual ack (if autoCommit=false)
});
```

### Pull-based (Poll)

```java
List<ReceivedMessage<String, String>> messages = consumer.poll(1000);  // timeout ms
for (var msg : messages) {
    process(msg);
}
```

### Cleanup

```java
consumer.close();
```

## Embedded Consumer

```java
EmbeddedConsumer consumer = broker.createConsumer("my-group", "orders", "events");

// Poll
List<TitanMessage> messages = consumer.poll(100);

// Subscribe (background thread)
consumer.subscribe(msg -> process(msg));

// Offset management
consumer.seek(new TopicPartition("orders", 0), 500);
consumer.seekToBeginning();
long pos = consumer.currentOffset(new TopicPartition("orders", 0));

consumer.close();
```

## Consumer Groups

Multiple consumers in the same group share the partitions of a topic. Each partition is assigned to exactly one consumer in the group.

```
Topic "orders" (4 partitions)
Consumer Group "order-service":
  Consumer A: [partition-0, partition-1]
  Consumer B: [partition-2, partition-3]
```

When a consumer joins or leaves, partitions are rebalanced using round-robin assignment.

## Offset Management

| Mode | Behavior |
|---|---|
| Auto-commit (`autoCommit=true`) | Offset committed after handler returns |
| Manual commit (`autoCommit=false`) | Call `message.ack()` to commit |

Committed offsets are stored in `OffsetStore` per consumer group per topic-partition. On restart, consumption resumes from the last committed offset.

## Delivery Guarantees

- **At-most-once**: Auto-commit before processing (message may be lost if processing fails)
- **At-least-once**: Commit after processing (message may be redelivered on crash)
- **Exactly-once**: Planned — requires idempotent consumer + transactional commit
