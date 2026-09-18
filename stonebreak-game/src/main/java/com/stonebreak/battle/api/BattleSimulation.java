package com.stonebreak.battle.api;

/** A running battle: view + input + the clock. Implemented by {@code com.stonebreak.battle.BattleState}. */
public interface BattleSimulation extends BattleView, BattleInput {
    /**
     * Advances the battle by {@code dt} seconds, then publishes every event raised since the previous
     * update (including those raised by input calls) as {@link #frameEvents()}.
     */
    void update(float dt);
}
