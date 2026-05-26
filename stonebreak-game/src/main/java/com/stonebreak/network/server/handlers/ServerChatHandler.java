package com.stonebreak.network.server.handlers;

import com.stonebreak.network.packet.chat.ChatMessageC2S;
import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;

/**
 * Server-authoritative chat relay. Receives {@link ChatMessageC2S}, attaches the sender's
 * name, and broadcasts {@link ChatMessageS2C} to <b>everyone</b> — including the sender, whose
 * client renders it on receipt. In the two-world model the local (host) player is a normal
 * client, so it submits chat the same way; there is no host special case and no server-side
 * local echo (that would double-print for the local client).
 */
public final class ServerChatHandler {

    public void handleChat(ServerPlayer sp, ChatMessageC2S c, ServerWorldContext ctx) {
        String text = sanitize(c.text());
        if (text.isEmpty()) {
            return;
        }
        ctx.broadcast(new ChatMessageS2C(sp.playerId(), sp.username(), text), false);
    }

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("[\\p{Cntrl}]", "").trim();
        return cleaned.length() > 256 ? cleaned.substring(0, 256) : cleaned;
    }
}
