package com.stonebreak.network.server.handlers;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.blocks.furnace.FurnaceStateRegistry;
import com.stonebreak.blocks.workbench.WorkbenchState;
import com.stonebreak.blocks.workbench.WorkbenchStateRegistry;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.items.InventorySorter;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemStackStateCodec;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.player.StatCreditS2C;
import com.stonebreak.network.packet.world.BlockStateS2C;
import com.stonebreak.network.server.ServerInventory;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Replays {@link ContainerActionC2S} clicks against the SERVER's authoritative state: the
 * player's {@link ServerInventory} (with its own carried-cursor stack) plus the target
 * block's {@link FurnaceState} / {@link WorkbenchState}. Every accepted action ends in an
 * {@link InventorySyncS2C}; every refused one syncs too (refusal = "no mutation") and — for
 * block containers — re-sends the container's current {@link BlockStateS2C} so an
 * over-optimistic client reverts.
 *
 * <p>The rules mirror the client's slot handlers (InventorySlotManager /
 * FurnaceInputManager / InventoryDragDropHandler): pick up, place, merge, swap (with
 * drag-origin return), split, one-at-a-time deposit, shift-move, double-click gather,
 * middle-click balance, sort, and — critically — output slots that are TAKE-only with the
 * craft/smelting result computed SERVER-side, so two players at one container can no longer
 * both take the output and no client can write an output slot.
 */
public final class ContainerActionProcessor {

    private static final float MAX_REACH_SQ = 8.0f * 8.0f;
    /** Furnace slots are one-stack-each; grids too — the client rules never stack past max. */
    private static final int MAX_CRAFT_BATCHES = 64;

    private final CraftingManager craftingManager;
    private final com.stonebreak.crafting.SmeltingManager smeltingManager;

    public ContainerActionProcessor(CraftingManager craftingManager,
                                    com.stonebreak.crafting.SmeltingManager smeltingManager) {
        this.craftingManager = craftingManager;
        this.smeltingManager = smeltingManager;
    }

    /**
     * Applies one action. Returns true when accepted (the caller still syncs).
     * On refusal this sends the refusal echoes itself.
     */
    public boolean apply(ServerPlayer sp, ContainerActionC2S a, ServerWorldContext ctx) {
        recordIntent(sp, a, ctx);
        if (!validRanges(a) || !regionsMatchKind(a) || !plausibleOrigin(a)) {
            refuse(sp, a, ctx);
            return false;
        }
        // Output regions are TAKE-only (issue #334): no client may write a furnace output,
        // a workbench result or the inventory craft result. Take-style reads (TAKE_ALL,
        // TAKE_ONE, SPLIT_HALF, TAKE_OUTPUT, QUICK_MOVE) stay legal; everything that writes
        // the slot (PUT_ALL, MERGE, SWAP, DROP_ONE, BALANCE) is refused — including SWAP
        // with an output ORIGIN, which would write the displaced stack back into it.
        if (isOutputRegion(a.region()) && isOutputWrite(a.action())
                || (a.action() == ContainerActionC2S.Action.SWAP
                    && (isOutputRegion(a.region()) || isOutputRegion(a.originRegion())))) {
            refuse(sp, a, ctx);
            return false;
        }

        // Furnace slot restrictions (issue #334): only fuels may enter the fuel slot and
        // only smeltables the ingredient slot. Checked BEFORE any mutation, so a refused
        // deposit leaves container and inventory untouched and the echo re-baselines.
        if (depositsIntoFurnaceInput(a) && !furnaceDepositAllowed(sp, a, ctx)) {
            refuse(sp, a, ctx);
            return false;
        }

        ServerInventory inv = sp.inventory();
        boolean slotless = a.action() == ContainerActionC2S.Action.TAKE_OUTPUT
                || a.action() == ContainerActionC2S.Action.CRAFT_ALL
                || a.action() == ContainerActionC2S.Action.GATHER
                || a.action() == ContainerActionC2S.Action.SORT
                || a.action() == ContainerActionC2S.Action.DEPOSIT_CARRIED
                || a.action() == ContainerActionC2S.Action.DROP_CARRIED;
        SlotView target = viewFor(sp, a, a.region(), a.slot(), ctx);
        SlotView origin = viewFor(sp, a, a.originRegion(), a.originSlot(), ctx);
        if ((!slotless && target == null)
                || (a.originRegion() != ContainerActionC2S.Region.NONE && origin == null)) {
            refuse(sp, a, ctx);
            return false;
        }

        boolean accepted = switch (a.action()) {
            case ContainerActionC2S.Action.TAKE_ALL -> takeAll(inv, target);
            case ContainerActionC2S.Action.PUT_ALL -> putAll(inv, target);
            case ContainerActionC2S.Action.MERGE -> merge(inv, target);
            case ContainerActionC2S.Action.SWAP -> swap(inv, target, origin);
            case ContainerActionC2S.Action.SPLIT_HALF -> splitHalf(inv, target);
            case ContainerActionC2S.Action.TAKE_ONE -> takeOne(inv, target);
            case ContainerActionC2S.Action.DROP_ONE -> dropOne(inv, target);
            case ContainerActionC2S.Action.QUICK_MOVE -> quickMove(sp, inv, a, target, ctx);
            case ContainerActionC2S.Action.TAKE_OUTPUT -> takeOutput(sp, inv, a, target, ctx);
            case ContainerActionC2S.Action.CRAFT_ALL -> craftAll(sp, inv, a, ctx);
            case ContainerActionC2S.Action.GATHER -> gather(inv);
            case ContainerActionC2S.Action.BALANCE -> balance(target);
            case ContainerActionC2S.Action.SORT -> sortInventory(inv);
            // Closing the inventory screen returns its 2×2 grid along with the cursor
            // (issue #334): the grid is ephemeral, so nothing may stay behind in it.
            case ContainerActionC2S.Action.DEPOSIT_CARRIED ->
                    a.containerKind() == ContainerActionC2S.ContainerKind.INVENTORY
                            ? ServerPlayerHandler.returnHeldToInventoryOrWorld(sp, inv, ctx)
                            : ServerPlayerHandler.depositCarriedToInventoryOrWorld(sp, inv, ctx);
            case ContainerActionC2S.Action.DROP_CARRIED -> dropCarried(sp, inv, a, ctx);
            default -> false;
        };
        if (!accepted) {
            refuse(sp, a, ctx);
            return false;
        }
        // Workbench grids are not ticked: an accepted edit persists + echoes explicitly
        // (the chunk write fires the registry's change listener — issue #307, #334). Furnaces
        // persist at once too rather than at their next tick, so every echo built after this
        // action — the ones acknowledging it — carries its slots (issue #353).
        if (a.containerKind() == ContainerActionC2S.ContainerKind.WORKBENCH
                && ctx.world() != null && ctx.world().getWorkbenchRegistry() != null) {
            ctx.world().getWorkbenchRegistry().persist(ctx.world(),
                    new BlockPos(a.x(), a.y(), a.z()));
        } else if (a.containerKind() == ContainerActionC2S.ContainerKind.FURNACE
                && ctx.world() != null && ctx.world().getFurnaceRegistry() != null) {
            ctx.world().getFurnaceRegistry().persist(ctx.world(),
                    new BlockPos(a.x(), a.y(), a.z()));
        }
        // A numbered action is always acknowledged, even when nothing changed: without the
        // ack the sender would treat every later echo of this block as stale.
        if (a.seq() != 0) {
            echoContainer(sp, a, ctx);
        }
        return accepted;
    }

