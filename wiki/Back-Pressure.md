# Back-Pressure

TitanMQ implements adaptive back-pressure with a dual-watermark system that prevents OOM conditions and cascading failures.

## The Problem

| System | Back-Pressure Approach | Limitation |
|---|---|---|
| Kafka | None built-in | Broker accepts until disk fills; no producer signal |
| RabbitMQ | TCP back-pressure + memory alarms | Binary on/off; blocks entirely or accepts everything |
| ZeroMQ | High-water mark | Library-only; no broker-level control |

## How TitanMQ Does It

```
                    ┌─── High Watermark (100,000) ───┐
                    │         REJECT zone             │
                    │                                 │
  throttleRatio:    │  ← gradual 0.0 → 1.0 →         │
                    │                                 │
                    ├─── Low Watermark (50,000) ──────┤
                    │         ACCEPT zone             │
                    │     throttleRatio = 0.0          │
                    └─────────────────────────────────┘
```

### Three Zones

1. **Below low watermark**: Full speed. `tryAcquire()` always returns `true`. `throttleRatio()` = 0.0
2. **Between watermarks**: Accept messages but signal producers to slow down. `throttleRatio()` returns 0.0–1.0 linearly.
3. **Above high watermark**: Reject new messages. `tryAcquire()` returns `false`. Producer gets an error response.

### The Hysteresis Gap

The gap between high (100K) and low (50K) watermarks prevents oscillation. Without it, the system would rapidly flip between throttled/unthrottled when hovering near a single threshold.

## API

```java
BackPressureController controller = new BackPressureController(100_000, 50_000);

// Before writing a message
if (!controller.tryAcquire()) {
    // Reject: broker is overloaded
    return error("Back-pressure active");
}
try {
    writeMessage(msg);
} finally {
    controller.release();
}

// For gradual producer-side slowdown
double ratio = controller.throttleRatio();  // 0.0 to 1.0
if (ratio > 0) {
    Thread.sleep((long)(ratio * 100));  // Gradual delay
}
```

## Integration

Back-pressure is enforced in `BrokerRequestHandler.handleProduce()`:

```java
private void handleProduce(ChannelHandlerContext ctx, Command cmd) throws Exception {
    if (!backPressureController.tryAcquire()) {
        sendError(ctx, cmd.correlationId(), "Back-pressure: broker is overloaded");
        return;
    }
    try {
        // Write to commit log
    } finally {
        backPressureController.release();
    }
}
```

## Configuration

```java
new BrokerConfig()
    .backPressureHighWaterMark(200_000)   // Reject above this
    .backPressureLowWaterMark(100_000);   // Resume below this
```

Rule of thumb: set the gap to 50% of the high watermark.
