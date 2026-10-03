package com.stonebreak.blocks.workbench;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemStackStateCodec;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Per-position crafting-table state: the 3×3 grid of one placed workbench. Held by
 * {@link WorkbenchStateRegistry}; persisted in the chunk's block states so items left in
 * the grid survive closing the UI, unloading the chunk and reloading the world (issue #307).
 *
 * <p>The slot array's identity never changes — echoes and chunk re-hydration write into it
 * in place — so an open workbench UI bound to {@link #getSlots()} tracks the block live.
 * The crafting output is not stored: it is always recomputed from the grid.
 *
 * <p>State string format (stored in {@code Chunk.blockStates}):
 * <pre>
 *   workbench:grid=B:5:3|B:0:0|...|I:46:1     (nine {@link ItemStackStateCodec} tokens, row-major)
 * </pre>
 * It names no mesh variant ({@code BlockRenderState.meshVariantKey} projects it to null), so
 * grid edits never remesh the chunk.
 */
public final class WorkbenchState {

    public static final String STATE_PREFIX = "workbench:";
    public static final int GRID_SIZE = 3;
    public static final int SLOT_COUNT = GRID_SIZE * GRID_SIZE;

    private static final String GRID_KEY = "grid=";
    private static final String SLOT_SEPARATOR = "|";

    private final BlockPos pos;
    private final ItemStack[] slots = new ItemStack[SLOT_COUNT];

    /** Slot snapshot taken just before the first server echo since the last poll; null if none. */
    private String preEchoSlots;

    public WorkbenchState(BlockPos pos) {
        this.pos = pos;
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = ItemStackStateCodec.empty();
        }
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
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (i > 0) sb.append(SLOT_SEPARATOR);
            sb.append(ItemStackStateCodec.encode(slots[i]));
        }
        return sb.toString();
    }

    /**
     * Applies a slot snapshot in place. Used both for untrusted client intents on the server
     * (stacks are clamped to their max size) and for re-hydration. Missing or malformed slots
     * become empty.
     */
    public void applySlots(String encoded) {
        String[] parts = encoded != null ? encoded.split("\\|", -1) : new String[0];
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = i < parts.length
                    ? ItemStackStateCodec.clamp(ItemStackStateCodec.decode(parts[i]))
                    : ItemStackStateCodec.empty();
        }
    }

    /* ── Serialization (round-trips through Chunk.blockStates) ──────── */

    /** The value stored under {@code Chunk.blockStates}, prefix included. */
    public String toStateString() {
        return STATE_PREFIX + GRID_KEY + encodeSlots();
    }

    public static boolean isWorkbenchState(String raw) {
        return raw != null && raw.startsWith(STATE_PREFIX);
    }

    /** Parses a value produced by {@link #toStateString()}; anything else yields an empty grid. */
    public static WorkbenchState fromStateString(BlockPos pos, String raw) {
        WorkbenchState s = new WorkbenchState(pos);
        s.loadStateString(raw);
        return s;
    }

    /** Overwrites the grid IN PLACE from a state string (null/foreign → empty grid). */
    public void loadStateString(String raw) {
        applySlots(gridPayload(raw));
    }

    /**
     * Applies an authoritative server echo in place, first remembering what the grid looked
     * like so an open UI can tell its own un-sent edits apart from the server's corrections
     * (see {@link #consumePreEchoSlots()}).
     */
    public void applyStateString(String raw) {
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

    private static String gridPayload(String raw) {
        if (!isWorkbenchState(raw)) return null;
        String body = raw.substring(STATE_PREFIX.length());
        for (String part : body.split(";")) {
            if (part.startsWith(GRID_KEY)) return part.substring(GRID_KEY.length());
        }
        return null;
    }

    /* ── Break ───────────────────────────────────────────────── */

    /**
     * Drops every non-empty slot as an ItemDrop at {@code worldPos} and empties the grid.
     * {@code preferencePoint} (the breaker, may be null) biases where the drops spawn, the
     * same rule block drops follow (issue #225).
     */
    public void dropContentsAt(World world, Vector3f worldPos, Vector3f preferencePoint) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack s = slots[i];
            if (s != null && !s.isEmpty()) {
                DropUtil.createItemDrop(world, worldPos, s.copy(), preferencePoint);
            }
            slots[i] = ItemStackStateCodec.empty();
        }
    }
}
