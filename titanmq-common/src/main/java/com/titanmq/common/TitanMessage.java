package com.titanmq.common;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Core message abstraction for TitanMQ.
 * Designed for zero-copy transfer with direct ByteBuffer support.
 *
 * <p>Wire format:
 * <pre>
 * [4 bytes: total length]
 * [16 bytes: message ID (UUID)]
 * [8 bytes: timestamp]
 * [4 bytes: key length] [N bytes: key]
 * [4 bytes: headers count] [repeated: 4+N key, 4+N value]
 * [4 bytes: payload length] [N bytes: payload]
 * </pre>
 */
public final class TitanMessage {

    private final String id;
    private final String topic;
    private final int partition;
    private final long offset;
    private final long timestamp;
    private final byte[] key;
    private final byte[] payload;
    private final Map<String, String> headers;

    private TitanMessage(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.topic = Objects.requireNonNull(builder.topic, "topic must not be null");
        this.partition = builder.partition;
        this.offset = builder.offset;
        this.timestamp = builder.timestamp > 0 ? builder.timestamp : System.currentTimeMillis();
        this.key = builder.key;
        this.payload = Objects.requireNonNull(builder.payload, "payload must not be null");
        this.headers = builder.headers != null
                ? Collections.unmodifiableMap(new HashMap<>(builder.headers))
                : Collections.emptyMap();
    }

    public String id() { return id; }
    public String topic() { return topic; }
    public int partition() { return partition; }
    public long offset() { return offset; }
    public long timestamp() { return timestamp; }
    public byte[] key() { return key; }
    public byte[] payload() { return payload; }
    public Map<String, String> headers() { return headers; }

    /**
     * Serialize this message into a ByteBuffer for zero-copy network transfer.
     */
    public ByteBuffer serialize() {
        byte[] idBytes = id.getBytes();
        byte[] topicBytes = topic.getBytes();
        int headersSize = 4; // count
        for (var entry : headers.entrySet()) {
            headersSize += 4 + entry.getKey().getBytes().length + 4 + entry.getValue().getBytes().length;
        }

        int totalSize = 4 + idBytes.length   // id
                + 4 + topicBytes.length       // topic
                + 4                           // partition
                + 8                           // offset
                + 8                           // timestamp
                + 4 + (key != null ? key.length : 0) // key
                + headersSize                 // headers
                + 4 + payload.length;         // payload

        ByteBuffer buffer = ByteBuffer.allocate(4 + totalSize);
        buffer.putInt(totalSize);
        buffer.putInt(idBytes.length);
        buffer.put(idBytes);
        buffer.putInt(topicBytes.length);
        buffer.put(topicBytes);
        buffer.putInt(partition);
        buffer.putLong(offset);
        buffer.putLong(timestamp);
        buffer.putInt(key != null ? key.length : 0);
        if (key != null) buffer.put(key);
        buffer.putInt(headers.size());
        for (var entry : headers.entrySet()) {
            byte[] k = entry.getKey().getBytes();
            byte[] v = entry.getValue().getBytes();
            buffer.putInt(k.length);
            buffer.put(k);
            buffer.putInt(v.length);
            buffer.put(v);
        }
        buffer.putInt(payload.length);
        buffer.put(payload);
        buffer.flip();
        return buffer;
    }

    /**
     * Deserialize a message from a ByteBuffer.
     */
    public static TitanMessage deserialize(ByteBuffer buffer) {
        int totalSize = buffer.getInt();
        int idLen = buffer.getInt();
        byte[] idBytes = new byte[idLen];
        buffer.get(idBytes);
        int topicLen = buffer.getInt();
        byte[] topicBytes = new byte[topicLen];
        buffer.get(topicBytes);
        int partition = buffer.getInt();
        long offset = buffer.getLong();
        long timestamp = buffer.getLong();
        int keyLen = buffer.getInt();
        byte[] key = keyLen > 0 ? new byte[keyLen] : null;
        if (key != null) buffer.get(key);
        int headerCount = buffer.getInt();
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < headerCount; i++) {
            int kLen = buffer.getInt();
            byte[] k = new byte[kLen];
            buffer.get(k);
            int vLen = buffer.getInt();
            byte[] v = new byte[vLen];
            buffer.get(v);
            headers.put(new String(k), new String(v));
        }
        int payloadLen = buffer.getInt();
        byte[] payload = new byte[payloadLen];
        buffer.get(payload);

        return TitanMessage.builder()
                .id(new String(idBytes))
                .topic(new String(topicBytes))
                .partition(partition)
                .offset(offset)
                .timestamp(timestamp)
                .key(key)
                .payload(payload)
                .headers(headers)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String topic;
        private int partition = -1;
        private long offset = -1;
        private long timestamp;
        private byte[] key;
        private byte[] payload;
        private Map<String, String> headers;

        public Builder id(String id) { this.id = id; return this; }
        public Builder topic(String topic) { this.topic = topic; return this; }
        public Builder partition(int partition) { this.partition = partition; return this; }
        public Builder offset(long offset) { this.offset = offset; return this; }
        public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }
        public Builder key(byte[] key) { this.key = key; return this; }
        public Builder key(String key) { this.key = key != null ? key.getBytes() : null; return this; }
        public Builder payload(byte[] payload) { this.payload = payload; return this; }
        public Builder payload(String payload) { this.payload = payload != null ? payload.getBytes() : null; return this; }
        public Builder headers(Map<String, String> headers) { this.headers = headers; return this; }
        public Builder header(String key, String value) {
            if (this.headers == null) this.headers = new HashMap<>();
            this.headers.put(key, value);
            return this;
        }
        public TitanMessage build() { return new TitanMessage(this); }
    }

    @Override
    public String toString() {
        return "TitanMessage{id='%s', topic='%s', partition=%d, offset=%d, payloadSize=%d}"
                .formatted(id, topic, partition, offset, payload.length);
    }
}
