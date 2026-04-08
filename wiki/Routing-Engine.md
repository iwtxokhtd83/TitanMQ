# Routing Engine

TitanMQ's routing engine brings RabbitMQ-style exchange flexibility to a Kafka-style commit log. Messages are routed at the broker level before being written, so consumers only receive messages they care about.

## Exchange Types

### Direct Exchange

Routes messages to destinations with an exact routing key match.

```java
DirectExchange exchange = new DirectExchange("order-events");
exchange.bind("fulfillment-queue", "order.created");
exchange.bind("billing-queue", "order.paid");
exchange.bind("analytics-queue", "order.created");  // Multiple bindings OK

// Routes to "fulfillment-queue" and "analytics-queue"
exchange.route(message, "order.created");

// Routes to "billing-queue" only
exchange.route(message, "order.paid");

// Routes to nothing
exchange.route(message, "order.cancelled");
```

### Topic Exchange

Routes using wildcard pattern matching:
- `*` matches exactly one word
- `#` matches zero or more words

Words are separated by dots (`.`).

```java
TopicExchange exchange = new TopicExchange("events");
exchange.bind("us-handler", "order.us.*");
exchange.bind("all-errors", "#.error");
exchange.bind("specific", "order.*.created");

// Matches "us-handler"
exchange.route(message, "order.us.created");

// Matches "all-errors"
exchange.route(message, "payment.processing.error");

// Matches "specific"
exchange.route(message, "order.eu.created");

// Matches "all-errors" (# matches zero or more)
exchange.route(message, "error");
```

### Fanout Exchange

Broadcasts to ALL bound destinations regardless of routing key.

```java
FanoutExchange exchange = new FanoutExchange("notifications");
exchange.bind("email-service", "");
exchange.bind("sms-service", "");
exchange.bind("push-service", "");

// Routes to all three services
exchange.route(message, "anything");
```

### Content-Based Exchange

Routes based on message header matching. This goes beyond RabbitMQ — supports arbitrary predicates.

```java
ContentBasedExchange exchange = new ContentBasedExchange("smart-router");

// Simple key=value matching
exchange.bind("us-queue", "region=us");
exchange.bind("eu-queue", "region=eu,type=order");

// Programmatic predicate
exchange.bind("priority-queue", headers ->
    Integer.parseInt(headers.getOrDefault("priority", "0")) > 5);

// Message with header region=us routes to "us-queue"
TitanMessage msg = TitanMessage.builder()
    .topic("test")
    .payload("data".getBytes())
    .header("region", "us")
    .build();
exchange.route(msg, "");  // → ["us-queue"]
```

## Why Not Just Use Topics?

In Kafka, if you want to route order events differently based on region, you'd either:
- Create separate topics (`orders-us`, `orders-eu`) — topic proliferation
- Have every consumer filter client-side — wasted bandwidth

TitanMQ's routing engine handles this at the broker level. One logical event stream, multiple routing rules, consumers only get what they need.
