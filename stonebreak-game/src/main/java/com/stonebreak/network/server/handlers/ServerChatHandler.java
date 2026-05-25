package com.stonebreak.network.server.handlers;

import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;
import com.stonebreak.network.packet.chat.ChatMessageC2S;
import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;

/**
 * Server-authoritative chat relay — successor of the old {@code ChatSynchronizer} HOST path.
 * Receives {@link ChatMessageC2S}, attaches the sender's name, appends to the host's own
 * chat, and broadcasts {@link ChatMessageS2C} to everyone. The host's own submissions enter
 * through {@link #onHostChat}.
 */
public final class ServerChatHandler {

    public void handleChat(ServerPlayer sp, ChatMessageC2S c, ServerWorldContext ctx) {
        String text = sanitize(c.text());
        if (text.isEmpty()) {
            return;
        }
        ChatMessageS2C out = new ChatMessageS2C(sp.playerId(), sp.username(), text);
        appendLocal(out);
        ctx.broadcast(out, false);
    }

    /** Host-originated chat submission (wired from the chat UI in the lifecycle phase). */
    public void onHostChat(String rawText, ServerWorldContext ctx) {
        String text = sanitize(rawText);
        if (text.isEmpty()) {
            return;
        }
        String name = Settings.getInstance().getMultiplayerUsername();
        ChatMessageS2C s2c = new ChatMessageS2C(ServerWorldContext.HOST_PLAYER_ID, name, text);
        appendLocal(s2c);
        ctx.broadcast(s2c, false);
    }

    private static void appendLocal(ChatMessageS2C msg) {
        if (Game.getInstance().getChatSystem() == null) {
            return;
        }
        Game.getInstance().getChatSystem().addMessage("<" + msg.senderName() + "> " + msg.text());
    }

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("[\\p{Cntrl}]", "").trim();
        return cleaned.length() > 256 ? cleaned.substring(0, 256) : cleaned;
    }
}
