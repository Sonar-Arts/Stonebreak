package com.stonebreak.ui.chat.emoji;

public enum EmojiType {
    BANANA        ("banana",         "/ui/textChat/emojiPNGS/Banana.png"),
    SWORD         ("sword",          "/ui/textChat/emojiPNGS/Sword.png"),
    SWORDS_CROSSED("swords_crossed", "/ui/textChat/emojiPNGS/Swords_Crossed.png");

    public final String id;
    public final String resourcePath;
    public final String token;

    EmojiType(String id, String resourcePath) {
        this.id           = id;
        this.resourcePath = resourcePath;
        this.token        = "[" + id + "]";
    }
}
