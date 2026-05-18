package com.stonebreak.ui.chat.emoji;

public enum GifEmojiType implements ChatEmoji {
    PROBLEM("problem", "/ui/textChat/emojiGIFS/Problem.gif");

    public final String id;
    public final String resourcePath;
    public final String token;

    GifEmojiType(String id, String resourcePath) {
        this.id = id;
        this.resourcePath = resourcePath;
        this.token = "[" + id + "]";
    }

    @Override public String getId() { return id; }
    @Override public String getToken() { return token; }
    @Override public boolean isAnimated() { return true; }
}
