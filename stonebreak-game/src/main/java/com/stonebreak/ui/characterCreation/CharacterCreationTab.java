package com.stonebreak.ui.characterCreation;

public enum CharacterCreationTab {
    BACKGROUND,
    ABILITY_SCORE,
    CLASS_ABILITIES,
    SKILLS,
    FEATS,
    LOOKS,
    CLOTHING;

    public String displayName() {
        return switch (this) {
            case BACKGROUND     -> "Background";
            case ABILITY_SCORE  -> "Ability Score";
            case CLASS_ABILITIES-> "Class Abilities";
            case SKILLS         -> "Skills";
            case FEATS          -> "Feats";
            case LOOKS          -> "Looks";
            case CLOTHING       -> "Clothing";
        };
    }
}
