package com.stonebreak.network.server.handlers;

import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.IllusionDecoy;
import com.stonebreak.network.packet.entity.DecoyCastC2S;
import com.stonebreak.network.server.ServerInventory;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.network.server.combat.CombatRules;
import com.stonebreak.player.PlayerBodyOrientation;
import com.stonebreak.player.PlayerConstants;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Server-side Mirrored Deceit decoys (issue #341). A cast ({@link DecoyCastC2S}) spawns the
 * decoys in the SERVER world — where server mobs live and can target them — and they replicate
 * to every client like any other entity. Each decoy mirrors the server's record of its owner
 * ({@link IllusionDecoy.MirrorSource}) and is retired when the ability's duration lapses or the
 * owner leaves; a hit kills it through the normal damage path. The caster's client keeps the
 * ability's cooldown, smoke and punish bookkeeping on the replicated shadows.
 *
 * <p>Server thread only (dispatch + tick), so the bookkeeping is a plain map.
 */
public final class ServerIllusionHandler {

    private static final class Cast {
        final ServerPlayer owner;
        final List<IllusionDecoy> decoys = new ArrayList<>();
        float remaining = PlayerConstants.ILLUSIONIST_DECOY_DURATION;

        Cast(ServerPlayer owner) {
            this.owner = owner;
        }
    }

    /** playerId → that player's live decoy set. */
    private final Map<Integer, Cast> casts = new HashMap<>();

    /**
     * Spawns the caster's decoys at its reported position. One set per player at a time — a
     * second cast while the first is out is ignored — and only for an Illusionist with Mirrored
     * Deceit unlocked, at most once per decoy life plus cooldown ({@link CombatRules}).
     */
    public void handleDecoyCast(ServerPlayer sp, DecoyCastC2S cast, ServerWorldContext ctx) {
        EntityManager em = ctx.entityManager();
        if (em == null || ctx.world() == null || sp.lastStateNs() == 0L || sp.isDead()
                || casts.containsKey(sp.playerId()) || !CombatRules.mayCastDecoy(sp.serverStats())
                || !sp.combat().tryDecoyCast(System.nanoTime())) {
            return;
        }
        Cast c = new Cast(sp);
        IllusionDecoy.MirrorSource source = mirrorOf(sp);
        Item held = heldItem(sp.inventory());
        for (float angle : IllusionDecoy.fanAngles()) {
            IllusionDecoy decoy = new IllusionDecoy(ctx.world(), new Vector3f(sp.x(), sp.y(), sp.z()),
                    source, angle);
            decoy.setHeldItem(held);
            decoy.setOwnerPlayerId(sp.playerId());
            em.addEntity(decoy);
            c.decoys.add(decoy);
        }
        casts.put(sp.playerId(), c);
    }

    /** Ages every cast: lapsed or orphaned decoys are retired; the rest track the held item. */
    public void tick(ServerWorldContext ctx, float dtSeconds) {
        Iterator<Map.Entry<Integer, Cast>> it = casts.entrySet().iterator();
        while (it.hasNext()) {
            Cast c = it.next().getValue();
            c.remaining -= dtSeconds;
            boolean retire = c.remaining <= 0f || ctx.player(c.owner.playerId()) != c.owner;
            Item held = retire ? null : heldItem(c.owner.inventory());
            c.decoys.removeIf(decoy -> {
                if (retire) {
                    decoy.setAlive(false);
                } else {
                    // Mirror mid-cast item swaps (the replicated shadows show the spawn item).
                    decoy.setHeldItem(held);
                }
                return !decoy.isAlive();
            });
            if (c.decoys.isEmpty()) {
                it.remove();
            }
        }
    }

    /** Session over: forget every cast (the world and its entities go with it). */
    public void onSessionEnd() {
        casts.clear();
    }

    /** The decoys {@code sp} has out (a copy). Package-private for tests. */
    List<IllusionDecoy> decoysOf(ServerPlayer sp) {
        Cast c = casts.get(sp.playerId());
        return c != null ? List.copyOf(c.decoys) : List.of();
    }

    /**
     * The server's view of a player as a mirror source: its reported position, and its look
     * yaw for both facings (the server does not track the body's lagging turn).
     */
    private static IllusionDecoy.MirrorSource mirrorOf(ServerPlayer sp) {
        return new IllusionDecoy.MirrorSource() {
            private final Vector3f pos = new Vector3f();

            @Override
            public Vector3f position() {
                return pos.set(sp.x(), sp.y(), sp.z());
            }

            @Override
            public float bodyYaw() {
                return lookYaw();
            }

            @Override
            public float lookYaw() {
                double yawRad = Math.toRadians(sp.yaw());
                return PlayerBodyOrientation.modelYawFromDirection(
                        (float) Math.cos(yawRad), (float) Math.sin(yawRad));
            }
        };
    }

    /** The item in the player's selected hotbar slot, or null when empty-handed. */
    private static Item heldItem(ServerInventory inv) {
        ItemStack held = inv.slot(inv.selectedHotbarSlot());
        return held.isEmpty() ? null : held.getItem();
    }
}
