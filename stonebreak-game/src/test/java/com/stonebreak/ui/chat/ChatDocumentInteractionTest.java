package com.stonebreak.ui.chat;

import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.ui.UiWallClock;
import com.stonebreak.ui.chat.emoji.EmojiType;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat document's pointer input (#301), in the legacy precedence: it drives the real
 * {@link ChatSystem} through {@code stonebreak:chat} actions. The tabs now hit where they are drawn
 * (the legacy hit-test used unscaled 70 px tabs against 80 x scale drawn ones).
 */
@Tag("regression")
class ChatDocumentInteractionTest {

    @AfterEach
    void releaseClock() {
        UiWallClock.release();
    }

    private static DocumentStage stage(ChatSystem chat, float scale) throws Exception {
        DocumentStage stage = DocumentChatCapture.stage(GameUiDocuments.readScreen(ChatDocument.DOCUMENT_ID), chat,
            1920, 1080, scale);
        stage.host.chat().poll();
        stage.settle();
        return stage;
    }

    private static void press(DocumentStage stage, UiElement el) {
        UiRect r = el.rect();
        stage.clickAt(r.x() + r.width() / 2f, r.y() + r.height() / 2f);
        stage.host.chat().poll();
        stage.settle();
    }

    @Test
    void tabsAndCommandButtonsFillTheInput() throws Exception {
        ChatDocumentGateTest.requireLua();
        ChatSystem chat = ChatFixtures.chat("open");
        try (DocumentStage stage = stage(chat, 1.5f)) {
            press(stage, stage.q("#tab-commands"));
            assertEquals(ChatSystem.ChatTab.COMMANDS, chat.getCurrentTab(), "a scaled tab hits where it is drawn");
            List<UiElement> buttons = stage.view.instance().qAll(".command-button");
            UiElement first = buttons.get(0);
            String label = find(first, "command-name").text("text");
            press(stage, first);
            assertEquals(label + " ", chat.getCurrentInput(), "fills the command, does not run it");
            assertEquals(ChatSystem.ChatTab.CHAT, chat.getCurrentTab());
        }
    }

    @Test
    void theEmojiPickerTogglesFavoritesInsertsAndClosesOnAnyOtherPress() throws Exception {
        ChatDocumentGateTest.requireLua();
        ChatSystem chat = ChatFixtures.chat("open");
        try (DocumentStage stage = stage(chat, 1f)) {
            press(stage, stage.q("#emoji-button"));
            assertTrue(chat.isEmojiPickerOpen());
            UiElement banana = cell(stage, "banana");
            press(stage, find(banana, "cell-star"));
            assertTrue(chat.getEmojiSystem().getFavorites().contains(EmojiType.BANANA), "the star favourites");
            assertTrue(chat.isEmojiPickerOpen(), "and keeps the picker open");
            press(stage, cell(stage, "sword"));
            assertTrue(chat.getCurrentInput().endsWith("[sword]"), chat.getCurrentInput());
            assertFalse(chat.isEmojiPickerOpen(), "inserting closes the picker");

            press(stage, stage.q("#emoji-button"));
            press(stage, stage.q("#tab-commands"));
            assertFalse(chat.isEmojiPickerOpen(), "any other press closes it ...");
            assertEquals(ChatSystem.ChatTab.CHAT, chat.getCurrentTab(), "... and does nothing else");
        }
    }

    /** The first descendant of {@code el} with class {@code cls}. */
    private static UiElement find(UiElement el, String cls) {
        for (UiElement c : el.children()) {
            if (c.hasClass(cls)) {
                return c;
            }
            UiElement deeper = find(c, cls);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private static UiElement cell(DocumentStage stage, String emoji) {
        for (UiElement c : stage.view.instance().qAll(".emoji-cell")) {
            if (find(c, "cell-sprite").prop("params") instanceof com.openmason.engine.format.omui.UiValue.Obj o
                    && o.get("emoji") instanceof com.openmason.engine.format.omui.UiValue.Str s
                    && emoji.equals(s.value()) && !c.isCollapsed()) {
                return c;
            }
        }
        throw new AssertionError("no cell " + emoji);
    }

    @Test
    void theScrollbarStepsOnTheTrackAndFollowsADraggedThumb() throws Exception {
        ChatDocumentGateTest.requireLua();
        ChatSystem chat = ChatFixtures.chat("scrolled"); // 3 lines up of 10: the thumb sits 30 % down the track
        try (DocumentStage stage = stage(chat, 1f)) {
            UiRect track = stage.rect("chat-scrollbar");
            float x = track.x() + track.width() / 2f;
            stage.clickAt(x, track.y() + 2);
            stage.host.chat().poll();
            stage.settle();
            assertEquals(4, chat.getScrollOffset(), "a press above the thumb scrolls up a line");

            UiRect thumb = find(stage.q("#chat-scrollbar"), "thumb").rect();
            float grabY = thumb.y() + thumb.height() / 2f;
            stage.view.pointerMove(x, grabY);
            stage.view.pointerDown(x, grabY);
            stage.view.pointerMove(x, track.y() + track.height() + 40); // past the track's end
            stage.view.pointerUp(x, track.y() + track.height() + 40);
            stage.settle();
            assertEquals(chat.getMaxScroll(), chat.getScrollOffset(), "the thumb follows the pointer to the end");
        }
    }

    @Test
    void aClosedChatNeverTakesThePointer() throws Exception {
        ChatDocumentGateTest.requireLua();
        ChatSystem chat = ChatFixtures.chat("closed");
        try (DocumentStage stage = stage(chat, 1f)) {
            assertEquals("ignore", String.valueOf(stage.q("#root").computedStyle().keyword("picking-mode", "position")));
            assertEquals(null, stage.view.instance().hitTest(100, 1000), "the world behind gets every click");
        }
    }
}
