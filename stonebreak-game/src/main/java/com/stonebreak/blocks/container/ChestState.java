package com.stonebreak.blocks.container;

import com.openmason.engine.util.BlockPos;

/**
 * A placed chest's 27 slots (three rows of nine). Players open it like a crafting table; a goblin
 * camp keeps its stash in one. State string: {@code chest:grid=<27 tokens>}.
 */
public final class ChestState extends SlotGridState {

    public static final String STATE_PREFIX = "chest:";
    public static final int COLS = 9;
    public static final int ROWS = 3;
    public static final int SLOT_COUNT = COLS * ROWS;

    public ChestState(BlockPos pos) {
        super(STATE_PREFIX, SLOT_COUNT, pos);
    }

    public static boolean isChestState(String raw) {
        return raw != null && raw.startsWith(STATE_PREFIX);
    }
}
