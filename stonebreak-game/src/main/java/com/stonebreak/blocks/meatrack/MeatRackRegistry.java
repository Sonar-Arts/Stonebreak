package com.stonebreak.blocks.meatrack;

import com.stonebreak.blocks.container.SlotGridRegistry;

/** The world's meat racks; see {@link SlotGridRegistry}. */
public class MeatRackRegistry extends SlotGridRegistry<MeatRackState> {

    public MeatRackRegistry() {
        super(MeatRackState::new, MeatRackState::isMeatRackState);
    }
}
