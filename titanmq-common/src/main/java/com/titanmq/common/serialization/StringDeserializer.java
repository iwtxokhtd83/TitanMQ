package com.titanmq.common.serialization;

import java.nio.charset.StandardCharsets;

public class StringDeserializer implements Deserializer<String> {

    @Override
    public String deserialize(byte[] data) {
        return data != null ? new String(data, StandardCharsets.UTF_8) : null;
    }
}
