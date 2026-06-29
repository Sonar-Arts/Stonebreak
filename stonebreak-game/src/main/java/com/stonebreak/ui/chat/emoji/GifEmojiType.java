package com.stonebreak.ui.chat.emoji;

public enum GifEmojiType implements ChatEmoji {
    PROBLEM("problem", "/ui/textChat/emojiGIFS/Problem.gif", EmojiGroup.GENERAL);

    public final String id;
    public final String resourcePath;
    public final String token;
    public final EmojiGroup group;

    GifEmojiType(String id, String resourcePath, EmojiGroup group) {
        this.id = id;
        this.resourcePath = resourcePath;
        this.token = "[" + id + "]";
        this.group = group;
    }

    @Override public String getId() { return id; }
    @Override public String getToken() { return token; }
    @Override public boolean isAnimated() { return true; }
    @Override public EmojiGroup getGroup() { return group; }
}
