package com.stonebreak.network.server.handlers;

import com.stonebreak.network.packet.player.PlayerHeldItemC2S;
import com.stonebreak.network.packet.player.PlayerHeldItemS2C;
import com.stonebreak.network.packet.player.PlayerStateC2S;
import com.stonebreak.network.packet.player.PlayerStateS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.player.Player;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

/**
 * Server-authoritative player-state relay — successor of the old
 * {@code PlayerStateSynchronizer} HOST path. Validates and caches inbound client transforms
 * (the cache feeds block-edit reach checks and chunk targeting), rebroadcasts them to other
 * clients, relays held-item changes, and each tick broadcasts the host player's own state.
 *
 * <p>Remote-player spawn/despawn + interpolation is a client concern and lives in the
 * client world view, not here.
 */
public final class ServerPlayerHandler {

    /** Last held item id broadcast for the host (player 0); -1 = never sent. */
    private int lastHostHeldItemId = -1;

    public void onSessionStart() {
        lastHostHeldItemId = -1;
    }

    // ─── Inbound ───────────────────────────────────────────────────────────────

    public void handlePlayerState(ServerPlayer sp, PlayerStateC2S ps, ServerWorldContext ctx) {
        if (!validateAndCache(sp, ps)) {
            return;
        }
        ctx.broadcastExcept(sp,
            new PlayerStateS2C(sp.playerId(), ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch()), true);
    }

    public void handleHeldItem(ServerPlayer sp, PlayerHeldItemC2S h, ServerWorldContext ctx) {
        sp.setHeldItemId(h.itemId());
        ctx.broadcast(new PlayerHeldItemS2C(sp.playerId(), h.itemId()), false);
    }

    /** Bring a newly-joined client up to date on every existing player's held item. */
    public void onPeerJoined(ServerPlayer joined, ServerWorldContext ctx) {
        Player host = ctx.hostPlayer();
        if (host != null && host.getInventory() != null) {
            joined.send(new PlayerHeldItemS2C(ServerWorldContext.HOST_PLAYER_ID,
                host.getInventory().getSelectedBlockTypeId()), false);
        }
        for (ServerPlayer other : ctx.players()) {
            if (other.playerId() == joined.playerId()) {
                continue;
            }
            joined.send(new PlayerHeldItemS2C(other.playerId(), other.heldItemId()), false);
        }
    }

    // ─── Per-tick host broadcast ────────────────────────────────────────────────

    public void tick(ServerWorldContext ctx) {
        Player p = ctx.hostPlayer();
        if (p == null) {
            return;
        }
        Vector3f pos = p.getPosition();
        float yaw = p.getCamera() != null ? p.getCamera().getYaw() : 0f;
        float pitch = p.getCamera() != null ? p.getCamera().getPitch() : 0f;
        ctx.broadcast(new PlayerStateS2C(ServerWorldContext.HOST_PLAYER_ID,
            pos.x, pos.y, pos.z, yaw, pitch), true);

        if (p.getInventory() != null) {
            int held = p.getInventory().getSelectedBlockTypeId();
            if (held != lastHostHeldItemId) {
                lastHostHeldItemId = held;
                ctx.broadcast(new PlayerHeldItemS2C(ServerWorldContext.HOST_PLAYER_ID, held), false);
            }
        }
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
