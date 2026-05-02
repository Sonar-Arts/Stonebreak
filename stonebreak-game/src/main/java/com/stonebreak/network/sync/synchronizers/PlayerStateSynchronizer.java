package com.stonebreak.network.sync.synchronizers;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.network.protocol.Packet;
import com.stonebreak.network.sync.SyncContext;
import com.stonebreak.network.sync.SyncEvent;
import com.stonebreak.network.sync.SyncMode;
import com.stonebreak.network.sync.Synchronizer;
import com.stonebreak.player.Player;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Owns:
 *  - Periodic broadcast of the local player's position/yaw/pitch (~20 Hz).
 *  - Spawning/despawning {@link RemotePlayer} cylinders for every other peer.
 *  - Applying inbound PlayerState updates onto the right RemotePlayer.
 */
public final class PlayerStateSynchronizer implements Synchronizer {

    private final Map<Integer, RemotePlayer> remotePlayers = new HashMap<>();

    @Override
    public void onSessionStart(SyncContext ctx) {
        remotePlayers.clear();
    }

    @Override
    public void onSessionEnd() {
        EntityManager em = Game.getEntityManager();
        if (em != null) {
            for (RemotePlayer rp : remotePlayers.values()) em.removeEntity(rp);
        }
        remotePlayers.clear();
    }

    @Override
    public boolean handlesInbound(Packet packet) {
        return packet instanceof Packet.PlayerStateC2S
                || packet instanceof Packet.PlayerStateS2C
                || packet instanceof Packet.PlayerJoinS2C
                || packet instanceof Packet.PlayerLeaveS2C;
    }

    @Override
    public void applyInbound(Packet packet, Integer originId, SyncContext ctx) {
        switch (packet) {
            case Packet.PlayerStateC2S ps -> {
                if (originId == null) return;
                applyRemoteState(originId, ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch());
                ctx.broadcastExcept(originId,
                        new Packet.PlayerStateS2C(originId, ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch()));
            }
            case Packet.PlayerStateS2C ps -> {
                if (ps.playerId() == ctx.localPlayerId()) return;
                applyRemoteState(ps.playerId(), ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch());
            }
            case Packet.PlayerJoinS2C j -> {
                if (j.playerId() == ctx.localPlayerId()) return;
                spawnRemote(j.playerId(), j.username(), j.x(), j.y(), j.z());
            }
            case Packet.PlayerLeaveS2C l -> despawnRemote(l.playerId());
            default -> {}
        }
    }

    @Override
    public boolean handlesLocal(SyncEvent event) { return false; }

    @Override
    public void emitLocal(SyncEvent event, SyncContext ctx) { /* no-op */ }

    @Override
    public void tick(float deltaTime, SyncContext ctx) {
        // Server tick is fixed 20 Hz — no further wall-clock throttling needed.
        Player p = Game.getPlayer();
        if (p == null) return;
        Vector3f pos = p.getPosition();
        float yaw   = p.getCamera() != null ? p.getCamera().getYaw()   : 0f;
        float pitch = p.getCamera() != null ? p.getCamera().getPitch() : 0f;

        if (ctx.mode() == SyncMode.HOST) {
            ctx.broadcast(new Packet.PlayerStateS2C(0, pos.x, pos.y, pos.z, yaw, pitch));
        } else if (ctx.mode() == SyncMode.CLIENT) {
            ctx.broadcast(new Packet.PlayerStateC2S(pos.x, pos.y, pos.z, yaw, pitch));
        }
    }

    // ─── Remote-player lifecycle ──────────────────────────────────────────

    private void spawnRemote(int playerId, String username, float x, float y, float z) {
        if (Game.getWorld() == null || Game.getEntityManager() == null) return;
        if (remotePlayers.containsKey(playerId)) return;
        RemotePlayer rp = new RemotePlayer(Game.getWorld(), new Vector3f(x, y, z), playerId, username);
        // Network shadow + interpolator so the cylinder lerps smoothly between
        // 20 Hz player-state snapshots instead of teleporting.
        rp.setNetworkShadow(true);
        com.stonebreak.network.sync.NetworkInterpolator interp =
                new com.stonebreak.network.sync.NetworkInterpolator();
        interp.seed(x, y, z, 0f, 0f);
        rp.setInterpolator(interp);
        Game.getEntityManager().addEntity(rp);
        remotePlayers.put(playerId, rp);
    }

    private void despawnRemote(int playerId) {
        RemotePlayer rp = remotePlayers.remove(playerId);
        if (rp != null && Game.getEntityManager() != null) {
            Game.getEntityManager().removeEntity(rp);
        }
    }

    private void applyRemoteState(int playerId, float x, float y, float z, float yaw, float pitch) {
        RemotePlayer rp = remotePlayers.get(playerId);
        if (rp == null) {
            // Late state for an unknown player — spawn lazily.
            spawnRemote(playerId, "Player" + playerId, x, y, z);
            rp = remotePlayers.get(playerId);
        }
        if (rp != null) rp.applyNetworkState(x, y, z, yaw, pitch);
    }
}
