package com.stonebreak.network.client.handlers;

import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
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
    /** Held-item snapshots for players whose figure doesn't exist yet (join buffered, or dead). */
    private final Map<Integer, Integer> pendingHeldItems = new ConcurrentHashMap<>();
    /**
     * Players the server holds dead (#343, {@code PlayerHealthS2C}): their figure is gone until
     * they respawn, and neither their state stream nor a roster join may bring it back early.
     */
    private final java.util.Set<Integer> deadPlayers = ConcurrentHashMap.newKeySet();
    /** Names of the players in the roster, so a figure re-created after a death keeps its name. */
    private final Map<Integer, String> usernames = new ConcurrentHashMap<>();
    /**
     * Our own latest health while the client world is still being built: the join's snapshot
     * arrives then, when {@code Game.getPlayer()} is still the previous session's player.
     */
    private volatile com.stonebreak.network.packet.player.PlayerHealthS2C pendingOwnHealth;

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
        usernames.put(j.playerId(), j.username());
        if (deadPlayers.contains(j.playerId())) {
            return; // a dead player's figure stays hidden until the respawn
        }
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
        deadPlayers.remove(l.playerId());
        usernames.remove(l.playerId());
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

    /** Replay joins (and our own health) buffered while the client world was being built/swapped. */
    public void tick() {
        drainPendingJoins();
        com.stonebreak.network.packet.player.PlayerHealthS2C own = pendingOwnHealth;
        if (own != null && Game.isClientWorldReady()) {
            pendingOwnHealth = null;
            applyOwnHealth(own);
        }
    }

    private void drainPendingJoins() {
        if (pendingJoins.isEmpty() || !com.stonebreak.core.Game.isClientWorldReady()) {
            return;
        }
        PlayerJoinS2C j;
        while ((j = pendingJoins.poll()) != null) {
            if (!departed.contains(j.playerId()) && !deadPlayers.contains(j.playerId())) {
                spawnRemote(j.playerId(), j.username(), j.x(), j.y(), j.z());
            }
        }
    }

    /**
     * The server refused a reported move and holds the player here (issue #336): go back, so
     * the next reports start from where the server agrees the player is.
     */
    public void handlePosition(com.stonebreak.network.packet.player.PlayerPositionS2C p) {
        Player local = Game.getPlayer();
        if (local != null) {
            local.teleport(p.x(), p.y(), p.z());
        }
    }

    /**
     * Server-owned health (#343). Our own goes to the local player — a death the server decided,
     * a respawn it made, the hearts in between. Another player's death hides their figure until
     * they respawn (their state stream brings it back, at the spot the server put them).
     */
    public void handleHealth(int localPlayerId, com.stonebreak.network.packet.player.PlayerHealthS2C h) {
        if (h.playerId() == localPlayerId) {
            if (Game.isClientWorldReady()) {
                pendingOwnHealth = null;
                applyOwnHealth(h);
            } else {
                pendingOwnHealth = h; // only the latest matters
            }
            return;
        }
        if (!h.dead()) {
            deadPlayers.remove(h.playerId());
            return;
        }
        if (deadPlayers.add(h.playerId())) {
            pendingJoins.removeIf(j -> j.playerId() == h.playerId());
            RemotePlayer rp = remotePlayers.get(h.playerId());
            if (rp != null) {
                pendingHeldItems.put(h.playerId(), rp.getHeldItemId()); // re-applied on respawn
            }
            despawnRemote(h.playerId());
        }
    }

    private static void applyOwnHealth(com.stonebreak.network.packet.player.PlayerHealthS2C h) {
        Player local = Game.getPlayer();
        if (local != null) {
            local.applyServerHealth(h.health(), h.maxHealth(), h.dead(),
                    com.stonebreak.network.packet.player.PlayerHealthS2C.isCombat(h.cause()));
        }
    }

    /** A blow the server landed on us pushes us (#343). */
    public void handleKnockback(com.stonebreak.network.packet.player.PlayerKnockbackS2C k) {
        Player local = Game.getPlayer();
        if (local != null) {
            local.applyKnockback(k.vx(), k.vy(), k.vz());
        }
    }

    public void handleGiveItem(GiveItemS2C g) {
        Player local = Game.getPlayer();
        if (local == null || local.getInventory() == null) {
            return;
        }
        // The server already capped the count to what fit its replica and re-dropped any
        // overflow itself (issue #334) — adding here can never overflow, and re-dropping the
        // difference would re-enter the debit path with a duplicated spawn. The stack keeps its
        // state (#342) and merges only with stacks of the same state (#350).
        ItemStack given = g.stack().toItemStack();
        if (given.isEmpty()) {
            return;
        }
        local.getInventory().addItemAndReturnCount(given);
        SoundSystem ss = Game.getInstance().getSoundSystem();
        if (ss != null) {
            ss.playSound("blockpickup");
        }
    }

    /**
     * Server-attributed hit/kill credit: apply the stat counters to the LOCAL player.
     * This replaces the old server-side local-credit path (which could only ever credit
     * the host); every client — host included — is credited through this uniform packet.
     * XP is NOT applied here: level/xp are server-owned (issue #334) and arrive via the
     * {@code ProgressS2C} echo.
     *
     * <p>Also the hit's feedback and on-hit effects ({@code HitResultFeedback}, #371/#374).
     *
     * @param target the hit entity's shadow on this client (by the credit's network id), or null
     */
    public void handleKillCredit(com.stonebreak.network.packet.player.KillCreditS2C k,
                                 com.stonebreak.mobs.entities.Entity target) {
        Player local = Game.getPlayer();
        if (local == null) {
            return;
        }
        com.stonebreak.mobs.entities.LivingEntity.DamageSource[] sources =
                com.stonebreak.mobs.entities.LivingEntity.DamageSource.values();
        com.stonebreak.mobs.entities.LivingEntity.DamageSource source =
                k.sourceOrdinal() >= 0 && k.sourceOrdinal() < sources.length
                        ? sources[k.sourceOrdinal()]
                        : com.stonebreak.mobs.entities.LivingEntity.DamageSource.UNKNOWN;
        com.stonebreak.player.combat.HitResultFeedback.apply(local,
                target instanceof com.stonebreak.mobs.entities.LivingEntity le ? le : null,
                source, com.stonebreak.mobs.entities.HitKind.fromWire(k.hitKind()),
                k.damageDealt(), new org.joml.Vector3f(k.x(), k.y(), k.z()));
        local.getStats().addDamageDealt(k.damageDealt());
        if (k.killed()) {
            local.getStats().incrementEntitiesKilled();
            com.stonebreak.mobs.entities.EntityType[] types = com.stonebreak.mobs.entities.EntityType.values();
            if (k.entityTypeOrdinal() >= 0 && k.entityTypeOrdinal() < types.length) {
                local.getStats().incrementKillsForType(types[k.entityTypeOrdinal()]);
            }
        }
    }

    /** Server-authoritative progression echo (issue #334): level/xp/point pools only. */
    public void handleProgress(com.stonebreak.network.packet.player.ProgressS2C p) {
        Player local = Game.getPlayer();
        if (local == null) {
            return;
        }
        local.getCharacterStats().applyServerProgression(
            p.level(), p.xp(), p.remainingCp(), p.remainingSp(), p.remainingFp(), p.remainingAp());
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
        deadPlayers.clear();
        usernames.clear();
        pendingOwnHealth = null;
    }

    // ─── Remote-player lifecycle ────────────────────────────────────────────────

    private void spawnRemote(int playerId, String username, float x, float y, float z) {
        // Never spawn against a not-yet-swapped world (see pendingJoins): the figure would
        // land in the previous session's entity manager and never render.
        if (!Game.isClientWorldReady()) {
            return;
        }
        RemotePlayer existing = remotePlayers.get(playerId);
        if (existing != null) {
            // Duplicate join = authoritative position snapshot (first-state re-announce or
            // manual-resync roster refresh): correct a badly-parked figure — e.g. one
            // rostered at spawn before its player reported state, whose droppable state
            // stream was then discarded under our own join chunk burst. Small offsets are
            // left to the 20 Hz stream; repositioning mid-walk would visibly hitch.
            Vector3f cur = existing.getPosition();
            float dx = cur.x - x, dy = cur.y - y, dz = cur.z - z;
            if (dx * dx + dy * dy + dz * dz > 4.0f) {
                NetworkInterpolator interp = existing.getInterpolator();
                float yaw = interp != null ? interp.currentTargetYaw() : existing.getRotation().y;
                float pitch = interp != null ? interp.currentTargetPitch() : 0f;
                existing.applyNetworkState(x, y, z, yaw, pitch);
            }
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

    private void applyRemoteState(int playerId, float x, float y, float z, float yaw, float pitch, short flags) {
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
            if (departed.contains(playerId) || deadPlayers.contains(playerId)) {
                return;
            }
            spawnRemote(playerId, usernames.getOrDefault(playerId, "Player" + playerId), x, y, z);
            rp = remotePlayers.get(playerId);
        }
        if (rp != null) {
            rp.applyNetworkState(x, y, z, yaw, pitch);
            rp.setStateFlags(flags);
        }
    }
}
