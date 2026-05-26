package com.stonebreak.network.server.handlers;

import com.stonebreak.network.packet.player.PlayerHeldItemC2S;
import com.stonebreak.network.packet.player.PlayerHeldItemS2C;
import com.stonebreak.network.packet.player.PlayerStateC2S;
import com.stonebreak.network.packet.player.PlayerStateS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.operations.WorldConfiguration;

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
            new PlayerStateS2C(sp.playerId(), ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch()), true);
    }

    public void handleHeldItem(ServerPlayer sp, PlayerHeldItemC2S h, ServerWorldContext ctx) {
        sp.setHeldItemId(h.itemId());
        ctx.broadcast(new PlayerHeldItemS2C(sp.playerId(), h.itemId()), false);
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
