/**
 * Focus battle mode contract: the only types shared between the battle model
 * ({@code com.stonebreak.battle}), the cinematic camera ({@code battle.camera}), the stage
 * ({@code battle.stage}) and the HUD ({@code ui.focusBattle}).
 *
 * <p>Everything here is GL-free and immutable or read-only. Consumers read a
 * {@link com.stonebreak.battle.api.BattleView} and the events published for the current frame;
 * they change the battle only through {@link com.stonebreak.battle.api.BattleInput}.
 */
package com.stonebreak.battle.api;
