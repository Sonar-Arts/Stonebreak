package com.stonebreak.ui.chat.emoji;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class ChatEmojiSystem {

    private boolean open = false;
    private final LinkedList<EmojiType> recentlyUsed = new LinkedList<>();
    private final Set<EmojiType> favorites = EnumSet.noneOf(EmojiType.class);

    public void toggle() { open = !open; }
    public void close()  { open = false; }
    public boolean isOpen() { return open; }

    public void onEmojiUsed(EmojiType type) {
        recentlyUsed.remove(type);
        recentlyUsed.addFirst(type);
        close();
    }

    public void toggleFavorite(EmojiType type) {
        if (!favorites.remove(type)) {
            favorites.add(type);
        }
    }

    public List<EmojiType> getRecentlyUsed() {
        return Collections.unmodifiableList(recentlyUsed);
    }

    public Set<EmojiType> getFavorites() {
        return Collections.unmodifiableSet(favorites);
    }
}
