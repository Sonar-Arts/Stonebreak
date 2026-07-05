package com.stonebreak.network.client.handlers;

import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.network.client.NetworkInterpolator;
import com.stonebreak.network.packet.player.GiveItemS2C;
import com.stonebreak.network.packet.player.PlayerHeldItemS2C;
import com.stonebreak.network.packet.player.PlayerJoinS2C;
import com.stonebreak.network.packet.player.PlayerLeaveS2C;
import com.stonebreak.network.packet.player.PlayerStateS2C;
import com.stonebreak.player.Player;
import org.joml.Vector3f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side: spawns/despawns {@link RemotePlayer} cylinders for other peers, applies
 * their state through an interpolator, relays their held items, and hands GiveItem stacks
 * to the local inventory. Successor of the old {@code PlayerStateSynchronizer} CLIENT path.
 */
public final class ClientPlayerHandler {

    private final Map<Integer, RemotePlayer> remotePlayers = new ConcurrentHashMap<>();

    /**
     * Roster joins that arrived before the client world was ready. The server sends the
     * roster bootstrap right after WelcomeS2C, while the client is still building/swapping
     * its render world on the "ClientWorld-Build" thread — spawning then would add the
     * figure to the PREVIOUS session's entity manager (permanently invisible on a rejoin).
     * Replayed by {@link #tick()} once {@code Game.isClientWorldReady()}.
     */
    private final java.util.Deque<PlayerJoinS2C> pendingJoins = new java.util.ArrayDeque<>();
    /** Held-item snapshots for players whose figure doesn't exist yet (join buffered). */
    private final Map<Integer, Integer> pendingHeldItems = new ConcurrentHashMap<>();

    public void handlePlayerState(int localPlayerId, PlayerStateS2C ps) {
        if (ps.playerId() == localPlayerId) {
            return;
        }
        applyRemoteState(ps.playerId(), ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch(), ps.flags());
    }

    /** Players that LEFT (PlayerLeaveS2C) and haven't rejoined: late in-flight state packets
     *  for these ids must not resurrect a ghost figure (they'd linger forever). */
    private final java.util.Set<Integer> departed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void handleJoin(int localPlayerId, PlayerJoinS2C j) {
        if (j.playerId() == localPlayerId) {
            return;
        }
        departed.remove(j.playerId());
        if (!com.stonebreak.core.Game.isClientWorldReady()) {
            pendingJoins.add(j);
            return;
        }
        spawnRemote(j.playerId(), j.username(), j.x(), j.y(), j.z());
    }

    public void handleLeave(PlayerLeaveS2C l) {
        departed.add(l.playerId());
        pendingJoins.removeIf(j -> j.playerId() == l.playerId());
        pendingHeldItems.remove(l.playerId());
        despawnRemote(l.playerId());
    }

    public void handleHeldItem(int localPlayerId, PlayerHeldItemS2C h) {
        if (h.playerId() == localPlayerId) {
            return;
        }
        RemotePlayer rp = remotePlayers.get(h.playerId());
        if (rp != null) {
            rp.setHeldItemId(h.itemId());
        } else if (!departed.contains(h.playerId())) {
            // Figure not spawned yet (join buffered during a world rebuild) — the held-item
            // snapshot is sent once at join, so keep it for when the figure appears.
            pendingHeldItems.put(h.playerId(), h.itemId());
        }
    }

    /** Replay joins buffered while the client world was being built/swapped. */
    public void tick() {
        drainPendingJoins();
    }

    private void drainPendingJoins() {
        if (pendingJoins.isEmpty() || !com.stonebreak.core.Game.isClientWorldReady()) {
            return;
        }
        PlayerJoinS2C j;
        while ((j = pendingJoins.poll()) != null) {
            if (!departed.contains(j.playerId())) {
                spawnRemote(j.playerId(), j.username(), j.x(), j.y(), j.z());
            }
        }
    }

    public void handleGiveItem(GiveItemS2C g) {
        Player local = Game.getPlayer();
        if (local == null || local.getInventory() == null) {
            return;
        }
        // Capacity-aware add: anything that doesn't fit is returned to the server as a
        // re-drop at our position instead of silently vanishing into a full inventory.
        int added = local.getInventory().addItemsAndReturnCount(g.itemId(), g.count());
        int leftover = g.count() - added;
        if (leftover > 0) {
            com.stonebreak.network.MultiplayerSession.sendDropItem(g.itemId(), leftover);
        }
        if (added > 0) {
            SoundSystem ss = Game.getInstance().getSoundSystem();
            if (ss != null) {
                ss.playSound("blockpickup");
            }
        }
    }

