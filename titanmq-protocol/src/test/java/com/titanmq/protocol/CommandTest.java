package com.titanmq.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class CommandTest {

    @Test
    void shouldEncodeAndDecodeCommand() {
        byte[] payload = "test-payload".getBytes();
        Command original = new Command(CommandType.PRODUCE, 42, payload);

        ByteBuffer encoded = original.encode();
        Command decoded = Command.decode(encoded);

        assertEquals(CommandType.PRODUCE, decoded.type());
        assertEquals(42, decoded.correlationId());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void shouldHandleNullPayload() {
        Command original = new Command(CommandType.HEARTBEAT, 1, null);

        ByteBuffer encoded = original.encode();
        Command decoded = Command.decode(encoded);

        assertEquals(CommandType.HEARTBEAT, decoded.type());
        assertEquals(1, decoded.correlationId());
        assertNull(decoded.payload());
    }

    @Test
    void shouldResolveAllCommandTypes() {
        for (CommandType type : CommandType.values()) {
            assertEquals(type, CommandType.fromCode(type.code()));
        }
    }

    @Test
    void shouldThrowOnUnknownCommandCode() {
        assertThrows(IllegalArgumentException.class, () ->
                CommandType.fromCode((byte) 0x99));
    }
}
