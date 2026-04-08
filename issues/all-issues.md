# TitanMQ Issues — Copy to GitHub

---

## Issue 1

**Title:** `bug: TitanConsumer.poll() always returns empty list — networked consumer is non-functional`

**Labels:** `bug`, `critical`, `client`

**Body:**

### Description

`TitanConsumer.poll()` is a stub that sleeps for 10ms and returns `List.of()`. Networked consumers cannot receive any messages.

```java
// titanmq-client/.../TitanConsumer.java line 95
public List<ReceivedMessage<K, V>> poll(long timeoutMs) {
    try {
        Thread.sleep(Math.min(timeoutMs, 10));
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    return List.of();  // Always empty!
}
```

### Expected Behavior

`poll()` should send a FETCH request to the broker, deserialize the FETCH_RESPONSE, track offsets, and return deserialized messages.

### Impact

Core consumer functionality is broken in networked mode. Only embedded mode works.

---

## Issue 2

**Title:** `bug: Producer async sends hang forever — ClientHandler doesn't route PRODUCE_ACK to producer`

**Labels:** `bug`, `critical`, `client`

**Body:**

### Description

`TitanConnection.ClientHandler` receives broker responses but discards them. `TitanProducer.onProduceAck()` is never called, so `CompletableFuture<SendResult>` from `send()` never completes.

```java
// titanmq-client/.../TitanConnection.java
private static class ClientHandler extends SimpleChannelInboundHandler<Command> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Command cmd) {
        log.debug("Received response: type={}, correlationId={}", cmd.type(), cmd.correlationId());
        // Response is logged but never routed to the producer!
    }
}
```

### Fix

`TitanConnection` needs a reference to the producer (or a response dispatcher) to route `PRODUCE_ACK` responses back via `onProduceAck()`. Also need to handle `FETCH_RESPONSE`, `COMMIT_OFFSET_ACK`, and `ERROR` responses.

---

## Issue 3

**Title:** `bug: OffsetStore is in-memory only — consumer offsets lost on broker restart`

**Labels:** `bug`, `critical`, `storage`

**Body:**

### Description

`OffsetStore` uses a `ConcurrentHashMap` with no persistence. When the broker restarts, all consumer group offsets are lost. Consumers restart from offset 0, causing full message reprocessing.

### Fix