    /**
     * Records a numbered furnace/workbench action (issue #353), accepted or refused, so every
     * later echo of that block to this player carries its seq as the ack. Only cells that hold
     * the named container are recorded, which bounds the per-player map to real containers.
     */
    private static void recordIntent(ServerPlayer sp, ContainerActionC2S a, ServerWorldContext ctx) {
        if (a.seq() != 0 && isContainerCell(a, ctx)) {
            sp.recordSlotIntent(new BlockPos(a.x(), a.y(), a.z()), a.seq());
        }
    }

    /** True when the action's cell holds the furnace/workbench its kind names. */
    private static boolean isContainerCell(ContainerActionC2S a, ServerWorldContext ctx) {
        BlockType expected = switch (a.containerKind()) {
            case ContainerActionC2S.ContainerKind.FURNACE -> BlockType.FURNACE;
            case ContainerActionC2S.ContainerKind.WORKBENCH -> BlockType.WORKBENCH;
            default -> null;
        };
        return expected != null && ctx.world() != null
                && a.y() >= 0 && a.y() < com.stonebreak.world.operations.WorldConfiguration.WORLD_HEIGHT
                && ctx.world().getBlockAt(a.x(), a.y(), a.z()) == expected;
    }

    // ─── Refusal echo: sync, no mutation ─────────────────────────────────────────

    private void refuse(ServerPlayer sp, ContainerActionC2S a, ServerWorldContext ctx) {
        sp.send(sp.inventorySync(), false);
        echoContainer(sp, a, ctx);
    }

