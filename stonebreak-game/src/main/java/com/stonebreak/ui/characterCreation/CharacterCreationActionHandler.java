package com.stonebreak.ui.characterCreation;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.proficiency.WeaponTraining;

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
            // In join mode: disconnect and return to main menu (the one way out, #368)
            state.reset();
            Game.getInstance().leaveWorldToMenu();
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
        state.getCharacterStats().addRank(key);
    }

    /**
     * A right press: takes back one point of what is under the pointer. Nothing is saved before the world
     * is created, so any point spent here can come back. {@code target} is rank (a tree node key), ability
     * (an index), skill (an id), train (a weapon group name) or feat (an id).
     *
     * @return false for an unknown target
     */
    public boolean onRefund(String target, String key) {
        CharacterStats stats = state.getCharacterStats();
        CharacterStats.RpgSnapshot saved = state.getBaseline();
        switch (target) {
            case "rank" -> stats.refundSessionRank(key, saved);
            case "ability" -> {
                try {
                    stats.refundSessionAbilityPoint(Integer.parseInt(key), saved);
                } catch (NumberFormatException ignored) {
                    // not an ability index: no press lands
                }
            }
            case "skill" -> stats.refundSessionSkillPoint(key, saved);
            case "train" -> stats.refundSessionWeaponRank(WeaponTraining.group(key), saved);
            case "feat" -> stats.refundSessionFeat(key, saved);
            default -> {
                return false;
            }
        }
        return true;
    }

    public void onInvestSkill(String skillId) {
        state.getCharacterStats().investSkillPoint(skillId);
    }

    public void onSelectSkillMode(com.stonebreak.rpg.SkillListMode mode) {
        state.setSkillMode(mode);
    }

    /** Spends the starting (or earned) WTP on one proficiency rank. */
    public void onTrainWeapon(com.stonebreak.items.weapon.WeaponGroup group) {
        state.getCharacterStats().trainWeapon(group);
    }

    public void onAcquireFeat(String featId) {
        state.getCharacterStats().acquireFeat(featId);
    }

    public void onSelectBackground(String id) {
        state.getCharacterStats().setSelectedBackground(id);
    }

    public void onSelectHair(String hairId) {
        com.stonebreak.player.PlayerLooks.selectHair(hairId);
    }

    public void onSelectHat(String hatId) {
        com.stonebreak.player.PlayerLooks.selectHat(hatId);
    }

    public void onSelectAccessory(String accessoryId) {
        com.stonebreak.player.PlayerLooks.selectAccessory(accessoryId);
    }
}
