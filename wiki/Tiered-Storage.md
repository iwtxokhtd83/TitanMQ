# Tiered Storage

TitanMQ supports three-tier storage to balance performance and cost for different data ages.

## Tier Model

```
  HOT (active, memory-mapped)          ← Sub-ms access, highest cost
    │ after hotRetention (default: 1h)
    ▼
  WARM (local SSD, read-optimized)     ← Ms access, moderate cost
    │ after warmRetention (default: 7d)
    ▼
  COLD (remote/object storage)         ← Higher latency, lowest cost
```

## How It Works

1. New messages are written to **HOT** segments (the active commit log)
2. When a segment's age exceeds `hotRetention`, it's demoted to **WARM**
3. When a WARM segment's age exceeds `warmRetention`, it's offloaded to **COLD** storage
4. Reads from COLD segments transparently fetch data back to local cache

Consumers don't know or care which tier their data is in — reads work across all tiers.

## Configuration

```java
TierConfig config = new TierConfig()
    .hotRetention(Duration.ofHours(2))
    .warmRetention(Duration.ofDays(7))
    .warmStoragePath(Path.of("/ssd/titanmq/warm"))
    .coldStoragePath(Path.of("/archive/titanmq/cold"))
    .coldTierEnabled(true)
    .tierCheckIntervalMs(60_000);  // Check every minute
```

## Storage Backends

Cold storage uses the `RemoteStorageBackend` interface:

```java
public interface RemoteStorageBackend {
    void upload(Path localPath, String remotePath) throws IOException;
    void download(String remotePath, Path localPath) throws IOException;
    void delete(String remotePath) throws IOException;
    boolean exists(String remotePath) throws IOException;
}
```

Built-in implementations:
- `LocalFileStorageBackend` — Local filesystem (dev/testing, or different mount point)

Planned:
- S3-compatible object storage
- Google Cloud Storage
- Azure Blob Storage

## Monitoring

```java
TieredStorageManager manager = ...;
TierStats stats = manager.getStats();
// TierStats{hot=5, warm=12, cold=45, total=62}
```

## Comparison with Kafka

Kafka stores all data on the same tier (local disk). For long retention periods (30+ days), this means expensive SSD storage for data that's rarely accessed. TitanMQ's tiered approach can reduce storage costs by 80%+ for long-retention use cases by moving old data to cheap object storage.