Persist offsets to a dedicated file or internal topic (like Kafka's `__consumer_offsets`). Load on startup.

---

## Issue 4

**Title:** `bug: CommitLog has no recovery on restart — existing segments are ignored`

**Labels:** `bug`, `critical`, `storage`

**Body:**

### Description

`CommitLog` constructor always calls `rollNewSegment()` starting from offset 0. It never scans the data directory for existing segment files. After a broker restart, all previously written data is invisible (new segments overwrite or shadow old ones).

```java
public CommitLog(Path directory, long maxSegmentSize) throws IOException {
    this.directory = directory;
    this.maxSegmentSize = maxSegmentSize;
    this.directory.toFile().mkdirs();
    rollNewSegment();  // Always starts fresh at offset 0
}
```

### Fix

On startup, scan the data directory for existing `.log` files, sort by base offset, recover `nextOffset` from the last segment's end offset, and reopen existing segments.

---

## Issue 5

**Title:** `bug: No fsync — messages can be lost on OS crash`

**Labels:** `bug`, `high`, `storage`

**Body:**

### Description

`LogSegment.append()` calls `channel.write()` but never calls `channel.force(true)` to flush to disk. If the OS crashes (not just the JVM), buffered writes are lost.

### Fix

Add configurable fsync policy:
- `EVERY_MESSAGE` — `force()` after each append (safest, slowest)
- `BATCH` — `force()` every N messages
- `PERIODIC` — `force()` every N milliseconds (default, good balance)
- `NONE` — rely on OS page cache (fastest, least safe)

---

## Issue 6

**Title:** `bug: Raft log is in-memory only — cluster state lost on restart`

**Labels:** `bug`, `critical`, `cluster`

**Body:**

### Description

`RaftLog` stores entries in an `ArrayList` in memory. On broker restart, the entire Raft state (term, votedFor, log entries) is lost. This can cause:
- Split-brain: two nodes think they're leader for the same term
- Data loss: committed entries disappear
- Violated safety: a node votes for two different candidates in the same term

### Fix

Persist to disk:
1. `currentTerm` and `votedFor` — must survive restart (Raft §5.2)
2. Log entries — append to a dedicated WAL file
3. Load on startup before joining the cluster

---

## Issue 7

**Title:** `bug: BackPressureController.tryAcquire() has race condition — can exceed high watermark`

**Labels:** `bug`, `medium`, `core`

**Body:**

### Description

`tryAcquire()` uses `incrementAndGet()` then checks the limit. Between the increment and the check, N other threads can also increment, allowing the count to exceed `highWaterMark` by up to N.

```java
public boolean tryAcquire() {
    long current = inFlightCount.incrementAndGet();  // Already incremented!
    if (current > highWaterMark) {
        inFlightCount.decrementAndGet();  // Undo, but damage may be done
        return false;
    }
    return true;
}
```

### Fix

Use a CAS loop:
```java
public boolean tryAcquire() {
    while (true) {
        long current = inFlightCount.get();
        if (current >= highWaterMark) return false;
        if (inFlightCount.compareAndSet(current, current + 1)) return true;
    }
}
```

---

## Issue 8

**Title:** `bug: No segment cleanup — disk usage grows unbounded`

**Labels:** `bug`, `high`, `storage`

**Body:**

### Description

`CommitLog` never deletes old segments. Over time, disk usage grows without bound until the broker runs out of space.

### Fix

Implement retention policies:
- **Time-based**: Delete segments older than N hours/days
- **Size-based**: Delete oldest segments when total size exceeds limit
- **Offset-based**: Delete segments below the minimum committed offset across all consumer groups

Run cleanup on a background thread, configurable via `BrokerConfig`.

---

## Issue 9

**Title:** `feat: Integrate routing engine into broker — exchanges are currently dead code`

**Labels:** `enhancement`, `high`, `routing`

**Body:**

### Description

The four exchange types (Direct, Topic, Fanout, Content-Based) are fully implemented and tested, but never used by the broker. `BrokerRequestHandler.handleProduce()` writes directly to `TopicManager` without routing.

### Proposal

1. Add an `ExchangeManager` to the broker that maintains named exchanges
2. Add `DECLARE_EXCHANGE` and `BIND_EXCHANGE` wire protocol commands
3. In `handleProduce()`, check if the target has an exchange binding and route accordingly
4. Fall back to direct topic write if no exchange is configured (backward compatible)

---

## Issue 10

**Title:** `feat: Integrate tiered storage into CommitLog lifecycle`

**Labels:** `enhancement`, `high`, `storage`

**Body:**

### Description

`TieredStorageManager`, `TieredSegment`, and `RemoteStorageBackend` are implemented but not connected to `CommitLog`. All segments stay in HOT tier forever.

### Proposal

1. `CommitLog` wraps completed (non-active) segments in `TieredSegment`
2. `TieredStorageManager` runs background tier checks on these segments
3. Reads transparently fetch from cold storage when needed
4. Add `TierConfig` to `BrokerConfig`

---

## Issue 11

**Title:** `feat: Implement consumer group rebalancing on membership changes`

**Labels:** `enhancement`, `high`, `core`

**Body:**

### Description

`ConsumerGroupManager.rebalance()` exists but is never called. When consumers join or leave a group, partitions are not redistributed.

### Proposal

1. Trigger rebalance on `joinGroup()` and `leaveGroup()`
2. Notify affected consumers of their new partition assignments
3. Support pluggable strategies: RoundRobin (current), Range, Sticky
4. Add a rebalance protocol to the wire protocol (REBALANCE_NOTIFY command)

---

## Issue 12

**Title:** `feat: Add message size validation in broker`

**Labels:** `enhancement`, `medium`, `server`

**Body:**

### Description

`BrokerRequestHandler.handleProduce()` doesn't check if the incoming message exceeds `maxMessageSizeBytes` (configured at 10MB). Oversized messages are silently accepted and can cause OOM.

### Fix

Add size check before writing:
```java
if (cmd.payload().length > config.maxMessageSizeBytes()) {
    sendError(ctx, cmd.correlationId(), "Message exceeds max size");
    return;
}
```

---

## Issue 13

**Title:** `feat: Add CRC32 checksums to log segments for corruption detection`

**Labels:** `enhancement`, `medium`, `storage`

**Body:**

### Description

Log segments have no integrity checks. Corrupted data (from disk errors, partial writes) is silently returned to consumers.

### Proposal

Add a 4-byte CRC32 checksum per entry:
```
[8B offset] [4B size] [4B CRC32] [NB message]
```

Validate on read. If checksum fails, skip the entry and log a warning.

---

## Issue 14

**Title:** `feat: Persist segment index to .idx files for fast startup`

**Labels:** `enhancement`, `medium`, `storage`

**Body:**

### Description

`LogSegment`'s sparse in-memory index is lost on restart. After restart, reads require a full sequential scan of the segment file to find a specific offset.

### Proposal

Write index entries to a companion `.idx` file alongside each `.log` file. Load on startup for O(log n) reads immediately.

---

## Issue 15

**Title:** `feat: Add idempotent producer for exactly-once delivery`

**Labels:** `enhancement`, `medium`, `client`

**Body:**

### Description

No deduplication mechanism exists. If a producer retries a failed send, the message may be written twice.

### Proposal

1. Assign each producer a unique `producerId` on connect
2. Each message gets a monotonic `sequenceNumber` per producer
3. Broker tracks `(producerId, sequenceNumber)` → reject duplicates
4. Sequence state persisted in a producer state log

---

## Issue 16

**Title:** `feat: Add Prometheus metrics for observability`

**Labels:** `enhancement`, `medium`, `observability`

**Body:**

### Description

No metrics are exposed. Operators cannot monitor throughput, latency, consumer lag, or error rates.

### Proposal

Add Micrometer metrics:
- `titanmq.messages.produced` (counter, by topic)
- `titanmq.messages.consumed` (counter, by topic, group)
- `titanmq.produce.latency` (timer)
- `titanmq.consumer.lag` (gauge, by topic, partition, group)
- `titanmq.backpressure.throttle.ratio` (gauge)
- `titanmq.raft.term` (gauge)
- `titanmq.raft.elections` (counter)
- `titanmq.storage.segments` (gauge, by tier)

Expose via `/metrics` HTTP endpoint (Prometheus format).

---

## Issue 17

**Title:** `feat: Add wire protocol versioning for backward compatibility`

**Labels:** `enhancement`, `medium`, `protocol`

**Body:**

### Description

The wire protocol has no version field. Any protocol change breaks all existing clients.

### Proposal

Add a 2-byte version field to the frame header:
```
[4B length] [2B version] [1B command type] [4B correlationId] [NB payload]
```

Broker negotiates version on connection handshake. Older clients get older protocol behavior.

---

## Issue 18

**Title:** `feat: Add producer message batching for higher throughput`

**Labels:** `enhancement`, `medium`, `client`

**Body:**

### Description

Each `send()` call creates a separate network RPC. For high-throughput workloads, this is inefficient.

### Proposal

Add a `RecordAccumulator` that batches messages:
- `batchSize` — max bytes per batch (default: 16KB)
- `lingerMs` — max time to wait for a full batch (default: 5ms)
- Batch is sent when either threshold is reached
- Each batch is a single PRODUCE command with multiple messages

---

## Issue 19

**Title:** `feat: Add consumer lag tracking and monitoring`

**Labels:** `enhancement`, `medium`, `core`

**Body:**

### Description

No way to query how far behind a consumer group is. This is critical for operational monitoring.

### Proposal

Add to `ConsumerGroupManager`:
```java
public long getLag(String groupId, TopicPartition tp) {
    long latest = topicManager.latestOffset(tp);
    long committed = offsetStore.getCommittedOffset(groupId, tp);
    return latest - committed;
}
```

Expose via admin API and metrics.

---

## Issue 20

**Title:** `feat: Add Raft snapshots for fast follower recovery`

**Labels:** `enhancement`, `low`, `cluster`

**Body:**

### Description

Followers must replay the entire Raft log from index 0 to catch up. For large logs, this is slow.

### Proposal

Implement Raft snapshots (§7):
1. Periodically snapshot the state machine state
2. Truncate log entries before the snapshot
3. Send snapshot to slow followers via InstallSnapshot RPC
4. Follower loads snapshot and resumes from snapshot index

---

## Issue 21

**Title:** `feat: Add dynamic cluster membership changes`

**Labels:** `enhancement`, `low`, `cluster`

**Body:**

### Description

Cluster membership is fixed at startup via `--peers`. Cannot add or remove brokers without restarting the entire cluster.

### Proposal

Implement Raft joint consensus (§6):
1. Leader proposes membership change as a special log entry
2. Transition through joint configuration (old + new)
3. Once committed, switch to new configuration
4. Add `ADD_NODE` / `REMOVE_NODE` admin commands

---

## Issue 22

**Title:** `feat: Add authentication and authorization (SASL/TLS + ACLs)`

**Labels:** `enhancement`, `low`, `security`

**Body:**

### Description

No security whatsoever. Any client can connect and read/write any topic.

### Proposal

Phase 1: TLS encryption for transport
Phase 2: SASL authentication (PLAIN, SCRAM)
Phase 3: ACLs — per-topic read/write permissions per user/group

---

## Issue 23

**Title:** `feat: Add structured error codes to wire protocol`

**Labels:** `enhancement`, `low`, `protocol`

**Body:**

### Description

Error responses are plain UTF-8 strings. Clients can't programmatically distinguish error types.

### Proposal

Define error code enum:
```
0x01 UNKNOWN_TOPIC
0x02 PARTITION_NOT_FOUND
0x03 MESSAGE_TOO_LARGE
0x04 BACK_PRESSURE
0x05 NOT_LEADER
0x06 INVALID_OFFSET
0x07 UNAUTHORIZED
0xFF UNKNOWN
```

Error response format: `[2B error code] [4B message length] [NB message]`

---

## Issue 24

**Title:** `feat: Add S3-compatible RemoteStorageBackend for cold tier`

**Labels:** `enhancement`, `low`, `storage`

**Body:**

### Description

Only `LocalFileStorageBackend` exists for cold storage. Production deployments need object storage support.

### Proposal

Implement `S3StorageBackend` using the AWS SDK:
- Upload segments to S3 with configurable bucket/prefix
- Download on-demand for cold reads
- Support S3-compatible APIs (MinIO, GCS, etc.)

---

## Issue 25

**Title:** `feat: Add message compaction (retain latest value per key)`

**Labels:** `enhancement`, `low`, `storage`

**Body:**

### Description

No log compaction support. For changelog/state-store use cases (like Kafka's compacted topics), users need to retain only the latest value per key.

### Proposal

Add a compaction mode per topic:
- `DELETE` — standard retention (delete old segments)
- `COMPACT` — retain only the latest message per key
- `COMPACT_DELETE` — compact + delete after retention period

Run compaction as a background thread that rewrites segments.

---

## Issue 26

**Title:** `feat: Add topic name validation`

**Labels:** `enhancement`, `low`, `core`

**Body:**

### Description

No validation on topic names. Topics like `../../../etc/passwd` or empty strings can be created, potentially causing path traversal or filesystem issues.

### Fix

Validate topic names: `^[a-zA-Z0-9._-]{1,255}$`. Reject invalid names with a clear error message.

---

## Issue 27

**Title:** `feat: Add connection pooling in client SDK`

**Labels:** `enhancement`, `low`, `client`

**Body:**

### Description

Each `TitanProducer` and `TitanConsumer` creates its own `TitanConnection`. For applications with many producers/consumers, this wastes connections and memory.

### Proposal

Implement a `ConnectionPool` shared across clients targeting the same broker. Reuse Netty channels with reference counting.

---

## Issue 28

**Title:** `feat: Add automatic retry with exponential backoff in producer`

**Labels:** `enhancement`, `low`, `client`

**Body:**

### Description

Failed sends immediately propagate the error to the caller. No retry logic.

### Proposal

Add configurable retry:
- `retries` — max retry count (default: 3)
- `retryBackoffMs` — initial backoff (default: 100ms)
- `retryBackoffMaxMs` — max backoff (default: 5000ms)
- Exponential backoff with jitter
