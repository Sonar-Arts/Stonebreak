package com.stonebreak.network.server.handlers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.items.ToolMiningRules;
import com.stonebreak.mobs.entities.ai.perception.Loudness;
import com.stonebreak.mobs.entities.ai.perception.NoiseBus;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.packet.world.BlockChangeS2C;
import com.stonebreak.network.packet.world.MultiBlockChangeS2C;
import com.stonebreak.network.server.ServerInventory;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authoritative block-edit handler — successor of the old {@code BlockSynchronizer} HOST
 * path. Validates inbound client edits (world bounds, known block id, reach distance from
 * the sender's cached position), applies accepted edits to the authoritative world via the
 * <b>non-broadcasting</b> {@code setBlockAt(..., false)} path, spawns drops for breaks, and
 * batches outgoing changes per tick into {@link BlockChangeS2C} / {@link MultiBlockChangeS2C}.
 *
 * <p>Drops spawned for a break enter the world's {@code EntityManager}; their network
 * broadcast is handled by {@link ServerEntityHandler} via the entity-add listener — there is
 * no separate drop broadcast here.
 */
public final class ServerBlockHandler {

    /** Feet to the block's centre; {@link ServerVehicleHandler} places boats within it too. */
    static final float MAX_REACH = 8.0f;
    private static final float MAX_REACH_SQ = MAX_REACH * MAX_REACH;

    private final ServerChunkHandler chunkHandler;

    /** Outbound edits buffered this tick, grouped by section key {@code (sx, sy, sz)}.
     *  Insertion-ordered so wire order matches edit order. */
    private final Map<Long, SectionBatch> pendingByTick = new LinkedHashMap<>();

    /** Ticks an accepted edit may wait for its server chunk to load before being reverted. */
    private static final int RETRY_TTL_TICKS = 20; // 1 s at 20 Hz

    /** Validated edits whose target chunk wasn't loaded server-side yet: the chunk load has
     *  been kicked off and the edit retries each tick until it applies or its TTL expires
     *  (then the originator gets a corrective revert). Without this, an edit racing the
     *  server's chunk load was silently dropped while the originator kept it locally —
     *  a permanent one-block divergence. */
    private final java.util.ArrayDeque<PendingEdit> retryQueue = new java.util.ArrayDeque<>();

    private static final class PendingEdit {
        final ServerPlayer sp;
        final BlockChangeC2S change;
        /** What the placement took when it was validated — refunded exactly on expiry. */
        final PlacementDebit debit;
        /** When the edit arrived — a break waiting for its chunk is timed from this (#333). */
        final long receivedNs;
        /** The break timing already passed (a deferred break that then met an unloaded chunk). */
        final boolean timed;
        int ticksLeft = RETRY_TTL_TICKS;
        PendingEdit(ServerPlayer sp, BlockChangeC2S change, PlacementDebit debit, long receivedNs,
                    boolean timed) {
            this.sp = sp;
            this.change = change;
            this.debit = debit;
            this.receivedNs = receivedNs;
            this.timed = timed;
        }
    }

    /**
     * Slack a break finish may arrive before the server's own clock says the block broke
     * (issue #333): frame quantisation and network jitter between the start and the finish.
     * A finish earlier than this waits instead of being refused.
     */
    static final long BREAK_TOLERANCE_NS = 100_000_000L;
    /** Breaks one player may have waiting at once; more early finishes than this are refused. */
    static final int MAX_DEFERRED_BREAKS = 4;

    /** A break whose finish arrived before the block would really have broken: it applies at
     *  {@code dueNs}, exactly as if it had arrived then. */
    private record DeferredBreak(ServerPlayer sp, BlockChangeC2S change, long dueNs) {}

    /** Breaks waiting for their due time, in arrival order. */
    private final java.util.ArrayDeque<DeferredBreak> deferredBreaks = new java.util.ArrayDeque<>();

    /** How {@link #timeBreak} decided. */
    private enum BreakTiming { GO, WAIT, REFUSE }

    /** Snow-layer changes buffered this tick, per section — flushed as {@code BlockMetaS2C}. */
    private final Map<Long, SectionBatch> pendingSnowByTick = new LinkedHashMap<>();

    /** Water flow-level changes buffered this tick, per section — flushed as
     *  {@code BlockMetaS2C} (KIND_WATER_LEVEL) AFTER the block batches, so a cell's
     *  same-tick WATER block change lands before its level meta. */
    private final Map<Long, SectionBatch> pendingWaterByTick = new LinkedHashMap<>();

    /** Cells whose block state must be echoed behind this tick's block batches, so a state
     *  packet can never overtake — and be wiped by — its own block change (a client's
     *  {@code Chunk.setBlock} drops the cell's state on a type change). The state is read
     *  from the world at flush time: a later same-tick change wins, a broken block sends
     *  nothing. Insertion-ordered; a cell queued twice is echoed once. */
    private final java.util.Set<com.openmason.engine.util.BlockPos> pendingStateEchoes =
            new java.util.LinkedHashSet<>();

    public ServerBlockHandler(ServerChunkHandler chunkHandler) {
        this.chunkHandler = chunkHandler;
    }

    public void onSessionEnd() {
        pendingByTick.clear();
        pendingSnowByTick.clear();
        pendingWaterByTick.clear();
        pendingStateEchoes.clear();
        retryQueue.clear();
        deferredBreaks.clear();
    }

    // ─── Inbound (client edit) ───────────────────────────────────────────────────

    public void handleBlockChange(ServerPlayer sp, BlockChangeC2S c, ServerWorldContext ctx) {
        handleBlockChange(sp, c, ctx, System.nanoTime());
    }

    /** As {@link #handleBlockChange(ServerPlayer, BlockChangeC2S, ServerWorldContext)}, with
     *  the time the packet arrived ({@code Envelope.receivedNanos}) for break timing. */
    public void handleBlockChange(ServerPlayer sp, BlockChangeC2S c, ServerWorldContext ctx, long receivedNs) {
        World world = ctx.world();
        if (world == null) {
            return;
        }
        if (!validateClientEdit(sp, c)) {
            sendRevert(sp, c.x(), c.y(), c.z(), ctx);
            return;
        }
        BlockType incoming = BlockType.getById(c.blockTypeId() & 0xFFFF);
        // The server's OWN cell decides what was there (issues #333, #370): drops, container
        // cleanup and the scoop all read it. An unloaded chunk reads AIR here, which never
        // conflicts; such an edit waits in the retry queue and is checked again once loaded.
        BlockType serverPrev = world.getBlockAt(c.x(), c.y(), c.z());
        boolean loaded = chunkLoaded(world, c.x(), c.z());
        if (loaded && conflictsWithServerCell(c, incoming, serverPrev)) {
            // Someone else changed the cell first: nothing is taken, the placer sees the real
            // block, and the sync undoes whatever their mirror spent on the edit.
            sendRevert(sp, c.x(), c.y(), c.z(), ctx);
            syncInventory(sp);
            return;
        }
        if (loaded) {
            BreakTiming timing = timeBreak(sp, c, incoming, serverPrev, receivedNs, ctx);
            if (timing == BreakTiming.REFUSE) {
                sendRevert(sp, c.x(), c.y(), c.z(), ctx);
                return;
            }
            if (timing == BreakTiming.WAIT) {
                return; // applies when due (tick → drainDeferredBreaks)
            }
        }
        // Placement debits the server-owned inventory (issue #334): a non-air edit always
        // costs one of that block, exactly as the client's BlockPlacer predicts — the client
        // has no free-placement mode, so an exemption here would leave the replica holding
        // items the client already spent, and the next sync would hand them back. A refusal
        // reverts the optimistic client edit — no world mutation. The debit records exactly
        // what it took, so a later refusal or retry expiry refunds that and nothing else.
        PlacementDebit debit = PlacementDebit.NONE;
        if (incoming != null && incoming != BlockType.AIR) {
            debit = debitForPlacement(sp, incoming, ctx);
            if (debit == null) {
                syncInventory(sp);
                sendRevert(sp, c.x(), c.y(), c.z(), ctx);
                return;
            }
        }
        boolean scoop = isScoop(world, c, incoming, serverPrev); // pre-apply snapshot
        boolean applied = applyToWorld(world, c.x(), c.y(), c.z(), c.blockTypeId());
        if (!applied) {
            // Server chunk not loaded yet (the reach check bounds this to a near-player chunk,
            // so it's a load race, not garbage). Kick off the load and retry for a bounded
            // window; expiry reverts the originator instead of silently diverging.
            world.getChunkAt(Math.floorDiv(c.x(), 16), Math.floorDiv(c.z(), 16));
            retryQueue.add(new PendingEdit(sp, c, debit, receivedNs, loaded));
            return;
        }
        completeEdit(sp, c, incoming, serverPrev, scoop, debit, world, ctx);
    }

    /**
     * Whether a client edit was made against a cell the server no longer holds as the client
     * saw it (issue #370) — the second of two players editing one block inside a round trip.
     * <ul>
     *   <li>A placement needs a cell the client's own placer accepts: air or water
     *       ({@code BlockPlacer}). Anything else is another player's fresh block, which the
     *       placement would otherwise overwrite (destroying it and its container contents).</li>
     *   <li>A break needs the block the client says it broke. A cell that is already air is
     *       not a conflict — the break is a no-op that drops nothing (a double break, or leaves
     *       the server already decayed). A client prev of air is "unknown" and not checked.</li>
     * </ul>
     */
    static boolean conflictsWithServerCell(BlockChangeC2S c, BlockType incoming, BlockType serverPrev) {
        if (incoming != null && incoming != BlockType.AIR) {
            return serverPrev != BlockType.AIR && serverPrev != BlockType.WATER;
        }
        BlockType clientPrev = BlockType.getById(c.prevBlockTypeId() & 0xFFFF);
        if (clientPrev == null || clientPrev == BlockType.AIR || serverPrev == BlockType.AIR) {
            return false;
        }
        return clientPrev != serverPrev;
    }

    /**
     * C2S: mining started on a block (issue #333) — the first half of a break, timed against
     * the finish ({@link #timeBreak}). Out-of-reach starts, and starts from a player that has
     * not reported a position yet, are ignored: their finish then has no start and is refused.
     */
    public void handleBreakStart(ServerPlayer sp, com.stonebreak.network.packet.world.BlockBreakStartC2S s,
                                 long receivedNs) {
        if (s.y() < 0 || s.y() >= WorldConfiguration.WORLD_HEIGHT || !inReach(sp, s.x(), s.y(), s.z())) {
            return;
        }
        sp.startBreak(s.x(), s.y(), s.z(), receivedNs);
    }

    /**
     * Server-side break timing (issue #333): a break of a block that takes time to mine needs a
     * {@code BlockBreakStartC2S} for that cell, and the finish must arrive no earlier than the
     * block's break time ({@link ToolMiningRules#breakSeconds} with the held tool the server
     * knows) after it, less {@link #BREAK_TOLERANCE_NS}. An earlier finish is not refused — it
     * WAITs until the block would really have broken, as a vanilla server does — so jitter
     * never reverts an honest break, and a fast client gains nothing: breaks are sequential
     * ({@link ServerPlayer#startBreak}) and at most {@link #MAX_DEFERRED_BREAKS} may wait.
     *
     * <p>Not timed: placements, fluids (scoops, the air half of a pour), a cell that is already
     * air (a no-op break), blocks that break instantly, and creative players on a server with
     * cheats on ({@link #breakTimingExempt}). An unbreakable block is refused.
     */
    private BreakTiming timeBreak(ServerPlayer sp, BlockChangeC2S c, BlockType incoming, BlockType serverPrev,
                                  long receivedNs, ServerWorldContext ctx) {
        if (incoming != BlockType.AIR || serverPrev == BlockType.AIR || serverPrev == BlockType.WATER
                || breakTimingExempt(sp, ctx)) {
            return BreakTiming.GO;
        }
        if (!serverPrev.isBreakable()) {
            return BreakTiming.REFUSE;
        }
        ServerInventory inv = sp.inventory();
        float seconds = ToolMiningRules.breakSeconds(
                inv.slot(inv.selectedHotbarSlot()), serverPrev);
        if (Float.isNaN(seconds) || Float.isInfinite(seconds)) {
            return BreakTiming.REFUSE;
        }
        if (seconds <= 0f) {
            return BreakTiming.GO;
        }
        if (!sp.isBreaking(c.x(), c.y(), c.z())) {
            return BreakTiming.REFUSE; // no start for this cell: the client never mined it
        }
        long dueNs = sp.breakStartNs() + (long) (seconds * 1_000_000_000d) - BREAK_TOLERANCE_NS;
        if (receivedNs >= dueNs) {
            sp.finishBreak(receivedNs);
            return BreakTiming.GO;
        }
        if (deferredBreaksOf(sp) >= MAX_DEFERRED_BREAKS) {
            sp.finishBreak(dueNs);
            return BreakTiming.REFUSE;
        }
        deferredBreaks.add(new DeferredBreak(sp, c, dueNs));
        sp.finishBreak(dueNs);
        return BreakTiming.WAIT;
    }

    private int deferredBreaksOf(ServerPlayer sp) {
        int n = 0;
        for (DeferredBreak d : deferredBreaks) {
            if (d.sp() == sp) {
                n++;
            }
        }
        return n;
    }

    /**
     * Applies the breaks whose time has come, as if their finish arrived now: the cell is
     * checked again (someone may have changed it while it waited), and a chunk that unloaded
     * meanwhile sends the edit through the retry queue. A player who left drops theirs.
     */
    private void drainDeferredBreaks(ServerWorldContext ctx, long nowNs) {
        if (deferredBreaks.isEmpty()) {
            return;
        }
        World world = ctx.world();
        if (world == null) {
            deferredBreaks.clear();
            return;
        }
        java.util.Collection<ServerPlayer> online = ctx.players();
        int n = deferredBreaks.size();
        for (int i = 0; i < n; i++) {
            DeferredBreak d = deferredBreaks.poll();
            if (d == null) {
                break;
            }
            if (!online.contains(d.sp())) {
                continue;
            }
            if (d.dueNs() > nowNs) {
                deferredBreaks.add(d);
                continue;
            }
            BlockChangeC2S c = d.change();
            BlockType serverPrev = world.getBlockAt(c.x(), c.y(), c.z());
            if (!chunkLoaded(world, c.x(), c.z())) {
                world.getChunkAt(Math.floorDiv(c.x(), 16), Math.floorDiv(c.z(), 16));
                retryQueue.add(new PendingEdit(d.sp(), c, PlacementDebit.NONE, d.dueNs(), true));
            } else if (conflictsWithServerCell(c, BlockType.AIR, serverPrev)) {
                sendRevert(d.sp(), c.x(), c.y(), c.z(), ctx);
            } else if (applyToWorld(world, c.x(), c.y(), c.z(), c.blockTypeId())) {
                completeEdit(d.sp(), c, BlockType.AIR, serverPrev, false, PlacementDebit.NONE, world, ctx);
            }
        }
    }

    /**
     * Whether {@code (x, y, z)} is within reach of the player's last reported position. A player
     * that has not reported one yet reaches nothing (issue #333): its edits would otherwise be
     * accepted at any coordinate, and start chunk generation anywhere through the retry queue.
     */
    private static boolean inReach(ServerPlayer sp, int x, int y, int z) {
        if (sp.lastStateNs() == 0L) {
            return false;
        }
        float dx = (x + 0.5f) - sp.x();
        float dy = (y + 0.5f) - sp.y();
        float dz = (z + 0.5f) - sp.z();
        return dx * dx + dy * dy + dz * dz <= MAX_REACH_SQ;
    }

    /**
     * Takes {@code added} SNOW for a layer increase. A client adds one layer per intent and
     * spends it like a placement, so one layer goes through the placement debit and its
     * depletion auto-switch (issue #422); a larger jump debits plainly.
     */
    private static boolean debitSnow(ServerPlayer sp, int added) {
        return added == 1
                ? sp.inventory().debitOneWithAutoSwitch(BlockType.SNOW.getId()) != null
                : sp.inventory().debit(BlockType.SNOW.getId(), added);
    }

    /**
     * Breaks go untimed only when the SERVER's cheats flag is on AND this player's accepted game
     * mode is creative — the client's own cheats state is never trusted (issue #334). Placement
     * has no such exemption: the client's BlockPlacer always spends the block.
     */
    private static boolean breakTimingExempt(ServerPlayer sp, ServerWorldContext ctx) {
        com.stonebreak.network.server.ServerLevel level = ctx.serverLevel();
        return level != null && level.cheatsEnabled() && sp.gameMode() == 1;
    }

    /** Whether the server holds the chunk under {@code (x, z)} — an unloaded cell reads AIR. */
    private static boolean chunkLoaded(World world, int x, int z) {
        return world.getChunkIfLoaded(Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE),
                Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE)) != null;
    }

    /**
     * Everything that follows a successful world apply — shared by the direct path and the
     * chunk-load retry path, so a retried edit gets the same effects (issue #334). {@code prev}
     * is the server's own pre-apply cell.
     */
    private void completeEdit(ServerPlayer sp, BlockChangeC2S c, BlockType incoming, BlockType prev,
                              boolean scoop, PlacementDebit debit, World world, ServerWorldContext ctx) {
        applyAccepted(sp, c, incoming, prev, debit, world, ctx);
        emitEditNoise(sp, world, c, incoming, prev);
        int sel = sp.inventory().selectedHotbarSlot();
        if (scoop) {
            // Bucket scoop (mirrors BlockPlacer.handleEmptyBucket): removing a water SOURCE
            // with a non-water bucket selected fills exactly one of them server-side.
            if (isBucket(sp.inventory().slot(sel), false)
                    && !convertOneBucket(sp, sel, ItemType.BUCKET_STATE_WATER, ctx)) {
                syncInventory(sp); // nowhere to put the filled bucket — the client's copy is wrong
            }
        } else if (incoming == BlockType.AIR
                && (c.prevBlockTypeId() & 0xFFFF) == BlockType.WATER.getId()
                && isBucket(sp.inventory().slot(sel), false)) {
            // The client scooped water the server no longer holds as a source (it flowed
            // away inside the round trip): its mirror filled a bucket the server did not.
            syncInventory(sp);
        }
    }

    /**
     * Breaking a block is loud and placing one less so (#321); scooping or pouring water is quiet.
     * The editor's Stealth skill softens either.
     */
    private static void emitEditNoise(ServerPlayer sp, World world, BlockChangeC2S c, BlockType incoming,
                                      BlockType prev) {
        float loudness;
        if (incoming == null || incoming == BlockType.AIR) {
            loudness = prev == BlockType.AIR || prev == BlockType.WATER ? 0f : Loudness.BREAK_BLOCK;
        } else {
            loudness = incoming == BlockType.WATER ? 0f : Loudness.PLACE_BLOCK;
        }
        NoiseBus.emitIn(world, new Vector3f(c.x() + 0.5f, c.y() + 0.5f, c.z() + 0.5f), sp.quieted(loudness));
    }

    /**
     * Whether an edit is a bucket scoop: AIR over a water SOURCE (flowing water is never
     * scooped — BlockPlacer.handleEmptyBucket refuses it, and a bucket poured into flowing
     * water arrives as AIR then WATER, whose AIR half must not fill the pouring bucket).
     * Gated on the SERVER's pre-apply snapshot — clientPrev is trusted only for drop
     * selection, so a modified client cannot mint water by claiming prev=WATER at any
     * reach-valid cell; a legit scoop raced by server-side flow simply re-scoops (#334).
     */
    private static boolean isScoop(World world, BlockChangeC2S c, BlockType incoming, BlockType serverPrev) {
        if (incoming != BlockType.AIR || serverPrev != BlockType.WATER) {
            return false;
        }
        int value = world.getWaterValueAt(c.x(), c.y(), c.z());
        return value >= 0 && com.stonebreak.world.chunk.ChunkWaterLayer.level(value)
                == com.stonebreak.world.chunk.ChunkWaterLayer.SOURCE;
    }

    /**
     * Exactly what a placement debit took from the server inventory (issue #334): one item
     * of {@code itemId} carrying {@code state}, or — {@code bucketPoured} — one water bucket
     * emptied. A later refusal or retry expiry refunds this record, never the placed block's
     * id, and nothing for a break.
     */
    private record PlacementDebit(int itemId, String state, boolean bucketPoured) {
        static final PlacementDebit NONE = new PlacementDebit(0, null, false);
    }

    /**
     * The placement debit for one block (issue #334), or null when the player does not hold
     * what the placement costs (nothing taken). Most blocks debit their own id, but the torch
     * ITEM places the torch BLOCK (ids differ — a stack may hold either: crafted torches carry
     * the item id, picked-up ones the block id), and water poured from a selected water bucket
     * empties exactly one bucket (mirrors {@code BlockPlacer.handleWaterBucket}).
     */
    private static PlacementDebit debitForPlacement(ServerPlayer sp, BlockType incoming,
                                                    ServerWorldContext ctx) {
        if (incoming == BlockType.WATER) {
            // The client pours from the bucket whenever a water bucket is selected; otherwise
            // water is held as plain WATER blocks (starting items).
            int sel = sp.inventory().selectedHotbarSlot();
            if (isBucket(sp.inventory().slot(sel), true)) {
                return convertOneBucket(sp, sel, ItemType.BUCKET_STATE_EMPTY, ctx)
                        ? new PlacementDebit(ItemType.WOODEN_BUCKET.getId(), null, true) : null;
            }
            return takeOne(sp, incoming.getId());
        }
        if (incoming == BlockType.TORCH_PLACED) {
            ItemType torch = com.stonebreak.blocks.torch.TorchBlock.item();
            int blockId = incoming.getId();
            int itemId = torch != null ? torch.getId() : blockId;
            // Whichever torch id the selected slot holds goes first — the stack the client's
            // placer spent (and auto-switches); the other id only when that one is not held.
            int first = sp.inventory().heldItemId() == blockId ? blockId : itemId;
            int second = first == blockId ? itemId : blockId;
            PlacementDebit taken = takeOne(sp, first);
            return taken != null || second == first ? taken : takeOne(sp, second);
        }
        return takeOne(sp, incoming.getId());
    }

    /**
     * Removes one {@code itemId} (the selected slot first, with the client's depletion
     * auto-switch — issue #422) and records the state of the unit taken; null when none is held.
     */
    private static PlacementDebit takeOne(ServerPlayer sp, int itemId) {
        // The held (selected) stack first, like the client's Inventory.removeItem.
        ItemStack taken = sp.inventory().debitOneWithAutoSwitch(itemId);
        return taken == null ? null : new PlacementDebit(itemId, taken.getState(), false);
    }

    /** Gives back exactly what {@code debit} took, then syncs the player (issue #334). */
    private static void refund(ServerPlayer sp, PlacementDebit debit, ServerWorldContext ctx) {
        if (debit == null || debit == PlacementDebit.NONE) {
            return;
        }
        if (debit.bucketPoured()) {
            // Refill one emptied bucket — the selected slot first, where the pour emptied it.
            // If the player no longer holds any empty bucket there is nothing to refill;
            // granting a fresh water bucket would mint one.
            int slot = findBucketSlot(sp, false);
            if (slot >= 0) {
                convertOneBucket(sp, slot, ItemType.BUCKET_STATE_WATER, ctx);
            }
        } else {
            giveOrDrop(sp, ctx, debit.itemId(), debit.state());
        }
        syncInventory(sp);
    }

    /** True for a wooden bucket stack holding water ({@code water}) or not (any other state). */
    private static boolean isBucket(ItemStack s, boolean water) {
        ItemType bucket = ItemType.WOODEN_BUCKET;
        return bucket != null && s != null && !s.isEmpty() && s.getBlockTypeId() == bucket.getId()
                && ItemType.BUCKET_STATE_WATER.equals(s.getState()) == water;
    }

    /** The selected slot if it holds a matching bucket, else the first slot that does; -1 if none. */
    private static int findBucketSlot(ServerPlayer sp, boolean water) {
        int sel = sp.inventory().selectedHotbarSlot();
        if (isBucket(sp.inventory().slot(sel), water)) {
            return sel;
        }
        for (int i = 0; i < com.stonebreak.network.server.ServerInventory.TOTAL_SLOTS; i++) {
            if (isBucket(sp.inventory().slot(i), water)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Converts ONE bucket in {@code slot} to {@code toState}, with the client's stack rule
     * ({@code BlockPlacer.handleWaterBucket}/{@code handleEmptyBucket}): a lone bucket is
     * replaced in place; from a larger stack one leaves and the converted bucket is added
     * (stacking first, then an empty slot), dropping beside the player when it fits nowhere.
     * Never loses or mints a bucket: when the converted one can neither be stored nor dropped
     * the stack is restored and false returned. The caller has checked the slot holds a bucket.
     * An honest client refuses the use up front when its mirror has no room
     * ({@code BucketStacks.canConvertHeld}, #354), so the drop only covers a replica that
     * filled up while the intent was in flight.
     */
    private static boolean convertOneBucket(ServerPlayer sp, int slot, String toState,
                                            ServerWorldContext ctx) {
        ItemType bucket = ItemType.WOODEN_BUCKET;
        ItemStack s = sp.inventory().slot(slot);
        if (bucket == null || s.isEmpty() || s.getBlockTypeId() != bucket.getId()) {
            return false;
        }
        if (s.getCount() == 1) {
            // Replaced in place: a decrement-then-add would land the new bucket in this very
            // (now empty) slot whenever every slot before it is occupied.
            sp.inventory().setSlot(slot, new ItemStack(bucket, 1, toState));
            return true;
        }
        s.decrementCount(1);
        if (sp.inventory().add(bucket.getId(), 1, toState) == 1) {
            return true;
        }
        if (!canDrop(sp, ctx)) {
            s.incrementCount(1);
            return false;
        }
        ServerPlayerHandler.spawnTossDrop(sp, ctx, bucket.getId(), 1, toState);
        return true;
    }

    /**
     * Adds one item back — onto the selected stack when it is the same kind, so a refused
     * placement lands where the placer's hand is (issue #422) — dropping it beside the player
     * when the inventory is full.
     */
    private static void giveOrDrop(ServerPlayer sp, ServerWorldContext ctx, int itemId, String state) {
        if (sp.inventory().addToSelectedFirst(itemId, state) == 0) {
            ServerPlayerHandler.spawnTossDrop(sp, ctx, itemId, 1, state);
        }
    }

    /** The guard {@link ServerPlayerHandler#spawnTossDrop} applies before spawning. */
    private static boolean canDrop(ServerPlayer sp, ServerWorldContext ctx) {
        return ctx.world() != null && ctx.entityManager() != null && sp.lastStateNs() != 0L;
    }

    private static void syncInventory(ServerPlayer sp) {
        sp.send(sp.inventorySync(), false);
    }

    /**
     * Post-apply effects of an accepted edit: break drops/cleanup, broadcast. The edit and
     * every side effect reach clients as per-block packets (block batches, state echoes,
     * snow/water meta), so the chunk version is NOT bumped — a bump would re-stream the whole
     * chunk to every viewer, and the stale snapshot would briefly undo the editor's newer
     * predictions (issue #362). Only the audit hash is dropped.
     */
    private void applyAccepted(ServerPlayer sp, BlockChangeC2S c, BlockType incoming, BlockType prev,
                               PlacementDebit debit, World world, ServerWorldContext ctx) {
        // A container leaving its cell — broken, or replaced by any other block — drops its
        // contents and leaves its registry, so nothing keeps ticking or broadcasting on a cell
        // that no longer holds it (issue #370). {@code prev} is the server's own cell.
        if (prev != incoming) {
            if (prev == BlockType.FURNACE && world.getFurnaceRegistry() != null) {
                // The SERVER world's registry (per-world) — drops contents authoritatively.
                world.getFurnaceRegistry().onBlockBroken(world, c.x(), c.y(), c.z());
            }
            if (prev == BlockType.WORKBENCH) {
                // Issue #307: the grid's items drop with the table, never vanish.
                world.getWorkbenchRegistry().onBlockBroken(world, c.x(), c.y(), c.z(),
                        new Vector3f(sp.x(), sp.y(), sp.z()));
            }
        }
        // Break (non-air → air): spawn drops authoritatively. A cell the server already held as
        // air drops nothing — a second player's break of the same block, or leaves that decayed
        // first (issue #370). The EntityManager listener broadcasts the resulting drop entities
        // (see ServerEntityHandler).
        if (prev != null && prev != BlockType.AIR && incoming == BlockType.AIR) {
            Vector3f dropPos = new Vector3f(c.x() + 0.5f, c.y() + 0.5f, c.z() + 0.5f);
            // The breaker's last-reported held item selects per-tool drop overrides
            // (SBO `drops.byTool`). Block ids and item ids never collide, so a held
            // block simply resolves to no tool.
            // The breaker's held item comes from the server's replica (issue #334) — the client
            // cannot forge a tool id to unlock per-tool drop overrides.
            com.stonebreak.items.ItemType tool = com.stonebreak.items.ItemType.getById(sp.inventory().heldItemId());
            // Issue #225: the breaker's position biases which adjacent passable cell the
            // drops spawn into (nearest to the player) and the direction they spit out.
            com.stonebreak.util.DropUtil.handleBlockBroken(world, dropPos, prev, tool, 0,
                    new Vector3f(sp.x(), sp.y(), sp.z()));
        }
        // Furnace placement: register the authoritative state (Unlit, empty). The client's
        // own BlockPlacer only touched ITS display registry.
        if (incoming == BlockType.FURNACE && world.getFurnaceRegistry() != null) {
            world.getFurnaceRegistry().onBlockPlaced(world, c.x(), c.y(), c.z(), incoming);
        }
        // Crafting-table placement: a fresh, empty grid — echoed so every client's display
        // registry drops whatever a table previously broken at this cell held.
        if (incoming == BlockType.WORKBENCH) {
            world.getWorkbenchRegistry().onBlockPlaced(world, c.x(), c.y(), c.z());
        }
        // Door placement: initialize the authoritative state (closed, panel on the placer's
        // edge) and echo it so every client — the placer included — agrees on the facing.
        // The echo is QUEUED, not broadcast immediately: block changes flush in per-tick
        // batches, and a state packet overtaking its block change would be wiped when the
        // late-arriving block apply clears the cell's state entry.
        if (incoming == BlockType.OAK_DOOR) {
            // The placer's proposed facing (any edge is legitimate — it is where they stood);
            // a door is always placed closed. No proposal: derive it from their position.
            com.stonebreak.blocks.door.DoorState placed = c.hasPlacementState()
                    && com.stonebreak.blocks.door.DoorState.isDoorState(c.placementState())
                    ? new com.stonebreak.blocks.door.DoorState(com.stonebreak.blocks.door.DoorState.CLOSED,
                            com.stonebreak.blocks.door.DoorState.parse(c.placementState()).facing())
                    : com.stonebreak.blocks.door.DoorState.placed(sp.x(), sp.z(), c.x(), c.z());
            world.setBlockStateAt(c.x(), c.y(), c.z(), placed.toStateString());
            queueStateEcho(c.x(), c.y(), c.z());
        }
        // Stair placement: the facing is authoritative too — it decides the
        // baked orientation every client meshes and the step profile every
        // body collides with. Echoed on the same queued path as the door.
        if (incoming != null && incoming.isStairs()) {
            // The placer's proposed facing (any of the four is legitimate); none: their yaw.
            String placed = (c.hasPlacementState()
                    && com.stonebreak.blocks.stairs.StairState.isStairState(c.placementState())
                    ? com.stonebreak.blocks.stairs.StairState.parse(c.placementState())
                    : com.stonebreak.blocks.stairs.StairState.placedFromYaw(sp.yaw())).toStateString();
            world.setBlockStateAt(c.x(), c.y(), c.z(), placed);
            queueStateEcho(c.x(), c.y(), c.z());
        }
        // Torch placement: Ground/Side + facing depend on which FACE the placer
        // clicked, which only the client knows — it proposes the state in the
        // packet and the server validates it against its own world (the support
        // must be able to hold a torch — canHoldTorch, full-cube solids; foliage
        // and the door's swinging panel are ruled out — issue #247; the underside
        // of a block is never accepted).
        // Rejected proposals revert the cell for the placer and refund the torch the
        // placement debited (issue #334).
        if (com.stonebreak.blocks.torch.TorchBlock.isTorch(incoming)) {
            // Never into water: the client's BlockPlacer refuses that, and a torch the sim
            // then washes out would drop a free torch item.
            com.stonebreak.blocks.torch.TorchState placed = c.hasPlacementState() && prev != BlockType.WATER
                    ? com.stonebreak.blocks.torch.TorchBlock.validatePlacement(
                            world, c.x(), c.y(), c.z(), c.placementState())
                    : null;
            if (placed == null) {
                // Restore the server's own cell (water may have been there — writing AIR
                // left the server dry while every other client still showed the water),
                // and queue it like any edit so every view converges.
                BlockType restore = prev == null ? BlockType.AIR : prev;
                world.setBlockAt(c.x(), c.y(), c.z(), restore, false);
                queueOutgoing(c.x(), c.y(), c.z(), (short) restore.getId());
                sendRevert(sp, c.x(), c.y(), c.z(), ctx);
                refund(sp, debit, ctx);
                return;
            }
            world.setBlockStateAt(c.x(), c.y(), c.z(), placed.toStateString());
            queueStateEcho(c.x(), c.y(), c.z());
        }
        // Stalagmite placement: hanging and facing come from the client's proposal (only it knows
        // which face was clicked); a missing or unreadable one stands upright facing the placer's
        // yaw. A placed stalagmite is always size 1 — it grows with water, never by proposal.
        if (incoming == BlockType.LIMESTONE_STALAGMITE) {
            com.stonebreak.blocks.stalagmite.StalagmiteState placed = c.hasPlacementState()
                    && c.placementState().startsWith(com.stonebreak.blocks.stalagmite.StalagmiteState.STATE_PREFIX)
                    ? com.stonebreak.blocks.stalagmite.StalagmiteState.parse(c.placementState()).withSize(1)
                    : new com.stonebreak.blocks.stalagmite.StalagmiteState(1, false,
                            com.stonebreak.blocks.stairs.StairState.Facing.fromYaw(sp.yaw()));
            world.setBlockStateAt(c.x(), c.y(), c.z(), placed.toStateString());
            queueStateEcho(c.x(), c.y(), c.z());
        }
        // Torches held up by the block that just changed pop off with it (a break, or
        // any edit leaving a non-solid cell) — issue #247. The World.setBlockAt funnel
        // already swept this cell when the edit applied (applyToWorld precedes this), so
        // this is a no-op here; kept as a safety net for any caller that reaches
        // applyAccepted without a funnel apply. findUnsupported self-gates on solid cells
        // and popUnsupported reports each popped AIR through the replication sink so
        // remote clients observe the pop through the same per-section batches as sim edits.
        com.stonebreak.blocks.torch.TorchBlock.popUnsupported(world, c.x(), c.y(), c.z());
        // Stalagmites span up to three cells. Breaking any of them — or the floor under one —
        // clears the rest of the formation here; clients learn of it through the queued block
        // broadcast. Only the cell the player broke drops (one size-1 stalagmite, via the drop
        // above); a stalagmite knocked off its floor drops its own.
        if (incoming == null || !incoming.isSolid()) {
            com.stonebreak.blocks.stalagmite.Stalagmite.BreakResult fell =
                    com.stonebreak.blocks.stalagmite.Stalagmite.breakAt(
                            com.stonebreak.blocks.stalagmite.Stalagmite.of(world), c.x(), c.y(), c.z(),
                            prev == BlockType.LIMESTONE_STALAGMITE);
            for (org.joml.Vector3i a : fell.fallenAnchors()) {
                com.stonebreak.util.DropUtil.handleBlockBroken(world,
                        new Vector3f(a.x + 0.5f, a.y + 0.5f, a.z + 0.5f), BlockType.LIMESTONE_STALAGMITE);
            }
            for (org.joml.Vector3i r : fell.removed()) {
                chunkHandler.invalidateHash(Math.floorDiv(r.x, 16), Math.floorDiv(r.z, 16));
                queueOutgoing(r.x, r.y, r.z, (short) BlockType.AIR.getId());
            }
        }
        // Snow layer bookkeeping derived from the block change (the SnowLayerC2S intent only
        // covers layer increments on an EXISTING snow block): a placed SNOW block starts at
        // 1 layer; a broken one drops its tracking. The manager's mutation listener then
        // dirties the chunk and queues the BlockMetaS2C broadcast.
        if (world.getSnowLayerManager() != null) {
            if (incoming == BlockType.SNOW) {
                world.getSnowLayerManager().setSnowLayers(c.x(), c.y(), c.z(), 1);
            } else if (prev == BlockType.SNOW && incoming == BlockType.AIR) {
                world.getSnowLayerManager().removeSnowLayers(c.x(), c.y(), c.z());
            }
        }
        // Water placement: send the cell's level. A bucket poured into flowing water arrives
        // as AIR then WATER, which the section batch coalesces to one WATER change; a client
        // whose cell is already WATER applies that as a no-op and would keep the old flow
        // level. The meta flushes after the block batches, so it also lands correctly where
        // the client cell was not water.
        if (incoming == BlockType.WATER) {
            int water = world.getWaterValueAt(c.x(), c.y(), c.z());
            if (water >= 0) {
                onServerWaterChange(c.x(), c.y(), c.z(), water);
            }
        }
        chunkHandler.invalidateHash(Math.floorDiv(c.x(), 16), Math.floorDiv(c.z(), 16));
        queueOutgoing(c.x(), c.y(), c.z(), c.blockTypeId());
    }

    /**
     * C2S: a player claims a snow-layer change at a position (the increment-on-existing-snow
     * case that carries no block change). Validates like a block edit, then applies to the
     * authoritative SnowLayerManager — its mutation listener dirties the chunk for save and
     * feeds {@link #onServerSnowChange} for the broadcast (echoed to the originator too, so
     * an over-optimistic client self-corrects).
     */
    public void handleSnowLayer(ServerPlayer sp, com.stonebreak.network.packet.world.SnowLayerC2S s,
                                ServerWorldContext ctx) {
        World world = ctx.world();
        if (world == null || world.getSnowLayerManager() == null) {
            return;
        }
        int layers = s.layers();
        if (layers < 0 || layers > 8 || s.y() < 0 || s.y() >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }
        if (!inReach(sp, s.x(), s.y(), s.z())) {
            sendSnowRevert(sp, s.x(), s.y(), s.z(), world); // undo the client's prediction (#363)
            syncInventory(sp); // ...and the SNOW its mirror spent on it
            return;
        }
        if (layers == 0) {
            world.getSnowLayerManager().removeSnowLayers(s.x(), s.y(), s.z());
        } else if (world.getBlockAt(s.x(), s.y(), s.z()) != BlockType.SNOW) {
            // The cell is not snow here: the client's predicted layer (and its collision height)
            // would otherwise stay until the chunk re-streams (#363). The block itself is left
            // to its own BlockChange flow (a placement may still be waiting on a retry).
            sendSnowRevert(sp, s.x(), s.y(), s.z(), world);
            syncInventory(sp);
        } else {
            // Raising the layer count consumes one SNOW block PER LAYER added from the
            // server-owned inventory (issue #334) — breaking the cell drops one SNOW per
            // layer (DropUtil), so anything cheaper would duplicate snow. A refusal syncs
            // and mutates nothing.
            int current = world.getSnowLayerManager().getSnowLayers(s.x(), s.y(), s.z());
            int added = layers - current;
            if (added <= 0) {
                // Another player raised this cell first (issue #370): the layer the client
                // predicted is already there and took nothing from the server replica.
                sendSnowRevert(sp, s.x(), s.y(), s.z(), world);
                syncInventory(sp);
                return;
            }
            if (!debitSnow(sp, added)) {
                syncInventory(sp);
                // Re-assert the cell's actual layer count so an over-optimistic client reverts.
                sendSnowRevert(sp, s.x(), s.y(), s.z(), world);
                return;
            }
            world.getSnowLayerManager().setSnowLayers(s.x(), s.y(), s.z(), layers);
        }
    }

    /**
     * C2S: the player right-clicked a toggleable block (door). Validates (reach, target is
     * a door on the server world), flips the authoritative door state, and echoes the
     * result to ALL clients — originator included — as {@link com.stonebreak.network.packet.world.BlockStateS2C}.
     * Clients play the new state's animation clip when they observe the flip.
     */
    public void handleBlockToggle(ServerPlayer sp, com.stonebreak.network.packet.world.BlockToggleC2S t,
                                  ServerWorldContext ctx) {
        World world = ctx.world();
        if (world == null) {
            return;
        }
        if (t.y() < 0 || t.y() >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }
        if (!inReach(sp, t.x(), t.y(), t.z())) {
            if (world.getBlockAt(t.x(), t.y(), t.z()) == BlockType.LIMESTONE_STALAGMITE) {
                syncInventory(sp); // the client already emptied its bucket on the growth
            }
            return;
        }
        BlockType target = world.getBlockAt(t.x(), t.y(), t.z());
        if (target == BlockType.LIMESTONE_STALAGMITE) {
            growStalagmite(sp, world, t.x(), t.y(), t.z(), ctx);
            return;
        }
        if (target != BlockType.OAK_DOOR) {
            return;
        }
        com.stonebreak.blocks.door.DoorState next = com.stonebreak.blocks.door.DoorState
                .parse(world.getBlockStateAt(t.x(), t.y(), t.z()))
                .toggled();
        world.setBlockStateAt(t.x(), t.y(), t.z(), next.toStateString());
        // Door-open pop (issue #247): the door keeps its (solid) TYPE on toggle, so the
        // funnel sweep never fires for it — a torch mounted on the door hangs off the
        // panel that swung away. treatCellAsNonSupport asserts the swung-away door no
        // longer holds things up; the popped AIR write re-enters the World.setBlockAt
        // funnel (its sweep for the torch's own cell terminates empty) and is reported
        // through the replication sink, so clients observe the pop alongside the state
        // echo — the torch cell carries no state entry to wipe, so no packet can overtake.
        if (next.isOpen()) {
            com.stonebreak.blocks.torch.TorchBlock.popUnsupported(world, t.x(), t.y(), t.z(), true);
        }
        ctx.broadcast(new com.stonebreak.network.packet.world.BlockStateS2C(
                t.x(), t.y(), t.z(), next.toStateString()), false);
    }

    /**
     * The toggle intent on a stalagmite is the water-bucket use: grow it one size. The new
     * upper cell goes out on the block batch and both states ride the queued echo, so a state
     * packet can never overtake the block change that would clear it.
     *
     * <p>The water costs one water bucket from the server-owned inventory (issue #334): the
     * selected slot must hold one (as {@code StalagmiteInteraction.tryGrow} requires) and one
     * bucket of it is emptied with the pour's stack rule. A refusal, or a stalagmite the server says cannot grow, syncs the
     * sender, whose client already emptied its own copy.
     */
    private void growStalagmite(ServerPlayer sp, World world, int x, int y, int z, ServerWorldContext ctx) {
        com.stonebreak.blocks.stalagmite.Stalagmite.Cells cells =
                com.stonebreak.blocks.stalagmite.Stalagmite.of(world);
        int sel = sp.inventory().selectedHotbarSlot();
        if (!isBucket(sp.inventory().slot(sel), true)
                || !com.stonebreak.blocks.stalagmite.Stalagmite.canGrow(cells, x, y, z)
                || !convertOneBucket(sp, sel, ItemType.BUCKET_STATE_EMPTY, ctx)) {
            syncInventory(sp);
            return;
        }
        java.util.List<org.joml.Vector3i> written =
                com.stonebreak.blocks.stalagmite.Stalagmite.grow(cells, x, y, z);
        if (!written.isEmpty()) {
            org.joml.Vector3i newCell = written.get(0);
            queueOutgoing(newCell.x, newCell.y, newCell.z, (short) BlockType.LIMESTONE_STALAGMITE.getId());
        }
        for (org.joml.Vector3i w : written) {
            chunkHandler.invalidateHash(Math.floorDiv(w.x, 16), Math.floorDiv(w.z, 16));
            queueStateEcho(w.x, w.y, w.z);
        }
    }

    /**
     * Authoritative snow mutation ({@code layers == 0} = removed) from the server world's
     * SnowLayerManager listener. Batched per section and flushed each tick as
     * {@link com.stonebreak.network.packet.world.BlockMetaS2C} (KIND_SNOW_LAYERS).
     */
    public void onServerSnowChange(int x, int y, int z, int layers) {
        if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }
        int sx = Math.floorDiv(x, 16);
        int sy = Math.floorDiv(y, 16);
        int sz = Math.floorDiv(z, 16);
        long key = sectionKey(sx, sy, sz);
        SectionBatch batch = pendingSnowByTick.computeIfAbsent(key, k -> new SectionBatch(sx, sy, sz));
        batch.add(x - sx * 16, y - sy * 16, z - sz * 16, (short) layers);
    }

    /**
     * Authoritative water flow-level mutation from the sim ({@code value} = layer byte:
     * 1..7 flowing, 8 falling, 0 = entry removed / became source). Batched per section and
     * flushed each tick as {@link com.stonebreak.network.packet.world.BlockMetaS2C}
     * (KIND_WATER_LEVEL), after the block batches. No hash invalidation needed: the desync
     * audit hashes block ids only, and the accompanying WATER/AIR block changes already
     * invalidate via {@link #onServerBlockChange}. Repeated same-tick writes to one cell
     * coalesce to the last value in the section batch.
     */
    public void onServerWaterChange(int x, int y, int z, int value) {
        if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }
        int sx = Math.floorDiv(x, 16);
        int sy = Math.floorDiv(y, 16);
        int sz = Math.floorDiv(z, 16);
        long key = sectionKey(sx, sy, sz);
        SectionBatch batch = pendingWaterByTick.computeIfAbsent(key, k -> new SectionBatch(sx, sy, sz));
        batch.add(x - sx * 16, y - sy * 16, z - sz * 16, (short) value);
    }

    /** Retry edits that were waiting on a server chunk load; revert the originator on expiry. */
    private void drainRetries(ServerWorldContext ctx) {
        if (retryQueue.isEmpty()) {
            return;
        }
        World world = ctx.world();
        if (world == null) {
            retryQueue.clear();
            return;
        }
        int n = retryQueue.size();
        for (int i = 0; i < n; i++) {
            PendingEdit pe = retryQueue.poll();
            if (pe == null) {
                break;
            }
            BlockChangeC2S c = pe.change;
            BlockType incoming = BlockType.getById(c.blockTypeId() & 0xFFFF);
            BlockType serverPrev = world.getBlockAt(c.x(), c.y(), c.z()); // before apply
            if (chunkLoaded(world, c.x(), c.z()) && conflictsWithServerCell(c, incoming, serverPrev)) {
                // The cell the edit waited for turned out to be taken (issue #370): undo it and
                // give back exactly what the placement took.
                sendRevert(pe.sp, c.x(), c.y(), c.z(), ctx);
                refund(pe.sp, pe.debit, ctx);
                if (pe.debit == PlacementDebit.NONE) {
                    syncInventory(pe.sp);
                }
                continue;
            }
            if (!pe.timed && chunkLoaded(world, c.x(), c.z())) {
                // The chunk was not loaded when the break arrived, so it is timed now — from
                // its arrival, exactly as it would have been then (issue #333).
                BreakTiming timing = timeBreak(pe.sp, c, incoming, serverPrev, pe.receivedNs, ctx);
                if (timing == BreakTiming.REFUSE) {
                    sendRevert(pe.sp, c.x(), c.y(), c.z(), ctx);
                    refund(pe.sp, pe.debit, ctx);
                    continue;
                }
                if (timing == BreakTiming.WAIT) {
                    continue; // now in deferredBreaks
                }
            }
            boolean scoop = isScoop(world, c, incoming, serverPrev);
            if (applyToWorld(world, c.x(), c.y(), c.z(), c.blockTypeId())) {
                completeEdit(pe.sp, c, incoming, serverPrev, scoop, pe.debit, world, ctx);
                if (scoop) {
                    // The intent was acknowledged when it arrived, and syncs sent since then
                    // lack the filled bucket — this one carries it.
                    syncInventory(pe.sp);
                }
            } else if (--pe.ticksLeft <= 0) {
                // The debit was taken when the edit was validated; give back exactly what it
                // took (nothing for an exempt placement or a break) and sync (issue #334).
                sendRevert(pe.sp, c.x(), c.y(), c.z(), ctx);
                refund(pe.sp, pe.debit, ctx);
            } else {
                retryQueue.add(pe); // still loading — try again next tick
            }
        }
    }

    /** Host-originated edit (wired from {@code World.setBlockAt} in the lifecycle phase). */
    public void onLocalBlockChange(int x, int y, int z, BlockType type, ServerWorldContext ctx) {
        short id = (short) (type == null ? 0 : type.getId());
        chunkHandler.invalidateHash(Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE),
            Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE));
        queueOutgoing(x, y, z, id);
    }

    /** Sim edits accepted per tick before falling back to whole-chunk re-streams. Flowing
     *  water peaks well under this; the cap only bites on pathological floods. */
    private static final int MAX_SIM_EDITS_PER_TICK = 2048;
    private int simEditsThisTick = 0;

    /**
     * Authoritative SIMULATION mutation (water flow etc., via the {@code World} mutation
     * callback on the server tick thread). Rides the same per-section batches as player
     * edits ({@code MultiBlockChangeS2C}) but does NOT bump the chunk version — a version
     * bump would re-stream the whole chunk to every viewer continuously under flowing water.
     * Clients that don't hold the chunk drop the apply harmlessly and receive a fresh
     * snapshot (encoded from live state at send time) when it enters view.
     *
     * <p>Overflow guardrail: past {@link #MAX_SIM_EDITS_PER_TICK} queued sim edits in one
     * tick, fall back to marking the chunk modified (one full re-stream self-heals it) and
     * skip per-block queuing for the rest of the tick.
     */
    public void onServerBlockChange(int x, int y, int z, BlockType type) {
        if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }
        if (simEditsThisTick >= MAX_SIM_EDITS_PER_TICK) {
            int cx = Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE);
            int cz = Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE);
            chunkHandler.markChunkModified(cx, cz);
            // Still a sim edit: record it for the audit's sim-edit grace window too.
            chunkHandler.invalidateHash(cx, cz);
            return;
        }
        simEditsThisTick++;
        // Sim edits change chunk contents without a version bump, so the audit hash cache
        // must be invalidated here (player edits do the same in applyAccepted).
        chunkHandler.invalidateHash(Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE),
            Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE));
        queueOutgoing(x, y, z, (short) (type == null ? 0 : type.getId()));
    }

    // ─── Per-tick flush ──────────────────────────────────────────────────────────

    public void tick(ServerWorldContext ctx) {
        tick(ctx, System.nanoTime());
    }

    /** {@link #tick(ServerWorldContext)} on an explicit clock (deferred breaks fall due by it). */
    void tick(ServerWorldContext ctx, long nowNs) {
        simEditsThisTick = 0;
        drainDeferredBreaks(ctx, nowNs);
        drainRetries(ctx);
        if (!pendingByTick.isEmpty()) {
            for (SectionBatch sb : pendingByTick.values()) {
                if (sb.size() == 1) {
                    int packed = sb.packed[0];
                    int lx = (packed >>> 24) & 0xF;
                    int ly = (packed >>> 20) & 0xF;
                    int lz = (packed >>> 16) & 0xF;
                    short blockId = (short) (packed & 0xFFFF);
                    sendToHolders(ctx, sb, new BlockChangeS2C(sb.sx * 16 + lx, sb.sy * 16 + ly, sb.sz * 16 + lz, blockId));
                } else {
                    sendToHolders(ctx, sb, new MultiBlockChangeS2C(sb.sx, sb.sy, sb.sz, sb.toPackedArray()));
                }
            }
            pendingByTick.clear();
        }
        if (!pendingSnowByTick.isEmpty()) {
            for (SectionBatch sb : pendingSnowByTick.values()) {
                sendToHolders(ctx, sb, new com.stonebreak.network.packet.world.BlockMetaS2C(
                    sb.sx, sb.sy, sb.sz,
                    com.stonebreak.network.packet.world.BlockMetaS2C.KIND_SNOW_LAYERS,
                    sb.toPackedArray()));
            }
            pendingSnowByTick.clear();
        }
        // AFTER the block batches: a cell's same-tick WATER placement must apply on the
        // client before its flow-level meta (meta on a non-WATER cell would be stale).
        if (!pendingWaterByTick.isEmpty()) {
            for (SectionBatch sb : pendingWaterByTick.values()) {
                sendToHolders(ctx, sb, new com.stonebreak.network.packet.world.BlockMetaS2C(
                    sb.sx, sb.sy, sb.sz,
                    com.stonebreak.network.packet.world.BlockMetaS2C.KIND_WATER_LEVEL,
                    sb.toPackedArray()));
            }
            pendingWaterByTick.clear();
        }
        // AFTER the block batches: state echoes for cells changed this tick
        // (see pendingStateEchoes — order matters, block apply clears state).
        if (!pendingStateEchoes.isEmpty()) {
            World world = ctx.world();
            if (world != null) {
                for (com.openmason.engine.util.BlockPos p : pendingStateEchoes) {
                    String state = world.getBlockStateAt(p.x(), p.y(), p.z());
                    if (state != null && !state.isEmpty()) {
                        sendStateEcho(p, state, ctx);
                    }
                }
            }
            pendingStateEchoes.clear();
        }
    }

    /**
     * Sends a per-block batch only to the players this chunk was streamed to (#363). Anyone
     * else gets the change inside the snapshot when the chunk streams; sending it anyway made
     * the client fail the apply for a chunk it never had and spend resync budget on it. The
     * stream scan runs on this same tick thread, so a chunk is either streamed with the change
     * already in it or marked sent before this flush.
     */
    private static void sendToHolders(ServerWorldContext ctx, SectionBatch sb, com.openmason.engine.net.protocol.Packet packet) {
        long key = ServerChunkHandler.packKey(sb.sx, sb.sz);
        for (ServerPlayer sp : ctx.players()) {
            if (sp.sentChunkVersion(key) >= 0) {
                sp.send(packet, false);
            }
        }
    }

    /**
     * Sends one block's state echo to every connection, each stamped with the ack of that
     * player's own slot intents at this block (issue #353). Players that never edited this
     * block share one ack-0 packet; the common case (nobody did) stays a single broadcast.
     */
    private void sendStateEcho(com.openmason.engine.util.BlockPos p, String state, ServerWorldContext ctx) {
        com.stonebreak.network.packet.world.BlockStateS2C plain =
                new com.stonebreak.network.packet.world.BlockStateS2C(p.x(), p.y(), p.z(), state);
        java.util.Map<com.openmason.engine.net.server.ServerConnection, Integer> acks = null;
        for (ServerPlayer sp : ctx.players()) {
            int ack = sp.slotIntentAck(p);
            if (ack != 0) {
                if (acks == null) acks = new java.util.HashMap<>();
                acks.put(sp.connection(), ack);
            }
        }
        if (acks == null) {
            ctx.broadcast(plain, false);
            return;
        }
        for (com.openmason.engine.net.server.ServerConnection c : ctx.connections().all()) {
            ServerPlayer viewer = c.attachment();
            if (viewer != null && !viewer.handshakeDone()) {
                continue; // not in the world yet (the broadcast path filters these too)
            }
            Integer ack = acks.get(c);
            c.send(ack == null ? plain
                    : new com.stonebreak.network.packet.world.BlockStateS2C(p.x(), p.y(), p.z(), state, ack),
                    false);
        }
    }

    /**
     * Echo the cell's block state to every client after this tick's block batches. Used for
     * placement states and for the furnace/workbench registries' change listeners, whose
     * first write lands in the same tick as the block that created them.
     */
    public void queueStateEcho(int x, int y, int z) {
        pendingStateEchoes.add(new com.openmason.engine.util.BlockPos(x, y, z));
    }

    // ─── Outgoing batching ─────────────────────────────────────────────────────────

    private void queueOutgoing(int x, int y, int z, short blockId) {
        int sx = Math.floorDiv(x, 16);
        int sy = Math.floorDiv(y, 16);
        int sz = Math.floorDiv(z, 16);
        int lx = x - sx * 16;
        int ly = y - sy * 16;
        int lz = z - sz * 16;
        long key = sectionKey(sx, sy, sz);
        SectionBatch batch = pendingByTick.computeIfAbsent(key, k -> new SectionBatch(sx, sy, sz));
        batch.add(lx, ly, lz, blockId);
    }

    private static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0xFFFFFF) << 36) | ((long) (sz & 0xFFFFFF) << 12) | (sy & 0xFFF);
    }

    // ─── Inbound application + validation ────────────────────────────────────────────

    private boolean applyToWorld(World world, int x, int y, int z, short blockTypeId) {
        BlockType type = BlockType.getById(blockTypeId & 0xFFFF);
        if (type == null) {
            type = BlockType.AIR;
        }
        // false = non-broadcasting: applies to the authoritative world without firing the
        // local-change hook (the server decides the rebroadcast via queueOutgoing).
        return world.setBlockAt(x, y, z, type, false);
    }

    private boolean validateClientEdit(ServerPlayer sp, BlockChangeC2S c) {
        if (c.y() < 0 || c.y() >= WorldConfiguration.WORLD_HEIGHT) {
            return false;
        }
        if (BlockType.getById(c.blockTypeId() & 0xFFFF) == null) {
            return false;
        }
        return inReach(sp, c.x(), c.y(), c.z());
    }

    /**
     * Re-asserts the server's snow layer count at a cell to one player (0 = no snow there,
     * which clears the client's entry) after refusing their snow-layer request.
     */
    private static void sendSnowRevert(ServerPlayer sp, int x, int y, int z, World world) {
        int current = world.getBlockAt(x, y, z) == BlockType.SNOW
            ? world.getSnowLayerManager().getSnowLayers(x, y, z) : 0;
        int sx = Math.floorDiv(x, 16);
        int sy = Math.floorDiv(y, 16);
        int sz = Math.floorDiv(z, 16);
        int lx = x - sx * 16;
        int ly = y - sy * 16;
        int lz = z - sz * 16;
        sp.send(new com.stonebreak.network.packet.world.BlockMetaS2C(sx, sy, sz,
                com.stonebreak.network.packet.world.BlockMetaS2C.KIND_SNOW_LAYERS,
                new int[]{(((lx << 8) | (ly << 4) | lz) << 16) | current}), false);
    }

    private void sendRevert(ServerPlayer sp, int x, int y, int z, ServerWorldContext ctx) {
        World world = ctx.world();
        if (world == null || y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }
        BlockType actual = world.getBlockAt(x, y, z);
        short id = (short) (actual == null ? 0 : actual.getId());
        sp.send(new BlockChangeS2C(x, y, z, id), false);
    }

    /** Mutable buffer for batched edits in one section (mirrors the legacy wire layout). */
    private static final class SectionBatch {
        final int sx, sy, sz;
        int[] packed = new int[8];
        int n = 0;

        SectionBatch(int sx, int sy, int sz) { this.sx = sx; this.sy = sy; this.sz = sz; }

        /** Past this many cells, repeat edits are found through {@link #slotOf}, not a scan. */
        private static final int INDEX_THRESHOLD = 16;
        /** Cell (lx<<8|ly<<4|lz) → entry index + 1; built once the batch is large (a water
         *  flood or a big sim tick), where the linear coalescing scan went quadratic. */
        private short[] slotOf;

        void add(int lx, int ly, int lz, short blockId) {
            int posBits = ((lx & 0xF) << 24) | ((ly & 0xF) << 20) | ((lz & 0xF) << 16);
            int v = posBits | (blockId & 0xFFFF);
            int cell = ((lx & 0xF) << 8) | ((ly & 0xF) << 4) | (lz & 0xF);
            if (slotOf != null) {
                int slot = slotOf[cell];
                if (slot != 0) {
                    packed[slot - 1] = v; // coalesce repeated edits to the same cell
                    return;
                }
            } else {
                int posMask = 0x0FFF0000;
                for (int i = 0; i < n; i++) {
                    if ((packed[i] & posMask) == (v & posMask)) {
                        packed[i] = v; // coalesce repeated edits to the same cell
                        return;
                    }
                }
            }
            if (n == packed.length) {
                int[] grown = new int[packed.length * 2];
                System.arraycopy(packed, 0, grown, 0, n);
                packed = grown;
            }
            packed[n++] = v;
            if (slotOf != null) {
                slotOf[cell] = (short) n;
            } else if (n >= INDEX_THRESHOLD) {
                slotOf = new short[4096];
                for (int i = 0; i < n; i++) {
                    int e = packed[i];
                    slotOf[(((e >>> 24) & 0xF) << 8) | (((e >>> 20) & 0xF) << 4) | ((e >>> 16) & 0xF)] = (short) (i + 1);
                }
            }
        }

        int size() { return n; }

        /** Wire layout: {@code (localPos << 16) | blockId}, {@code localPos = (lx<<8)|(ly<<4)|lz}. */
        int[] toPackedArray() {
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                int v = packed[i];
                int lx = (v >>> 24) & 0xF;
                int ly = (v >>> 20) & 0xF;
                int lz = (v >>> 16) & 0xF;
                int blockId = v & 0xFFFF;
                out[i] = (((lx << 8) | (ly << 4) | lz) << 16) | blockId;
            }
            return out;
        }
    }
}