    /**
     * Server-attributed hit/kill credit: apply the stat/XP grant to the LOCAL player. This
     * replaces the old server-side local-credit path (which could only ever credit the
     * host); every client — host included — is credited through this uniform packet.
     */
    public void handleKillCredit(com.stonebreak.network.packet.player.KillCreditS2C k) {
        Player local = Game.getPlayer();
        if (local == null) {
            return;
        }
        local.getStats().addDamageDealt(k.damageDealt());
        if (k.killed()) {
            local.getStats().incrementEntitiesKilled();
            com.stonebreak.mobs.entities.EntityType[] types = com.stonebreak.mobs.entities.EntityType.values();
            if (k.entityTypeOrdinal() >= 0 && k.entityTypeOrdinal() < types.length) {
                local.getStats().incrementKillsForType(types[k.entityTypeOrdinal()]);
            }
            if (k.xpReward() > 0) {
                local.getCharacterStats().addXp(k.xpReward());
            }
        }
    }

    public void onSessionEnd() {
        EntityManager em = Game.getEntityManager();
        if (em != null) {
            for (RemotePlayer rp : remotePlayers.values()) {
                em.removeEntity(rp);
            }
        }
        remotePlayers.clear();
        departed.clear();
        pendingJoins.clear();
        pendingHeldItems.clear();
    }

    // ─── Remote-player lifecycle ────────────────────────────────────────────────

    private void spawnRemote(int playerId, String username, float x, float y, float z) {
        // Never spawn against a not-yet-swapped world (see pendingJoins): the figure would
        // land in the previous session's entity manager and never render.
        if (!Game.isClientWorldReady()) {
            return;
        }
        if (remotePlayers.containsKey(playerId)) {
            return;
        }
        RemotePlayer rp = new RemotePlayer(Game.getWorld(), new Vector3f(x, y, z), playerId, username);
        rp.setNetworkShadow(true);
        NetworkInterpolator interp = new NetworkInterpolator();
        interp.seed(x, y, z, 0f, 0f);
        rp.setInterpolator(interp);
        Game.getEntityManager().addEntity(rp);
        remotePlayers.put(playerId, rp);
        Integer heldItem = pendingHeldItems.remove(playerId);
        if (heldItem != null) {
            rp.setHeldItemId(heldItem);
        }
    }

    private void despawnRemote(int playerId) {
        RemotePlayer rp = remotePlayers.remove(playerId);
        if (rp != null && Game.getEntityManager() != null) {
            Game.getEntityManager().removeEntity(rp);
        }
    }

    private void applyRemoteState(int playerId, float x, float y, float z, float yaw, float pitch, byte flags) {
        // Droppable 20 Hz stream: while the world is rebuilding just drop the packet (the
        // next one is 50 ms away). Replay buffered joins first so the on-demand fallback
        // below never beats a real join to the spawn (it would lose the username).
        if (!Game.isClientWorldReady()) {
            return;
        }
        drainPendingJoins();
        RemotePlayer rp = remotePlayers.get(playerId);
        // Defensive: if the entity died via another path (chunk unload, world reload), drop
        // the stale reference and respawn from the latest state.
        if (rp != null && !rp.isAlive()) {
            remotePlayers.remove(playerId);
            rp = null;
        }
        if (rp == null) {
            // A state packet racing behind a PlayerLeaveS2C must NOT resurrect the figure —
            // that ghost would linger forever (its player is gone; no leave will follow).
            // A rejoin lifts the block via handleJoin before any of its state arrives
            // (PlayerJoinS2C is non-droppable and sent first).
            if (departed.contains(playerId)) {
                return;
            }
            spawnRemote(playerId, "Player" + playerId, x, y, z);
            rp = remotePlayers.get(playerId);
        }
        if (rp != null) {
            rp.applyNetworkState(x, y, z, yaw, pitch);
            rp.setStateFlags(flags);
        }
    }
}
