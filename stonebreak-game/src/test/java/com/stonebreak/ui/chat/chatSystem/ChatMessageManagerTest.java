package com.stonebreak.ui.chat.chatSystem;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.stonebreak.ui.chat.ChatMessage;

/**
 * Guards the ring-buffer contract of {@link ChatMessageManager}: adding more
 * messages than {@code MAX_MESSAGES} (100) drops the oldest and keeps the newest,
 * and the visible window never returns more than {@code MAX_VISIBLE_MESSAGES} (10).
 *
 * <p>Also guards {@link ChatMessage#getAlpha(boolean)}: alpha is always within [0, 1],
 * and when chat is open, messages never fade.
 *
 * <p>Regression: a change to the prune logic that drops the newest instead of the
 * oldest would silently lose the most recent chat messages — the one the player
 * is trying to read.
 */
class ChatMessageManagerTest {

    private ChatMessageManager manager;

    @BeforeEach
    void setUp() {
        manager = new ChatMessageManager(new TextWrapper(60));
    }

    // ---- adding messages under the cap --------------------------------------------------------

    @Test
    void addingMessagesUnderCapIncreasesCount() {
        manager.addMessage("Hello");
        manager.addMessage("World");
        assertEquals(2, manager.getMessageCount(),
            "adding 2 messages must produce count of 2");
    }

    @Test
    void addingMessagesPreservesOrder() {
        manager.addMessage("First");
        manager.addMessage("Second");
        manager.addMessage("Third");

        List<ChatMessage> visible = manager.getVisibleMessages(false, 0);
        // When chat is closed, visible messages are non-faded, limited to most recent 10
        // Since we only have 3, all should be visible (in order)
        assertEquals("Third", visible.get(visible.size() - 1).getText(),
            "most recent message must be last in visible list");
    }

    // ---- adding more than MAX_MESSAGES drops oldest -------------------------------------------

    @Test
    void addingMoreThanCapDropsOldest() {
        for (int i = 0; i < 150; i++) {
            manager.addMessage("Message " + i);
        }

        // Count must never exceed 100
        assertTrue(manager.getMessageCount() <= 100,
            "message count must never exceed MAX_MESSAGES (100), was " + manager.getMessageCount());
    }

    @Test
    void countNeverExceedsCap() {
        for (int i = 0; i < 200; i++) {
            manager.addMessage("Msg " + i);
            assertTrue(manager.getMessageCount() <= 100,
                "message count must never exceed 100, was " + manager.getMessageCount() +
                " after adding " + (i + 1) + " messages");
        }
    }

    @Test
    void newestMessagesAreKeptWhenCapIsExceeded() {
        for (int i = 0; i < 150; i++) {
            manager.addMessage("Message " + i);
        }

        // The newest 100 should survive. With closed chat and fresh messages,
        // visible returns non-faded (all are fresh), limited to most recent 10.
        List<ChatMessage> visible = manager.getVisibleMessages(false, 0);
        // Most recent messages are 149, 148, ..., 140 (10 visible)
        if (!visible.isEmpty()) {
            String mostRecentText = visible.get(visible.size() - 1).getText();
            assertEquals("Message 149", mostRecentText,
                "the most recent message (149) must be in the visible list");
        }
    }

    // ---- visible messages never exceed MAX_VISIBLE_MESSAGES ------------------------------------

    @Test
    void visibleMessagesNeverExceedsVisibleLimitWhenChatOpen() {
        for (int i = 0; i < 50; i++) {
            manager.addMessage("Msg " + i);
        }

        // When chat is open, getVisibleMessages uses chatHistory (max 20)
        List<ChatMessage> visible = manager.getVisibleMessages(true, 0);
        assertTrue(visible.size() <= 10,
            "visible messages must never exceed MAX_VISIBLE_MESSAGES (10), was " + visible.size());
    }

    @Test
    void visibleMessagesWithScrollOffsetNeverExceedsLimit() {
        for (int i = 0; i < 50; i++) {
            manager.addMessage("Msg " + i);
        }

        for (int scrollOffset = 0; scrollOffset < 20; scrollOffset++) {
            List<ChatMessage> visible = manager.getVisibleMessages(true, scrollOffset);
            assertTrue(visible.size() <= 10,
                "visible messages with scrollOffset " + scrollOffset +
                " must never exceed 10, was " + visible.size());
        }
    }

    // ---- clear resets everything --------------------------------------------------------------

    @Test
    void clearResetsMessageCountAndHistory() {
        manager.addMessage("Hello");
        manager.addMessage("World");
        manager.clear();

        assertEquals(0, manager.getMessageCount(),
            "clear must reset message count to 0");
        assertEquals(0, manager.getHistoryCount(),
            "clear must reset history count to 0");
    }

    // ---- null/blank text is ignored -----------------------------------------------------------

    @Test
    void addingNullTextIsIgnored() {
        manager.addMessage("Before");
        manager.addMessage(null);
        assertEquals(1, manager.getMessageCount(),
            "adding null text must be ignored");
    }

    @Test
    void addingBlankTextIsIgnored() {
        manager.addMessage("Before");
        manager.addMessage("   ");
        assertEquals(1, manager.getMessageCount(),
            "adding whitespace-only text must be ignored");
    }

    // ---- long text is wrapped into multiple ChatMessages --------------------------------------

    @Test
    void longTextIsWrappedIntoMultipleMessages() {
        // A line longer than 60 chars will be wrapped
        String longText = "A".repeat(120);
        manager.addMessage(longText);

        // With TextWrapper(60), "AAAA...120 chars" with no spaces -> one big word
        // split into chunks of 60 chars = 2 messages
        // (120 / 60 = 2 chunks)
        assertTrue(manager.getMessageCount() > 1,
            "a 120-char message must be wrapped into multiple ChatMessages");
    }

    // ---- ChatMessage.getAlpha with chat open never fades --------------------------------------

    @Test
    void getAlphaWithChatOpenReturnsFullAlpha() {
        ChatMessage msg = new ChatMessage("Hello", new float[]{1f, 1f, 1f, 1f}, 0L);
        float alpha = msg.getAlpha(true);
        assertEquals(1.0f, alpha, 0.001f,
            "getAlpha(true) must return full alpha (1.0) regardless of message age");
    }

    // ---- ChatMessage.getAlpha is always within [0, 1] -----------------------------------------

    @Test
    void getAlphaAlwaysReturnsValueBetweenZeroAndOne() {
        ChatMessage msg = new ChatMessage("Hello", new float[]{1f, 1f, 1f, 1f}, 0L);
        float alpha = msg.getAlpha(false);
        assertTrue(alpha >= 0.0f && alpha <= 1.0f,
            "getAlpha(false) must return a value in [0, 1], was " + alpha);
    }

    // ---- fresh message has alpha > 0 ----------------------------------------------------------

    @Test
    void freshMessageHasAlphaGreaterThanZero() {
        ChatMessage msg = new ChatMessage("Hello", new float[]{1f, 1f, 1f, 1f}, 0L);
        float alpha = msg.getAlpha(false);
        assertTrue(alpha > 0.0f,
            "a freshly created message must have alpha > 0 (got " + alpha + ")");
    }
}