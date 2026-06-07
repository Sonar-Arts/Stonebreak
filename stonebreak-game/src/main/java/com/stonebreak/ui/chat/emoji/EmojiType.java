package com.stonebreak.ui.chat.emoji;

public enum EmojiType implements ChatEmoji {
    BANANA        ("banana",         "/ui/textChat/emojiPNGS/Banana.png",         EmojiGroup.GENERAL),
    SWORD         ("sword",          "/ui/textChat/emojiPNGS/Sword.png",          EmojiGroup.GENERAL),
    SWORDS_CROSSED("swords_crossed", "/ui/textChat/emojiPNGS/Swords_Crossed.png", EmojiGroup.GENERAL),
    RAGE          ("rage",           "/ui/textChat/emojiPNGS/Rage.png",           EmojiGroup.ABILITIES),
    RAMPAGE       ("rampage",        "/ui/textChat/emojiPNGS/Rampage.png",        EmojiGroup.ABILITIES),
    SKULL_CRUSHER ("skull_crusher",  "/ui/textChat/emojiPNGS/Skull_Crusher.png",  EmojiGroup.ABILITIES);

    public final String id;
    public final String resourcePath;
    public final String token;
    public final EmojiGroup group;

    EmojiType(String id, String resourcePath, EmojiGroup group) {
        this.id           = id;
        this.resourcePath = resourcePath;
        this.token        = "[" + id + "]";
        this.group        = group;
    }

    @Override public String getId() { return id; }
    @Override public String getToken() { return token; }
    @Override public boolean isAnimated() { return false; }
    @Override public EmojiGroup getGroup() { return group; }
}
