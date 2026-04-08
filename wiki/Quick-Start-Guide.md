# Quick Start Guide

Get TitanMQ running in under 5 minutes.

## Prerequisites

- Java 21+
- Maven 3.9+

## Build

```bash
git clone https://github.com/iwtxokhtd83/TitanMQ.git
cd TitanMQ
mvn clean install -DskipTests
```

## Option 1: Standalone Broker

Start a single-node broker:

```bash
java -jar titanmq-server/target/titanmq-server-1.0.0-SNAPSHOT.jar \
  --port 9500 \
  --data-dir /tmp/titanmq \
  --partitions 8
```

Then use the client SDK:

```java
// Producer
TitanProducer<String, String> producer = TitanMQ.<String, String>newProducer()
    .brokers("localhost:9500")
    .keySerializer(new StringSerializer())
    .valueSerializer(new StringSerializer())
    .build()
    .connect();

producer.send("orders", "order-123", "{\"item\": \"widget\", \"qty\": 5}");

// Consumer
TitanConsumer<String, String> consumer = TitanMQ.<String, String>newConsumer()
    .brokers("localhost:9500")
    .group("order-service")
    .topics("orders")
    .keyDeserializer(new StringDeserializer())
    .valueDeserializer(new StringDeserializer())
    .build()
    .connect();

consumer.subscribe(message -> {
    System.out.println("Received: " + message.value());
    message.ack();
});
```

## Option 2: Embedded Mode (No Broker Needed)

Run everything in-process — no separate broker, no network:

```java
import com.titanmq.core.embedded.*;

EmbeddedBroker broker = EmbeddedBroker.builder()
    .dataDir(Path.of("/tmp/titanmq"))
    .numPartitions(4)
    .build()
    .start();

// Producer
EmbeddedProducer producer = broker.createProducer();
producer.send("events", "user-123", "logged-in".getBytes());

// Consumer
EmbeddedConsumer consumer = broker.createConsumer("my-group", "events");
List<TitanMessage> messages = consumer.poll(100);
messages.forEach(msg -> System.out.println(new String(msg.payload())));

// Or use push-based subscription
consumer.subscribe(msg -> {
    System.out.println("Event: " + new String(msg.payload()));
});

// Cleanup
producer.close();
consumer.close();
broker.close();
```

## Option 3: Cluster Mode

See [[Cluster Deployment]] for multi-node setup.

## Next Steps

- [[Message Model]] — Understand the message format
- [[Routing Engine]] — Set up flexible message routing
- [[Configuration Reference]] — Tune broker settings