    /**
     * Sends the actor the container's current state, stamped with the action's seq (issue
     * #353) so the client adopts it over its prediction. An unreachable container still
     * answers a numbered action from the chunk's state string, so the ack is never lost.
     */
    private void echoContainer(ServerPlayer sp, ContainerActionC2S a, ServerWorldContext ctx) {
        String state = null;
        if (a.containerKind() == ContainerActionC2S.ContainerKind.FURNACE) {
            FurnaceState s = furnaceAt(sp, a, ctx);
            state = s == null ? null : s.toStateString();
        } else if (a.containerKind() == ContainerActionC2S.ContainerKind.WORKBENCH) {
            WorkbenchState s = workbenchAt(sp, a, ctx);
            state = s == null ? null : s.toStateString();
        } else {
            return;
        }
        if (state == null && a.seq() != 0 && isContainerCell(a, ctx)) {
            // The same string every viewer already receives in this block's broadcasts.
            state = ctx.world().getBlockStateAt(a.x(), a.y(), a.z());
        }
        if (state != null && !state.isEmpty()) {
            sp.send(new BlockStateS2C(a.x(), a.y(), a.z(), state, a.seq()), false);
        }
    }

    // ─── Atomic transfer rules (mirror the client slot handlers) ─────────────────

    private boolean takeAll(ServerInventory inv, SlotView slot) {
        ItemStack s = slot.get();
        if (!inv.carried().isEmpty() || s.isEmpty()) {
            return false;
        }
        inv.setCarried(s.copy());
        slot.set(ItemStackStateCodec.empty());
        return true;
    }

    private boolean putAll(ServerInventory inv, SlotView slot) {
        ItemStack carried = inv.carried();
        if (carried.isEmpty() || !slot.get().isEmpty()) {
            return false;
        }
        slot.set(carried.copy());
        inv.setCarried(ItemStackStateCodec.empty());
        return true;
    }

    private boolean merge(ServerInventory inv, SlotView slot) {
        ItemStack carried = inv.carried();
        ItemStack s = slot.get();
        if (carried.isEmpty()) {
            return false;
        }
        if (s.isEmpty()) {
            return putAll(inv, slot);
        }
        if (!s.canStackWith(carried)) {
            return false;
        }
        int canAdd = s.getMaxStackSize() - s.getCount();
        int toAdd = Math.min(canAdd, carried.getCount());
        if (toAdd <= 0) {
            return false;
        }
        s.incrementCount(toAdd);
        carried.decrementCount(toAdd);
        if (carried.isEmpty()) {
            inv.setCarried(ItemStackStateCodec.empty());
        }
        return true;
    }

    /**
     * carried ↔ slot. The displaced stack returns to the drag's ORIGIN slot when the client
     * says there is one (mirroring the client's swap-back prediction); otherwise it stays
     * carried (a furnace-sourced drag keeps the displaced item on the cursor). The origin is
     * always a player slot ({@link #plausibleOrigin}) and is written only while it is empty
     * — an occupied origin (a split pickup left part of the stack there) keeps the
     * displaced stack on the cursor instead of overwriting the remainder.
     */
    private boolean swap(ServerInventory inv, SlotView slot, SlotView origin) {
        ItemStack carried = inv.carried();
        ItemStack s = slot.get();
        if (carried.isEmpty() || s.isEmpty() || sameKind(s, carried)) {
            return false; // swaps only happen between different items (a full stack included)
        }
        ItemStack displaced = s.copy();
        slot.set(carried.copy());
        if (origin != null && origin.get().isEmpty()) {
            origin.set(displaced);
            inv.setCarried(ItemStackStateCodec.empty());
        } else {
            inv.setCarried(displaced);
        }
        return true;
    }

    private boolean splitHalf(ServerInventory inv, SlotView slot) {
        ItemStack s = slot.get();
        if (!inv.carried().isEmpty() || s.isEmpty()) {
            return false;
        }
        int take = (s.getCount() + 1) / 2;
        inv.setCarried(new ItemStack(s.getItem(), take, s.getState()));
        int left = s.getCount() - take;
        if (left <= 0) {
            slot.set(ItemStackStateCodec.empty());
        } else {
            s.setCount(left);
        }
        return true;
    }

    private boolean takeOne(ServerInventory inv, SlotView slot) {
        ItemStack s = slot.get();
        ItemStack carried = inv.carried();
        if (s.isEmpty()) {
            return false;
        }
        if (carried.isEmpty()) {
            inv.setCarried(new ItemStack(s.getItem(), 1, s.getState()));
        } else if (carried.canStackWith(s) && carried.getCount() < carried.getMaxStackSize()) {
            carried.incrementCount(1);
        } else {
            return false;
        }
        s.decrementCount(1);
        if (s.isEmpty()) {
            slot.set(ItemStackStateCodec.empty());
        }
        return true;
    }

    private boolean dropOne(ServerInventory inv, SlotView slot) {
        ItemStack carried = inv.carried();
        if (carried.isEmpty()) {
            return false;
        }
        ItemStack s = slot.get();
        if (s.isEmpty()) {
            slot.set(new ItemStack(carried.getItem(), 1, carried.getState()));
        } else if (s.canStackWith(carried) && s.getCount() < s.getMaxStackSize()) {
            s.incrementCount(1);
        } else {
            return false;
        }
        carried.decrementCount(1);
        if (carried.isEmpty()) {
            inv.setCarried(ItemStackStateCodec.empty());
        }
        return true;
    }

