package com.stonebreak.world.save.serialization;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.world.save.model.EntityData;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BinaryEntitySerializer to ensure correct serialization/deserialization
 * of all entity types and verify size reduction compared to JSON.
 */
class BinaryEntitySerializerTest {

    private BinaryEntitySerializer binarySerializer;
    private JsonEntitySerializer jsonSerializer;

    @BeforeEach
    void setUp() {
        binarySerializer = new BinaryEntitySerializer();
        jsonSerializer = new JsonEntitySerializer();
    }

    @Test
    void testBlockDropSerialization() {
        // Create block drop entity
        Map<String, Object> customData = new HashMap<>();
        customData.put("blockType", BlockType.STONE);
        customData.put("despawnTimer", 295.5f);
        customData.put("stackCount", 3);

        EntityData original = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(new Vector3f(10.5f, 64.0f, -20.3f))
            .velocity(new Vector3f(0.1f, -0.5f, 0.2f))
            .rotation(new Vector3f(45.0f, 90.0f, 0.0f))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(5.0f)
            .alive(true)
            .customData(customData)
            .build();

        // Serialize and deserialize
        byte[] binaryData = binarySerializer.serialize(original);
        EntityData deserialized = binarySerializer.deserialize(binaryData);

        // Verify all fields match
        assertEquals(original.getEntityType(), deserialized.getEntityType());
        assertVector3fEquals(original.getPosition(), deserialized.getPosition(), 0.001f);
        assertVector3fEquals(original.getVelocity(), deserialized.getVelocity(), 0.001f);
        assertVector3fEquals(original.getRotation(), deserialized.getRotation(), 0.001f);
        assertEquals(original.getHealth(), deserialized.getHealth(), 0.001f);
        assertEquals(original.getMaxHealth(), deserialized.getMaxHealth(), 0.001f);
        assertEquals(original.getAge(), deserialized.getAge(), 0.001f);
        assertEquals(original.isAlive(), deserialized.isAlive());

        // Verify custom data
        assertEquals(BlockType.STONE, deserialized.getCustomData().get("blockType"));
        assertEquals(295.5f, ((Number) deserialized.getCustomData().get("despawnTimer")).floatValue(), 0.001f);
        assertEquals(3, ((Number) deserialized.getCustomData().get("stackCount")).intValue());

        // Compare size with JSON
        byte[] jsonData = jsonSerializer.serialize(original);
        System.out.printf("[BLOCK_DROP] Binary: %d bytes, JSON: %d bytes, Reduction: %.1f%%%n",
            binaryData.length, jsonData.length, (1.0 - (double) binaryData.length / jsonData.length) * 100);

        assertTrue(binaryData.length < jsonData.length, "Binary should be smaller than JSON");
    }

    @Test
    void testItemDropSerialization() {
        // Create item drop entity
        Map<String, Object> customData = new HashMap<>();
        customData.put("itemId", 42);
        customData.put("isBlockType", true);
        customData.put("itemCount", 16);
        customData.put("despawnTimer", 280.0f);
        customData.put("stackCount", 1);

        EntityData original = EntityData.builder()
            .entityType(EntityType.ITEM_DROP)
            .position(new Vector3f(5.0f, 70.0f, 15.0f))
            .velocity(new Vector3f(0.0f, 0.0f, 0.0f))
            .rotation(new Vector3f(0.0f, 180.0f, 0.0f))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(10.0f)
            .alive(true)
            .customData(customData)
            .build();

        // Serialize and deserialize
        byte[] binaryData = binarySerializer.serialize(original);
        EntityData deserialized = binarySerializer.deserialize(binaryData);

        // Verify all fields match
        assertEquals(original.getEntityType(), deserialized.getEntityType());
        assertVector3fEquals(original.getPosition(), deserialized.getPosition(), 0.001f);

        // Verify custom data
        assertEquals(42, ((Number) deserialized.getCustomData().get("itemId")).intValue());
        assertEquals(true, deserialized.getCustomData().get("isBlockType"));
        assertEquals(16, ((Number) deserialized.getCustomData().get("itemCount")).intValue());
        assertEquals(280.0f, ((Number) deserialized.getCustomData().get("despawnTimer")).floatValue(), 0.001f);
        assertEquals(1, ((Number) deserialized.getCustomData().get("stackCount")).intValue());

        // Compare size with JSON
        byte[] jsonData = jsonSerializer.serialize(original);
        System.out.printf("[ITEM_DROP] Binary: %d bytes, JSON: %d bytes, Reduction: %.1f%%%n",
            binaryData.length, jsonData.length, (1.0 - (double) binaryData.length / jsonData.length) * 100);

        assertTrue(binaryData.length < jsonData.length, "Binary should be smaller than JSON");
    }

