package com.titanmq.common.serialization;

/**
 * Serializer interface for converting objects to byte arrays.
 *
 * @param <T> the type to serialize
 */
public interface Serializer<T> {

    byte[] serialize(T data);
}
