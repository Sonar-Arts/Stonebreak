package com.stonebreak.blocks.container;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemStackStateCodec;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * A block's grid of item slots — a crafting table's 3×3, a chest's 27 — persisted in its chunk's
 * block states so items left in it survive closing the UI, unloading the chunk and reloading the
 * world (issue #307).
 *
 * <p>The slot array's identity never changes — echoes and chunk re-hydration write into it in
 * place — so an open UI bound to {@link #getSlots()} tracks the block live.
 *
 * <p>State string: {@code <prefix>grid=<token>|<token>|...} ({@link ItemStackStateCodec} tokens).
 * It names no mesh variant ({@code BlockRenderState.meshVariantKey} projects it to null), so slot
 * edits never remesh the chunk.
 */
public class SlotGridState {

    private static final String GRID_KEY = "grid=";
    private static final String SLOT_SEPARATOR = "|";

    private final String prefix;
    private final BlockPos pos;
    private final ItemStack[] slots;

    /** Slot snapshot taken just before the first server echo since the last poll; null if none. */
    private String preEchoSlots;

    /** Client display copy only: seq of the newest slot intent sent for this grid (0 = none). */
    private volatile int sentSeq;
    /** Client display copy only: newest {@code BlockStateS2C.ack} received for this grid. */
    private volatile int ackedSeq;

    /** @param prefix the block's state prefix, with its colon ({@code "chest:"}) */
    public SlotGridState(String prefix, int slotCount, BlockPos pos) {
        this.prefix = prefix;
        this.pos = pos;
        this.slots = new ItemStack[slotCount];
        for (int i = 0; i < slotCount; i++) {
            slots[i] = ItemStackStateCodec.empty();
        }
    }

    public String prefix() { return prefix; }

    public int slotCount() { return slots.length; }

    /** Whether {@code raw} is this kind of grid's state. */
    public boolean isOwnState(String raw) {
        return raw != null && raw.startsWith(prefix);
    }

    public BlockPos getPos() { return pos; }

    /** The live grid (row-major). The UI binds to and edits this array directly. */
    public ItemStack[] getSlots() { return slots; }

    public boolean isEmpty() {
        for (ItemStack s : slots) {
            if (s != null && !s.isEmpty()) return false;
        }
        return true;
    }

    /* ── Slot snapshots (client intent) ─────────────────────── */

    /** Encodes the nine slots, {@code |}-separated. */
    public String encodeSlots() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < slots.length; i++) {
            if (i > 0) sb.append(SLOT_SEPARATOR);
            sb.append(ItemStackStateCodec.encode(slots[i]));
        }
        return sb.toString();
    }

    /**
     * Applies a slot snapshot in place. Used for re-hydration from persisted chunk state
     * (stacks are clamped to their max size); live container edits go through the validated
     * {@code ContainerActionC2S} ops instead. Missing or malformed slots become empty.
     */
    public void applySlots(String encoded) {
        String[] parts = encoded != null ? encoded.split("\\|", -1) : new String[0];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = i < parts.length
                    ? ItemStackStateCodec.clamp(ItemStackStateCodec.decode(parts[i]))
                    : ItemStackStateCodec.empty();
        }
    }

    /* ── Serialization (round-trips through Chunk.blockStates) ──────── */

    /** The value stored under {@code Chunk.blockStates}, prefix included. */
    public String toStateString() {
        return prefix + GRID_KEY + encodeSlots();
    }

    /** Overwrites the grid IN PLACE from a state string (null/foreign → empty grid). */
    public void loadStateString(String raw) {
        applySlots(gridPayload(raw));
    }

    /**
     * Records that the UI sent a slot snapshot numbered {@code seq}: until an echo acknowledges
     * it, echoes describe the grid from before it and must not overwrite the local grid.
     */
    public void markSent(int seq) {
        sentSeq = seq;
    }

    /** True while a sent slot snapshot has not been acknowledged by an echo yet. */
    public boolean awaitingAck() {
        return ackedSeq < sentSeq;
    }

    /**
     * Applies a server echo (or a re-streamed chunk state) that carries no ack: treated as of
     * the newest ack already seen, so it is ignored while a snapshot is in flight.
     */
    public void applyStateString(String raw) {
        applyStateString(raw, ackedSeq);
    }

    /**
     * Applies an authoritative server echo in place, first remembering what the grid looked
     * like so an open UI can tell its own un-sent edits apart from the server's corrections
     * (see {@link #consumePreEchoSlots()}).
     *
     * <p>{@code ack} is the last of OUR slot intents the server had processed when it built
     * the echo. If we have sent a newer one since, the echo is stale (issue #353): the server
     * will apply that snapshot over it, so adopting the echo would roll back edits the player
     * already made — losing an item that left the cursor, or bringing back one that went to
     * the inventory. Its grid is dropped; the echo that acknowledges our latest send corrects us.
     */
    public void applyStateString(String raw, int ack) {
        if (ack > ackedSeq) ackedSeq = ack;
        if (ack < sentSeq) return;
        if (preEchoSlots == null) preEchoSlots = encodeSlots();
        loadStateString(raw);
    }

    /**
     * Returns the slot snapshot from immediately before the first echo applied since the
     * previous call (null if none landed), and resets it. Lets the workbench UI re-baseline
     * after an echo instead of re-sending the echoed contents as a fresh edit.
     */
    public String consumePreEchoSlots() {
        String s = preEchoSlots;
        preEchoSlots = null;
        return s;
    }

    private String gridPayload(String raw) {
        if (!isOwnState(raw)) return null;
        String body = raw.substring(prefix.length());
        for (String part : body.split(";")) {
            if (part.startsWith(GRID_KEY)) return part.substring(GRID_KEY.length());
        }
        return null;
    }

    /**
     * Moves as much of {@code s} as fits into {@code grid} — topping up stacks of the same item
     * and state first, then empty slots — the shift-click into a container, on the server and as
     * the client's prediction alike. {@code s} keeps the rest.
     *
     * @return whether anything moved
     */
    public static boolean fill(ItemStack[] grid, ItemStack s) {
        int before = s.getCount();
        for (ItemStack cell : grid) {
            if (s.isEmpty()) break;
            if (cell != null && !cell.isEmpty() && cell.getItem().isSameType(s.getItem())
                    && ItemStack.statesMatch(cell.getItem(), cell.getState(), s.getState())
                    && cell.getCount() < cell.getMaxStackSize()) {
                int move = Math.min(cell.getMaxStackSize() - cell.getCount(), s.getCount());
                cell.incrementCount(move);
                s.decrementCount(move);
            }
        }
        for (int i = 0; i < grid.length && !s.isEmpty(); i++) {
            if (grid[i] == null || grid[i].isEmpty()) {
                int move = Math.min(s.getMaxStackSize(), s.getCount());
                grid[i] = new ItemStack(s.getItem(), move, s.getState());
                s.decrementCount(move);
            }
        }
        return s.getCount() < before;
    }

    /* ── Break ───────────────────────────────────────────────── */

    /**
     * Drops every non-empty slot as an ItemDrop at {@code worldPos} and empties the grid.
     * {@code preferencePoint} (the breaker, may be null) biases where the drops spawn, the
     * same rule block drops follow (issue #225).
     */
    public void dropContentsAt(World world, org.joml.Vector3dc worldPos, org.joml.Vector3dc preferencePoint) {
        for (int i = 0; i < slots.length; i++) {
            ItemStack s = slots[i];
            if (s != null && !s.isEmpty()) {
                DropUtil.createItemDrop(world, worldPos, s.copy(), preferencePoint);
            }
            slots[i] = ItemStackStateCodec.empty();
        }
    }
}
