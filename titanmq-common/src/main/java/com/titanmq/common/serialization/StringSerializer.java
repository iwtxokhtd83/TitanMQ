package com.titanmq.common.serialization;

import java.nio.charset.StandardCharsets;

public class StringSerializer implements Serializer<String> {

    @Override
    public byte[] serialize(String data) {
        return data != null ? data.getBytes(StandardCharsets.UTF_8) : null;
    }
}
