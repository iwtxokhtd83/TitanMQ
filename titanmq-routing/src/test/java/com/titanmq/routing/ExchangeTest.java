package com.titanmq.routing;

import com.titanmq.common.TitanMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExchangeTest {

    // --- Direct Exchange ---

    @Test
    void directExchangeShouldRouteByExactKey() {
        DirectExchange exchange = new DirectExchange("direct-1");
        exchange.bind("queue-a", "order.created");
        exchange.bind("queue-b", "order.shipped");

        TitanMessage msg = testMessage();
        assertEquals(List.of("queue-a"), exchange.route(msg, "order.created"));
        assertEquals(List.of("queue-b"), exchange.route(msg, "order.shipped"));
        assertEquals(List.of(), exchange.route(msg, "order.cancelled"));
    }

    @Test
    void directExchangeShouldSupportMultipleBindings() {
        DirectExchange exchange = new DirectExchange("direct-2");
        exchange.bind("queue-a", "events");
        exchange.bind("queue-b", "events");

        List<String> destinations = exchange.route(testMessage(), "events");
        assertEquals(2, destinations.size());
        assertTrue(destinations.contains("queue-a"));
        assertTrue(destinations.contains("queue-b"));
    }

    // --- Topic Exchange ---

    @Test
    void topicExchangeShouldMatchWildcardStar() {
        TopicExchange exchange = new TopicExchange("topic-1");
        exchange.bind("queue-a", "order.*");

        assertEquals(List.of("queue-a"), exchange.route(testMessage(), "order.created"));
        assertEquals(List.of("queue-a"), exchange.route(testMessage(), "order.shipped"));
        assertEquals(List.of(), exchange.route(testMessage(), "order.us.created"));
    }

    @Test
    void topicExchangeShouldMatchWildcardHash() {
        TopicExchange exchange = new TopicExchange("topic-2");
        exchange.bind("queue-a", "order.#");

        assertEquals(List.of("queue-a"), exchange.route(testMessage(), "order.created"));
        assertEquals(List.of("queue-a"), exchange.route(testMessage(), "order.us.created"));
        assertEquals(List.of("queue-a"), exchange.route(testMessage(), "order.us.east.created"));
        assertEquals(List.of(), exchange.route(testMessage(), "payment.created"));
    }

    @Test
    void topicExchangeShouldMatchComplexPatterns() {
        TopicExchange exchange = new TopicExchange("topic-3");
        exchange.bind("queue-a", "*.*.created");
        exchange.bind("queue-b", "#.error");

        assertEquals(List.of("queue-a"), exchange.route(testMessage(), "order.us.created"));
        assertEquals(List.of("queue-b"), exchange.route(testMessage(), "system.payment.error"));
        assertEquals(List.of("queue-b"), exchange.route(testMessage(), "error"));
    }

    // --- Fanout Exchange ---

    @Test
    void fanoutExchangeShouldRouteToAllBindings() {
        FanoutExchange exchange = new FanoutExchange("fanout-1");
        exchange.bind("queue-a", "ignored");
        exchange.bind("queue-b", "also-ignored");
        exchange.bind("queue-c", "");

        List<String> destinations = exchange.route(testMessage(), "any-key");
        assertEquals(3, destinations.size());
    }

    // --- Content-Based Exchange ---

    @Test
    void contentBasedExchangeShouldRouteByHeaders() {
        ContentBasedExchange exchange = new ContentBasedExchange("content-1");
        exchange.bind("us-queue", "region=us");
        exchange.bind("eu-queue", "region=eu");

        TitanMessage usMsg = TitanMessage.builder()
                .topic("test")
                .payload("data")
                .header("region", "us")
                .build();

        TitanMessage euMsg = TitanMessage.builder()
                .topic("test")
                .payload("data")
                .header("region", "eu")
                .build();

        assertEquals(List.of("us-queue"), exchange.route(usMsg, ""));
        assertEquals(List.of("eu-queue"), exchange.route(euMsg, ""));
    }

    @Test
    void contentBasedExchangeShouldSupportMultipleHeaderMatching() {
        ContentBasedExchange exchange = new ContentBasedExchange("content-2");
        exchange.bind("priority-us", "region=us,priority=high");

        TitanMessage match = TitanMessage.builder()
                .topic("test")
                .payload("data")
                .header("region", "us")
                .header("priority", "high")
                .build();

        TitanMessage noMatch = TitanMessage.builder()
                .topic("test")
                .payload("data")
                .header("region", "us")
                .header("priority", "low")
                .build();

        assertEquals(List.of("priority-us"), exchange.route(match, ""));
        assertEquals(List.of(), exchange.route(noMatch, ""));
    }

    // --- Topic Pattern Matching ---

    @Test
    void topicPatternMatchingShouldHandleEdgeCases() {
        assertTrue(TopicExchange.matches("a.b.c", "a.b.c"));
        assertTrue(TopicExchange.matches("a.b.c", "#"));
        assertTrue(TopicExchange.matches("a.b.c", "a.#"));
        assertTrue(TopicExchange.matches("a.b.c", "#.c"));
        assertTrue(TopicExchange.matches("a.b.c", "a.*.c"));
        assertFalse(TopicExchange.matches("a.b.c", "a.b"));
        assertFalse(TopicExchange.matches("a.b", "a.b.c"));
        assertTrue(TopicExchange.matches("a", "#"));
        assertTrue(TopicExchange.matches("a", "a"));
    }

    private TitanMessage testMessage() {
        return TitanMessage.builder()
                .topic("test")
                .payload("test-data")
                .build();
    }
}
