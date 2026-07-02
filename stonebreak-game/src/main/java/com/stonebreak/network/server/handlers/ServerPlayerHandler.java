package com.stonebreak.network.server.handlers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.network.packet.player.DropItemC2S;
import com.stonebreak.network.packet.player.PlayerHeldItemC2S;
import com.stonebreak.network.packet.player.PlayerHeldItemS2C;
import com.stonebreak.network.packet.player.PlayerStateC2S;
import com.stonebreak.network.packet.player.PlayerStateS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

/**
 * Server-authoritative player-state relay — successor of the old
 * {@code PlayerStateSynchronizer} HOST path. Validates and caches inbound client transforms
 * (the cache feeds block-edit reach checks and chunk targeting), rebroadcasts them to other
 * clients, and relays held-item changes.
 *
 * <p>In the two-world model every player — including the in-process local (host) player —
 * is a normal client that sends its own {@link PlayerStateC2S}; there is no host special case
 * here. Remote-player spawn/despawn + interpolation is a client concern (client world view).
 */
public final class ServerPlayerHandler {

    public void onSessionStart() {
        // no per-handler state to reset
    }

    // ─── Inbound ───────────────────────────────────────────────────────────────

    public void handlePlayerState(ServerPlayer sp, PlayerStateC2S ps, ServerWorldContext ctx) {
        if (!validateAndCache(sp, ps)) {
            return;
        }
        ctx.broadcastExcept(sp,
            new PlayerStateS2C(sp.playerId(), ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch(), ps.flags()), true);
    }

    public void handleHeldItem(ServerPlayer sp, PlayerHeldItemC2S h, ServerWorldContext ctx) {
        sp.setHeldItemId(h.itemId());
        ctx.broadcast(new PlayerHeldItemS2C(sp.playerId(), h.itemId()), false);
    }

    /** Upper bound on one DropItemC2S so a buggy/hostile client can't spawn item mountains. */
    private static final int MAX_DROP_COUNT = 64;
    /** Toss pickup lock-out: long enough for the dropper to walk away from their own toss. */
    private static final float TOSS_PICKUP_DELAY_SECONDS = 1.5f;

    /**
     * Player tossed an item (Q / inventory drop) or returned give-overflow: spawn the
     * authoritative drop entity in front of the player on the SERVER world; the entity-add
     * listener replicates it to every client (including the dropper) as a normal drop shadow.
     */
    public void handleDropItem(ServerPlayer sp, DropItemC2S d, ServerWorldContext ctx) {
        World world = ctx.world();
        EntityManager em = ctx.entityManager();
        if (world == null || em == null || d.count() <= 0 || sp.lastStateNs() == 0L) {
            return;
        }
        int count = Math.min(d.count(), MAX_DROP_COUNT);

        // Spawn slightly in front of the player's facing, with a gentle forward toss. Yaw
        // comes from the cached PlayerStateC2S (camera convention: front = (cos, 0, sin));
        // pitch is ignored (flat toss like drops).
        float yawRad = (float) Math.toRadians(sp.yaw());
        float fx = (float) Math.cos(yawRad);
        float fz = (float) Math.sin(yawRad);
        Vector3f pos = new Vector3f(sp.x() + fx * 1.5f, sp.y() + 1.0f, sp.z() + fz * 1.5f);
        Vector3f vel = new Vector3f(fx * 2.0f, 1.0f, fz * 2.0f);

        BlockType bt = BlockType.getById(d.itemId());
        if (bt != null && bt != BlockType.AIR) {
            BlockDrop drop = BlockDrop.createDropWithVelocity(world, pos, bt, vel);
            drop.setStackCount(count);
            drop.setPickupDelay(TOSS_PICKUP_DELAY_SECONDS);
            em.addEntity(drop);
        } else {
            ItemStack stack = new ItemStack(d.itemId(), count);
            if (stack.isEmpty()) {
                return; // unknown id — refuse quietly
            }
            ItemDrop drop = ItemDrop.createDropWithVelocity(world, pos, stack, vel);
            drop.setPickupDelay(TOSS_PICKUP_DELAY_SECONDS);
            em.addEntity(drop);
        }
    }

    /** Bring a newly-joined client up to date on every existing player's held item. */
    public void onPeerJoined(ServerPlayer joined, ServerWorldContext ctx) {
        for (ServerPlayer other : ctx.players()) {
            if (other.playerId() == joined.playerId()) {
                continue;
            }
            joined.send(new PlayerHeldItemS2C(other.playerId(), other.heldItemId()), false);
        }
    }

    // ─── Per-tick ───────────────────────────────────────────────────────────────

    public void tick(ServerWorldContext ctx) {
        // No host special case: every player (incl. the local one) relays its own state via
        // PlayerStateC2S → handlePlayerState. Nothing to broadcast from here.
    }

    // ─── Validation ──────────────────────────────────────────────────────────────

    private static boolean validateAndCache(ServerPlayer sp, PlayerStateC2S ps) {
        if (!Float.isFinite(ps.x()) || !Float.isFinite(ps.y()) || !Float.isFinite(ps.z())
            || !Float.isFinite(ps.yaw()) || !Float.isFinite(ps.pitch())) {
            return false;
        }
        if (ps.y() < -64f || ps.y() > WorldConfiguration.WORLD_HEIGHT + 64f) {
            return false;
        }
        // Per-packet velocity / no-teleport anti-cheat is intentionally NOT applied — naive
        // caps cause permanent desync on legitimate teleports (respawn, world-load, /tp).
        sp.updateState(ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch());
        return true;
    }
}
