# Wire Protocol

TitanMQ uses a custom binary protocol over TCP, implemented with Netty.

## Frame Format

```
┌──────────────┬──────────────┬─────────────────┬──────────────┐
│ 4 bytes      │ 1 byte       │ 4 bytes         │ N bytes      │
│ Frame Length  │ Command Type │ Correlation ID  │ Payload      │
└──────────────┴──────────────┴─────────────────┴──────────────┘
```

- **Frame Length**: Size of everything after this field (type + correlationId + payload)
- **Command Type**: Single byte identifying the operation
- **Correlation ID**: Client-assigned ID for matching requests to responses
- **Payload**: Command-specific binary data

Total overhead: 9 bytes per frame.

## Command Types

### Producer Commands

| Code | Name | Direction | Description |
|---|---|---|---|
| `0x01` | `PRODUCE` | Client → Broker | Send a message |
| `0x02` | `PRODUCE_ACK` | Broker → Client | Acknowledge with partition + offset |

### Consumer Commands

| Code | Name | Direction | Description |
|---|---|---|---|
| `0x10` | `FETCH` | Client → Broker | Request messages from a partition |
| `0x11` | `FETCH_RESPONSE` | Broker → Client | Return fetched messages |
| `0x12` | `COMMIT_OFFSET` | Client → Broker | Commit consumer offset |
| `0x13` | `COMMIT_OFFSET_ACK` | Broker → Client | Acknowledge offset commit |

### Admin Commands

| Code | Name | Direction | Description |
|---|---|---|---|
| `0x20` | `CREATE_TOPIC` | Client → Broker | Create a new topic |
| `0x21` | `DELETE_TOPIC` | Client → Broker | Delete a topic |
| `0x22` | `DESCRIBE_TOPIC` | Client → Broker | Get topic metadata |
| `0x23` | `ADMIN_RESPONSE` | Broker → Client | Admin operation result |

### Cluster Commands (Raft)

| Code | Name | Direction | Description |
|---|---|---|---|
| `0x30` | `HEARTBEAT` | Leader → Follower | Raft heartbeat |
| `0x31` | `VOTE_REQUEST` | Candidate → Peer | Request vote in election |
| `0x32` | `VOTE_RESPONSE` | Peer → Candidate | Vote grant/deny |
| `0x33` | `APPEND_ENTRIES` | Leader → Follower | Replicate log entries |
| `0x34` | `APPEND_ENTRIES_RESPONSE` | Follower → Leader | Replication result |

### Error

| Code | Name | Description |
|---|---|---|
| `0xFF` | `ERROR` | Error response (payload = UTF-8 error message) |

## Netty Pipeline

```
Client/Server Pipeline:
  ┌─────────────────────────┐
  │ TitanMessageDecoder     │  ← LengthFieldBasedFrameDecoder (handles TCP fragmentation)
  ├─────────────────────────┤
  │ TitanMessageEncoder     │  ← Encodes Command → ByteBuf
  ├─────────────────────────┤
  │ BrokerRequestHandler    │  ← Business logic (server) or ClientHandler (client)
  └─────────────────────────┘
```

## Design Rationale

We chose a custom binary protocol over gRPC/HTTP2 for:
- Minimal overhead (9 bytes vs. gRPC's protobuf + HTTP/2 framing)
- Full control over serialization
- Direct ByteBuffer integration for zero-copy paths
- Simpler state management than HTTP/2 streams
