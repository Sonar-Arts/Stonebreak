package com.stonebreak.network.server.handlers;

import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.FishingBobber;
import com.stonebreak.mobs.entities.FishingManager;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.network.packet.player.FishingRodC2S;
import com.stonebreak.network.packet.player.StatCreditS2C;
import com.stonebreak.network.server.ServerInventory;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Server-authoritative fishing (issue #341). The bobber is a SERVER entity, replicated to
 * every client like a projectile; this handler owns which player's bobber is out. A rod use
 * ({@link FishingRodC2S}) casts or reels in on the server: the rod's state changes on the
 * server's inventory replica (so the sync confirms the cast instead of reverting it), and a
 * catch is rolled here and spawned in the server world — its pickup then goes through the
 * normal server pickup path. The client never spawns a bobber or a catch.
 *
 * <p>Server thread only (dispatch + tick), so the bookkeeping is a plain map.
 */
public final class ServerFishingHandler {

    /** Cast origin must be near the player (the client sends its camera position). */
    private static final float MAX_CAST_ORIGIN_DIST_SQ = 8f * 8f;

    /**
     * Shortest accepted interval between two rod uses. Every cast spawns, and every reel
     * despawns, a replicated bobber, so a client alternating at packet rate would churn
     * entities and broadcasts to every viewer.
     */
    static final long ROD_MIN_INTERVAL_NANOS = 250_000_000L;

    private record Cast(ServerPlayer owner, FishingBobber bobber) {}

    /** playerId → the bobber that player has out. */
    private final Map<Integer, Cast> casts = new HashMap<>();
    /** playerId → when that player's last accepted rod use arrived. */
    private final Map<Integer, Long> lastUseNanos = new HashMap<>();
    private final FishingManager catches;
    private final LongSupplier clock;

    public ServerFishingHandler() {
        this(new FishingManager(), System::nanoTime);
    }

    /** With a given catch resolver (seeded loot rolls in tests) and monotonic nano clock. */
    public ServerFishingHandler(FishingManager catches, LongSupplier clock) {
        this.catches = catches;
        this.clock = clock;
    }

    /**
     * The player used the rod in the selected slot: reel in when their bobber is out (rolling
     * a catch when a fish is biting), else cast a new one. Answered with a sync whatever the
     * outcome — the client predicted the rod state flip, and a refusal must undo it.
     */
    public void handleRodUse(ServerPlayer sp, FishingRodC2S use, ServerWorldContext ctx) {
        ItemStack rod = heldRod(sp.inventory());
        EntityManager em = ctx.entityManager();
        if (rod != null && em != null && ctx.world() != null
                && sp.lastStateNs() != 0L && !sp.isDead() && acceptUse(sp)) {
            FishingBobber out = activeBobber(sp);
            if (out != null) {
                reelIn(sp, out, em);
                rod.setState(ItemType.FISHING_ROD_STATE_REELED_IN);
            } else if (cast(sp, use, em)) {
                rod.setState(ItemType.FISHING_ROD_STATE_CAST);
            }
        }
        sp.send(sp.inventorySync(), false);
    }

    /**
     * Retires bobbers whose owner left (or was replaced by a reconnect) and forgets bobbers
     * that are gone from the world, reeling the owner's rods back in so the client's rod
     * never stays "cast" with nothing on the line.
     */
    public void tick(ServerWorldContext ctx) {
        Iterator<Map.Entry<Integer, Cast>> it = casts.entrySet().iterator();
        while (it.hasNext()) {
            Cast c = it.next().getValue();
            boolean ownerHere = ctx.player(c.owner().playerId()) == c.owner();
            if (ownerHere && c.bobber().isAlive()) {
                continue;
            }
            c.bobber().setAlive(false);
            it.remove();
            if (ownerHere && reelInRods(c.owner().inventory())) {
                c.owner().queueInventorySync();
            }
        }
    }

    /** Session over: forget every cast (the world and its entities go with it). */
    public void onSessionEnd() {
        casts.clear();
        lastUseNanos.clear();
    }

    /** Spends this player's rod use, or false when it came too soon after the last one. */
    private boolean acceptUse(ServerPlayer sp) {
        long now = clock.getAsLong();
        Long last = lastUseNanos.get(sp.playerId());
        if (last != null && now - last < ROD_MIN_INTERVAL_NANOS) {
            return false;
        }
        lastUseNanos.put(sp.playerId(), now);
        return true;
    }

    /** The bobber {@code sp} has out, or null. Package-private for tests. */
    FishingBobber activeBobber(ServerPlayer sp) {
        Cast c = casts.get(sp.playerId());
        if (c == null) {
            return null;
        }
        if (c.owner() != sp || !c.bobber().isAlive()) {
            c.bobber().setAlive(false);
            casts.remove(sp.playerId());
            return null;
        }
        return c.bobber();
    }

    private boolean cast(ServerPlayer sp, FishingRodC2S use, EntityManager em) {
        if (!Float.isFinite(use.x()) || !Float.isFinite(use.y()) || !Float.isFinite(use.z())
                || !Float.isFinite(use.dx()) || !Float.isFinite(use.dy()) || !Float.isFinite(use.dz())) {
            return false;
        }
        Vector3f dir = new Vector3f(use.dx(), use.dy(), use.dz());
        if (dir.lengthSquared() < 1e-6f) {
            return false;
        }
        float ox = use.x() - sp.x();
        float oy = use.y() - sp.y();
        float oz = use.z() - sp.z();
        if (ox * ox + oy * oy + oz * oz > MAX_CAST_ORIGIN_DIST_SQ) {
            return false;
        }
        FishingBobber bobber = em.spawnBobber(new Vector3f(use.x(), use.y(), use.z()), dir.normalize());
        bobber.setOwnerPlayerId(sp.playerId());
        casts.put(sp.playerId(), new Cast(sp, bobber));
        return true;
    }

    private void reelIn(ServerPlayer sp, FishingBobber bobber, EntityManager em) {
        // The catch arcs toward the angler's reported position (the old client aim).
        ItemDrop caught = catches.tryCatch(bobber, new Vector3f(sp.x(), sp.y(), sp.z()), em);
        if (caught != null && caught.getItemType() == ItemType.BASS) {
            sp.send(new StatCreditS2C(StatCreditS2C.STAT_BASS_CAUGHT, 1), false);
        }
        bobber.setAlive(false);
        casts.remove(sp.playerId());
    }

    /** The fishing rod in the selected hotbar slot, or null. */
    private static ItemStack heldRod(ServerInventory inv) {
        ItemStack held = inv.slot(inv.selectedHotbarSlot());
        return !held.isEmpty() && held.getItem() == ItemType.FISHING_ROD ? held : null;
    }

    /** Puts every cast rod in {@code inv} back to reeled in; true when any changed. */
    private static boolean reelInRods(ServerInventory inv) {
        boolean changed = false;
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            ItemStack s = inv.slot(i);
            if (!s.isEmpty() && s.getItem() == ItemType.FISHING_ROD
                    && ItemType.FISHING_ROD_STATE_CAST.equals(s.getState())) {
                s.setState(ItemType.FISHING_ROD_STATE_REELED_IN);
                changed = true;
            }
        }
        return changed;
    }
}
