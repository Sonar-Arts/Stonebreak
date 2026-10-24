package com.stonebreak.blocks.furnace;

import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.crafting.SmeltingRecipe;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemStackStateCodec;
import com.openmason.engine.util.BlockPos;
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
 * Each ItemStack is encoded by {@link ItemStackStateCodec} ({@code kind:id:count[:state]}).
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

        if (cooking || wasLit) {
            notifyChanged();
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
     * Server only: progress-only changes ({@code burn}/{@code cook}, which move every tick
     * while cooking) are echoed to clients at most this often. 4 Hz is under a pixel of the
     * furnace UI's arrow/flame per update; contents and lit flips still echo at once.
     */
    static final int PROGRESS_ECHO_TICKS = 5;
    /** Server only: the state string clients last received, and ticks since. */
    private String echoedState;
    private String lastTickState;
    private int ticksSinceEcho;

    /**
     * Server only: whether this tick's {@code fresh} state should be echoed to clients — at
     * once when contents or lit state changed, otherwise every {@link #PROGRESS_ECHO_TICKS}
     * or as soon as progress stops moving (so the final value always arrives).
     */
    boolean echoDue(String fresh) {
        if (fresh.equals(echoedState)) {
            lastTickState = fresh;
            return false;
        }
        boolean settled = fresh.equals(lastTickState);
        lastTickState = fresh;
        if (settled || ++ticksSinceEcho >= PROGRESS_ECHO_TICKS || echoedState == null) {
            return true;
        }
        // Everything before ";burn=" (lit state + the three stacks) is the structural part.
        int cut = fresh.indexOf(";burn=");
        return cut < 0 || !echoedState.regionMatches(0, fresh, 0, cut + 6);
    }

    /** Server only: {@code state} reached clients (echoed, or streamed with the chunk). */
    void markEchoed(String state) {
        echoedState = state;
        lastTickState = state;
        ticksSinceEcho = 0;
    }

    /** Client display copy only: seq of the newest slot intent sent for this furnace (0 = none). */
    private volatile int sentSeq;
    /** Client display copy only: newest {@code BlockStateS2C.ack} received for this furnace. */
    private volatile int ackedSeq;

    /**
     * Records that the UI sent a slot snapshot numbered {@code seq}: until an echo acknowledges
     * it, echoes carry the slots from before it and must not overwrite the local slots.
     */
    public void markSent(int seq) {
        sentSeq = seq;
    }

    /** True while a sent slot snapshot has not been acknowledged by an echo yet. */
    public boolean awaitingAck() {
        return ackedSeq < sentSeq;
    }

    /**
     * Applies a server echo that carries no ack: treated as of the newest ack already seen,
     * so its slots are ignored while a snapshot is in flight (its timers always apply).
     */
    public void applyStateString(String raw) {
        applyStateString(raw, ackedSeq);
    }

    /**
     * Overwrites this state IN PLACE from a state string (server echo) — same object
     * identity, so an open furnace UI bound to this state sees the update live.
     *
     * <p>{@code ack} is the last of OUR slot intents the server had processed when it built
     * the echo. If we have sent a newer one since, the echo's slots are stale (issue #353) —
     * the server will apply our snapshot over them — so only the server-owned timers are
     * taken; adopting the slots would roll back the player's edits (losing a placed stack, or
     * re-filling an output slot already shift-clicked into the inventory).
     */
    public void applyStateString(String raw, int ack) {
        if (ack > ackedSeq) ackedSeq = ack;
        boolean slotsCurrent = ack >= sentSeq;
        // Remember what the slots looked like before this echo clobbers them, so an open UI
        // can tell its own un-sent edits apart from the server's corrections.
        if (slotsCurrent && preEchoSlots == null) preEchoSlots = encodeSlots();
        FurnaceState parsed = fromStateString(pos, raw);
        if (slotsCurrent) {
            this.ingredient = parsed.ingredient;
            this.fuel = parsed.fuel;
            this.output = parsed.output;
        }
        this.burnTimeRemaining = parsed.burnTimeRemaining;
        this.currentBurnUnitTotal = parsed.currentBurnUnitTotal;
        this.cookProgress = parsed.cookProgress;
        this.cooking = parsed.cookProgress > 0;
        notifyChanged();
    }

    /** Called after this state's timers or slots change (tick or server echo); may run on any thread. */
    private volatile Runnable changeListener;

    /**
     * Observes this furnace: an open furnace UI mirrors progress into its data source from here
     * instead of reading the state every frame (#289). One listener; {@code null} detaches.
     */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    private void notifyChanged() {
        Runnable l = changeListener;
        if (l != null) {
            // Runs inside the server's furnace tick or a network echo handler: an observer's
            // failure must never take the furnace (or the connection) down with it.
            try {
                l.run();
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(FurnaceState.class)
                    .warn("Furnace change listener failed at {}", pos, e);
            }
        }
    }

    /** Slot snapshot taken just before the first server echo since the last poll; null if none. */
    private String preEchoSlots;

    /**
     * Returns the slot snapshot from immediately before the first server echo applied since
     * the previous call (null if no echo landed), and resets it. Lets the furnace UI detect
     * that an echo overwrote the slots so it re-baselines instead of re-sending the echoed
     * contents as a fresh edit (which duplicated shift-clicked output).
     */
    public String consumePreEchoSlots() {
        String s = preEchoSlots;
        preEchoSlots = null;
        return s;
    }

    /** Encodes only the three slots ({@code ing|fuel|out}), for the client slot intent. */
    public String encodeSlots() {
        return encodeStack(ingredient) + "|" + encodeStack(fuel) + "|" + encodeStack(output);
    }

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
        return ItemStackStateCodec.encode(s);
    }

    private static ItemStack decodeStack(String v) {
        return ItemStackStateCodec.decode(v);
    }

    private static int parseInt(String s) {
        // Save data may be missing or corrupted; default to 0 to reset furnace progress gracefully.
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static ItemStack nonNull(ItemStack s) {
        return (s != null && !s.isEmpty()) ? s : new ItemStack(0, 0);
    }
}
