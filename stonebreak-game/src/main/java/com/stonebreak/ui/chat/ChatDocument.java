package com.stonebreak.ui.chat;

import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.contracts.ChatContracts;
import com.stonebreak.ui.runtime.screens.DocumentScreen;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;
import com.stonebreak.ui.runtime.screens.PresentationSlot;
import com.stonebreak.ui.runtime.screens.PresentedDocument;
import com.stonebreak.ui.runtime.screens.ScreenPresentation;

import java.util.function.Supplier;

/**
 * The chat's presentation (#301): the shipped {@code ui/documents/chat.sbui} in the HUD states.
 * {@link ChatSystem} stays the controller: the keyboard still goes to it, the document takes the chat's
 * pointer input (tabs, scrollbars, command buttons, the emoji button and picker) through
 * {@code stonebreak:chat} actions.
 */
public final class ChatDocument {

    /** The shipped document: {@code ui/documents/chat.sbui}. */
    public static final String DOCUMENT_ID = "chat";

    private final PresentationSlot slot = new PresentationSlot();
    private boolean installed;

    public void setPresentation(ScreenPresentation p) {
        installed = true;
        slot.install(p);
    }

    /** Main thread, every frame: the chat is drawn in the HUD states. */
    public void sync(boolean visible) {
        if (visible && !installed) {
            setPresentation(new PresentedDocument(DOCUMENT_ID, DocumentScreenHost.get(), DocumentScreen.Options.hud()));
        }
        slot.setVisible(visible);
    }

    /**
     * Paints the chat document at the chat's place in the HUD: publishes the chat first (lines fade on
     * the wall clock). A chat with nothing to show (closed, every line faded) paints nothing.
     */
    public void paint(Supplier<ChatSystem> chat, int width, int height) {
        if (!slot.showing() || !ChatContracts.shows(chat.get())) {
            return;
        }
        GameUiHost.get().chat().poll();
        slot.paint(width, height);
    }
}
