package com.titanmq.protocol;

import java.nio.ByteBuffer;

/**
 * Wire protocol command frame.
 *
 * <p>Frame format:
 * <pre>
 * [4 bytes: total frame length]
 * [1 byte:  command type]
 * [4 bytes: correlation ID]
 * [N bytes: payload]
 * </pre>
 */
public record Command(CommandType type, int correlationId, byte[] payload) {

    public static final int HEADER_SIZE = 4 + 1 + 4; // length + type + correlationId

    public ByteBuffer encode() {
        int payloadLen = payload != null ? payload.length : 0;
        int frameLen = 1 + 4 + payloadLen; // type + correlationId + payload
        ByteBuffer buffer = ByteBuffer.allocate(4 + frameLen);
        buffer.putInt(frameLen);
        buffer.put(type.code());
        buffer.putInt(correlationId);
        if (payload != null) {
            buffer.put(payload);
        }
        buffer.flip();
        return buffer;
    }

    public static Command decode(ByteBuffer buffer) {
        int frameLen = buffer.getInt();
        byte typeCode = buffer.get();
        int correlationId = buffer.getInt();
        int payloadLen = frameLen - 1 - 4;
        byte[] payload = null;
        if (payloadLen > 0) {
            payload = new byte[payloadLen];
            buffer.get(payload);
        }
        return new Command(CommandType.fromCode(typeCode), correlationId, payload);
    }
}
