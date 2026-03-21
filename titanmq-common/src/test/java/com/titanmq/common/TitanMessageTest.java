package com.titanmq.common;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TitanMessageTest {

    @Test
    void shouldBuildMessageWithAllFields() {
        TitanMessage msg = TitanMessage.builder()
                .topic("orders")
                .key("order-123")
                .payload("test payload")
                .partition(2)
                .header("region", "us-east")
                .build();

        assertEquals("orders", msg.topic());
        assertArrayEquals("order-123".getBytes(), msg.key());
        assertArrayEquals("test payload".getBytes(), msg.payload());
        assertEquals(2, msg.partition());
        assertEquals("us-east", msg.headers().get("region"));
        assertNotNull(msg.id());
        assertTrue(msg.timestamp() > 0);
    }

    @Test
    void shouldSerializeAndDeserialize() {
        TitanMessage original = TitanMessage.builder()
                .topic("test-topic")
                .key("key-1")
                .payload("hello world")
                .partition(0)
                .offset(42)
                .header("type", "event")
                .header("source", "test")
                .build();

        ByteBuffer buffer = original.serialize();
        TitanMessage deserialized = TitanMessage.deserialize(buffer);

        assertEquals(original.id(), deserialized.id());
        assertEquals(original.topic(), deserialized.topic());
        assertEquals(original.partition(), deserialized.partition());
        assertEquals(original.offset(), deserialized.offset());
        assertArrayEquals(original.key(), deserialized.key());
        assertArrayEquals(original.payload(), deserialized.payload());
        assertEquals(original.headers(), deserialized.headers());
    }

    @Test
    void shouldHandleNullKey() {
        TitanMessage msg = TitanMessage.builder()
                .topic("test")
                .payload("data")
                .build();

        assertNull(msg.key());

        ByteBuffer buffer = msg.serialize();
        TitanMessage deserialized = TitanMessage.deserialize(buffer);
        assertNull(deserialized.key());
    }

    @Test
    void shouldHandleEmptyHeaders() {
        TitanMessage msg = TitanMessage.builder()
                .topic("test")
                .payload("data")
                .build();

        assertTrue(msg.headers().isEmpty());

        ByteBuffer buffer = msg.serialize();
        TitanMessage deserialized = TitanMessage.deserialize(buffer);
        assertTrue(deserialized.headers().isEmpty());
    }

    @Test
    void shouldRejectNullTopic() {
        assertThrows(NullPointerException.class, () ->
                TitanMessage.builder().payload("data").build());
    }

    @Test
    void shouldRejectNullPayload() {
        assertThrows(NullPointerException.class, () ->
                TitanMessage.builder().topic("test").build());
    }
}
