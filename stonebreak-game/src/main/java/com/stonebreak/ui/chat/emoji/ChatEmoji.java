package com.stonebreak.ui.chat.emoji;

public sealed interface ChatEmoji permits EmojiType, GifEmojiType {
    String getId();
    String getToken();
    boolean isAnimated();
}
