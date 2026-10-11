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
 * Where the chat is shown (#301): the shipped {@code ui/documents/chat.sbui} in the HUD states, else the
 * legacy {@link SkijaChatRenderer} (not shipped, refused, {@code -Dstonebreak.ui.legacy=chat}).
 * {@link ChatSystem} stays the controller: the keyboard still goes to it, the document takes the chat's
 * pointer input (tabs, scrollbars, command buttons, the emoji button and picker) through
 * {@code stonebreak:chat} actions, and the legacy pointer router stands down while it shows.
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

    public boolean showing() {
        return slot.showing();
    }

    /**
     * Paints the chat document where the legacy chat was drawn: publishes the chat first (lines fade on
     * the wall clock). @return true when the document handled it (a chat with nothing to show paints nothing)
     */
    public boolean paint(Supplier<ChatSystem> chat, int width, int height) {
        if (!slot.showing()) {
            return false;
        }
        ChatSystem c = chat.get();
        if (!ChatContracts.shows(c)) {
            return true; // closed and every line faded: the legacy renderer drew nothing either
        }
        GameUiHost.get().chat().poll();
        return slot.paint(width, height);
    }
}
