# Producer API

## Networked Producer

```java
TitanProducer<String, String> producer = TitanMQ.<String, String>newProducer()
    .brokers("localhost:9500")
    .keySerializer(new StringSerializer())
    .valueSerializer(new StringSerializer())
    .build()
    .connect();
```

### Async Send

```java
CompletableFuture<SendResult> future = producer.send("orders", "order-123", orderJson);
future.thenAccept(result -> {
    System.out.printf("Sent to partition %d at offset %d%n",
        result.partition(), result.offset());
});
```

### Sync Send

```java
SendResult result = producer.sendSync("orders", "order-123", orderJson);
```

### Send with Headers

```java
producer.send("orders", "order-123", orderJson, Map.of(
    "region", "us-east",
    "priority", "high",
    "source", "web-app"
));
```

### Cleanup

```java
producer.close();  // Cancels pending requests, closes connection
```

## Embedded Producer

```java
EmbeddedProducer producer = broker.createProducer();

// Sync (returns offset directly)
long offset = producer.send("topic", "key", payload);

// Async
CompletableFuture<Long> future = producer.sendAsync("topic", "key", payload);

producer.close();
```

## Delivery Semantics

| Mode | How |
|---|---|
| At-most-once | Fire and forget — don't wait for ACK |
| At-least-once | Wait for ACK, retry on failure |
| Exactly-once | Idempotent producer (dedup by message ID) — planned |

## Partitioning

The producer determines the target partition:

1. Explicit: `TitanMessage.builder().partition(3)` — goes to partition 3
2. Key-based: `producer.send("topic", "customer-123", data)` — MurmurHash3 of key
3. Round-robin: `producer.send("topic", null, data)` — distributed by thread
