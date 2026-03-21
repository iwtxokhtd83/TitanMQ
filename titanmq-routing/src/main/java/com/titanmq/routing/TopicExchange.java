package com.titanmq.routing;

import com.titanmq.common.TitanMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Topic exchange: routes messages using wildcard pattern matching.
 *
 * <p>Patterns:
 * <ul>
 *   <li>{@code *} matches exactly one word (e.g., {@code orders.*} matches {@code orders.created})</li>
 *   <li>{@code #} matches zero or more words (e.g., {@code orders.#} matches {@code orders.us.created})</li>
 * </ul>
 */
public class TopicExchange implements Exchange {

    private final String name;
    private final ConcurrentHashMap<String, List<String>> bindings = new ConcurrentHashMap<>();

    public TopicExchange(String name) {
        this.name = name;
    }

    @Override
    public String name() { return name; }

    @Override
    public ExchangeType type() { return ExchangeType.TOPIC; }

    @Override
    public void bind(String destinationTopic, String routingKey) {
        bindings.computeIfAbsent(routingKey, k -> new ArrayList<>()).add(destinationTopic);
    }

    @Override
    public void unbind(String destinationTopic, String routingKey) {
        List<String> destinations = bindings.get(routingKey);
        if (destinations != null) {
            destinations.remove(destinationTopic);
        }
    }

    @Override
    public List<String> route(TitanMessage message, String routingKey) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : bindings.entrySet()) {
            if (matches(routingKey, entry.getKey())) {
                result.addAll(entry.getValue());
            }
        }
        return result;
    }

    /**
     * Match a routing key against a binding pattern.
     */
    static boolean matches(String routingKey, String pattern) {
        String[] routingParts = routingKey.split("\\.");
        String[] patternParts = pattern.split("\\.");
        return matchParts(routingParts, 0, patternParts, 0);
    }

    private static boolean matchParts(String[] routing, int ri, String[] pattern, int pi) {
        if (ri == routing.length && pi == pattern.length) return true;
        if (pi == pattern.length) return false;

        if (pattern[pi].equals("#")) {
            // # matches zero or more words
            if (pi == pattern.length - 1) return true;
            for (int i = ri; i <= routing.length; i++) {
                if (matchParts(routing, i, pattern, pi + 1)) return true;
            }
            return false;
        }

        if (ri == routing.length) return false;

        if (pattern[pi].equals("*") || pattern[pi].equals(routing[ri])) {
            return matchParts(routing, ri + 1, pattern, pi + 1);
        }

        return false;
    }
}
