package com.stonebreak.ui.characterCreation;

public enum CharacterCreationTab {
    BACKGROUND,
    ABILITY_SCORE,
    TALENTS,
    LOOKS;

    public String displayName() {
        return switch (this) {
            case BACKGROUND     -> "Background";
            case ABILITY_SCORE  -> "Ability Score";
            case TALENTS        -> "Talents";
            case LOOKS          -> "Looks";
        };
    }
}
