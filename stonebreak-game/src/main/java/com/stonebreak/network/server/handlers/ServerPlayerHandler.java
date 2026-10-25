package com.stonebreak.network.server.handlers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemStackStateCodec;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.mobs.entities.ai.perception.Loudness;
import com.stonebreak.mobs.entities.ai.perception.NoiseBus;
import com.stonebreak.network.packet.player.ConsumeHeldC2S;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import com.stonebreak.network.packet.player.DeathDropC2S;
import com.stonebreak.network.packet.player.DropItemC2S;
import com.stonebreak.network.packet.player.HotbarSelectC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.player.PlayerHeldItemS2C;
import com.stonebreak.network.packet.player.PlayerJoinS2C;
import com.stonebreak.network.packet.player.PlayerLifeC2S;
import com.stonebreak.network.packet.player.PlayerStateC2S;
import com.stonebreak.network.packet.player.PlayerStateS2C;
import com.stonebreak.network.server.ServerInventory;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

/**
 * Server-authoritative player-state handler — successor of the old
 * {@code PlayerStateSynchronizer} HOST path. Validates and caches inbound client transforms
 * (the cache feeds block-edit reach checks and chunk targeting), rebroadcasts them to other
 * clients, treats {@link DropItemC2S} as a DEBIT request against the server-owned
 * {@code ServerInventory} (rate limited), and applies {@link HotbarSelectC2S} selections —
 * the held item is derived from the replica, never taken from the client's word.
 *
 * <p>In the two-world model every player — including the in-process local (host) player —
 * is a normal client that sends its own {@link PlayerStateC2S}; there is no host special case
 * here. Remote-player spawn/despawn + interpolation is a client concern (client world view).
 */
public final class ServerPlayerHandler {

    /** Server-side crafting/smelting recipes for {@code TAKE_OUTPUT} (issue #334, reviewer note c). */
    private final com.stonebreak.crafting.CraftingManager craftingManager =
            new com.stonebreak.crafting.CraftingManager();
    /** Server-side smelting recipes + fuel table for the furnace slot restrictions (issue #334). */
    private final com.stonebreak.crafting.SmeltingManager smeltingManager =
            new com.stonebreak.crafting.SmeltingManager();
    private final ContainerActionProcessor containerActions =
            new ContainerActionProcessor(craftingManager, smeltingManager);

    public void onSessionStart() {
        // Recipes are declared on the SBO assets; the registries static-initialize on touch.
        craftingManager.clearRecipes();
        com.stonebreak.crafting.RecipeLoader.loadFromSBOs(craftingManager);
        com.stonebreak.crafting.SmeltingRecipeLoader.loadFromSBOs(smeltingManager);
    }

    public ContainerActionProcessor containerActions() {
        return containerActions;
    }

    // ─── Inbound ───────────────────────────────────────────────────────────────

    public void handlePlayerState(ServerPlayer sp, PlayerStateC2S ps, ServerWorldContext ctx) {
        handlePlayerState(sp, ps, ctx, System.nanoTime());
    }

    /**
     * {@code receivedNs}: when the report arrived on the event loop — the movement budget
     * refills by arrival time, so reports bunched by a tick hitch are not judged as a sprint.
     */
    public void handlePlayerState(ServerPlayer sp, PlayerStateC2S ps, ServerWorldContext ctx,
                                  long receivedNs) {
        boolean firstReport = sp.lastStateNs() == 0L;
        if (!validateAndCache(sp, ps, ctx, receivedNs)) {
            return;
        }
        if (firstReport) {
            // Re-announce with the first REAL position, non-droppable. Peers who received the
            // roster join before this player reported state (the re-host window: the host's
            // client world is still rebuilding when someone rejoins) have its figure parked at
            // the placeholder spawn, and the droppable 20 Hz state stream below is discarded
            // while their channel is saturated by the join chunk burst. The duplicate join is
            // idempotent client-side (existing figures are repositioned, missing ones spawned).
            ctx.broadcastExcept(sp,
                new PlayerJoinS2C(sp.playerId(), sp.username(), ps.x(), ps.y(), ps.z()), false);
        }
        ctx.broadcastExcept(sp,
            new PlayerStateS2C(sp.playerId(), ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch(), ps.flags()), true);
    }