    /** Shift-click: player region ↔ other player region, or container region → player inventory. */
    private boolean quickMove(ServerPlayer sp, ServerInventory inv, ContainerActionC2S a,
                              SlotView slot, ServerWorldContext ctx) {
        ItemStack s = slot.get();
        if (s.isEmpty()) {
            return false;
        }
        byte region = a.region();
        if (region == ContainerActionC2S.Region.PLAYER_HOTBAR
                || region == ContainerActionC2S.Region.PLAYER_MAIN) {
            // hotbar ↔ main: move as much as fits into the other region
            int added = addToRegion(inv, s, region == ContainerActionC2S.Region.PLAYER_HOTBAR
                    ? ContainerActionC2S.Region.PLAYER_MAIN
                    : ContainerActionC2S.Region.PLAYER_HOTBAR);
            if (added <= 0) {
                return false;
            }
            if (s.isEmpty()) {
                slot.set(ItemStackStateCodec.empty());
            }
            return true;
        }
        // Container region → player inventory: whatever fits moves, the remainder stays in
        // its slot — the client's shift-click rule for every container slot, the inventory
        // screen's 2×2 grid included (InventorySlotManager.tryShiftClickCraftingInput). A
        // grid remainder cannot strand: closing the screen (DEPOSIT_CARRIED on the inventory)
        // and leaving the server both return the grid, dropping what does not fit (#334).
        int added = inv.addStack(s);
        if (added <= 0) {
            return false;
        }
        int left = s.getCount() - added;
        if (left > 0) {
            s.setCount(left);
        } else {
            slot.set(ItemStackStateCodec.empty());
        }
        return true;
    }

    /**
     * Output slots. Furnace: the sim already produced the stack — a plain take. Crafting
     * grids: the recipe is matched and its inputs consumed SERVER-side, so two players at
     * one table cannot both take the same output.
     */
    private boolean takeOutput(ServerPlayer sp, ServerInventory inv, ContainerActionC2S a,
                               SlotView slot, ServerWorldContext ctx) {
        byte region = a.region();
        if (region == ContainerActionC2S.Region.FURNACE_OUT) {
            return takeAll(inv, slot);
        }
        if (region != ContainerActionC2S.Region.WORKBENCH_OUT
                && region != ContainerActionC2S.Region.INV_CRAFT_OUT) {
            return false;
        }
        SlotView grid = viewFor(sp, a,
                region == ContainerActionC2S.Region.WORKBENCH_OUT
                        ? ContainerActionC2S.Region.WORKBENCH_GRID
                        : ContainerActionC2S.Region.INV_CRAFT_GRID, a.slot(), ctx);
        if (grid == null) {
            return false;
        }
        ItemStack[] cells = grid.all();
        Recipe recipe = craftingManager.getMatchedRecipe(asGrid(cells));
        if (recipe == null) {
            return false;
        }
        ItemStack output = recipe.getOutput().copy();
        ItemStack carried = inv.carried();
        if (carried.isEmpty()) {
            inv.setCarried(output);
        } else if (carried.canStackWith(output)
                && carried.getCount() + output.getCount() <= carried.getMaxStackSize()) {
            carried.incrementCount(output.getCount());
        } else {
            return false;
        }
        // Consume one unit of every non-empty grid cell (consumeCraftingIngredients rule).
        for (ItemStack cell : cells) {
            if (!cell.isEmpty()) {
                cell.decrementCount(1);
                if (cell.isEmpty()) {
                    cell.clear();
                }
            }
        }
        creditCrafted(sp, output.getCount());
        return true;
    }

    private static void creditCrafted(ServerPlayer sp, int items) {
        if (items > 0) {
            sp.send(new StatCreditS2C(StatCreditS2C.STAT_ITEMS_CRAFTED, items), false);
        }
    }

