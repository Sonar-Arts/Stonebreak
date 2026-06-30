package com.stonebreak.world.save.serialization;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.chicken.Chicken;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.mobs.sheep.Sheep;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.world.World;
import com.stonebreak.world.save.model.EntityData;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        // Network-driven entities aren't part of the world's persistent state;
        // they're recreated on connect from inbound packets. Skip silently to
        // avoid the "Unknown entity type" warning at every save.
        if (entity.isNetworkShadow() || entity.getType() == EntityType.REMOTE_PLAYER) {
            return null;
        }
        // Transient entities (projectiles such as fire bolts, arrows, and
        // bobbers) are not part of the world's persistent state. Skip silently
        // to avoid the "Unknown entity type" warning at every save.
        if (!entity.isPersistent()) {
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
            case ITEM_DROP -> serializeItemDrop((ItemDrop) entity, builder);
            case COW -> serializeCow((Cow) entity, builder);
            case CHICKEN -> serializeChicken((Chicken) entity, builder);
            case SHEEP -> serializeSheep((Sheep) entity, builder);
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
            case ITEM_DROP -> deserializeItemDrop(entityData, world);
            case COW -> deserializeCow(entityData, world);
            case CHICKEN -> deserializeChicken(entityData, world);
            case SHEEP -> deserializeSheep(entityData, world);
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

    /**
     * Reads the persisted AI behavior-state name from an entity's custom data, or null when it
     * was never written (older saves) or is not a string. Restoring it on load keeps a mob's
     * behavior continuous across a save/reload instead of resetting to its default state.
     */
    private static String readAiState(EntityData entityData) {
        Object value = entityData.getCustomData().get("aiState");
        return (value instanceof String s) ? s : null;
    }

    // ===== BlockDrop Serialization =====

    private static void serializeBlockDrop(BlockDrop blockDrop, EntityData.Builder builder) {
        builder.addCustomData("blockType", blockDrop.getBlockType())
               .addCustomData("despawnTimer", blockDrop.getDespawnTimer())
               .addCustomData("stackCount", blockDrop.getStackCount());
    }

    private static Entity deserializeBlockDrop(EntityData entityData, World world) {
        try {
            EntityData.BlockDropData blockDropData = EntityData.BlockDropData.fromCustomData(entityData);
            Vector3f position = entityData.getPosition();

            BlockDrop blockDrop = new BlockDrop(world, position, blockDropData.getBlockType());
            blockDrop.setPosition(position);
            blockDrop.setVelocity(entityData.getVelocity());
            blockDrop.setHealth(entityData.getHealth());
            blockDrop.setAlive(entityData.isAlive());
            blockDrop.setStackCount(blockDropData.getStackCount());
            blockDrop.setDespawnTimer(blockDropData.getDespawnTimer());
            blockDrop.setAge(entityData.getAge());
            return blockDrop;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to deserialize BlockDrop", e);
            return null;
        }
    }

    // ===== ItemDrop Serialization =====

    private static void serializeItemDrop(ItemDrop itemDrop, EntityData.Builder builder) {
        ItemStack itemStack = itemDrop.getItemStack();
        if (itemStack == null || itemStack.isEmpty()) {
            logger.log(Level.WARNING, "ItemDrop has null or empty ItemStack");
            return;
        }
        builder.addCustomData("itemId", itemStack.getItem().getId())
               .addCustomData("isBlockType", itemStack.asBlockType() != null)
               .addCustomData("itemCount", itemStack.getCount())
               .addCustomData("despawnTimer", itemDrop.getDespawnTimer())
               .addCustomData("stackCount", itemDrop.getStackCount());
    }

    private static Entity deserializeItemDrop(EntityData entityData, World world) {
        try {
            EntityData.ItemDropData itemDropData = EntityData.ItemDropData.fromCustomData(entityData);
            Vector3f position = entityData.getPosition();

            ItemStack itemStack;
            if (itemDropData.isBlockType()) {
                BlockType blockType = BlockType.getById(itemDropData.getItemId());
                if (blockType == null) {
                    logger.log(Level.WARNING, "Failed to find BlockType with ID: " + itemDropData.getItemId());
                    return null;
                }
                itemStack = new ItemStack(blockType, itemDropData.getItemCount());
            } else {
                ItemType itemType = ItemType.getById(itemDropData.getItemId());
                if (itemType == null) {
                    logger.log(Level.WARNING, "Failed to find ItemType with ID: " + itemDropData.getItemId());
                    return null;
                }
                itemStack = new ItemStack(itemType, itemDropData.getItemCount());
            }

            ItemDrop itemDrop = new ItemDrop(world, position, itemStack);
            itemDrop.setPosition(position);
            itemDrop.setVelocity(entityData.getVelocity());
            itemDrop.setHealth(entityData.getHealth());
            itemDrop.setAlive(entityData.isAlive());
            itemDrop.setStackCount(itemDropData.getStackCount());
            itemDrop.setDespawnTimer(itemDropData.getDespawnTimer());
            itemDrop.setAge(entityData.getAge());
            return itemDrop;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to deserialize ItemDrop", e);
            return null;
        }
    }

    // ===== Cow Serialization =====

    private static void serializeCow(Cow cow, EntityData.Builder builder) {
        String aiState = cow.getAI() != null ? cow.getAI().getCurrentState().name() : "IDLE";
        builder.addCustomData("textureVariant", cow.getTextureVariant())
               .addCustomData("canBeMilked", cow.isCanBeMilked())
               .addCustomData("milkRegenTimer", cow.getMilkRegenTimer())
               .addCustomData("aiState", aiState);
    }

    private static Entity deserializeCow(EntityData entityData, World world) {
        try {
            EntityData.CowData cowData = EntityData.CowData.fromCustomData(entityData);
            Vector3f position = entityData.getPosition();

            Cow cow = new Cow(world, position, cowData.getTextureVariant());
            cow.setPosition(position);
            cow.setVelocity(entityData.getVelocity());
            cow.setRotation(entityData.getRotation());
            cow.setHealth(entityData.getHealth());
            cow.setMaxHealth(entityData.getMaxHealth());
            cow.setAlive(entityData.isAlive());
            cow.setCanBeMilked(cowData.canBeMilked());
            cow.setMilkRegenTimer(cowData.getMilkRegenTimer());
            cow.setAge(entityData.getAge());
            String aiState = readAiState(entityData);
            if (aiState != null && cow.getAI() != null) {
                try {
                    cow.getAI().setState(
                        com.stonebreak.mobs.cow.CowAI.CowBehaviorState.valueOf(aiState));
                } catch (IllegalArgumentException ignored) {
                    // Unknown/renamed state — leave the AI at its constructor default.
                }
            }
            return cow;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to deserialize Cow", e);
            return null;
        }
    }

    // ===== Chicken Serialization =====

    private static void serializeChicken(Chicken chicken, EntityData.Builder builder) {
        String aiState = chicken.getAI() != null
                ? chicken.getAI().getCurrentState().name()
                : "IDLE";
        builder.addCustomData("aiState", aiState);
    }

    private static Entity deserializeChicken(EntityData entityData, World world) {
        try {
            Vector3f position = entityData.getPosition();
            Chicken chicken = new Chicken(world, position);
            chicken.setPosition(position);
            chicken.setVelocity(entityData.getVelocity());
            chicken.setRotation(entityData.getRotation());
            chicken.setHealth(entityData.getHealth());
            chicken.setMaxHealth(entityData.getMaxHealth());
            chicken.setAlive(entityData.isAlive());
            chicken.setAge(entityData.getAge());
            String aiState = readAiState(entityData);
            if (aiState != null && chicken.getAI() != null) {
                try {
                    chicken.getAI().setState(
                        com.stonebreak.mobs.chicken.ChickenAI.ChickenBehaviorState.valueOf(aiState));
                } catch (IllegalArgumentException ignored) {
                    // Unknown/renamed state — leave the AI at its constructor default.
                }
            }
            return chicken;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to deserialize Chicken", e);
            return null;
        }
    }

    // ===== Sheep Serialization =====

    private static void serializeSheep(Sheep sheep, EntityData.Builder builder) {
        String aiState = sheep.getAI() != null
                ? sheep.getAI().getCurrentState().name()
                : "IDLE";
        builder.addCustomData("textureVariant", sheep.getTextureVariant())
               .addCustomData("aiState", aiState);
    }

    private static Entity deserializeSheep(EntityData entityData, World world) {
        try {
            Vector3f position = entityData.getPosition();
            Map<String, Object> customData = entityData.getCustomData();
            String textureVariant = (String) customData.getOrDefault("textureVariant", "default");

            Sheep sheep = new Sheep(world, position, textureVariant);
            sheep.setPosition(position);
            sheep.setVelocity(entityData.getVelocity());
            sheep.setRotation(entityData.getRotation());
            sheep.setHealth(entityData.getHealth());
            sheep.setMaxHealth(entityData.getMaxHealth());
            sheep.setAlive(entityData.isAlive());
            sheep.setAge(entityData.getAge());
            String aiState = readAiState(entityData);
            if (aiState != null && sheep.getAI() != null) {
                try {
                    sheep.getAI().setState(
                        com.stonebreak.mobs.sheep.SheepAI.SheepBehaviorState.valueOf(aiState));
                } catch (IllegalArgumentException ignored) {
                    // Unknown/renamed state — leave the AI at its constructor default.
                }
            }
            return sheep;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to deserialize Sheep", e);
            return null;
        }
    }
}
