package com.stonebreak.ui.characterCreation;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.rpg.classes.ClassRegistry;

/**
 * Screen-level business logic for character creation. Handlers call in;
 * renderers never do.
 */
public final class CharacterCreationActionHandler {

    private final CharacterCreationStateManager state;

    public CharacterCreationActionHandler(CharacterCreationStateManager state) {
        this.state = state;
    }

    public void goBackToWorldSelect() {
        state.reset();
        Game.getInstance().setState(GameState.WORLD_SELECT);
    }

    public void goToTerrainMapper() {
        Game.getInstance().setState(GameState.TERRAIN_MAPPER);
    }

    public void onAbilityIncrement(int index) {
        state.getCharacterStats().incrementAbilityScore(index);
    }

    public void onAbilityDecrement(int index) {
        state.getCharacterStats().decrementAbilityScore(index);
    }

    public void onSelectClass(int index) {
        if (index < 0 || index >= ClassRegistry.ALL.size()) return;
        state.setSelectedClassIndex(index);
        state.getCharacterStats().selectClass(ClassRegistry.ALL.get(index).id());
    }

    public void onSpendCp(String key) {
        state.getCharacterStats().spendCpOnAbility(key);
    }

    public void onInvestSkill(String skillId) {
        state.getCharacterStats().investSkillPoint(skillId);
    }

    public void onAcquireFeat(String featId) {
        state.getCharacterStats().acquireFeat(featId);
    }

    public void onSelectBackground(String id) {
        state.getCharacterStats().setSelectedBackground(id);
    }
}
