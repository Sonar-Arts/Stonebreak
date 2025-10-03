package com.stonebreak.world.save.serialization;

/**
 * Strategy interface for serialization.
 * Follows Open/Closed principle - extensible for new formats.
 */
public interface Serializer<T> {
    /**
     * Serializes an object to bytes.
     */
    byte[] serialize(T object);

    /**
     * Deserializes bytes to an object.
     */
    T deserialize(byte[] data);
}