    /** The Craft All button: repeat TAKE_OUTPUT into the player inventory; overflow drops. */
    private boolean craftAll(ServerPlayer sp, ServerInventory inv, ContainerActionC2S a,
                             ServerWorldContext ctx) {
        // The grid is the one the action's container names: a workbench's, or the inventory
        // screen's 2×2 — never "whichever resolves" (issue #334).
        byte gridRegion = switch (a.containerKind()) {
            case ContainerActionC2S.ContainerKind.WORKBENCH -> ContainerActionC2S.Region.WORKBENCH_GRID;
            case ContainerActionC2S.ContainerKind.INVENTORY -> ContainerActionC2S.Region.INV_CRAFT_GRID;
            default -> ContainerActionC2S.Region.NONE;
        };
        SlotView grid = viewFor(sp, a, gridRegion, 0, ctx);
        if (grid == null) {
            return false;
        }
        int crafted = 0;
        int craftedItems = 0;
        for (int i = 0; i < MAX_CRAFT_BATCHES; i++) {
            ItemStack[] cells = grid.all();
            Recipe recipe = craftingManager.getMatchedRecipe(asGrid(cells));
            if (recipe == null) {
                break;
            }
            ItemStack output = recipe.getOutput().copy();
            int added = inv.addStack(output);
            int overflow = output.getCount() - added;
            if (overflow > 0) {
                ServerPlayerHandler.spawnTossDrop(sp, ctx, output.getBlockTypeId(), overflow,
                        output.getState());
            }
            for (ItemStack cell : cells) {
                if (!cell.isEmpty()) {
                    cell.decrementCount(1);
                    if (cell.isEmpty()) {
                        cell.clear();
                    }
                }
            }
            crafted++;
            craftedItems += output.getCount();
        }
        creditCrafted(sp, craftedItems);
        return crafted > 0;
    }

    /**
     * Double-click gather: matching stacks fill the carried stack — main inventory first,
     * then the hotbar, full stacks included, exactly as the client's
     * {@code InventorySlotManager.gatherMatchingItemsToStack} predicts (issue #334).
     */
    private boolean gather(ServerInventory inv) {
        ItemStack carried = inv.carried();
        if (carried.isEmpty()) {
            return false;
        }
        int max = carried.getMaxStackSize();
        boolean moved = false;
        for (int k = 0; k < ServerInventory.TOTAL_SLOTS && carried.getCount() < max; k++) {
            int i = (k + ServerInventory.HOTBAR_SIZE) % ServerInventory.TOTAL_SLOTS; // main, then hotbar
            ItemStack s = inv.slot(i);
            if (sameKind(s, carried)) {
                int canAdd = Math.min(max - carried.getCount(), s.getCount());
                carried.incrementCount(canAdd);
                s.decrementCount(canAdd);
                if (s.isEmpty()) {
                    inv.setSlot(i, ItemStackStateCodec.empty());
                }
                moved = true;
            }
        }
        return moved;
    }

