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

    public void handlePlayerState(int localPlayerId, PlayerStateS2C ps) {
        if (ps.playerId() == localPlayerId) {
            return;
        }
        applyRemoteState(ps.playerId(), ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch(), ps.flags());
    }

    public void handleJoin(int localPlayerId, PlayerJoinS2C j) {
        if (j.playerId() == localPlayerId) {
            return;
        }
        spawnRemote(j.playerId(), j.username(), j.x(), j.y(), j.z());
    }

    public void handleLeave(PlayerLeaveS2C l) {
        despawnRemote(l.playerId());
    }

    public void handleHeldItem(int localPlayerId, PlayerHeldItemS2C h) {
        if (h.playerId() == localPlayerId) {
            return;
        }
        RemotePlayer rp = remotePlayers.get(h.playerId());
        if (rp != null) {
            rp.setHeldItemId(h.itemId());
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
    }

    // ─── Remote-player lifecycle ────────────────────────────────────────────────

    private void spawnRemote(int playerId, String username, float x, float y, float z) {
        if (Game.getWorld() == null || Game.getEntityManager() == null) {
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
    }

    private void despawnRemote(int playerId) {
        RemotePlayer rp = remotePlayers.remove(playerId);
        if (rp != null && Game.getEntityManager() != null) {
            Game.getEntityManager().removeEntity(rp);
        }
    }

    private void applyRemoteState(int playerId, float x, float y, float z, float yaw, float pitch, byte flags) {
        RemotePlayer rp = remotePlayers.get(playerId);
        // Defensive: if the entity died via another path (chunk unload, world reload), drop
        // the stale reference and respawn from the latest state.
        if (rp != null && !rp.isAlive()) {
            remotePlayers.remove(playerId);
            rp = null;
        }
        if (rp == null) {
            spawnRemote(playerId, "Player" + playerId, x, y, z);
            rp = remotePlayers.get(playerId);
        }
        if (rp != null) {
            rp.applyNetworkState(x, y, z, yaw, pitch);
            rp.setStateFlags(flags);
        }
    }
}
