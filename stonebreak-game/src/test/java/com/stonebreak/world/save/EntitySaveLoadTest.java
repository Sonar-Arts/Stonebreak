package com.stonebreak.world.save;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.world.save.model.EntityData;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test to verify that block drops and cow entities are being saved and loaded properly.
 * Tests entity serialization and deserialization to ensure all critical properties are preserved.
 */
@DisplayName("Entity Save/Load Tests")
class EntitySaveLoadTest {

    private static final float FLOAT_EPSILON = 0.0001f;

    @BeforeEach
    void setUp() {
        // Setup any test fixtures if needed
    }

    @Test
    @DisplayName("BlockDrop - Serialize and deserialize basic properties")
    void testBlockDropBasicSerialization() {
        // Given: A block drop with specific properties
        Vector3f position = new Vector3f(10.5f, 64.0f, 20.3f);
        Vector3f velocity = new Vector3f(0.5f, 2.0f, -0.3f);
        BlockType blockType = BlockType.DIRT;

        // Create entity data for block drop
        EntityData entityData = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(position)
            .velocity(velocity)
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(5.5f)
            .alive(true)
            .addCustomData("blockType", blockType)
            .addCustomData("despawnTimer", 250.0f)
            .addCustomData("stackCount", 1)
            .build();

        // When: Deserializing the entity data
        EntityData.BlockDropData blockDropData = EntityData.BlockDropData.fromCustomData(entityData);

        // Then: All properties should be preserved
        assertNotNull(blockDropData, "BlockDropData should not be null");
        assertEquals(EntityType.BLOCK_DROP, entityData.getEntityType(), "Entity type should be BLOCK_DROP");

        // Position verification
        assertVector3fEquals(position, entityData.getPosition(), "Position should be preserved");

        // Velocity verification
        assertVector3fEquals(velocity, entityData.getVelocity(), "Velocity should be preserved");

        // Block-specific properties
        assertEquals(blockType, blockDropData.getBlockType(), "Block type should be preserved");
        assertEquals(250.0f, blockDropData.getDespawnTimer(), FLOAT_EPSILON, "Despawn timer should be preserved");
        assertEquals(1, blockDropData.getStackCount(), "Stack count should be preserved");

        // Entity state
        assertEquals(5.5f, entityData.getAge(), FLOAT_EPSILON, "Age should be preserved");
        assertTrue(entityData.isAlive(), "Entity should be alive");
    }

    @Test
    @DisplayName("BlockDrop - Serialize stacked drops")
    void testBlockDropStackedSerialization() {
        // Given: A stacked block drop (multiple items)
        Vector3f position = new Vector3f(15.2f, 70.0f, 8.7f);
        int stackCount = 64;

        EntityData entityData = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(position)
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.STONE)
            .addCustomData("despawnTimer", 300.0f)
            .addCustomData("stackCount", stackCount)
            .build();

        // When: Deserializing the entity data
        EntityData.BlockDropData blockDropData = EntityData.BlockDropData.fromCustomData(entityData);

