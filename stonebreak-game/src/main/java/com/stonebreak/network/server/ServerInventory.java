package com.stonebreak.network.server;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemStackStateCodec;

/**
 * The server's authoritative replica of one player's inventory: 36 slots
 * (0–8 hotbar, 9–35 main — the same layout {@code Inventory} serializes), the
 * stack carried on the container-screen cursor, the selected hotbar slot, and
 * the ephemeral 2×2 inventory-screen crafting grid (never persisted).
 *
 * <p>Every client-supplied inventory mutation is validated against and applied
 * to this replica; the client's {@code Inventory} is a mirror kept in sync with
 * {@link com.stonebreak.network.packet.player.InventorySyncS2C}. Headless and
 * GL-free so it is directly unit-testable.
 */
public final class ServerInventory {

    public static final int HOTBAR_SIZE = 9;
    public static final int MAIN_SIZE = 27;
    public static final int TOTAL_SLOTS = 36;
    /** The inventory screen's crafting grid is 2×2 (see InventoryLayoutCalculator). */
    public static final int CRAFT_GRID_SIZE = 4;

    private final ItemStack[] slots = new ItemStack[TOTAL_SLOTS];
    private final ItemStack[] craftGrid = new ItemStack[CRAFT_GRID_SIZE];
    private ItemStack carried = ItemStackStateCodec.empty();
    private int selectedHotbarSlot = 0;

