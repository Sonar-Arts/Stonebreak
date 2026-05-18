package com.stonebreak.ui.chat.emoji;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class ChatEmojiSystem {

    private boolean open = false;
    private final LinkedList<ChatEmoji> recentlyUsed = new LinkedList<>();
    private final Set<ChatEmoji> favorites = new LinkedHashSet<>();

    public void toggle() { open = !open; }
    public void close()  { open = false; }
    public boolean isOpen() { return open; }

    public void onEmojiUsed(ChatEmoji emoji) {
        recentlyUsed.remove(emoji);
        recentlyUsed.addFirst(emoji);
        close();
    }

    public void toggleFavorite(ChatEmoji emoji) {
        if (!favorites.remove(emoji)) {
            favorites.add(emoji);
        }
    }

    public List<ChatEmoji> getRecentlyUsed() {
        return Collections.unmodifiableList(recentlyUsed);
    }

    public Set<ChatEmoji> getFavorites() {
        return Collections.unmodifiableSet(favorites);
    }
}
