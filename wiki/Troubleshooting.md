# Troubleshooting

## Common Issues

### Broker won't start: "Address already in use"

Another process is using port 9500. Either stop it or use a different port:

```bash
java -jar titanmq-server.jar --port 9501
```

### Producer gets "Back-pressure: broker is overloaded"

The broker's in-flight message count exceeded the high watermark. Options:
- Add more partitions to increase parallelism
- Increase `backPressureHighWaterMark` in config
- Scale out with more broker nodes
- Check if consumers are keeping up (consumer lag)

### Consumer not receiving messages

1. Verify the topic exists and has messages
2. Check the consumer group — another consumer in the same group may have the partition
3. Verify the offset — consumer may have already consumed past the available messages
4. For embedded mode, ensure `poll()` is called in a loop or use `subscribe()`

### Cluster: frequent leader elections

Symptoms: Raft term number increasing rapidly in logs.

Causes:
- Network instability between nodes
- GC pauses exceeding election timeout (300–500ms)
- Overloaded nodes can't process heartbeats in time

Fixes:
- Check network latency between nodes (should be < 50ms)
- Tune JVM GC: use ZGC or Shenandoah for low-pause collection
- Ensure nodes have sufficient CPU/memory headroom

### OutOfMemoryError

The broker is holding too many messages in memory. Options:
- Lower `backPressureHighWaterMark`
- Increase JVM heap: `-Xmx8g`
- Enable tiered storage to offload old segments
- Reduce `segmentSizeBytes` for more frequent segment rolling

### Slow reads from old data

If reading data that's been offloaded to cold storage, the first read will be slow (fetching from remote storage). Subsequent reads use the local cache. To pre-warm:

```java
// Seek to the beginning and poll to trigger cache population
consumer.seekToBeginning();
consumer.poll(1);
```

## Log Levels

TitanMQ uses SLF4J with Logback. Adjust in `logback.xml`:

```xml
<!-- Debug Raft consensus -->
<logger name="com.titanmq.cluster" level="DEBUG"/>

<!-- Debug storage layer -->
<logger name="com.titanmq.store" level="DEBUG"/>

<!-- Debug network -->
<logger name="io.netty" level="DEBUG"/>
```

## Getting Help

- [GitHub Issues](https://github.com/iwtxokhtd83/TitanMQ/issues) — Bug reports and feature requests
- [GitHub Discussions](https://github.com/iwtxokhtd83/TitanMQ/discussions) — Questions and community discussion
