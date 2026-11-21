package com.stonebreak.blocks.workbench;

import com.stonebreak.blocks.container.SlotGridRegistry;

/** The world's crafting-table grids (issue #307); see {@link SlotGridRegistry}. */
public class WorkbenchStateRegistry extends SlotGridRegistry<WorkbenchState> {

    public WorkbenchStateRegistry() {
        super(WorkbenchState::new, WorkbenchState::isWorkbenchState);
    }
}
