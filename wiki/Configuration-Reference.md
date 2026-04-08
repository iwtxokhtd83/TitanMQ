# Configuration Reference

All broker configuration is managed through `BrokerConfig`. Settings can be passed via CLI arguments or set programmatically.

## Broker Settings

| Setting | Default | CLI Flag | Description |
|---|---|---|---|
| `port` | `9500` | `--port` | TCP port for client connections |
| `dataDir` | `data/titanmq` | `--data-dir` | Root directory for data storage |
| `numPartitions` | `8` | `--partitions` | Default partition count for new topics |
| `segmentSizeBytes` | `1073741824` (1 GB) | — | Max size per log segment before rolling |
| `flushIntervalMs` | `1000` | — | Interval between forced disk flushes |
| `replicationFactor` | `1` | — | Number of replicas per partition |
| `numNetworkThreads` | `3` | — | Netty worker threads for I/O |
| `numIoThreads` | `8` | — | Threads for disk I/O operations |
| `maxMessageSizeBytes` | `10485760` (10 MB) | — | Maximum allowed message size |
| `enableZeroCopy` | `true` | — | Use memory-mapped I/O for reads |

## Back-Pressure Settings

| Setting | Default | Description |
|---|---|---|
| `backPressureHighWaterMark` | `100,000` | In-flight count that triggers throttling (CAS-enforced, never exceeded) |
| `backPressureLowWaterMark` | `50,000` | In-flight count that resumes normal operation |

## Durability Settings

| Setting | Default | Description |
|---|---|---|
| `flushPolicy` | `PERIODIC` | When to fsync: `EVERY_MESSAGE`, `PERIODIC`, or `NONE` |
| `flushIntervalMs` | `1000` | Interval for periodic fsync (only used with PERIODIC policy) |

## Retention Settings

| Setting | Default | Description |
|---|---|---|
| `retentionHours` | `-1` (infinite) | Delete segments older than this. -1 = keep forever |
| `retentionBytes` | `-1` (infinite) | Delete oldest segments when total exceeds this. -1 = no limit |

The gap between high and low watermarks prevents oscillation. See [[Back-Pressure]] for details.

## Cluster Settings

| CLI Flag | Description |
|---|---|
| `--node-id` | Unique identifier for this broker node |
| `--peers` | Comma-separated list of peer node IDs |

## Programmatic Configuration

```java
BrokerConfig config = new BrokerConfig()
    .port(9500)
    .dataDir(Path.of("/var/lib/titanmq"))
    .numPartitions(16)
    .segmentSizeBytes(512 * 1024 * 1024)  // 512 MB segments
    .numNetworkThreads(4)
    .numIoThreads(16)
    .backPressureHighWaterMark(200_000)
    .backPressureLowWaterMark(100_000);

TitanBroker broker = new TitanBroker(config);
broker.start();
```

## Tiered Storage Configuration

```java
TierConfig tierConfig = new TierConfig()
    .hotRetention(Duration.ofHours(2))       // Keep in memory-mapped files for 2h
    .warmRetention(Duration.ofDays(7))       // Keep on local SSD for 7 days
    .warmStoragePath(Path.of("/ssd/titanmq/warm"))
    .coldStoragePath(Path.of("/archive/titanmq/cold"))
    .coldTierEnabled(true)
    .tierCheckIntervalMs(60_000);            // Check every minute
```
