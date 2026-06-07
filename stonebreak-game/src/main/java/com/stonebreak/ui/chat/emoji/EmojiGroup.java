package com.stonebreak.ui.chat.emoji;

/** Named category an emoji belongs to, used to organize the "All" section of the picker. */
public enum EmojiGroup {
    GENERAL("General"),
    ABILITIES("Abilities");

    public final String label;

    EmojiGroup(String label) {
        this.label = label;
    }
}
