package com.stonebreak.ui.chat;

import com.stonebreak.ui.UiWallClock;
import com.stonebreak.ui.chat.emoji.EmojiType;
import com.stonebreak.ui.chat.emoji.GifEmojiType;

import java.util.List;

/**
 * Chat states of the #301 fidelity cases, built through {@link ChatSystem}'s own API on a pinned
 * {@link UiWallClock} (message fades and the GIF emoji's frame follow it). Not a test class.
 */
public final class ChatFixtures {

    private ChatFixtures() {
    }

    /** The pinned instant every case is drawn at (seconds after the pinned epoch). */
    public static final double NOW = 100;

    public static final List<String> VARIANTS = List.of("closed", "open", "scrolled", "commands", "ghost", "picker",
        "picker-empty", "long-input");

    /**
     * The chat of {@code variant}. Leaves the clock pinned at {@link #NOW}: release it with
     * {@link UiWallClock#release()} when done.
     */
    public static ChatSystem chat(String variant) {
        ChatSystem c = new ChatSystem();
        // history: older messages first (their fades differ while the chat is closed)
        UiWallClock.pin(NOW - 9.2, UiWallClock.DEFAULT_SEED);
        c.addMessage("Welcome to Stonebreak!", new float[]{1f, 0.8f, 0.33f, 1f});
        UiWallClock.pin(NOW - 8.5, UiWallClock.DEFAULT_SEED);
        c.addMessage("<Builder_42> anyone near the river? I found a [banana] tree and two [sword] chests, "
            + "come and see before it gets dark");
        UiWallClock.pin(NOW - 4, UiWallClock.DEFAULT_SEED);
        c.addMessage("<Player> [problem] that is a problem");
        c.addMessage("Teleported to 120, 64, -38", new float[]{0.6f, 1f, 0.6f, 1f});
        UiWallClock.pin(NOW - 1, UiWallClock.DEFAULT_SEED);
        for (int i = 1; i <= 9; i++) {
            c.addMessage("<Miner" + i + "> line " + i + " [swords_crossed]");
        }
        UiWallClock.pin(NOW, UiWallClock.DEFAULT_SEED);
        switch (variant) {
            case "closed" -> { }
            case "open" -> {
                c.openChat();
                type(c, "hello [banana] world");
            }
            case "scrolled" -> {
                c.openChat();
                c.handleScroll(1);
                c.handleScroll(1);
                c.handleScroll(1);
            }
            case "commands" -> {
                c.openChat();
                c.setCurrentTab(ChatSystem.ChatTab.COMMANDS);
                c.handleScroll(1);
                c.handleScroll(1);
            }
            case "ghost" -> {
                c.openChat();
                type(c, "/he");
            }
            case "picker" -> {
                c.openChat();
                c.toggleEmojiPicker();
                c.getEmojiSystem().toggleFavorite(EmojiType.SWORD);
                c.insertEmoji(EmojiType.RAGE);
                c.insertEmoji(GifEmojiType.PROBLEM);
                c.toggleEmojiPicker();
            }
            case "picker-empty" -> { // no favorites, no recents: "None yet" twice
                c.openChat();
                c.toggleEmojiPicker();
            }
            case "long-input" -> {
                c.openChat();
                type(c, "this is a very long message that keeps going until it runs well past the right edge "
                    + "of the input box so the text has to scroll left to keep the caret in view");
            }
            default -> throw new IllegalArgumentException("chat variant " + variant);
        }
        return c;
    }

    private static void type(ChatSystem c, String text) {
        for (char ch : text.toCharArray()) {
            c.handleCharInput(ch);
        }
    }
}
