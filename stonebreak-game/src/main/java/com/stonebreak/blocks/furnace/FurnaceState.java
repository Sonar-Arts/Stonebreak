package com.stonebreak.blocks.furnace;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.crafting.SmeltingRecipe;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.util.BlockPos;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Per-position furnace state. Held by {@link FurnaceStateRegistry} for every
 * placed furnace block. Ticks independently of the UI — a furnace continues to
 * smelt while its menu is closed, while the player is across the world, and
 * across world reloads.
 *
 * <p>State string format (stored in {@code Chunk.blockStates}):
 * <pre>
 *   furnace:state=Lit;ing=B:23:1;fuel=B:45:5;out=I:46:2;burn=120;burnTotal=200;cook=45
 * </pre>
 * Each ItemStack is encoded as {@code kind:id:count} where kind is
 * {@code B} (BlockType) or {@code I} (ItemType).
 */
public final class FurnaceState {

    public static final String STATE_PREFIX = "furnace:";
    public static final String STATE_LIT    = "Lit";
    public static final String STATE_UNLIT  = "Unlit";

    private final BlockPos pos;

    private ItemStack ingredient = new ItemStack(0, 0);
    private ItemStack fuel       = new ItemStack(0, 0);
    private ItemStack output     = new ItemStack(0, 0);

    private int burnTimeRemaining    = 0;
    private int currentBurnUnitTotal = 0;
    private int cookProgress         = 0;
    private boolean cooking          = false;

    public FurnaceState(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos getPos() { return pos; }

    public ItemStack getIngredient() { return ingredient; }
    public ItemStack getFuel()       { return fuel; }
    public ItemStack getOutput()     { return output; }

    public void setIngredient(ItemStack s) { this.ingredient = nonNull(s); }
    public void setFuel(ItemStack s)       { this.fuel       = nonNull(s); }
    public void setOutput(ItemStack s)     { this.output     = nonNull(s); }

    public int getBurnTimeRemaining()    { return burnTimeRemaining; }
    public int getCurrentBurnUnitTotal() { return currentBurnUnitTotal; }
    public int getCookProgress()         { return cookProgress; }
    public boolean isCooking()           { return cooking; }
    public boolean isLit()               { return burnTimeRemaining > 0; }

    public float getCookProgressRatio() {
        return (float) cookProgress / SmeltingManager.TICKS_PER_SMELT;
    }

    public float getFuelRatio() {
        if (burnTimeRemaining <= 0 || currentBurnUnitTotal <= 0) return 0f;
        return Math.min(1f, (float) burnTimeRemaining / currentBurnUnitTotal);
    }

    /**
     * Advances smelting by one game-tick worth of {@code dtSeconds}. Returns
     * {@code true} if the lit-state changed during this tick (caller should
     * update the chunk's per-block state so the mesher re-renders the block).
     */
    public boolean tick(SmeltingManager mgr, float dtSeconds) {
        boolean wasLit = isLit();

        boolean recipeReady = !ingredient.isEmpty()
                           && mgr.getRecipe(ingredient) != null
                           && canAcceptOutput();

        if (burnTimeRemaining <= 0 && recipeReady && !fuel.isEmpty()) {
            int perUnit = mgr.getBurnTimePerUnit(fuel.getItem());
            if (perUnit > 0) {
                fuel.decrementCount(1);
                if (fuel.getCount() <= 0) fuel.clear();
                burnTimeRemaining = perUnit;
                currentBurnUnitTotal = perUnit;
            }
        }

        cooking = recipeReady && burnTimeRemaining > 0;
        if (cooking) {
            cookProgress++;
            if (cookProgress >= SmeltingManager.TICKS_PER_SMELT) {
                cookProgress = 0;
                completeSmelt(mgr);
            }
        } else if (!recipeReady) {
            cookProgress = 0;
        }

        if (burnTimeRemaining > 0) {
            burnTimeRemaining--;
            if (burnTimeRemaining <= 0) {
                currentBurnUnitTotal = 0;
            }
        }

        return wasLit != isLit();
    }

    private boolean canAcceptOutput() {
        return true; // matches the old FurnaceController logic — overflow is dropped
    }

    private void completeSmelt(SmeltingManager mgr) {
        SmeltingRecipe recipe = mgr.getRecipe(ingredient);
        if (recipe == null) return;

        ItemStack recipeOutput = recipe.getOutput();
        if (output.isEmpty()) {
            output = recipeOutput.copy();
        } else if (output.canStackWith(recipeOutput)) {
            int canAdd = output.getMaxStackSize() - output.getCount();
            int toAdd = Math.min(canAdd, recipeOutput.getCount());
            output.incrementCount(toAdd);
        }

        ingredient.decrementCount(1);
        if (ingredient.getCount() <= 0) ingredient.clear();
    }

    /** Drops every non-empty slot as an ItemDrop at {@code worldPos}. */
    public void dropContentsAt(World world, Vector3f worldPos) {
        dropStack(world, worldPos, ingredient);
        dropStack(world, worldPos, fuel);
        dropStack(world, worldPos, output);
        ingredient.clear();
        fuel.clear();
        output.clear();
        burnTimeRemaining = 0;
        currentBurnUnitTotal = 0;
        cookProgress = 0;
        cooking = false;
    }

    private static void dropStack(World world, Vector3f pos, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        DropUtil.createItemDrop(world, pos, stack.copy());
    }

    /* ── Serialization (round-trips through Chunk.blockStates) ──────── */

    /** Returns the value to store under {@code Chunk.blockStates} — already
     *  includes the {@value #STATE_PREFIX} prefix. */
    public String toStateString() {
        StringBuilder sb = new StringBuilder(STATE_PREFIX);
        sb.append("state=").append(isLit() ? STATE_LIT : STATE_UNLIT);
        sb.append(";ing=").append(encodeStack(ingredient));
        sb.append(";fuel=").append(encodeStack(fuel));
        sb.append(";out=").append(encodeStack(output));
        sb.append(";burn=").append(burnTimeRemaining);
        sb.append(";burnTotal=").append(currentBurnUnitTotal);
        sb.append(";cook=").append(cookProgress);
        return sb.toString();
    }

    /** Parses a value previously produced by {@link #toStateString()}. */
    public static FurnaceState fromStateString(BlockPos pos, String raw) {
        FurnaceState s = new FurnaceState(pos);
        if (raw == null || !raw.startsWith(STATE_PREFIX)) return s;
        String body = raw.substring(STATE_PREFIX.length());
        for (String part : body.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq);
            String v = part.substring(eq + 1);
            switch (k) {
                case "ing"       -> s.ingredient = decodeStack(v);
                case "fuel"      -> s.fuel       = decodeStack(v);
                case "out"       -> s.output     = decodeStack(v);
                case "burn"      -> s.burnTimeRemaining = parseInt(v);
                case "burnTotal" -> s.currentBurnUnitTotal = parseInt(v);
                case "cook"      -> s.cookProgress = parseInt(v);
                default          -> { /* ignore unknown keys for forward-compat */ }
            }
        }
        return s;
    }