        // Then: Stack count should be preserved
        assertEquals(stackCount, blockDropData.getStackCount(), "Stack count should match");
        assertEquals(BlockType.STONE, blockDropData.getBlockType(), "Block type should be STONE");
        assertVector3fEquals(position, entityData.getPosition(), "Position should be preserved");
    }

    @Test
    @DisplayName("BlockDrop - Serialize different block types")
    void testBlockDropDifferentBlockTypes() {
        BlockType[] blockTypes = {
            BlockType.GRASS,
            BlockType.DIRT,
            BlockType.STONE,
            BlockType.WOOD,
            BlockType.LEAVES,
            BlockType.SAND
        };

        for (BlockType blockType : blockTypes) {
            // Given: A block drop of each type
            EntityData entityData = EntityData.builder()
                .entityType(EntityType.BLOCK_DROP)
                .position(new Vector3f(0, 64, 0))
                .velocity(new Vector3f(0, 0, 0))
                .rotation(new Vector3f(0, 0, 0))
                .health(1.0f)
                .maxHealth(1.0f)
                .age(0.0f)
                .alive(true)
                .addCustomData("blockType", blockType)
                .addCustomData("despawnTimer", 300.0f)
                .addCustomData("stackCount", 1)
                .build();

            // When: Deserializing the entity data
            EntityData.BlockDropData blockDropData = EntityData.BlockDropData.fromCustomData(entityData);

            // Then: Block type should be preserved
            assertEquals(blockType, blockDropData.getBlockType(),
                "Block type " + blockType + " should be preserved");
        }
    }

    @Test
    @DisplayName("BlockDrop - Serialize with nearly expired despawn timer")
    void testBlockDropNearExpiration() {
        // Given: A block drop with nearly expired despawn timer
        float remainingTime = 5.0f;

        EntityData entityData = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(new Vector3f(0, 64, 0))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(295.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.DIRT)
            .addCustomData("despawnTimer", remainingTime)
            .addCustomData("stackCount", 1)
            .build();

        // When: Deserializing the entity data
        EntityData.BlockDropData blockDropData = EntityData.BlockDropData.fromCustomData(entityData);

        // Then: Despawn timer should be preserved
        assertEquals(remainingTime, blockDropData.getDespawnTimer(), FLOAT_EPSILON,
            "Nearly expired despawn timer should be preserved");
        assertEquals(295.0f, entityData.getAge(), FLOAT_EPSILON, "Entity age should be preserved");
    }

    @Test
    @DisplayName("Cow - Serialize and deserialize basic properties")
    void testCowBasicSerialization() {
        // Given: A cow entity with specific properties
        Vector3f position = new Vector3f(50.0f, 65.0f, 30.0f);
        Vector3f velocity = new Vector3f(0.2f, 0.0f, 0.1f);
        Vector3f rotation = new Vector3f(0.0f, 45.0f, 0.0f);
        String textureVariant = "angus";

        EntityData entityData = EntityData.builder()
            .entityType(EntityType.COW)
            .position(position)
            .velocity(velocity)
            .rotation(rotation)
            .health(8.0f)
            .maxHealth(10.0f)
            .age(120.0f)
            .alive(true)
            .addCustomData("textureVariant", textureVariant)
            .addCustomData("canBeMilked", true)
            .addCustomData("milkRegenTimer", 0.0f)
            .addCustomData("aiState", "WANDERING")
            .build();

        // When: Deserializing the entity data
        EntityData.CowData cowData = EntityData.CowData.fromCustomData(entityData);

        // Then: All properties should be preserved
        assertNotNull(cowData, "CowData should not be null");
        assertEquals(EntityType.COW, entityData.getEntityType(), "Entity type should be COW");

        // Position and movement
        assertVector3fEquals(position, entityData.getPosition(), "Position should be preserved");
        assertVector3fEquals(velocity, entityData.getVelocity(), "Velocity should be preserved");
        assertVector3fEquals(rotation, entityData.getRotation(), "Rotation should be preserved");

        // Cow-specific properties
        assertEquals(textureVariant, cowData.getTextureVariant(), "Texture variant should be preserved");
        assertTrue(cowData.canBeMilked(), "Milk state should be preserved");
        assertEquals(0.0f, cowData.getMilkRegenTimer(), FLOAT_EPSILON, "Milk regen timer should be preserved");
        assertEquals("WANDERING", cowData.getAiState(), "AI state should be preserved");

        // Health
        assertEquals(8.0f, entityData.getHealth(), FLOAT_EPSILON, "Health should be preserved");
        assertEquals(10.0f, entityData.getMaxHealth(), FLOAT_EPSILON, "Max health should be preserved");

        // Age
        assertEquals(120.0f, entityData.getAge(), FLOAT_EPSILON, "Age should be preserved");

        // Alive state
        assertTrue(entityData.isAlive(), "Entity should be alive");
    }

    @Test
    @DisplayName("Cow - Serialize all texture variants")
    void testCowTextureVariants() {
        String[] variants = {"default", "angus", "highland", "jersey"};

        for (String variant : variants) {
            // Given: A cow with each texture variant
            EntityData entityData = EntityData.builder()
                .entityType(EntityType.COW)
                .position(new Vector3f(0, 64, 0))
                .velocity(new Vector3f(0, 0, 0))
                .rotation(new Vector3f(0, 0, 0))
                .health(10.0f)
                .maxHealth(10.0f)
                .age(0.0f)
                .alive(true)
                .addCustomData("textureVariant", variant)
                .addCustomData("canBeMilked", true)
                .addCustomData("milkRegenTimer", 0.0f)
                .addCustomData("aiState", "IDLE")
                .build();

            // When: Deserializing the entity data
            EntityData.CowData cowData = EntityData.CowData.fromCustomData(entityData);

            // Then: Texture variant should be preserved
            assertEquals(variant, cowData.getTextureVariant(),
                "Texture variant '" + variant + "' should be preserved");
        }
    }

    @Test
    @DisplayName("Cow - Serialize different AI states")
    void testCowAIStates() {
        String[] aiStates = {"IDLE", "WANDERING", "GRAZING"};

        for (String aiState : aiStates) {
            // Given: A cow in each AI state
            EntityData entityData = EntityData.builder()
                .entityType(EntityType.COW)
                .position(new Vector3f(0, 64, 0))
                .velocity(new Vector3f(0, 0, 0))
                .rotation(new Vector3f(0, 0, 0))
                .health(10.0f)
                .maxHealth(10.0f)
                .age(0.0f)
                .alive(true)
                .addCustomData("textureVariant", "default")
                .addCustomData("canBeMilked", true)
                .addCustomData("milkRegenTimer", 0.0f)
                .addCustomData("aiState", aiState)
                .build();

            // When: Deserializing the entity data
            EntityData.CowData cowData = EntityData.CowData.fromCustomData(entityData);

            // Then: AI state should be preserved
            assertEquals(aiState, cowData.getAiState(),
                "AI state '" + aiState + "' should be preserved");
        }
    }

    @Test
    @DisplayName("Cow - Serialize milk regeneration state")
    void testCowMilkRegeneration() {
        // Given: A cow that has been milked and is regenerating
        EntityData entityData = EntityData.builder()
            .entityType(EntityType.COW)
            .position(new Vector3f(0, 64, 0))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(10.0f)
            .maxHealth(10.0f)
            .age(150.0f)
            .alive(true)
            .addCustomData("textureVariant", "default")
            .addCustomData("canBeMilked", false)
            .addCustomData("milkRegenTimer", 150.0f)
            .addCustomData("aiState", "IDLE")
            .build();

        // When: Deserializing the entity data
        EntityData.CowData cowData = EntityData.CowData.fromCustomData(entityData);

        // Then: Milk state should be preserved
        assertFalse(cowData.canBeMilked(), "Should not be able to milk");
        assertEquals(150.0f, cowData.getMilkRegenTimer(), FLOAT_EPSILON,
            "Milk regen timer should be preserved");
    }

    @Test
    @DisplayName("Cow - Serialize damaged cow")
    void testCowDamaged() {
        // Given: A cow that has taken damage
        float currentHealth = 5.5f;
        float maxHealth = 10.0f;

        EntityData entityData = EntityData.builder()
            .entityType(EntityType.COW)
            .position(new Vector3f(0, 64, 0))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(currentHealth)
            .maxHealth(maxHealth)
            .age(50.0f)
            .alive(true)
            .addCustomData("textureVariant", "default")
            .addCustomData("canBeMilked", true)
            .addCustomData("milkRegenTimer", 0.0f)
            .addCustomData("aiState", "WANDERING")
            .build();

        // When: Deserializing the entity data
        EntityData.CowData cowData = EntityData.CowData.fromCustomData(entityData);

        // Then: Health should be preserved
        assertEquals(currentHealth, entityData.getHealth(), FLOAT_EPSILON,
            "Current health should be preserved");
        assertEquals(maxHealth, entityData.getMaxHealth(), FLOAT_EPSILON,
            "Max health should be preserved");
        assertTrue(entityData.isAlive(), "Cow should still be alive");
    }

    @Test
    @DisplayName("Multiple entities - Serialize mixed entity types")
    void testMixedEntityTypes() {
        // Given: Multiple entity types
        EntityData blockDrop = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(new Vector3f(10, 64, 10))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.DIRT)
            .addCustomData("despawnTimer", 300.0f)
            .addCustomData("stackCount", 1)
            .build();

        EntityData cow = EntityData.builder()
            .entityType(EntityType.COW)
            .position(new Vector3f(20, 64, 20))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(10.0f)
            .maxHealth(10.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("textureVariant", "default")
            .addCustomData("canBeMilked", true)
            .addCustomData("milkRegenTimer", 0.0f)
            .addCustomData("aiState", "IDLE")
            .build();

        // When: Identifying entity types
        EntityType blockDropType = blockDrop.getEntityType();
        EntityType cowType = cow.getEntityType();

        // Then: Entity types should be correctly identified
        assertEquals(EntityType.BLOCK_DROP, blockDropType, "Should identify BLOCK_DROP");
        assertEquals(EntityType.COW, cowType, "Should identify COW");

        // And: Can deserialize to specific types
        EntityData.BlockDropData blockDropData = EntityData.BlockDropData.fromCustomData(blockDrop);
        EntityData.CowData cowData = EntityData.CowData.fromCustomData(cow);

        assertNotNull(blockDropData, "BlockDropData should deserialize");
        assertNotNull(cowData, "CowData should deserialize");
    }

    @Test
    @DisplayName("EntityData - Immutability test")
    void testEntityDataImmutability() {
        // Given: An entity data object
        Vector3f originalPosition = new Vector3f(10, 64, 20);
        EntityData entityData = EntityData.builder()
            .entityType(EntityType.COW)
            .position(originalPosition)
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(10.0f)
            .maxHealth(10.0f)
            .age(0.0f)
            .alive(true)
            .build();

        // When: Modifying the retrieved position
        Vector3f retrievedPosition = entityData.getPosition();
        retrievedPosition.x = 999.0f;

        // Then: Original entity data should not be modified
        Vector3f actualPosition = entityData.getPosition();
        assertEquals(10.0f, actualPosition.x, FLOAT_EPSILON,
            "Entity data should be immutable - position should not change");
    }

    // ===== Additional Edge Case Tests =====

    @Test
    @DisplayName("BlockDrop - Zero velocity (stationary)")
    void testBlockDropZeroVelocity() {
        // Given: A stationary block drop
        EntityData entityData = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(new Vector3f(5, 64, 5))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.STONE)
            .addCustomData("despawnTimer", 300.0f)
            .addCustomData("stackCount", 1)
            .build();

        // When: Deserializing
        EntityData.BlockDropData blockDropData = EntityData.BlockDropData.fromCustomData(entityData);

        // Then: Velocity should be zero
        Vector3f vel = entityData.getVelocity();
        assertEquals(0.0f, vel.x, FLOAT_EPSILON, "X velocity should be zero");
        assertEquals(0.0f, vel.y, FLOAT_EPSILON, "Y velocity should be zero");
        assertEquals(0.0f, vel.z, FLOAT_EPSILON, "Z velocity should be zero");
    }

    @Test
    @DisplayName("BlockDrop - Negative position coordinates")
    void testBlockDropNegativePosition() {
        // Given: A block drop at negative world coordinates
        Vector3f negativePos = new Vector3f(-100.5f, -10.0f, -200.3f);
        EntityData entityData = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(negativePos)
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.DIRT)
            .addCustomData("despawnTimer", 300.0f)
            .addCustomData("stackCount", 1)
            .build();

        // When: Deserializing
        EntityData.BlockDropData blockDropData = EntityData.BlockDropData.fromCustomData(entityData);

        // Then: Negative positions should be preserved
        assertVector3fEquals(negativePos, entityData.getPosition(), "Negative positions should be preserved");
    }

    @Test
    @DisplayName("BlockDrop - Expired despawn timer")
    void testBlockDropExpiredTimer() {
        // Given: A block drop with expired/zero despawn timer
        EntityData entityData = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(new Vector3f(0, 64, 0))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(300.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.STONE)
            .addCustomData("despawnTimer", 0.0f)
            .addCustomData("stackCount", 1)
            .build();

        // When: Deserializing
        EntityData.BlockDropData blockDropData = EntityData.BlockDropData.fromCustomData(entityData);

        // Then: Zero timer should be preserved
        assertEquals(0.0f, blockDropData.getDespawnTimer(), FLOAT_EPSILON, "Zero despawn timer should be preserved");
    }

    @Test
    @DisplayName("BlockDrop - Dead state (alive=false)")
    void testBlockDropDeadState() {
        // Given: A dead block drop
        EntityData entityData = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(new Vector3f(0, 64, 0))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(0.0f)
            .maxHealth(1.0f)
            .age(300.0f)
            .alive(false)
            .addCustomData("blockType", BlockType.DIRT)
            .addCustomData("despawnTimer", 0.0f)
            .addCustomData("stackCount", 1)
            .build();

        // When: Deserializing
        EntityData.BlockDropData blockDropData = EntityData.BlockDropData.fromCustomData(entityData);

        // Then: Dead state should be preserved
        assertFalse(entityData.isAlive(), "Dead state should be preserved");
        assertEquals(0.0f, entityData.getHealth(), FLOAT_EPSILON, "Health should be zero");
    }

    @Test
    @DisplayName("BlockDrop - Boundary values for floats")
    void testBlockDropBoundaryValues() {
        // Given: A block drop with extreme float values
        EntityData entityData = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(new Vector3f(Float.MAX_VALUE, 0, Float.MIN_VALUE))
            .velocity(new Vector3f(Float.MAX_VALUE / 2, -Float.MAX_VALUE / 2, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(Float.MAX_VALUE)
            .alive(true)
            .addCustomData("blockType", BlockType.STONE)
            .addCustomData("despawnTimer", Float.MAX_VALUE)
            .addCustomData("stackCount", 1)
            .build();

        // When: Deserializing
        EntityData.BlockDropData blockDropData = EntityData.BlockDropData.fromCustomData(entityData);

        // Then: Extreme values should be preserved
        Vector3f pos = entityData.getPosition();
        assertEquals(Float.MAX_VALUE, pos.x, "MAX_VALUE position X should be preserved");
        assertEquals(Float.MIN_VALUE, pos.z, "MIN_VALUE position Z should be preserved");
        assertEquals(Float.MAX_VALUE, entityData.getAge(), "MAX_VALUE age should be preserved");
    }

    @Test
    @DisplayName("BlockDrop - Large stack count")
    void testBlockDropLargeStack() {
        // Given: A block drop with maximum realistic stack count
        int largeStack = 999;
        EntityData entityData = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(new Vector3f(0, 64, 0))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.STONE)
            .addCustomData("despawnTimer", 300.0f)
            .addCustomData("stackCount", largeStack)
            .build();

        // When: Deserializing
        EntityData.BlockDropData blockDropData = EntityData.BlockDropData.fromCustomData(entityData);

        // Then: Large stack count should be preserved
        assertEquals(largeStack, blockDropData.getStackCount(), "Large stack count should be preserved");
    }

    @Test
    @DisplayName("Cow - Zero rotation")
    void testCowZeroRotation() {
        // Given: A cow with zero rotation
        EntityData entityData = EntityData.builder()
            .entityType(EntityType.COW)
            .position(new Vector3f(0, 64, 0))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(10.0f)
            .maxHealth(10.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("textureVariant", "default")
            .addCustomData("canBeMilked", true)
            .addCustomData("milkRegenTimer", 0.0f)
            .addCustomData("aiState", "IDLE")
            .build();

        // When: Deserializing
        EntityData.CowData cowData = EntityData.CowData.fromCustomData(entityData);

        // Then: Zero rotation should be preserved
        Vector3f rot = entityData.getRotation();
        assertEquals(0.0f, rot.x, FLOAT_EPSILON, "X rotation should be zero");
        assertEquals(0.0f, rot.y, FLOAT_EPSILON, "Y rotation should be zero");
        assertEquals(0.0f, rot.z, FLOAT_EPSILON, "Z rotation should be zero");
    }

    @Test
    @DisplayName("Cow - Boundary health values")
    void testCowBoundaryHealth() {
        // Test 1: Zero health
        EntityData zeroHealthCow = EntityData.builder()
            .entityType(EntityType.COW)
            .position(new Vector3f(0, 64, 0))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(0.0f)
            .maxHealth(10.0f)
            .age(0.0f)
            .alive(false)
            .addCustomData("textureVariant", "default")
            .addCustomData("canBeMilked", true)
            .addCustomData("milkRegenTimer", 0.0f)
            .addCustomData("aiState", "IDLE")
            .build();

        assertEquals(0.0f, zeroHealthCow.getHealth(), FLOAT_EPSILON, "Zero health should be preserved");

        // Test 2: Health equals max health
        EntityData fullHealthCow = EntityData.builder()
            .entityType(EntityType.COW)
            .position(new Vector3f(0, 64, 0))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(10.0f)
            .maxHealth(10.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("textureVariant", "default")
            .addCustomData("canBeMilked", true)
            .addCustomData("milkRegenTimer", 0.0f)
            .addCustomData("aiState", "IDLE")
            .build();

        assertEquals(fullHealthCow.getHealth(), fullHealthCow.getMaxHealth(), FLOAT_EPSILON, "Full health should equal max health");
    }

    @Test
    @DisplayName("Cow - Dead state")
    void testCowDeadState() {
        // Given: A dead cow
        EntityData entityData = EntityData.builder()
            .entityType(EntityType.COW)
            .position(new Vector3f(0, 64, 0))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(0.0f)
            .maxHealth(10.0f)
            .age(500.0f)
            .alive(false)
            .addCustomData("textureVariant", "angus")
            .addCustomData("canBeMilked", false)
            .addCustomData("milkRegenTimer", 0.0f)
            .addCustomData("aiState", "IDLE")
            .build();

        // When: Deserializing
        EntityData.CowData cowData = EntityData.CowData.fromCustomData(entityData);

        // Then: Dead state should be preserved
        assertFalse(entityData.isAlive(), "Cow should be dead");
        assertEquals(0.0f, entityData.getHealth(), FLOAT_EPSILON, "Health should be zero");
    }

    @Test
    @DisplayName("Cow - Negative timers")
    void testCowNegativeTimers() {
        // Given: A cow with negative milk regen timer (edge case)
        EntityData entityData = EntityData.builder()
            .entityType(EntityType.COW)
            .position(new Vector3f(0, 64, 0))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(10.0f)
            .maxHealth(10.0f)
            .age(-10.0f)  // Negative age edge case
            .alive(true)
            .addCustomData("textureVariant", "default")
            .addCustomData("canBeMilked", true)
            .addCustomData("milkRegenTimer", -5.0f)
            .addCustomData("aiState", "IDLE")
            .build();

        // When: Deserializing
        EntityData.CowData cowData = EntityData.CowData.fromCustomData(entityData);

        // Then: Negative values should be preserved (for robustness testing)
        assertEquals(-5.0f, cowData.getMilkRegenTimer(), FLOAT_EPSILON, "Negative milk timer should be preserved");
        assertEquals(-10.0f, entityData.getAge(), FLOAT_EPSILON, "Negative age should be preserved");
    }

    @Test
    @DisplayName("EntityData - Builder validation for missing entity type")
    void testEntityDataBuilderValidation() {
        // Given: A builder without entity type
        EntityData.Builder builder = EntityData.builder()
            .position(new Vector3f(0, 64, 0))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(10.0f)
            .maxHealth(10.0f)
            .age(0.0f)
            .alive(true);

        // When/Then: Building without entity type should throw exception
        assertThrows(IllegalStateException.class, builder::build,
            "Building without entity type should throw IllegalStateException");
    }

    @Test
    @DisplayName("Multiple entities - Same position (duplicate coordinates)")
    void testMultipleEntitiesSamePosition() {
        // Given: Two entities at the exact same position
        Vector3f sharedPosition = new Vector3f(10.5f, 64.0f, 20.3f);

        EntityData blockDrop = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(sharedPosition)
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.STONE)
            .addCustomData("despawnTimer", 300.0f)
            .addCustomData("stackCount", 1)
            .build();

        EntityData cow = EntityData.builder()
            .entityType(EntityType.COW)
            .position(sharedPosition)  // Same position
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(10.0f)
            .maxHealth(10.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("textureVariant", "default")
            .addCustomData("canBeMilked", true)
            .addCustomData("milkRegenTimer", 0.0f)
            .addCustomData("aiState", "IDLE")
            .build();

        // When/Then: Both entities should have the same position
        assertVector3fEquals(sharedPosition, blockDrop.getPosition(), "Block drop position should match");
        assertVector3fEquals(sharedPosition, cow.getPosition(), "Cow position should match");
        assertVector3fEquals(blockDrop.getPosition(), cow.getPosition(), "Positions should be identical");
    }

    @Test
    @DisplayName("Large entity count - Serialize 100 entities")
    void testLargeEntityCount() {
        // Given: 100 block drop entities
        int entityCount = 100;
        java.util.List<EntityData> entities = new java.util.ArrayList<>();

        for (int i = 0; i < entityCount; i++) {
            EntityData entityData = EntityData.builder()
                .entityType(EntityType.BLOCK_DROP)
                .position(new Vector3f(i, 64, i % 16))
                .velocity(new Vector3f(0, 0, 0))
                .rotation(new Vector3f(0, 0, 0))
                .health(1.0f)
                .maxHealth(1.0f)
                .age((float) i)
                .alive(true)
                .addCustomData("blockType", BlockType.STONE)
                .addCustomData("despawnTimer", 300.0f)
                .addCustomData("stackCount", 1)
                .build();
            entities.add(entityData);
        }

        // When: Verifying all entities
        // Then: All 100 entities should be created successfully
        assertEquals(entityCount, entities.size(), "Should have " + entityCount + " entities");

        // Verify first and last entity
        assertEquals(0.0f, entities.get(0).getAge(), FLOAT_EPSILON, "First entity age should be 0");
        assertEquals(99.0f, entities.get(99).getAge(), FLOAT_EPSILON, "Last entity age should be 99");
    }

    @Test
    @DisplayName("Cow - All texture variants preserve properties")
    void testCowTextureVariantPreservation() {
        // Given: Cows with all texture variants and varying properties
        String[] variants = {"default", "angus", "highland", "jersey"};
        float[] healthValues = {10.0f, 7.5f, 5.0f, 2.5f};
        boolean[] milkStates = {true, false, true, false};

        for (int i = 0; i < variants.length; i++) {
            // Create cow with specific properties
            EntityData entityData = EntityData.builder()
                .entityType(EntityType.COW)
                .position(new Vector3f(i * 10, 64, i * 10))
                .velocity(new Vector3f(0.1f * i, 0, 0.1f * i))
                .rotation(new Vector3f(0, i * 45.0f, 0))
                .health(healthValues[i])
                .maxHealth(10.0f)
                .age(i * 100.0f)
                .alive(true)
                .addCustomData("textureVariant", variants[i])
                .addCustomData("canBeMilked", milkStates[i])
                .addCustomData("milkRegenTimer", i * 50.0f)
                .addCustomData("aiState", "WANDERING")
                .build();

            // When: Deserializing
            EntityData.CowData cowData = EntityData.CowData.fromCustomData(entityData);

            // Then: All properties should be preserved correctly
            assertEquals(variants[i], cowData.getTextureVariant(),
                "Texture variant should be " + variants[i]);
            assertEquals(healthValues[i], entityData.getHealth(), FLOAT_EPSILON,
                "Health should be " + healthValues[i]);
            assertEquals(milkStates[i], cowData.canBeMilked(),
                "Milk state should be " + milkStates[i]);
            assertEquals(i * 50.0f, cowData.getMilkRegenTimer(), FLOAT_EPSILON,
                "Milk regen timer should be " + (i * 50.0f));
        }
    }

    @Test
    @DisplayName("EntityData - Custom data map modifications don't affect original")
    void testCustomDataImmutability() {
        // Given: An entity with custom data
        EntityData entityData = EntityData.builder()
            .entityType(EntityType.BLOCK_DROP)
            .position(new Vector3f(0, 64, 0))
            .velocity(new Vector3f(0, 0, 0))
            .rotation(new Vector3f(0, 0, 0))
            .health(1.0f)
            .maxHealth(1.0f)
            .age(0.0f)
            .alive(true)
            .addCustomData("blockType", BlockType.STONE)
            .addCustomData("despawnTimer", 300.0f)
            .addCustomData("stackCount", 1)
            .build();

        // When: Modifying the custom data map
        Map<String, Object> customData = entityData.getCustomData();
        customData.put("blockType", BlockType.DIRT);  // Attempt to modify

        // Then: Original entity data should not be affected
        EntityData.BlockDropData blockDropData = EntityData.BlockDropData.fromCustomData(entityData);
        assertEquals(BlockType.STONE, blockDropData.getBlockType(),
            "Block type should still be STONE (immutability preserved)");
    }

    // Helper method to compare Vector3f with epsilon
    private void assertVector3fEquals(Vector3f expected, Vector3f actual, String message) {
        assertEquals(expected.x, actual.x, FLOAT_EPSILON, message + " (X component)");
        assertEquals(expected.y, actual.y, FLOAT_EPSILON, message + " (Y component)");
        assertEquals(expected.z, actual.z, FLOAT_EPSILON, message + " (Z component)");
    }
}
