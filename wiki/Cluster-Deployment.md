# Cluster Deployment

Run TitanMQ as a multi-node cluster with Raft-based consensus for high availability.

## Minimum Cluster Size

| Nodes | Fault Tolerance | Recommended For |
|---|---|---|
| 1 | 0 failures | Development, testing |
| 3 | 1 failure | Small production |
| 5 | 2 failures | Production |

Raft requires a majority (quorum) to operate: `(N/2) + 1` nodes must be available.

## Starting a 3-Node Cluster

```bash
# Node 1
java -jar titanmq-server.jar \
  --node-id broker-1 \
  --port 9500 \
  --data-dir /data/broker-1 \
  --peers broker-2,broker-3

# Node 2
java -jar titanmq-server.jar \
  --node-id broker-2 \
  --port 9501 \
  --data-dir /data/broker-2 \
  --peers broker-1,broker-3

# Node 3
java -jar titanmq-server.jar \
  --node-id broker-3 \
  --port 9502 \
  --data-dir /data/broker-3 \
  --peers broker-1,broker-2
```

## What Happens on Failover

1. Leader crashes or becomes unreachable
2. Followers detect missing heartbeats (election timeout: 300–500ms)
3. A follower starts an election, wins with majority votes
4. New leader appends a no-op entry and begins accepting writes
5. Total failover time: typically under 1 second

## Client Configuration

Clients should list all broker addresses for automatic failover:

```java
TitanProducer producer = TitanMQ.newProducer()
    .brokers("broker-1:9500,broker-2:9501,broker-3:9502")
    .build();
```

## Programmatic Cluster Setup

```java
BrokerConfig config = new BrokerConfig()
    .port(9500)
    .dataDir(Path.of("/data/broker-1"));

TitanBroker broker = new TitanBroker(config, "broker-1", List.of("broker-2", "broker-3"));
broker.start();
```

## Production Recommendations

- Use odd numbers of nodes (3, 5, 7) to avoid split-brain
- Place nodes in different availability zones
- Use SSDs for the data directory
- Monitor Raft term changes — frequent elections indicate network instability
- Set `--data-dir` to a dedicated disk/partition
