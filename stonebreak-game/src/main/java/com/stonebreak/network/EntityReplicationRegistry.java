package com.stonebreak.network;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.chicken.Chicken;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.mobs.entities.Arrow;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.CaltropCluster;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.items.Item;
import com.stonebreak.mobs.entities.FireBolt;
import com.stonebreak.mobs.entities.FishingBobber;
import com.stonebreak.mobs.entities.IllusionDecoy;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.GoblinLoadout;
import com.stonebreak.mobs.goose.Goose;
import com.stonebreak.mobs.entities.LeylineBreachZone;
import com.stonebreak.mobs.entities.NullSpikeProjectile;
import com.stonebreak.mobs.sheep.Sheep;
import com.stonebreak.network.packet.NetItemStack;
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
 * <p>BOBBER and ILLUSION_DECOY are server entities since #341: their metadata names the
 * owning player, so the owner's client can find its own bobber (fishing line) and decoys
 * (ability bookkeeping) among the shadows.
 */
public final class EntityReplicationRegistry {

    private EntityReplicationRegistry() {}

    /** Spawn metadata for a server entity, "" when the type carries none. */
    public static String metadataFor(Entity e) {
        return switch (e.getType()) {
            case COW -> ((Cow) e).getTextureVariant();
            case SHEEP -> ((Sheep) e).getTextureVariant();
            case GOBLIN -> ((Goblin) e).getLoadout().encode();
            case BLOCK_DROP -> Integer.toString(((BlockDrop) e).getBlockType().getId());
            case ITEM_DROP -> {
                // The one stack codec (#342): id, the drop's live count and the stack's state.
                ItemDrop drop = (ItemDrop) e;
                yield NetItemStack.of(drop.getItemStack(), drop.getStackCount()).toMetadata();
            }
            case LEYLINE_BREACH_ZONE -> {
                LeylineBreachZone zone = (LeylineBreachZone) e;
                yield zone.getRadius() + ":" + (zone.isOverloaded() ? 1 : 0);
            }
            case BOBBER -> Integer.toString(e.getOwnerPlayerId());
            case ILLUSION_DECOY -> {
                // owner:angle:heldItemId — the shadow shows the item held at the cast.
                IllusionDecoy decoy = (IllusionDecoy) e;
                Item held = decoy.getHeldItem();
                yield decoy.getOwnerPlayerId() + ":" + decoy.getAngle() + ":" + (held != null ? held.getId() : 0);
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
            case GOOSE -> new Goose(world, pos);
            case GOBLIN -> new Goblin(world, pos, GoblinLoadout.decode(metadata));
            case BLOCK_DROP -> {
                BlockType bt = BlockType.getById(parseInt(metadata, 0));
                yield new BlockDrop(world, pos, bt != null ? bt : BlockType.AIR);
            }
            case ITEM_DROP -> {
                NetItemStack stack = NetItemStack.fromMetadata(metadata);
                yield new ItemDrop(world, pos, new NetItemStack(
                        stack.itemId(), Math.max(1, stack.count()), stack.state()).toItemStack());
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
            case BOBBER -> {
                FishingBobber bobber = new FishingBobber(world, pos, new Vector3f(0, 0, 1));
                bobber.setOwnerPlayerId(parseInt(metadata, -1));
                yield bobber;
            }
            case ILLUSION_DECOY -> {
                // No mirror source: the server moves the decoy, the shadow follows the network.
                String[] parts = metadata != null ? metadata.split(":", 3) : new String[0];
                IllusionDecoy decoy = new IllusionDecoy(world, pos, null,
                        parts.length > 1 ? parseFloat(parts[1], 0f) : 0f);
                decoy.setOwnerPlayerId(parts.length > 0 ? parseInt(parts[0], -1) : -1);
                if (parts.length > 2) {
                    decoy.setHeldItem(new NetItemStack(parseInt(parts[2], 0), 1).item());
                }
                yield decoy;
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
