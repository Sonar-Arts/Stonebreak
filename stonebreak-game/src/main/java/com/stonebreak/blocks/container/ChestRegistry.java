package com.stonebreak.blocks.container;

/** The world's chests; see {@link SlotGridRegistry}. */
public class ChestRegistry extends SlotGridRegistry<ChestState> {

    public ChestRegistry() {
        super(ChestState::new, ChestState::isChestState);
    }
}