    /**
     * Death / respawn edge (#357). The flag is applied unconditionally — a client whose
     * position fails validation must not keep a stale dead/alive state. The edge's position
     * replaces the cached one only for a real transition of a player that has already
     * reported: on respawn the server must not hold a live player at the corpse until the
     * droppable state stream catches up, while a redundant "alive" (a dead save respawned on
     * load) carries a position the state stream has not vouched for.
     */
    public void handlePlayerLife(ServerPlayer sp, PlayerLifeC2S l, ServerWorldContext ctx) {
        handlePlayerLife(sp, l, ctx, System.nanoTime());
    }

    /**
     * The edge's position is held to the movement rules too (issue #336): a death happens
     * where the player was, so it is an ordinary move; a respawn is a teleport the server
     * checks itself — it must land at the world spawn — and refills the movement budget.
     */
    public void handlePlayerLife(ServerPlayer sp, PlayerLifeC2S l, ServerWorldContext ctx,
                                 long receivedNs) {
        boolean transition = sp.isDead() != l.dead();
        sp.setDead(l.dead());
        if (!transition || sp.lastStateNs() == 0L || !isValidPosition(l.x(), l.y(), l.z())) {
            return;
        }
        if (l.dead()) {
            if (!movementAllowed(sp, l.x(), l.z(), ctx, receivedNs)) {
                return; // the corpse stays where the server last had the player
            }
        } else if (!respawnAllowed(sp, l.x(), l.z(), ctx)) {
            correct(sp, receivedNs);
            return;
        } else {
            sp.movement().reset(receivedNs);
        }
        sp.updateState(l.x(), l.y(), l.z(), sp.yaw(), sp.pitch());
        if (l.dead()) {
            NoiseBus.emitIn(ctx.world(), new Vector3f(l.x(), l.y(), l.z()), Loudness.DEATH);
        }
    }

    /** Upper bound on one DropItemC2S so a buggy/hostile client can't spawn item mountains. */
    private static final int MAX_DROP_COUNT = 64;
    /** Toss pickup lock-out: long enough for the dropper to walk away from their own toss. */
    private static final float TOSS_PICKUP_DELAY_SECONDS = 1.5f;

    /**
     * Player tossed an item (Q / inventory drop / death scatter): a DEBIT request. The server
     * checks its own {@code ServerInventory} actually holds the stack, debits it, and spawns
     * the authoritative drop entity in front of the player. A refusal (unknown id, count
     * &le; 0, insufficient stock, rate limit, world not yet spawnable) spawns NOTHING and
     * syncs the sender — a stale or forged mirror cannot conjure items (issue #334), and the
     * debit never runs without a spawnable target.
     */
    public void handleDropItem(ServerPlayer sp, DropItemC2S d, ServerWorldContext ctx) {
        if (d.count() <= 0 || ctx.world() == null || ctx.entityManager() == null
                || sp.lastStateNs() == 0L || !sp.allowDrop()) {
            sp.send(sp.inventorySync(), false);
            return;
        }
        int count = Math.min(d.count(), MAX_DROP_COUNT);
        // The packet names the stack's state (issue #342): only stacks of exactly that kind
        // are debited, the selected slot first (Q tosses from there), and the drop carries the
        // debited state — a water bucket tossed stays a water bucket.
        ItemStack tossed = sp.inventory().debitStack(d.itemId(), count, d.stack().state());
        if (tossed == null) {
            sp.send(sp.inventorySync(), false);
            return;
        }
        spawnTossDrop(sp, ctx, tossed.getBlockTypeId(), tossed.getCount(), tossed.getState());
        sp.send(sp.inventorySync(), false);
    }

