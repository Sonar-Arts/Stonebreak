package com.stonebreak.network.client.handlers;

import com.openmason.engine.net.protocol.codec.EntityDeltaCodec;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.chicken.Chicken;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.mobs.goose.Goose;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.mobs.sheep.Sheep;
import com.stonebreak.network.client.NetworkInterpolator;
import com.stonebreak.network.packet.entity.EntityDespawnS2C;
import com.stonebreak.network.packet.entity.EntityMoveS2C;
import com.stonebreak.network.packet.entity.EntitySpawnS2C;
import com.stonebreak.network.packet.entity.EntityTeleportS2C;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side replication of non-player entities: creates "shadow" entities for inbound
 * spawns, feeds positions into a {@link NetworkInterpolator} so motion is smooth between
 * 20 Hz snapshots, and removes them on despawn. Successor of the old {@code EntitySynchronizer}
 * CLIENT path.
 */
public final class ClientEntityHandler {

    /** Network id → local shadow entity. Concurrent: session-end teardown can race the tick. */
    private final Map<Integer, Entity> byNetworkId = new ConcurrentHashMap<>();
    /** Spawn packets that arrived before the local world/entity manager was ready. */
    private final Deque<EntitySpawnS2C> pendingSpawns = new ArrayDeque<>();

    public void applySpawn(EntitySpawnS2C s) {
        if (Game.getWorld() == null || Game.getEntityManager() == null) {
            pendingSpawns.add(s);
            return;
        }
        if (byNetworkId.containsKey(s.networkId())) {
            return;
        }
        EntityType type = EntityType.values()[s.entityTypeOrdinal()];
        Entity entity = createShadow(type, new Vector3f(s.x(), s.y(), s.z()), s.metadata());
        if (entity == null) {
            return;
        }
        entity.setNetworkId(s.networkId());
        entity.setNetworkShadow(true);
        entity.setRotation(new Vector3f(0f, s.yaw(), 0f));
        NetworkInterpolator interp = new NetworkInterpolator();
        interp.seed(s.x(), s.y(), s.z(), s.yaw(), 0f);
        entity.setInterpolator(interp);
        Game.getEntityManager().addEntity(entity);
        byNetworkId.put(s.networkId(), entity);
    }

    public void applyDespawn(int networkId) {
        Entity e = byNetworkId.remove(networkId);
        if (e != null && Game.getEntityManager() != null) {
            Game.getEntityManager().removeEntity(e);
        }
    }

    public void applyDelta(EntityMoveS2C m) {
        Entity e = byNetworkId.get(m.networkId());
        if (e == null) {
            return;
        }
        NetworkInterpolator interp = e.getInterpolator();
        // Deltas rebase from the last broadcast target (not the displayed position) so they
        // don't smear between clients with mismatched display states.
        float baseX, baseY, baseZ;
        if (interp != null) {
            baseX = interp.targetX(); baseY = interp.targetY(); baseZ = interp.targetZ();
        } else {
            Vector3f p = e.getPosition();
            baseX = p.x; baseY = p.y; baseZ = p.z;
        }
        float nx = baseX + EntityDeltaCodec.decodePosDelta(m.dx());
        float ny = baseY + EntityDeltaCodec.decodePosDelta(m.dy());
        float nz = baseZ + EntityDeltaCodec.decodePosDelta(m.dz());
        float yaw = EntityDeltaCodec.decodeYawDeg(m.yawDeg10());
        applyAbsolute(m.networkId(), nx, ny, nz, yaw);
    }

    public void applyTeleport(EntityTeleportS2C t) {
        applyAbsolute(t.networkId(), t.x(), t.y(), t.z(), t.yaw());
    }

    /**
     * Apply a server-replicated animation/behavior state to a shadow entity. The shadow's AI is
     * otherwise frozen (shadows skip local update), so this is what makes its rendered animation,
     * debug wireframe, and debug overlay track the authoritative server state.
     */
    public void applyAnim(int networkId, String sbeState) {
        Entity e = byNetworkId.get(networkId);
        if (e != null) {
            e.applyNetworkState(sbeState);
        }
    }

    public void applyAbsolute(int networkId, float x, float y, float z, float yaw) {
        Entity e = byNetworkId.get(networkId);
        if (e == null) {
            return;
        }
        NetworkInterpolator interp = e.getInterpolator();
        if (interp != null) {
            interp.receive(x, y, z, yaw, e);
        } else {
            e.setPosition(new Vector3f(x, y, z));
            e.setRotation(new Vector3f(e.getRotation().x, yaw, e.getRotation().z));
        }
    }

    public void tick() {
        if (pendingSpawns.isEmpty() || Game.getWorld() == null || Game.getEntityManager() == null) {
            return;
        }
        Iterator<EntitySpawnS2C> it = pendingSpawns.iterator();
        while (it.hasNext()) {
            applySpawn(it.next());
            it.remove();
        }
    }

    public void onSessionEnd() {
        EntityManager em = Game.getEntityManager();
        if (em != null) {
            for (Entity e : byNetworkId.values()) {
                if (e.isNetworkShadow()) {
                    em.removeEntity(e);
                }
            }
        }
        byNetworkId.clear();
        pendingSpawns.clear();
    }

    // ─── Shadow construction ──────────────────────────────────────────────────

    private static Entity createShadow(EntityType type, Vector3f pos, String metadata) {
        return switch (type) {
            case COW -> {
                String variant = (metadata == null || metadata.isBlank()) ? "default" : metadata;
                yield new Cow(Game.getWorld(), pos, variant);
            }
            case CHICKEN -> new Chicken(Game.getWorld(), pos);
            case GOOSE -> new Goose(Game.getWorld(), pos);
            case SHEEP -> {
                String variant = (metadata == null || metadata.isBlank()) ? "default" : metadata;
                yield new Sheep(Game.getWorld(), pos, variant);
            }
            case BLOCK_DROP -> {
                BlockType bt = BlockType.getById(parseInt(metadata, 0));
                if (bt == null) {
                    bt = BlockType.AIR;
                }
                yield new BlockDrop(Game.getWorld(), pos, bt);
            }
            case ITEM_DROP -> {
                int itemId = 0;
                int count = 1;
                if (metadata != null && metadata.contains(":")) {
                    String[] parts = metadata.split(":", 2);
                    itemId = parseInt(parts[0], 0);
                    count = Math.max(1, parseInt(parts[1], 1));
                }
                yield new ItemDrop(Game.getWorld(), pos, new ItemStack(itemId, count));
            }
            default -> null;
        };
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
