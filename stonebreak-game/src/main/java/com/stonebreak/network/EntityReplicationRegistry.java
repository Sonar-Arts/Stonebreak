package com.stonebreak.network;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.chicken.Chicken;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.mobs.entities.Arrow;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.CaltropCluster;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.FireBolt;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.mobs.entities.LeylineBreachZone;
import com.stonebreak.mobs.entities.NullSpikeProjectile;
import com.stonebreak.mobs.sheep.Sheep;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Single source of truth for HOW each replicating entity type crosses the wire:
 * {@link #metadataFor} packs the type-specific spawn metadata on the server;
 * {@link #createShadow} builds the client-side display shadow from it. Replaces the pair of
 * switch statements that previously lived in {@code ServerEntityHandler} /
 * {@code ClientEntityHandler} — adding an entity means one entry in each method here plus
 * flipping {@link EntityType#replicates()}, nothing else.
 *
 * <p>Shadows never run local physics/AI ({@code networkShadow=true} is applied by the
 * caller); constructor-args that only drive simulation (damage, durations) are passed as
 * zeros — only visually-relevant metadata replicates.
 *
 * <p>Deliberately NOT replicated (owner-driven, invisible to other players for now):
 * BOBBER (the fishing controller holds and drives the owner's instance per frame) and
 * ILLUSION_DECOY (the ability mirrors the owner's movement client-side each frame). Both
 * are documented follow-ups; {@link EntityType#replicates()} reflects this.
 */
public final class EntityReplicationRegistry {

    private EntityReplicationRegistry() {}

    /** Spawn metadata for a server entity, "" when the type carries none. */
    public static String metadataFor(Entity e) {
        return switch (e.getType()) {
            case COW -> ((Cow) e).getTextureVariant();
            case SHEEP -> ((Sheep) e).getTextureVariant();
            case BLOCK_DROP -> Integer.toString(((BlockDrop) e).getBlockType().getId());
            case ITEM_DROP -> {
                ItemStack stack = ((ItemDrop) e).getItemStack();
                yield stack.getBlockTypeId() + ":" + stack.getCount();
            }
            case LEYLINE_BREACH_ZONE -> {
                LeylineBreachZone zone = (LeylineBreachZone) e;
                yield zone.getRadius() + ":" + (zone.isOverloaded() ? 1 : 0);
            }
            default -> "";
        };
    }

    /**
     * Client shadow for an inbound spawn, or null when the type has no factory (which the
     * caller reports loudly — a replicating type without a factory is a wiring bug).
     */
    public static Entity createShadow(EntityType type, World world, Vector3f pos, String metadata) {
        return switch (type) {
            case COW -> new Cow(world, pos, orDefault(metadata, "default"));
            case SHEEP -> new Sheep(world, pos, orDefault(metadata, "default"));
            case CHICKEN -> new Chicken(world, pos);
            case BLOCK_DROP -> {
                BlockType bt = BlockType.getById(parseInt(metadata, 0));
                yield new BlockDrop(world, pos, bt != null ? bt : BlockType.AIR);
            }
            case ITEM_DROP -> {
                int itemId = 0;
                int count = 1;
                if (metadata != null && metadata.contains(":")) {
                    String[] parts = metadata.split(":", 2);
                    itemId = parseInt(parts[0], 0);
                    count = Math.max(1, parseInt(parts[1], 1));
                }
                yield new ItemDrop(world, pos, new ItemStack(itemId, count));
            }
            // Projectiles: sim params are zeros — shadows skip update(), only visuals matter.
            case ARROW -> new Arrow(world, pos, new Vector3f(0, 0, 1));
            case FIRE_BOLT -> new FireBolt(world, pos, new Vector3f(0, 0, 1));
            case NULL_SPIKE -> new NullSpikeProjectile(world, pos, new Vector3f(0, 0, 1), 0f, 0f, false, 0f);
            case CALTROP_CLUSTER -> new CaltropCluster(world, pos, Float.MAX_VALUE);
            case LEYLINE_BREACH_ZONE -> {
                float radius = 3f;
                boolean overloaded = false;
                if (metadata != null && metadata.contains(":")) {
                    String[] parts = metadata.split(":", 2);
                    radius = parseFloat(parts[0], 3f);
                    overloaded = parseInt(parts[1], 0) != 0;
                }
                yield new LeylineBreachZone(world, pos, radius, 0f, 0f, Float.MAX_VALUE, overloaded);
            }
            default -> null;
        };
    }

    private static String orDefault(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static float parseFloat(String s, float fallback) {
        if (s == null) return fallback;
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
