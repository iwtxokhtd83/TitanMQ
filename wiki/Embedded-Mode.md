# Embedded Mode

Embedded mode runs the entire TitanMQ broker in-process without any network I/O. Inspired by ZeroMQ's library-based approach.

## When to Use

- **Unit/integration testing** — No need to start a separate broker process
- **Single-process applications** — Inter-thread communication with persistence
- **Ultra-low latency** — No network serialization overhead
- **Prototyping** — Get started without infrastructure setup

## Quick Start

```java
import com.titanmq.core.embedded.*;

// Create and start
EmbeddedBroker broker = EmbeddedBroker.builder()
    .dataDir(Path.of("/tmp/titanmq"))
    .numPartitions(4)
    .segmentSize(256 * 1024 * 1024)  // 256 MB segments
    .backPressure(100_000, 50_000)
    .build()
    .start();

// Use it
EmbeddedProducer producer = broker.createProducer();
EmbeddedConsumer consumer = broker.createConsumer("my-group", "my-topic");

// Cleanup
producer.close();
consumer.close();
broker.close();
```

## Producer

```java
EmbeddedProducer producer = broker.createProducer();

// Synchronous send (returns offset)
long offset = producer.send("topic", "key", "payload".getBytes());

// With headers
long offset = producer.send("topic", "key", payload, Map.of("region", "us"));

// Asynchronous send
CompletableFuture<Long> future = producer.sendAsync("topic", "key", payload);
future.thenAccept(off -> System.out.println("Written at offset " + off));
```

## Consumer

### Pull-based (poll)

```java
EmbeddedConsumer consumer = broker.createConsumer("group-1", "orders", "events");

// Poll for messages
List<TitanMessage> messages = consumer.poll(100);  // max 100 messages
for (TitanMessage msg : messages) {
    process(msg);
}
// Offsets are tracked automatically — next poll continues where you left off
```

### Push-based (subscribe)

```java
consumer.subscribe(msg -> {
    System.out.println("Received: " + new String(msg.payload()));
});
// Runs in a background thread, calls your handler for each message
```

### Offset Management

```java
// Seek to a specific offset
consumer.seek(new TopicPartition("orders", 0), 100);

// Replay from the beginning
consumer.seekToBeginning();

// Check current position
long offset = consumer.currentOffset(new TopicPartition("orders", 0));
```

## Direct Broker API

For maximum control, use the broker directly:

```java
// Publish
TitanMessage msg = TitanMessage.builder()
    .topic("events")
    .key("key-1")
    .payload("data".getBytes())
    .build();
long offset = broker.publish(msg);

// Consume
List<TitanMessage> msgs = broker.consume(
    new TopicPartition("events", 0), 0, 100);
```

## Embedded vs. Networked

| Aspect | Embedded | Networked |
|---|---|---|
| Latency | Sub-microsecond | Milliseconds |
| Deployment | In-process library | Separate broker process |
| Persistence | Yes (same commit log) | Yes |
| Back-pressure | Yes | Yes |
| Multi-process | No | Yes |
| Clustering | No | Yes (Raft) |

The same back-pressure controller and commit log are used in both modes — the only difference is whether messages travel over the network.
