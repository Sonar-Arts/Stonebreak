package com.stonebreak.network.server.handlers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemStackStateCodec;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import com.stonebreak.network.packet.player.DeathDropC2S;
import com.stonebreak.network.packet.player.DropItemC2S;
import com.stonebreak.network.packet.player.HotbarSelectC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.player.PlayerHeldItemS2C;
import com.stonebreak.network.packet.player.PlayerJoinS2C;
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
        boolean firstReport = sp.lastStateNs() == 0L;
        if (!validateAndCache(sp, ps)) {
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
            sp.send(new InventorySyncS2C(sp.inventory().encode()), false);
            return;
        }
        int count = Math.min(d.count(), MAX_DROP_COUNT);
        if (!sp.inventory().debit(d.itemId(), count)) {
            sp.send(new InventorySyncS2C(sp.inventory().encode()), false);
            return;
        }
        spawnTossDrop(sp, ctx, d.itemId(), count, null);
        sp.send(new InventorySyncS2C(sp.inventory().encode()), false);
    }

    /**
     * Death scatter (issue #334): one request replaces the per-stack toss burst. The server
     * spawns a drop for every stack its OWN replica holds — 36 slots, craft grid, carried
     * cursor — without consuming toss tokens (a death legitimately exceeds the toss
     * budget), then clears the replica and syncs. Nothing is client-supplied. When the
     * world/entities aren't spawnable yet, nothing is drained and the sync returns the
     * inventory intact — no debit without a spawn.
     */
    public void handleDeathDrop(ServerPlayer sp, DeathDropC2S d, ServerWorldContext ctx) {
        ServerInventory inv = sp.inventory();
        if (ctx.world() == null || ctx.entityManager() == null || sp.lastStateNs() == 0L) {
            sp.send(new InventorySyncS2C(inv.encode()), false);
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
        sp.send(new InventorySyncS2C(inv.encode()), false);
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
     * Player selected a hotbar slot (number keys / scroll / UI). The server stores the
     * selection; the held item is derived from the replica, so tool-specific drops and the
     * shears check read a server-derived value (issue #334). Out-of-range slots are refused
     * with a sync.
     */
    public void handleHotbarSelect(ServerPlayer sp, HotbarSelectC2S h, ServerWorldContext ctx) {
        if (h.slot() < 0 || h.slot() >= 9) {
            sp.send(new InventorySyncS2C(sp.inventory().encode()), false);
            return;
        }
        sp.inventory().selectHotbarSlot(h.slot());
        // Always broadcast: the client also re-sends the selection when the ITEM in the
        // selected slot changed (pickup into the held slot), not just the slot itself.
        ctx.broadcast(new PlayerHeldItemS2C(sp.playerId(), sp.inventory().heldItemId()), false);
    }

    /** Container action from an open furnace / workbench / inventory screen. */
    public void handleContainerAction(ServerPlayer sp, ContainerActionC2S a, ServerWorldContext ctx) {
        // Toss/craft overflow inside the processor spawns entities; without a spawnable
        // world every action is refused whole so no debit can outrun its spawn.
        if (ctx.world() == null || ctx.entityManager() == null || sp.lastStateNs() == 0L) {
            sp.send(new InventorySyncS2C(sp.inventory().encode()), false);
            return;
        }
        if (containerActions.apply(sp, a, ctx)) {
            sp.send(new InventorySyncS2C(sp.inventory().encode()), false);
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
