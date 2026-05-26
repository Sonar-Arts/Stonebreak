package com.stonebreak.network.client.handlers;

import com.stonebreak.core.Game;
import com.stonebreak.network.packet.chat.ChatMessageS2C;

/**
 * Client-side: appends an authoritative chat broadcast to the local chat history.
 * Successor of the old {@code ChatSynchronizer} CLIENT path.
 */
public final class ClientChatHandler {

    public void apply(ChatMessageS2C msg) {
        if (Game.getInstance().getChatSystem() == null) {
            return;
        }
        Game.getInstance().getChatSystem().addMessage("<" + msg.senderName() + "> " + msg.text());
    }
}
