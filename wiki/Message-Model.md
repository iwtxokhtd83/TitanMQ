# Message Model

## TitanMessage

Every message in TitanMQ is represented by `TitanMessage`, an immutable value object.

### Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `id` | `String` | Auto-generated | Unique message ID (UUID). Used for deduplication. |
| `topic` | `String` | Yes | The topic this message belongs to |
| `partition` | `int` | Auto-assigned | Partition number (-1 for auto-assignment) |
| `offset` | `long` | Broker-assigned | Position in the partition's commit log |
| `timestamp` | `long` | Auto-generated | Epoch milliseconds when the message was created |
| `key` | `byte[]` | No | Message key (used for partition routing) |
| `payload` | `byte[]` | Yes | The message body |
| `headers` | `Map<String, String>` | No | Arbitrary key-value metadata |

### Creating Messages

```java
TitanMessage message = TitanMessage.builder()
    .topic("orders")
    .key("customer-456")
    .payload("{\"item\": \"widget\"}".getBytes())
    .header("region", "us-east")
    .header("priority", "high")
    .build();
```

### Partition Assignment

Messages are assigned to partitions using this priority:

1. **Explicit partition** — If `partition >= 0`, use it directly
2. **Key-based** — If a key is present, `MurmurHash3(key) % numPartitions`
3. **Round-robin** — If no key, distribute by thread ID

Key-based partitioning guarantees ordering: all messages with the same key go to the same partition and are consumed in order.

## Wire Format

Messages are serialized to `ByteBuffer` for zero-copy network transfer:

```
[4 bytes: total size]
[4 bytes: id length]     [N bytes: id]
[4 bytes: topic length]  [N bytes: topic]
[4 bytes: partition]
[8 bytes: offset]
[8 bytes: timestamp]
[4 bytes: key length]    [N bytes: key]        (0 length = no key)
[4 bytes: header count]
  [4 bytes: key length]  [N bytes: key]        (repeated per header)
  [4 bytes: val length]  [N bytes: value]
[4 bytes: payload length] [N bytes: payload]
```

All numeric fields are big-endian. Strings are UTF-8 encoded.

## Serialization

TitanMQ provides a `Serializer<T>` / `Deserializer<T>` interface for type-safe message handling:

```java
// Built-in
new StringSerializer()
new StringDeserializer()

// Custom
public class JsonSerializer<T> implements Serializer<T> {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public byte[] serialize(T data) {
        return mapper.writeValueAsBytes(data);
    }
}
```
