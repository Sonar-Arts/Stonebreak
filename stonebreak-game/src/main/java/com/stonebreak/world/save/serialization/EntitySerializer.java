package com.stonebreak.world.save.serialization;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.mobs.cow.CowAI;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.world.World;
import com.stonebreak.world.save.model.EntityData;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serializer for converting Entity objects to/from EntityData.
 * Handles all entity types following the Single Responsibility Principle.
 */
public class EntitySerializer {
    private static final Logger logger = Logger.getLogger(EntitySerializer.class.getName());

    /**
     * Serializes an entity to EntityData.
     * @param entity The entity to serialize
     * @return EntityData representation, or null if entity cannot be serialized
     */
    public static EntityData serialize(Entity entity) {
        if (entity == null || !entity.isAlive()) {
            return null;
        }

        EntityType entityType = entity.getType();
        EntityData.Builder builder = EntityData.builder()
            .entityType(entityType)
            .position(entity.getPosition())
            .velocity(entity.getVelocity())
            .rotation(entity.getRotation())
            .health(entity.getHealth())
            .maxHealth(entity.getMaxHealth())
            .age(entity.getAge())
            .alive(entity.isAlive());

        // Add entity-specific data
        switch (entityType) {
            case BLOCK_DROP -> serializeBlockDrop((BlockDrop) entity, builder);
            case COW -> serializeCow((Cow) entity, builder);
            default -> {
                logger.log(Level.WARNING, "Unknown entity type for serialization: " + entityType);
                return null;
            }
        }

        return builder.build();
    }

    /**
     * Deserializes EntityData to an Entity.
     * @param entityData The entity data to deserialize
     * @param world The world to create the entity in
     * @return The deserialized entity, or null if deserialization fails
     */
    public static Entity deserialize(EntityData entityData, World world) {
        if (entityData == null || world == null) {
            return null;
        }

        EntityType entityType = entityData.getEntityType();
        return switch (entityType) {
            case BLOCK_DROP -> deserializeBlockDrop(entityData, world);
            case COW -> deserializeCow(entityData, world);
            default -> {
                logger.log(Level.WARNING, "Unknown entity type for deserialization: " + entityType);
                yield null;
            }
        };
    }

    /**
     * Serializes multiple entities to EntityData list.
     */
    public static List<EntityData> serializeEntities(List<Entity> entities) {
        List<EntityData> entityDataList = new ArrayList<>();
        for (Entity entity : entities) {
            EntityData data = serialize(entity);
            if (data != null) {
                entityDataList.add(data);
            }
        }
        return entityDataList;
    }

    /**
     * Deserializes multiple EntityData objects to entities.
     */
    public static List<Entity> deserializeEntities(List<EntityData> entityDataList, World world) {
        List<Entity> entities = new ArrayList<>();
        for (EntityData entityData : entityDataList) {
            Entity entity = deserialize(entityData, world);
            if (entity != null) {
                entities.add(entity);
            }
        }
        return entities;
    }

    // ===== BlockDrop Serialization =====

    private static void serializeBlockDrop(BlockDrop blockDrop, EntityData.Builder builder) {
        try {
            // Access private fields via reflection (alternatively, add getters to BlockDrop)
            Field blockTypeField = BlockDrop.class.getDeclaredField("blockType");
            blockTypeField.setAccessible(true);
            BlockType blockType = (BlockType) blockTypeField.get(blockDrop);

            Field despawnTimerField = BlockDrop.class.getDeclaredField("despawnTimer");
            despawnTimerField.setAccessible(true);
            float despawnTimer = despawnTimerField.getFloat(blockDrop);

            // Stack count is accessible via public getter
            int stackCount = blockDrop.getStackCount();

            builder.addCustomData("blockType", blockType)
                   .addCustomData("despawnTimer", despawnTimer)
                   .addCustomData("stackCount", stackCount);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to serialize BlockDrop", e);
        }
    }

    private static Entity deserializeBlockDrop(EntityData entityData, World world) {
        try {
            EntityData.BlockDropData blockDropData = EntityData.BlockDropData.fromCustomData(entityData);
            Vector3f position = entityData.getPosition();
            BlockType blockType = blockDropData.getBlockType();

            // Create block drop
            BlockDrop blockDrop = new BlockDrop(world, position, blockType);

            // Restore state
            blockDrop.setPosition(position);
            blockDrop.setVelocity(entityData.getVelocity());
            blockDrop.setHealth(entityData.getHealth());
            blockDrop.setAlive(entityData.isAlive());
            blockDrop.setStackCount(blockDropData.getStackCount());

            // Restore despawn timer via reflection
            Field despawnTimerField = BlockDrop.class.getDeclaredField("despawnTimer");
            despawnTimerField.setAccessible(true);
            despawnTimerField.setFloat(blockDrop, blockDropData.getDespawnTimer());

            // Restore age via reflection
            Field ageField = Entity.class.getDeclaredField("age");
            ageField.setAccessible(true);
            ageField.setFloat(blockDrop, entityData.getAge());

            return blockDrop;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to deserialize BlockDrop", e);
            return null;
        }
    }

    // ===== Cow Serialization =====

    private static void serializeCow(Cow cow, EntityData.Builder builder) {
        try {
            // Texture variant is accessible via public getter
            String textureVariant = cow.getTextureVariant();

            // Access private fields via reflection
            Field canBeMilkedField = Cow.class.getDeclaredField("canBeMilked");
            canBeMilkedField.setAccessible(true);
            boolean canBeMilked = canBeMilkedField.getBoolean(cow);

            Field milkRegenTimerField = Cow.class.getDeclaredField("milkRegenTimer");
            milkRegenTimerField.setAccessible(true);
            float milkRegenTimer = milkRegenTimerField.getFloat(cow);

            // Get AI state
            CowAI cowAI = cow.getAI();
            String aiState = cowAI != null ? cowAI.getCurrentState().name() : "IDLE";

            builder.addCustomData("textureVariant", textureVariant)
                   .addCustomData("canBeMilked", canBeMilked)
                   .addCustomData("milkRegenTimer", milkRegenTimer)
                   .addCustomData("aiState", aiState);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to serialize Cow", e);
        }
    }

    private static Entity deserializeCow(EntityData entityData, World world) {
        try {
            EntityData.CowData cowData = EntityData.CowData.fromCustomData(entityData);
            Vector3f position = entityData.getPosition();
            String textureVariant = cowData.getTextureVariant();

            // Create cow with texture variant
            Cow cow = new Cow(world, position, textureVariant);

            // Restore basic state
            cow.setPosition(position);
            cow.setVelocity(entityData.getVelocity());
            cow.setRotation(entityData.getRotation());
            cow.setHealth(entityData.getHealth());
            cow.setMaxHealth(entityData.getMaxHealth());
            cow.setAlive(entityData.isAlive());

            // Restore milk state via reflection
            Field canBeMilkedField = Cow.class.getDeclaredField("canBeMilked");
            canBeMilkedField.setAccessible(true);
            canBeMilkedField.setBoolean(cow, cowData.canBeMilked());

            Field milkRegenTimerField = Cow.class.getDeclaredField("milkRegenTimer");
            milkRegenTimerField.setAccessible(true);
            milkRegenTimerField.setFloat(cow, cowData.getMilkRegenTimer());

            // Restore age via reflection
            Field ageField = Entity.class.getDeclaredField("age");
            ageField.setAccessible(true);
            ageField.setFloat(cow, entityData.getAge());

            // Note: AI state will be restored when AI updates next frame
            // The cow will start in its natural behavior and transition properly

            return cow;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to deserialize Cow", e);
            return null;
        }
    }
}