    public ServerInventory() {
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            slots[i] = ItemStackStateCodec.empty();
        }
        for (int i = 0; i < CRAFT_GRID_SIZE; i++) {
            craftGrid[i] = ItemStackStateCodec.empty();
        }
    }

    // ─── Slots ──────────────────────────────────────────────────────────────────

    /** Live slot reference — callers within the server package may mutate in place. */
    public ItemStack slot(int index) {
        if (index < 0 || index >= TOTAL_SLOTS) {
            return ItemStackStateCodec.empty();
        }
        return slots[index];
    }

    public void setSlot(int index, ItemStack stack) {
        if (index < 0 || index >= TOTAL_SLOTS) {
            return;
        }
        slots[index] = normalized(stack);
    }

    public ItemStack craftSlot(int index) {
        if (index < 0 || index >= CRAFT_GRID_SIZE) {
            return ItemStackStateCodec.empty();
        }
        return craftGrid[index];
    }

    public void setCraftSlot(int index, ItemStack stack) {
        if (index < 0 || index >= CRAFT_GRID_SIZE) {
            return;
        }
        craftGrid[index] = normalized(stack);
    }

    public ItemStack[] craftGrid() {
        return craftGrid;
    }

    private static ItemStack normalized(ItemStack stack) {
        return (stack == null || stack.isEmpty()) ? ItemStackStateCodec.empty()
                : ItemStackStateCodec.clamp(stack);
    }

    // ─── Carried (cursor) stack ─────────────────────────────────────────────────

    public ItemStack carried() {
        return carried;
    }

    public void setCarried(ItemStack stack) {
        this.carried = normalized(stack);
    }

    // ─── Hotbar selection ───────────────────────────────────────────────────────

    public int selectedHotbarSlot() {
        return selectedHotbarSlot;
    }

    /** Clamps to 0..8; returns true when the value changed. */
    public boolean selectHotbarSlot(int slot) {
        int clamped = Math.max(0, Math.min(HOTBAR_SIZE - 1, slot));
        if (clamped != selectedHotbarSlot) {
            selectedHotbarSlot = clamped;
            return true;
        }
        return false;
    }

    /** The block/item id in the selected hotbar slot; 0 (air) when empty. */
    public int heldItemId() {
        ItemStack s = slots[selectedHotbarSlot];
        return s.isEmpty() ? BlockType.AIR.getId() : s.getBlockTypeId();
    }

    // ─── Count / debit / add ────────────────────────────────────────────────────

    /** Total count of {@code itemId} across all 36 slots. */
    public int countOf(int itemId) {
        int total = 0;
        for (ItemStack s : slots) {
            if (!s.isEmpty() && s.getBlockTypeId() == itemId) {
                total += s.getCount();
            }
        }
        return total;
    }

    /**
     * Removes {@code count} of {@code itemId} (hotbar first, mirroring
     * {@code Inventory.removeItem}). Returns false when the replica does not hold
     * enough — the caller must treat false as "nothing was removed".
     */
    public boolean debit(int itemId, int count) {
        if (itemId == BlockType.AIR.getId() || count <= 0) {
            return count == 0;
        }
        if (countOf(itemId) < count) {
            return false;
        }
        int remaining = count;
        for (int i = 0; i < TOTAL_SLOTS && remaining > 0; i++) {
            ItemStack s = slots[i];
            if (!s.isEmpty() && s.getBlockTypeId() == itemId) {
                int take = Math.min(remaining, s.getCount());
                s.decrementCount(take);
                remaining -= take;
                if (s.isEmpty()) {
                    slots[i] = ItemStackStateCodec.empty();
                }
            }
        }
        return true;
    }

    /**
     * Adds {@code count} of {@code itemId} (state-less stacks), stacking first,
     * then empty slots — the same order {@code Inventory.addItemsAndReturnCount}
     * uses so the mirror converges. Returns the number actually added.
     */
    public int add(int itemId, int count) {
        return add(itemId, count, null);
    }

    public int add(int itemId, int count, String state) {
        if (itemId == BlockType.AIR.getId() || count <= 0) {
            return 0;
        }
        Item item = resolveItem(itemId);
        if (item == null) {
            return 0;
        }
        ItemStack probe = new ItemStack(item, 1, state);
        int maxStack = probe.getMaxStackSize();
        int remaining = count;

        for (ItemStack s : slots) {
            if (remaining == 0) break;
            if (!s.isEmpty() && s.getBlockTypeId() == itemId
                    && java.util.Objects.equals(s.getState(), state)
                    && s.getCount() < maxStack) {
                int canAdd = Math.min(remaining, maxStack - s.getCount());
                s.incrementCount(canAdd);
                remaining -= canAdd;
            }
        }
        for (int i = 0; i < TOTAL_SLOTS && remaining > 0; i++) {
            if (slots[i].isEmpty()) {
                int canAdd = Math.min(remaining, maxStack);
                slots[i] = new ItemStack(item, canAdd, state);
                remaining -= canAdd;
            }
        }
        return count - remaining;
    }

    /** Item ids never collide between blocks and items; items win the lookup. */
    private static Item resolveItem(int itemId) {
        Item item = com.stonebreak.items.ItemType.getById(itemId);
        return item != null ? item : BlockType.getById(itemId);
    }

    /**
     * Adds an entire stack (preserving its item + state), stacking first, then empty
     * slots. Returns the number actually added; the caller owns any remainder.
     */
    public int addStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        return add(stack.getBlockTypeId(), stack.getCount(), stack.getState());
    }

    // ─── Wire encoding (InventorySyncS2C / seeding) ─────────────────────────────

    /**
     * Encodes the full replica state: 36 slot tokens, the carried token, the 4 inventory-screen
     * craft-grid tokens, the selected hotbar slot and the derived held id, {@code '|'}-joined —
     * {@link ItemStackStateCodec} tokens (the FurnaceState/WorkbenchState convention).
     */
    public String encode() {
        StringBuilder sb = new StringBuilder(TOTAL_SLOTS * 10);
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            if (i > 0) sb.append('|');
            sb.append(ItemStackStateCodec.encode(slots[i]));
        }
        sb.append('|').append(ItemStackStateCodec.encode(carried));
        for (ItemStack s : craftGrid) {
            sb.append('|').append(ItemStackStateCodec.encode(s));
        }
        sb.append('|').append(selectedHotbarSlot);
        sb.append('|').append(heldItemId());
        return sb.toString();
    }

    /** Applies a payload produced by {@link #encode()}; malformed fields become empty. */
    public void applyEncoded(String encoded) {
        if (encoded == null) {
            return;
        }
        String[] parts = encoded.split("\\|", -1);
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            slots[i] = i < parts.length
                    ? ItemStackStateCodec.clamp(ItemStackStateCodec.decode(parts[i]))
                    : ItemStackStateCodec.empty();
        }
        carried = parts.length > TOTAL_SLOTS
                ? ItemStackStateCodec.clamp(ItemStackStateCodec.decode(parts[TOTAL_SLOTS]))
                : ItemStackStateCodec.empty();
        for (int i = 0; i < CRAFT_GRID_SIZE; i++) {
            int idx = TOTAL_SLOTS + 1 + i;
            craftGrid[i] = parts.length > idx
                    ? ItemStackStateCodec.clamp(ItemStackStateCodec.decode(parts[idx]))
                    : ItemStackStateCodec.empty();
        }
        int selIdx = TOTAL_SLOTS + 1 + CRAFT_GRID_SIZE;
        if (parts.length > selIdx) {
            try {
                selectedHotbarSlot = Math.max(0, Math.min(HOTBAR_SIZE - 1,
                        Integer.parseInt(parts[selIdx].trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        // The final field is the derived held id — informational, recomputed live.
    }

    /** Seeds the replica from a save-model inventory array (36 entries, may be shorter). */
    public void loadFrom(java.util.List<ItemStack> inventory) {
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            ItemStack s = inventory != null && i < inventory.size() ? inventory.get(i) : null;
            slots[i] = normalized(s);
        }
    }

    /** Clears every slot, the carried stack and the craft grid. */
    public void clear() {
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            slots[i] = ItemStackStateCodec.empty();
        }
        for (int i = 0; i < CRAFT_GRID_SIZE; i++) {
            craftGrid[i] = ItemStackStateCodec.empty();
        }
        carried = ItemStackStateCodec.empty();
    }
}