    /**
     * Death scatter (issue #334): one request replaces the per-stack toss burst. The server
     * spawns a drop for every stack its OWN replica holds — 36 slots, craft grid, carried
     * cursor — without consuming toss tokens (a death legitimately exceeds the toss
     * budget), then clears the replica and syncs. Nothing is client-supplied. When the
     * world/entities aren't spawnable yet, nothing is drained and the sync returns the
     * inventory intact — no debit without a spawn.
     *
     * <p>Only a player the server holds dead (the reliable {@code PlayerLifeC2S} edge, which
     * the client sends first) may drain, once per death and at most once per toss window
     * ({@link ServerPlayer#claimDeathDrop}, #420); anything else is refused with a sync, so a
     * living client cannot dump its inventory past the toss limit.
     */
    public void handleDeathDrop(ServerPlayer sp, DeathDropC2S d, ServerWorldContext ctx) {
        ServerInventory inv = sp.inventory();
        // Spawnability first: a death that cannot drain yet must not use up its claim.
        if (ctx.world() == null || ctx.entityManager() == null || sp.lastStateNs() == 0L
                || !sp.claimDeathDrop()) {
            sp.send(sp.inventorySync(), false);
            return;
        }
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            ItemStack s = inv.slot(i);
            if (!s.isEmpty()) {
                spawnTossDrop(sp, ctx, s.getBlockTypeId(), s.getCount(), s.getState());
            }
        }
        ItemStack carried = inv.carried();
        if (!carried.isEmpty()) {
            spawnTossDrop(sp, ctx, carried.getBlockTypeId(), carried.getCount(), carried.getState());
        }
        for (int i = 0; i < ServerInventory.CRAFT_GRID_SIZE; i++) {
            ItemStack s = inv.craftSlot(i);
            if (!s.isEmpty()) {
                spawnTossDrop(sp, ctx, s.getBlockTypeId(), s.getCount(), s.getState());
            }
        }
        inv.clear();
        sp.send(sp.inventorySync(), false);
    }

    /**
     * Spawns the authoritative toss drop in front of the player on the SERVER world; the
     * entity-add listener replicates it to every client (including the dropper) as a normal
     * drop shadow. Callers must have already debited the stock.
     */
    public static void spawnTossDrop(ServerPlayer sp, ServerWorldContext ctx, int itemId, int count,
                              String state) {
        World world = ctx.world();
        EntityManager em = ctx.entityManager();
        if (world == null || em == null || sp.lastStateNs() == 0L || count <= 0) {
            return;
        }
        // Spawn slightly in front of the player's facing, with a gentle forward toss. Yaw
        // comes from the cached PlayerStateC2S (camera convention: front = (cos, 0, sin));
        // pitch is ignored (flat toss like drops).
        float yawRad = (float) Math.toRadians(sp.yaw());
        float fx = (float) Math.cos(yawRad);
        float fz = (float) Math.sin(yawRad);
        Vector3f pos = new Vector3f(sp.x() + fx * 1.5f, sp.y() + 1.0f, sp.z() + fz * 1.5f);
        Vector3f vel = new Vector3f(fx * 2.0f, 1.0f, fz * 2.0f);
        NoiseBus.emitIn(world, pos, Loudness.DROP_ITEM); // mobs can hear it land (#321)

        BlockType bt = BlockType.getById(itemId);
        if (bt != null && bt != BlockType.AIR) {
            BlockDrop drop = BlockDrop.createDropWithVelocity(world, pos, bt, vel);
            drop.setStackCount(count);
            drop.setPickupDelay(TOSS_PICKUP_DELAY_SECONDS);
            em.addEntity(drop);
        } else {
            Item item = com.stonebreak.items.ItemType.getById(itemId);
            if (item == null) {
                return; // unknown id — refuse quietly
            }
            ItemDrop drop = ItemDrop.createDropWithVelocity(world, pos,
                    new ItemStack(item, count, state), vel);
            drop.setPickupDelay(TOSS_PICKUP_DELAY_SECONDS);
            em.addEntity(drop);
        }
    }

    /**
     * Closes out a carried stack (the {@code DEPOSIT_CARRIED} container action, and the
     * save path's safety net): whatever fits goes into the replica's 36 slots, the
     * remainder spawns as a drop at the player, and the cursor ends empty — a carried
     * stack can never strand on save/rejoin (issue #307: closing a screen never destroys
     * items). Without a spawnable world the remainder stays carried instead of being
     * destroyed. Returns true when a carried stack was present (the action counts as
     * accepted).
     */
    public static boolean depositCarriedToInventoryOrWorld(ServerPlayer sp, ServerInventory inv,
                                                           ServerWorldContext ctx) {
        ItemStack carried = inv.carried();
        if (carried.isEmpty()) {
            return false;
        }
        int added = inv.addStack(carried);
        if (added > 0) {
            carried.decrementCount(added);
        }
        if (!carried.isEmpty()) {
            // Same guard as the action path (handleContainerAction): without a spawnable
            // world the drop cannot happen, so the remainder stays carried rather than
            // being silently destroyed — a later deposit/drain retries.
            if (ctx.world() == null || ctx.entityManager() == null || sp.lastStateNs() == 0L) {
                return true;
            }
            spawnTossDrop(sp, ctx, carried.getBlockTypeId(), carried.getCount(), carried.getState());
        }
        inv.setCarried(ItemStackStateCodec.empty());
        return true;
    }

    /**
     * Returns EVERYTHING a closing inventory screen holds (issue #334): the carried stack,
     * then the 2×2 craft grid cells in order — the order of the client's close prediction
     * ({@code InventoryController.returnHeldItemsToPlayer}). Each goes into the 36 slots by the
     * normal stacking rule and whatever does not fit drops at the player; without a spawnable
     * world a remainder stays where it was (never destroyed). Used by {@code DEPOSIT_CARRIED}
     * on the inventory screen and by the leave paths (disconnect, takeover, shutdown) — the
     * grid is never persisted, so anything left there would be lost. Returns true when
     * anything was held.
     */
    public static boolean returnHeldToInventoryOrWorld(ServerPlayer sp, ServerInventory inv,
                                                       ServerWorldContext ctx) {
        boolean held = depositCarriedToInventoryOrWorld(sp, inv, ctx);
        boolean spawnable = ctx.world() != null && ctx.entityManager() != null
                && sp.lastStateNs() != 0L;
        for (int i = 0; i < ServerInventory.CRAFT_GRID_SIZE; i++) {
            ItemStack cell = inv.craftSlot(i);
            if (cell.isEmpty()) {
                continue;
            }
            held = true;
            int added = inv.addStack(cell);
            if (added > 0) {
                cell.decrementCount(added);
            }
            if (!cell.isEmpty() && spawnable) {
                spawnTossDrop(sp, ctx, cell.getBlockTypeId(), cell.getCount(), cell.getState());
                cell.decrementCount(cell.getCount());
            }
            if (cell.isEmpty()) {
                inv.setCraftSlot(i, ItemStackStateCodec.empty());
            }
        }
        return held;
    }

    /**
     * Player selected a hotbar slot (number keys / scroll / UI). The server stores the
     * selection; the held item is derived from the replica, so tool-specific drops and the
     * shears check read a server-derived value (issue #334). Out-of-range slots are refused
     * with a sync.
     */
    public void handleHotbarSelect(ServerPlayer sp, HotbarSelectC2S h, ServerWorldContext ctx) {
        if (h.slot() < 0 || h.slot() >= 9) {
            sp.send(sp.inventorySync(), false);
            return;
        }
        sp.inventory().selectHotbarSlot(h.slot());
        // No broadcast here (issue #336): the tick announces the derived held item once per
        // change ({@link #broadcastHeldItemChanges}), however fast the client flips slots.
    }

    /**
     * Announces every in-world player's held item that changed since its last announcement —
     * a hotbar switch, or an item picked up into / used up from the selected slot. Once per
     * tick, so a client cycling its selection costs at most one broadcast per tick.
     */
    public void broadcastHeldItemChanges(ServerWorldContext ctx) {
        for (ServerPlayer sp : ctx.players()) {
            if (!sp.handshakeDone()) {
                continue;
            }
            int held = sp.takeHeldItemChange();
            if (held != Integer.MIN_VALUE) {
                ctx.broadcast(new PlayerHeldItemS2C(sp.playerId(), held), false);
            }
        }
    }

    /**
     * The player ate one item from the selected hotbar slot (issue #355): debit the replica
     * with the client's depletion auto-switch, then sync. Answered like a container action —
     * accepted or refused, the sync acknowledges the seq, so a refusal corrects the mirror.
     */
    public void handleConsumeHeld(ServerPlayer sp, ConsumeHeldC2S c) {
        sp.recordActionSeq(c.seq());
        sp.inventory().consumeHeldFood(c.itemId());
        sp.send(sp.inventorySync(), false);
    }

    /** Container action from an open furnace / workbench / inventory screen. */
    public void handleContainerAction(ServerPlayer sp, ContainerActionC2S a, ServerWorldContext ctx) {
        // Every action is answered with a sync acknowledging it, accepted or refused (#353).
        sp.recordActionSeq(a.seq());
        // Toss/craft overflow inside the processor spawns entities; without a spawnable
        // world every action is refused whole so no debit can outrun its spawn.
        if (ctx.world() == null || ctx.entityManager() == null || sp.lastStateNs() == 0L) {
            sp.send(sp.inventorySync(), false);
            return;
        }
        if (containerActions.apply(sp, a, ctx)) {
            sp.queueInventorySync(); // one sync per drain however many actions (a drag)
        }
        // A refusal already echoed the sync + container state from the processor.
    }

    /** Bring a newly-joined client up to date on every existing player's held item. */
    public void onPeerJoined(ServerPlayer joined, ServerWorldContext ctx) {
        for (ServerPlayer other : ctx.players()) {
            if (other.playerId() == joined.playerId()) {
                continue;
            }
            joined.send(new PlayerHeldItemS2C(other.playerId(), other.inventory().heldItemId()), false);
        }
    }

    /**
     * Re-send the full player roster (join + held item, current positions) to a resyncing
     * client — players aren't entities, so the entity resync alone can't restore a missing
     * remote-player figure. Joins are idempotent client-side.
     */
    public void onPeerResync(ServerPlayer sp, ServerWorldContext ctx) {
        Vector3f spawn = ctx.spawn();
        for (ServerPlayer other : ctx.players()) {
            if (other.playerId() == sp.playerId() || !other.handshakeDone()) {
                continue;
            }
            boolean reported = other.lastStateNs() != 0L;
            sp.send(new PlayerJoinS2C(other.playerId(), other.username(),
                reported ? other.x() : spawn.x,
                reported ? other.y() : spawn.y,
                reported ? other.z() : spawn.z), false);
            sp.send(new PlayerHeldItemS2C(other.playerId(), other.inventory().heldItemId()), false);
        }
    }

    // ─── Validation ──────────────────────────────────────────────────────────────

    private static boolean validateAndCache(ServerPlayer sp, PlayerStateC2S ps, ServerWorldContext ctx,
                                            long receivedNs) {
        if (!isValidPosition(ps.x(), ps.y(), ps.z())
            || !Float.isFinite(ps.yaw()) || !Float.isFinite(ps.pitch())) {
            return false;
        }
        // Movement limit (issue #336): a jump further than the player could have travelled
        // is refused and the client put back, instead of every far-away report generating a
        // view of chunks there. Legitimate teleports are exempt: respawn (checked in
        // handlePlayerLife), world load (the first report), /teleport (cheats only).
        if (!movementAllowed(sp, ps.x(), ps.z(), ctx, receivedNs)) {
            correct(sp, receivedNs);
            return false;
        }
        sp.updateState(ps.x(), ps.y(), ps.z(), ps.yaw(), ps.pitch());
        sp.setMovementFlags(ps.flags()); // sprinting is what server-side mobs notice (#340)
        return true;
    }

    /**
     * Whether a reported horizontal move to {@code (x, z)} is accepted (issue #336). The host
     * and cheat-enabled worlds are exempt; a player's first report must be near the position
     * the server seeded it at (its saved one, or the spawn); after that the move spends the
     * player's {@link com.stonebreak.network.server.MovementGuard} budget.
     */
    static boolean movementAllowed(ServerPlayer sp, float x, float z, ServerWorldContext ctx,
                                   long nowNs) {
        if (sp.isLocal() || ctx.cheatsEnabled()) {
            return true;
        }
        if (sp.lastStateNs() == 0L) {
            return com.stonebreak.network.server.MovementGuard.horizontalDistance(sp.x(), sp.z(), x, z)
                    <= com.stonebreak.network.server.MovementGuard.FIRST_REPORT_SLACK;
        }
        return sp.movement().allowMove(sp.x(), sp.z(), x, z, nowNs);
    }

    /** A respawn must land at the world spawn (where the client's respawn puts it). */
    private static boolean respawnAllowed(ServerPlayer sp, float x, float z, ServerWorldContext ctx) {
        Vector3f spawn = ctx.spawn();
        if (sp.isLocal() || ctx.cheatsEnabled() || spawn == null) {
            return true;
        }
        return com.stonebreak.network.server.MovementGuard.horizontalDistance(spawn.x, spawn.z, x, z)
                <= com.stonebreak.network.server.MovementGuard.RESPAWN_SLACK;
    }

    /** Puts the client back where the server holds it (rate-limited by the guard). */
    private static void correct(ServerPlayer sp, long nowNs) {
        if (sp.movement().shouldCorrect(nowNs)) {
            sp.send(new com.stonebreak.network.packet.player.PlayerPositionS2C(sp.x(), sp.y(), sp.z()), false);
        }
    }

    private static boolean isValidPosition(float x, float y, float z) {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)
            && y >= -64f && y <= WorldConfiguration.WORLD_HEIGHT + 64f;
    }
}
