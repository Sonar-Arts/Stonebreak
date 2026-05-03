package com.stonebreak.network.sync.synchronizers;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.protocol.Packet;
import com.stonebreak.network.server.IntegratedServer;
import com.stonebreak.network.server.RemoteClient;
import com.stonebreak.network.sync.SyncContext;
import com.stonebreak.network.sync.SyncEvent;
import com.stonebreak.network.sync.SyncMode;
import com.stonebreak.network.sync.Synchronizer;
import com.stonebreak.player.Player;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns:
 *  - Periodic broadcast of the local player's position/yaw/pitch (~20 Hz).
 *  - Spawning/despawning {@link RemotePlayer} cylinders for every other peer.
 *  - Applying inbound PlayerState updates onto the right RemotePlayer.
 */
public final class PlayerStateSynchronizer implements Synchronizer {

    /**
     * Concurrent because {@link #onSessionEnd()} can be invoked from the
     * shutdown thread while the main thread is mid-tick (synchronization on
     * MultiplayerSession bounds the worst case but the cost of CHM is trivial).
     */
    private final Map<Integer, RemotePlayer> remotePlayers = new ConcurrentHashMap<>();

    /** Last held item id we broadcast for the local player; -1 means "never sent". */
    private int lastBroadcastHeldItemId = -1;

    @Override
    public void onSessionStart(SyncContext ctx) {
        remotePlayers.clear();
        lastBroadcastHeldItemId = -1;
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
                || packet instanceof Packet.PlayerLeaveS2C
                || packet instanceof Packet.PlayerHeldItemC2S
                || packet instanceof Packet.PlayerHeldItemS2C
                || packet instanceof Packet.GiveItemS2C;
    }

    @Override
    public void applyInbound(Packet packet, Integer originId, SyncContext ctx) {
        switch (packet) {
            case Packet.PlayerStateC2S ps -> {
                if (originId == null) return;
                if (!validateAndCacheClientState(originId, ps)) return;
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
            case Packet.PlayerHeldItemC2S h -> {
                if (originId == null) return;
                IntegratedServer srv = MultiplayerSession.getServer();
                if (srv == null) return;
                RemoteClient rc = srv.getClient(originId);
                if (rc != null) rc.setHeldItemId(h.itemId());
                RemotePlayer rp = remotePlayers.get(originId);
                if (rp != null) rp.setHeldItemId(h.itemId());
                ctx.broadcast(new Packet.PlayerHeldItemS2C(originId, h.itemId()));
            }
            case Packet.PlayerHeldItemS2C h -> {
                if (h.playerId() == ctx.localPlayerId()) return;
                RemotePlayer rp = remotePlayers.get(h.playerId());
                if (rp != null) rp.setHeldItemId(h.itemId());
            }
            case Packet.GiveItemS2C g -> {
                Player local = Game.getPlayer();
                if (local == null || local.getInventory() == null) return;
                local.getInventory().addItem(new com.stonebreak.items.ItemStack(g.itemId(), g.count()));
                com.stonebreak.audio.SoundSystem ss = Game.getInstance().getSoundSystem();
                if (ss != null) ss.playSound("blockpickup");
            }
            default -> {}
        }
    }

    @Override
    public boolean handlesLocal(SyncEvent event) {
        return event instanceof SyncEvent.PeerJoined;
    }

    @Override
    public void emitLocal(SyncEvent event, SyncContext ctx) {
        if (ctx.mode() != SyncMode.HOST) return;
        if (!(event instanceof SyncEvent.PeerJoined p)) return;
        // Bring the new client up to date on every existing player's held item.
        IntegratedServer srv = MultiplayerSession.getServer();
        if (srv == null) return;
        // Host's own held item.
        Player local = Game.getPlayer();
        if (local != null && local.getInventory() != null) {
            int hostHeld = local.getInventory().getSelectedBlockTypeId();
            ctx.sendTo(p.playerId(), new Packet.PlayerHeldItemS2C(0, hostHeld));
        }
        // Each other connected client's last reported held item.
        for (RemoteClient rc : srv.getClients().values()) {
            if (rc.getPlayerId() == p.playerId()) continue;
            ctx.sendTo(p.playerId(), new Packet.PlayerHeldItemS2C(rc.getPlayerId(), rc.getHeldItemId()));
        }
    }

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

        // Held item changes are rare (slot scroll); poll the local inventory and
        // emit a packet only when it actually changed.
        if (p.getInventory() != null) {
            int currentHeld = p.getInventory().getSelectedBlockTypeId();
            if (currentHeld != lastBroadcastHeldItemId) {
                lastBroadcastHeldItemId = currentHeld;
                if (ctx.mode() == SyncMode.HOST) {
                    ctx.broadcast(new Packet.PlayerHeldItemS2C(0, currentHeld));
                } else {
                    ctx.broadcast(new Packet.PlayerHeldItemC2S(currentHeld));
                }
            }
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

    /**
     * Sanity-check inbound client state and update the cached server-side
     * position. Rejects only NaN/Inf and obviously out-of-world coordinates;
     * the cache is always updated so reach checks for block edits and the
     * RemotePlayer view track the client's true position.
     *
     * <p>Per-packet velocity / "no-teleport" anti-cheat is intentionally NOT
     * applied here — naive caps cause permanent desync when a client
     * legitimately teleports (respawn, world-load completion, /tp). A future
     * sliding-window velocity check can be layered on top to flag suspicious
     * movement for kicking, but it must not feed back into the cached state
     * or every legitimate teleport will freeze the player on the host.
     */
    private boolean validateAndCacheClientState(int originId, Packet.PlayerStateC2S ps) {
        if (!Float.isFinite(ps.x()) || !Float.isFinite(ps.y()) || !Float.isFinite(ps.z())
                || !Float.isFinite(ps.yaw()) || !Float.isFinite(ps.pitch())) {
            return false;
        }
        if (ps.y() < -64f || ps.y() > WorldConfiguration.WORLD_HEIGHT + 64f) {
            return false;
        }
        IntegratedServer srv = MultiplayerSession.getServer();
        if (srv == null) return false;
        RemoteClient rc = srv.getClient(originId);
        if (rc == null) return false;
        rc.updateState(ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch());
        return true;
    }

    private void applyRemoteState(int playerId, float x, float y, float z, float yaw, float pitch) {
        RemotePlayer rp = remotePlayers.get(playerId);
        // Defensive: if the entity was removed by some other path (chunk
        // unload, world reload), our cached reference is dead — drop it and
        // respawn from the latest network state.
        if (rp != null && !rp.isAlive()) {
            remotePlayers.remove(playerId);
            rp = null;
        }
        if (rp == null) {
            spawnRemote(playerId, "Player" + playerId, x, y, z);
            rp = remotePlayers.get(playerId);
        }
        if (rp != null) rp.applyNetworkState(x, y, z, yaw, pitch);
    }
}