    @Test
    void testCowSerialization() {
        // Create cow entity
        Map<String, Object> customData = new HashMap<>();
        customData.put("textureVariant", "default_cow");
        customData.put("canBeMilked", true);
        customData.put("milkRegenTimer", 120.5f);
        customData.put("aiState", "WANDERING");

        EntityData original = EntityData.builder()
            .entityType(EntityType.COW)
            .position(new Vector3f(100.0f, 64.0f, 50.0f))
            .velocity(new Vector3f(0.5f, 0.0f, 0.3f))
            .rotation(new Vector3f(0.0f, 45.0f, 0.0f))
            .health(8.5f)
            .maxHealth(10.0f)
            .age(1500.0f)
            .alive(true)
            .customData(customData)
            .build();

        // Serialize and deserialize
        byte[] binaryData = binarySerializer.serialize(original);
        EntityData deserialized = binarySerializer.deserialize(binaryData);

        // Verify all fields match
        assertEquals(original.getEntityType(), deserialized.getEntityType());
        assertVector3fEquals(original.getPosition(), deserialized.getPosition(), 0.001f);
        assertVector3fEquals(original.getVelocity(), deserialized.getVelocity(), 0.001f);
        assertVector3fEquals(original.getRotation(), deserialized.getRotation(), 0.001f);
        assertEquals(original.getHealth(), deserialized.getHealth(), 0.001f);
        assertEquals(original.getMaxHealth(), deserialized.getMaxHealth(), 0.001f);
        assertEquals(original.getAge(), deserialized.getAge(), 0.001f);
        assertEquals(original.isAlive(), deserialized.isAlive());

        // Verify custom data
        assertEquals("default_cow", deserialized.getCustomData().get("textureVariant"));
        assertEquals(true, deserialized.getCustomData().get("canBeMilked"));
        assertEquals(120.5f, ((Number) deserialized.getCustomData().get("milkRegenTimer")).floatValue(), 0.001f);
        assertEquals("WANDERING", deserialized.getCustomData().get("aiState"));

        // Compare size with JSON
        byte[] jsonData = jsonSerializer.serialize(original);
        System.out.printf("[COW] Binary: %d bytes, JSON: %d bytes, Reduction: %.1f%%%n",
            binaryData.length, jsonData.length, (1.0 - (double) binaryData.length / jsonData.length) * 100);

        assertTrue(binaryData.length < jsonData.length, "Binary should be smaller than JSON");
    }

    @Test
    void testDeadEntity() {
        // Test entity that is not alive
        Map<String, Object> customData = new HashMap<>();
        customData.put("blockType", BlockType.DIRT);
        customData.put("despawnTimer", 0.0f);
        customData.put("stackCount", 1);

        EntityData original = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(new Vector3f(0.0f, 0.0f, 0.0f))
            .velocity(new Vector3f(0.0f, 0.0f, 0.0f))
            .rotation(new Vector3f(0.0f, 0.0f, 0.0f))
            .health(0.0f)
            .maxHealth(1.0f)
            .age(300.0f)
            .alive(false) // Dead entity
            .customData(customData)
            .build();

        byte[] data = binarySerializer.serialize(original);
        EntityData deserialized = binarySerializer.deserialize(data);

        assertEquals(false, deserialized.isAlive());
        assertEquals(0.0f, deserialized.getHealth(), 0.001f);
    }

    @Test
    void testExtremeValues() {
        // Test with extreme coordinate values
        Map<String, Object> customData = new HashMap<>();
        customData.put("blockType", BlockType.GRASS);
        customData.put("despawnTimer", Float.MAX_VALUE);
        customData.put("stackCount", Integer.MAX_VALUE);

        EntityData original = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(new Vector3f(999999.0f, 255.0f, -999999.0f))
            .velocity(new Vector3f(100.0f, -100.0f, 100.0f))
            .rotation(new Vector3f(359.99f, 359.99f, 359.99f))
            .health(Float.MAX_VALUE)
            .maxHealth(Float.MAX_VALUE)
            .age(Float.MAX_VALUE)
            .alive(true)
            .customData(customData)
            .build();

        byte[] data = binarySerializer.serialize(original);
        EntityData deserialized = binarySerializer.deserialize(data);

        assertVector3fEquals(original.getPosition(), deserialized.getPosition(), 1.0f);
        assertEquals(Float.MAX_VALUE, ((Number) deserialized.getCustomData().get("despawnTimer")).floatValue());
        assertEquals(Integer.MAX_VALUE, ((Number) deserialized.getCustomData().get("stackCount")).intValue());
    }

    private void assertVector3fEquals(Vector3f expected, Vector3f actual, float delta) {
        assertEquals(expected.x, actual.x, delta, "X component mismatch");
        assertEquals(expected.y, actual.y, delta, "Y component mismatch");
        assertEquals(expected.z, actual.z, delta, "Z component mismatch");
    }
}
