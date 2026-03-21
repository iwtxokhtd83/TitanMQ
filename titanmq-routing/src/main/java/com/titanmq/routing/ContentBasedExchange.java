package com.titanmq.routing;

import com.titanmq.common.TitanMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Content-based exchange: routes messages based on header/content matching rules.
 *
 * <p>This goes beyond what RabbitMQ offers — allowing arbitrary predicates on message
 * headers for complex routing scenarios (e.g., route all messages where region=us AND priority>5).
 */
public class ContentBasedExchange implements Exchange {

    private final String name;
    private final CopyOnWriteArrayList<RoutingRule> rules = new CopyOnWriteArrayList<>();

    public ContentBasedExchange(String name) {
        this.name = name;
    }

    @Override
    public String name() { return name; }

    @Override
    public ExchangeType type() { return ExchangeType.CONTENT_BASED; }

    /**
     * Bind with a header-matching predicate.
     */
    public void bind(String destinationTopic, Predicate<Map<String, String>> headerMatcher) {
        rules.add(new RoutingRule(destinationTopic, headerMatcher));
    }

    @Override
    public void bind(String destinationTopic, String routingKey) {
        // Simple header key=value matching via routing key format: "key1=val1,key2=val2"
        bind(destinationTopic, headers -> {
            for (String pair : routingKey.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    if (!kv[1].equals(headers.get(kv[0]))) return false;
                }
            }
            return true;
        });
    }

    @Override
    public void unbind(String destinationTopic, String routingKey) {
        rules.removeIf(r -> r.destination.equals(destinationTopic));
    }

    @Override
    public List<String> route(TitanMessage message, String routingKey) {
        List<String> result = new ArrayList<>();
        for (RoutingRule rule : rules) {
            if (rule.matcher.test(message.headers())) {
                result.add(rule.destination);
            }
        }
        return result;
    }

    private record RoutingRule(String destination, Predicate<Map<String, String>> matcher) {}
}