    /** Middle-click on a crafting cell: even out counts across matching cells. */
    private boolean balance(SlotView grid) {
        ItemStack[] cells = grid.all();
        ItemStack clicked = cells[grid.index()];
        if (clicked == null || clicked.isEmpty()) {
            return false;
        }
        List<Integer> matching = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < cells.length; i++) {
            ItemStack cell = cells[i];
            if (cell != null && sameKind(cell, clicked)) {
                matching.add(i);
                total += cell.getCount();
            }
        }
        int n = matching.size();
        if (n <= 1) {
            return false;
        }
        int base = total / n;
        int remainder = total % n;
        for (int k = 0; k < n; k++) {
            cells[matching.get(k)].setCount(base + (k < remainder ? 1 : 0));
        }
        return true;
    }

    /**
     * Sort button: the client's own pure rule ({@code Inventory.sortInventory} →
     * {@link InventorySorter#sortRegion}) run over the replica — hotbar and main sorted
     * separately (items never cross between them), stacks of the same type + state merged,
     * ordered by category, name, then state — so the result is exactly the client's
     * prediction (issue #334). The hotbar re-selection the client does after sorting reaches
     * the server as its own {@code HotbarSelectC2S}, sent before this action.
     */
    private boolean sortInventory(ServerInventory inv) {
        sortRange(inv, 0, ServerInventory.HOTBAR_SIZE);
        sortRange(inv, ServerInventory.HOTBAR_SIZE, ServerInventory.TOTAL_SLOTS);
        return true; // sorting an already sorted inventory is a valid no-op
    }

    private static void sortRange(ServerInventory inv, int from, int to) {
        ItemStack[] region = new ItemStack[to - from];
        for (int i = 0; i < region.length; i++) {
            region[i] = inv.slot(from + i);
        }
        InventorySorter.sortRegion(region);
        for (int i = 0; i < region.length; i++) {
            inv.setSlot(from + i, region[i]);
        }
    }

    /** Carried stack tossed into the world (container-screen drop beyond the panel). */
    private boolean dropCarried(ServerPlayer sp, ServerInventory inv, ContainerActionC2S a,
                                ServerWorldContext ctx) {
        ItemStack carried = inv.carried();
        if (carried.isEmpty()) {
            return false;
        }
        int count = a.count() > 0 ? Math.min(a.count(), carried.getCount()) : carried.getCount();
        ServerPlayerHandler.spawnTossDrop(sp, ctx, carried.getBlockTypeId(),
                count, carried.getState());
        carried.decrementCount(count);
        if (carried.isEmpty()) {
            inv.setCarried(ItemStackStateCodec.empty());
        }
        return true;
    }

    // ─── Slot views ──────────────────────────────────────────────────────────────

    /** Uniform read/write access to one slot of any region the action can address. */
    private interface SlotView {
        ItemStack get();
        void set(ItemStack stack);
        /** All cells of the region (grid views); single-slot views return a 1-element array. */
        ItemStack[] all();
        /** Index of this view within {@link #all()}. */
        int index();
    }

    /**
     * Resolves a region to a slot view; null when the region is not valid on this screen.
     * The slot index is passed explicitly (the target call passes {@code a.slot()}, the
     * origin call {@code a.originSlot()}) so a drag whose origin differs from the target
     * cell resolves both ends correctly.
     */
    private SlotView viewFor(ServerPlayer sp, ContainerActionC2S a, byte region, int slot,
                             ServerWorldContext ctx) {
        ServerInventory inv = sp.inventory();
        return switch (region) {
            case ContainerActionC2S.Region.PLAYER_HOTBAR -> slotView(inv, slot, 0, ServerInventory.HOTBAR_SIZE,
                i -> inv.slot(i), (i, s) -> inv.setSlot(i, s));
            case ContainerActionC2S.Region.PLAYER_MAIN -> slotView(inv, slot, 0, ServerInventory.MAIN_SIZE,
                i -> inv.slot(ServerInventory.HOTBAR_SIZE + i),
                (i, s) -> inv.setSlot(ServerInventory.HOTBAR_SIZE + i, s));
            case ContainerActionC2S.Region.INV_CRAFT_GRID -> craftView(inv, slot);
            case ContainerActionC2S.Region.INV_CRAFT_OUT -> null; // virtual: never a stored slot
            case ContainerActionC2S.Region.FURNACE_ING -> furnaceView(sp, a, ctx, FurnaceState::getIngredient,
                FurnaceState::setIngredient);
            case ContainerActionC2S.Region.FURNACE_FUEL -> furnaceView(sp, a, ctx, FurnaceState::getFuel,
                FurnaceState::setFuel);
            case ContainerActionC2S.Region.FURNACE_OUT -> furnaceView(sp, a, ctx, FurnaceState::getOutput,
                FurnaceState::setOutput);
            case ContainerActionC2S.Region.WORKBENCH_GRID -> workbenchGridView(sp, a, ctx, slot);
            case ContainerActionC2S.Region.WORKBENCH_OUT -> null;
            default -> null;
        };
    }

    private interface Getter { ItemStack get(FurnaceState s); }
    private interface Setter { void set(FurnaceState s, ItemStack v); }

    private SlotView slotView(ServerInventory inv, int slot, int min, int max,
                              java.util.function.IntFunction<ItemStack> get,
                              java.util.function.BiConsumer<Integer, ItemStack> set) {
        if (slot < min || slot >= max) {
            return null;
        }
        final int idx = slot;
        return new SlotView() {
            @Override public ItemStack get() { return get.apply(idx); }
            @Override public void set(ItemStack stack) { set.accept(idx, stack); }
            @Override public ItemStack[] all() { return new ItemStack[]{get()}; }
            @Override public int index() { return 0; }
        };
    }

    private SlotView craftView(ServerInventory inv, int slot) {
        if (slot < 0 || slot >= ServerInventory.CRAFT_GRID_SIZE) {
            return null;
        }
        final int idx = slot;
        return new SlotView() {
            @Override public ItemStack get() { return inv.craftSlot(idx); }
            @Override public void set(ItemStack stack) { inv.setCraftSlot(idx, stack); }
            @Override public ItemStack[] all() { return inv.craftGrid(); }
            @Override public int index() { return idx; }
        };
    }

    private SlotView furnaceView(ServerPlayer sp, ContainerActionC2S a, ServerWorldContext ctx,
                                 Getter get, Setter set) {
        FurnaceState state = furnaceAt(sp, a, ctx);
        if (state == null) {
            return null;
        }
        return new SlotView() {
            @Override public ItemStack get() { return get.get(state); }
            @Override public void set(ItemStack stack) { set.set(state, stack); }
            @Override public ItemStack[] all() { return new ItemStack[]{get()}; }
            @Override public int index() { return 0; }
        };
    }

    private SlotView workbenchGridView(ServerPlayer sp, ContainerActionC2S a, ServerWorldContext ctx,
                                       int slot) {
        WorkbenchState state = workbenchAt(sp, a, ctx);
        if (state == null || slot < 0 || slot >= WorkbenchState.SLOT_COUNT) {
            return null;
        }
        final int idx = slot;
        ItemStack[] slots = state.getSlots();
        return new SlotView() {
            @Override public ItemStack get() { return slots[idx]; }
            @Override public void set(ItemStack stack) {
                slots[idx] = (stack == null || stack.isEmpty())
                        ? ItemStackStateCodec.empty() : ItemStackStateCodec.clamp(stack);
            }
            @Override public ItemStack[] all() { return slots; }
            @Override public int index() { return idx; }
        };
    }

    // ─── Container resolution (reach + block kind) ───────────────────────────────

    private FurnaceState furnaceAt(ServerPlayer sp, ContainerActionC2S a, ServerWorldContext ctx) {
        World world = ctx.world();
        FurnaceStateRegistry registry = world == null ? null : world.getFurnaceRegistry();
        if (registry == null || a.y() < 0 || a.y() >= com.stonebreak.world.operations.WorldConfiguration.WORLD_HEIGHT) {
            return null;
        }
        if (sp.lastStateNs() != 0L) {
            float dx = (a.x() + 0.5f) - sp.x();
            float dy = (a.y() + 0.5f) - sp.y();
            float dz = (a.z() + 0.5f) - sp.z();
            if (dx * dx + dy * dy + dz * dz > MAX_REACH_SQ) {
                return null;
            }
        }
        if (world.getBlockAt(a.x(), a.y(), a.z()) != BlockType.FURNACE) {
            return null;
        }
        return registry.getOrCreate(new BlockPos(a.x(), a.y(), a.z()));
    }

    private WorkbenchState workbenchAt(ServerPlayer sp, ContainerActionC2S a, ServerWorldContext ctx) {
        World world = ctx.world();
        WorkbenchStateRegistry registry = world == null ? null : world.getWorkbenchRegistry();
        if (registry == null || a.y() < 0 || a.y() >= com.stonebreak.world.operations.WorldConfiguration.WORLD_HEIGHT) {
            return null;
        }
        if (sp.lastStateNs() != 0L) {
            float dx = (a.x() + 0.5f) - sp.x();
            float dy = (a.y() + 0.5f) - sp.y();
            float dz = (a.z() + 0.5f) - sp.z();
            if (dx * dx + dy * dy + dz * dz > MAX_REACH_SQ) {
                return null;
            }
        }
        if (world.getBlockAt(a.x(), a.y(), a.z()) != BlockType.WORKBENCH) {
            return null;
        }
        return registry.getOrCreate(new BlockPos(a.x(), a.y(), a.z()));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private static boolean isOutputRegion(byte region) {
        return region == ContainerActionC2S.Region.FURNACE_OUT
                || region == ContainerActionC2S.Region.WORKBENCH_OUT
                || region == ContainerActionC2S.Region.INV_CRAFT_OUT;
    }

    /** True for the actions that write the carried stack into the target slot. */
    private static boolean depositsCarried(byte action) {
        return action == ContainerActionC2S.Action.PUT_ALL
                || action == ContainerActionC2S.Action.MERGE
                || action == ContainerActionC2S.Action.SWAP
                || action == ContainerActionC2S.Action.DROP_ONE;
    }

    /** Furnace fuel/ingredient target + a carried deposit = the slot restriction applies. */
    private static boolean depositsIntoFurnaceInput(ContainerActionC2S a) {
        return depositsCarried(a.action())
                && (a.region() == ContainerActionC2S.Region.FURNACE_FUEL
                    || a.region() == ContainerActionC2S.Region.FURNACE_ING);
    }

    /**
     * The carried stack about to be deposited must be a fuel (fuel slot) or a smeltable
     * (ingredient slot). Existing slot contents were validated the same way on the way in.
     */
    private boolean furnaceDepositAllowed(ServerPlayer sp, ContainerActionC2S a,
                                          ServerWorldContext ctx) {
        ItemStack carried = sp.inventory().carried();
        if (carried.isEmpty()) {
            return true;
        }
        if (furnaceAt(sp, a, ctx) == null) {
            return false;   // unknown/unreachable furnace — the normal view resolution refuses
        }
        return a.region() == ContainerActionC2S.Region.FURNACE_FUEL
                ? smeltingManager.getBurnTimePerUnit(carried.getItem()) > 0
                : smeltingManager.getRecipe(carried) != null;
    }

    /**
     * The client's stacking identity (issue #334/#350): same item type and same state, no
     * state meaning the item's default ({@link ItemStack#statesMatch}). Unlike
     * {@link ItemStack#canStackWith} a full stack still counts as the same kind.
     */
    private static boolean sameKind(ItemStack s, ItemStack other) {
        return s != null && !s.isEmpty() && other != null && !other.isEmpty()
                && s.getItem().isSameType(other.getItem())
                && ItemStack.statesMatch(s.getItem(), s.getState(), other.getState());
    }

    /**
     * Every addressed region must belong to the action's container (issue #334): player
     * slots are valid on every screen; furnace regions only with FURNACE, workbench regions
     * only with WORKBENCH, the 2×2 grid/result only with INVENTORY. Otherwise a furnace edit
     * sent under kind INVENTORY would skip the persist + acknowledgement.
     */
    private static boolean regionsMatchKind(ContainerActionC2S a) {
        return regionMatchesKind(a.region(), a.containerKind())
                && (a.originRegion() == ContainerActionC2S.Region.NONE
                    || regionMatchesKind(a.originRegion(), a.containerKind()));
    }

    private static boolean regionMatchesKind(byte region, byte kind) {
        return switch (region) {
            case ContainerActionC2S.Region.PLAYER_HOTBAR, ContainerActionC2S.Region.PLAYER_MAIN ->
                    kind == ContainerActionC2S.ContainerKind.FURNACE
                            || kind == ContainerActionC2S.ContainerKind.WORKBENCH
                            || kind == ContainerActionC2S.ContainerKind.INVENTORY;
            case ContainerActionC2S.Region.FURNACE_ING, ContainerActionC2S.Region.FURNACE_FUEL,
                 ContainerActionC2S.Region.FURNACE_OUT ->
                    kind == ContainerActionC2S.ContainerKind.FURNACE;
            case ContainerActionC2S.Region.WORKBENCH_GRID, ContainerActionC2S.Region.WORKBENCH_OUT ->
                    kind == ContainerActionC2S.ContainerKind.WORKBENCH;
            case ContainerActionC2S.Region.INV_CRAFT_GRID, ContainerActionC2S.Region.INV_CRAFT_OUT ->
                    kind == ContainerActionC2S.ContainerKind.INVENTORY;
            default -> false;
        };
    }

    /**
     * A drag ORIGIN is only ever a player slot (the client's {@code originRegionOf} sends
     * hotbar/main or NONE). SWAP writes the displaced stack
     * into the origin, so a furnace origin would bypass the fuel/smeltable restriction
     * (TAKE_ALL the fuel, then SWAP onto dirt with origin FURNACE_FUEL put the dirt in the
     * fuel slot) — refused outright (issue #334).
     */
    private static boolean plausibleOrigin(ContainerActionC2S a) {
        byte origin = a.originRegion();
        if (origin == ContainerActionC2S.Region.NONE) {
            return true;
        }
        return origin == ContainerActionC2S.Region.PLAYER_HOTBAR
                || origin == ContainerActionC2S.Region.PLAYER_MAIN;
    }

    private static boolean isOutputWrite(byte action) {
        return action == ContainerActionC2S.Action.PUT_ALL
                || action == ContainerActionC2S.Action.MERGE
                || action == ContainerActionC2S.Action.DROP_ONE
                || action == ContainerActionC2S.Action.BALANCE;
    }

    private boolean validRanges(ContainerActionC2S a) {
        return a.region() >= 0 && a.region() <= ContainerActionC2S.Region.INV_CRAFT_OUT
                && a.action() >= 0 && a.action() <= ContainerActionC2S.Action.DROP_CARRIED
                && a.count() >= 0
                && (a.originRegion() == ContainerActionC2S.Region.NONE
                    || (a.originRegion() >= 0 && a.originRegion() <= ContainerActionC2S.Region.INV_CRAFT_OUT));
    }

    /** {@code from} region slots added into {@code to} region (hotbar/main only). */
    private int addToRegion(ServerInventory inv, ItemStack stack, byte toRegion) {
        int start = toRegion == ContainerActionC2S.Region.PLAYER_HOTBAR ? 0 : ServerInventory.HOTBAR_SIZE;
        int end = toRegion == ContainerActionC2S.Region.PLAYER_HOTBAR
                ? ServerInventory.HOTBAR_SIZE : ServerInventory.TOTAL_SLOTS;
        int remaining = stack.getCount();
        int max = stack.getMaxStackSize();
        for (int i = start; i < end && remaining > 0; i++) {
            ItemStack s = inv.slot(i);
            if (!s.isEmpty() && s.canStackWith(stack)) {
                int canAdd = Math.min(max - s.getCount(), remaining);
                s.incrementCount(canAdd);
                remaining -= canAdd;
            }
        }
        for (int i = start; i < end && remaining > 0; i++) {
            if (inv.slot(i).isEmpty()) {
                int canAdd = Math.min(remaining, max);
                inv.setSlot(i, new ItemStack(stack.getItem(), canAdd, stack.getState()));
                remaining -= canAdd;
            }
        }
        int added = stack.getCount() - remaining;
        stack.decrementCount(added);
        return added;
    }

    /** Row-major {@code List<List<ItemStack>>} grid for the recipe matcher (null = empty cell). */
    private static List<List<ItemStack>> asGrid(ItemStack[] cells) {
        int size = cells.length == 9 ? 3 : 2;
        List<List<ItemStack>> grid = new ArrayList<>(size);
        for (int r = 0; r < size; r++) {
            List<ItemStack> row = new ArrayList<>(size);
            for (int c = 0; c < size; c++) {
                ItemStack s = cells[r * size + c];
                row.add(s == null || s.isEmpty() ? null : s);
            }
            grid.add(row);
        }
        return grid;
    }
}
