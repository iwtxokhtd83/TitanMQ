package com.titanmq.common.serialization;

/**
 * Deserializer interface for converting byte arrays back to objects.
 *
 * @param <T> the type to deserialize to
 */
public interface Deserializer<T> {

    T deserialize(byte[] data);
}
