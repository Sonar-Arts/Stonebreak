package com.stonebreak.ui.characterCreation;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.network.MultiplayerSession;
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
        if (isJoinMode()) {
            // In join mode: disconnect and return to main menu
            state.reset();
            MultiplayerSession.shutdown();
            Game.getInstance().setState(GameState.MAIN_MENU);
        } else {
            state.reset();
            Game.getInstance().setState(GameState.WORLD_SELECT);
        }
    }

    public void goToTerrainMapper() {
        if (isJoinMode()) {
            // In join mode: submit character creation to server and enter world
            submitCharacterCreation();
        } else {
            Game.getInstance().setState(GameState.TERRAIN_MAPPER);
        }
    }

    /** True when the character creation screen was triggered by a JOIN session. */
    private boolean isJoinMode() {
        return MultiplayerSession.isInMode(MultiplayerSession.Mode.JOIN);
    }

    /** Serializes the current character stats and sends them to the server. */
    private void submitCharacterCreation() {
        byte[] json = state.getCharacterStats().toCreationJson();
        MultiplayerSession.submitCharacterCreation(json);
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

    public void onSelectHat(String hatId) {
        com.stonebreak.player.PlayerLooks.selectHat(hatId);
    }
}
