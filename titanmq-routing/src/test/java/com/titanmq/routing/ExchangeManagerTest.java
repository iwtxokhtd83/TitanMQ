package com.titanmq.routing;

import com.titanmq.common.TitanMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExchangeManagerTest {

    private ExchangeManager manager;

    @BeforeEach
    void setup() {
        manager = new ExchangeManager();
    }

    @Test
    void shouldDeclareAndRouteDirectExchange() {
        manager.declareExchange("orders", ExchangeType.DIRECT);
        manager.bind("orders", "fulfillment", "order.created");
        manager.bind("orders", "billing", "order.paid");

        TitanMessage msg = testMessage();
        assertEquals(List.of("fulfillment"), manager.route("orders", msg, "order.created"));
        assertEquals(List.of("billing"), manager.route("orders", msg, "order.paid"));
        assertEquals(List.of(), manager.route("orders", msg, "order.cancelled"));
    }

    @Test
    void shouldDeclareAndRouteTopicExchange() {
        manager.declareExchange("events", ExchangeType.TOPIC);
        manager.bind("events", "us-handler", "order.us.*");
        manager.bind("events", "all-errors", "#.error");

        TitanMessage msg = testMessage();
        assertEquals(List.of("us-handler"), manager.route("events", msg, "order.us.created"));
        assertEquals(List.of("all-errors"), manager.route("events", msg, "payment.error"));
    }

    @Test
    void shouldDeclareAndRouteFanoutExchange() {
        manager.declareExchange("notifications", ExchangeType.FANOUT);
        manager.bind("notifications", "email", "");
        manager.bind("notifications", "sms", "");
        manager.bind("notifications", "push", "");

        List<String> destinations = manager.route("notifications", testMessage(), "anything");
        assertEquals(3, destinations.size());
        assertTrue(destinations.containsAll(List.of("email", "sms", "push")));
    }

    @Test
    void shouldDeclareAndRouteContentBasedExchange() {
        manager.declareExchange("smart", ExchangeType.CONTENT_BASED);
        manager.bind("smart", "us-queue", "region=us");

        TitanMessage usMsg = TitanMessage.builder()
                .topic("test").payload("data".getBytes())
                .header("region", "us").build();
        TitanMessage euMsg = TitanMessage.builder()
                .topic("test").payload("data".getBytes())
                .header("region", "eu").build();

        assertEquals(List.of("us-queue"), manager.route("smart", usMsg, ""));
        assertEquals(List.of(), manager.route("smart", euMsg, ""));
    }

    @Test
    void shouldBeIdempotentOnRedeclare() {
        manager.declareExchange("test", ExchangeType.DIRECT);
        manager.declareExchange("test", ExchangeType.DIRECT); // No-op
        assertTrue(manager.hasExchange("test"));
    }

    @Test
    void shouldRejectRedeclareWithDifferentType() {
        manager.declareExchange("test", ExchangeType.DIRECT);
        assertThrows(IllegalArgumentException.class, () ->
                manager.declareExchange("test", ExchangeType.FANOUT));
    }

    @Test
    void shouldReturnEmptyForNonExistentExchange() {
        assertEquals(List.of(), manager.route("nonexistent", testMessage(), "key"));
    }

    @Test
    void shouldUnbind() {
        manager.declareExchange("test", ExchangeType.DIRECT);
        manager.bind("test", "dest", "key");
        assertEquals(List.of("dest"), manager.route("test", testMessage(), "key"));

        manager.unbind("test", "dest", "key");
        assertEquals(List.of(), manager.route("test", testMessage(), "key"));
    }

    @Test
    void shouldDeleteExchange() {
        manager.declareExchange("test", ExchangeType.DIRECT);
        assertTrue(manager.hasExchange("test"));

        manager.deleteExchange("test");
        assertFalse(manager.hasExchange("test"));
    }

    @Test
    void shouldListExchanges() {
        manager.declareExchange("a", ExchangeType.DIRECT);
        manager.declareExchange("b", ExchangeType.TOPIC);
        List<String> names = manager.listExchanges();
        assertTrue(names.contains("a"));
        assertTrue(names.contains("b"));
    }

    private TitanMessage testMessage() {
        return TitanMessage.builder().topic("test").payload("data".getBytes()).build();
    }
}