    /**
     * Overwrites this state's fields from a previously-encoded state string,
     * <em>in place</em> (object identity preserved). Used by the multiplayer
     * client to apply an authoritative {@code BlockStateS2C} onto the furnace an
     * open UI is already bound to, so the UI reflects the host without rebinding.
     */
    public void applyStateString(String raw) {
        FurnaceState tmp = fromStateString(pos, raw);
        this.ingredient = tmp.ingredient;
        this.fuel = tmp.fuel;
        this.output = tmp.output;
        this.burnTimeRemaining = tmp.burnTimeRemaining;
        this.currentBurnUnitTotal = tmp.currentBurnUnitTotal;
        this.cookProgress = tmp.cookProgress;
        this.cooking = tmp.cooking;
    }

    /** Public wire helper: encode an ItemStack as {@code kind:id:count}. */
    public static String encodeStackString(ItemStack s) { return encodeStack(s); }

    /** Public wire helper: decode a {@code kind:id:count} string to an ItemStack. */
    public static ItemStack decodeStackString(String v) { return decodeStack(v); }

    /** Returns just the renderable state name ({@code "Lit"} / {@code "Unlit"}). */
    public static String extractRenderState(String raw) {
        if (raw == null || !raw.startsWith(STATE_PREFIX)) return null;
        String body = raw.substring(STATE_PREFIX.length());
        for (String part : body.split(";")) {
            if (part.startsWith("state=")) return part.substring("state=".length());
        }
        return null;
    }

    private static String encodeStack(ItemStack s) {
        if (s == null || s.isEmpty()) return "B:0:0";
        Item item = s.getItem();
        char kind = (item instanceof ItemType) ? 'I' : 'B';
        return kind + ":" + item.getId() + ":" + s.getCount();
    }

    private static ItemStack decodeStack(String v) {
        if (v == null || v.isEmpty()) return new ItemStack(0, 0);
        String[] parts = v.split(":");
        if (parts.length != 3) return new ItemStack(0, 0);
        char kind = parts[0].isEmpty() ? 'B' : parts[0].charAt(0);
        int id    = parseInt(parts[1]);
        int count = parseInt(parts[2]);
        if (count <= 0 || id <= 0) return new ItemStack(0, 0);
        if (kind == 'I') {
            ItemType it = ItemType.getById(id);
            return it != null ? new ItemStack(it, count) : new ItemStack(0, 0);
        }
        BlockType bt = BlockType.getById(id);
        return bt != null ? new ItemStack(bt, count) : new ItemStack(0, 0);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static ItemStack nonNull(ItemStack s) {
        return (s != null && !s.isEmpty()) ? s : new ItemStack(0, 0);
    }
}
