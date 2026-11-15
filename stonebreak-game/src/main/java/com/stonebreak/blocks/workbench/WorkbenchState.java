package com.stonebreak.blocks.workbench;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.container.SlotGridState;

/**
 * Per-position crafting-table state: the 3×3 grid of one placed workbench (issue #307), held by
 * {@link WorkbenchStateRegistry}. The crafting output is not stored: it is always recomputed from
 * the grid. State string: {@code workbench:grid=B:5:3|B:0:0|...} (row-major).
 */
public final class WorkbenchState extends SlotGridState {

    public static final String STATE_PREFIX = "workbench:";
    public static final int GRID_SIZE = 3;
    public static final int SLOT_COUNT = GRID_SIZE * GRID_SIZE;

    public WorkbenchState(BlockPos pos) {
        super(STATE_PREFIX, SLOT_COUNT, pos);
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
}
