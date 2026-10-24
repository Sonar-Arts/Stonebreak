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

    private final ServerCommandHandler commands = new ServerCommandHandler();

    /** Told to a player whose chat is over the rate limit. */
    static final String TOO_FAST = "You are sending messages too fast.";

    public void handleChat(ServerPlayer sp, ChatMessageC2S c, ServerWorldContext ctx) {
        String text = sanitize(c.text());
        if (text.isEmpty()) {
            return;
        }
        // Every line is rebroadcast to every player (or runs a command): rate-limited per
        // sender (issue #336). Over the limit the line is dropped and only the sender is told.
        if (!sp.allowChat(System.nanoTime())) {
            sp.send(new ChatMessageS2C(sp.playerId(), "Server", TOO_FAST), false);
            return;
        }
        // Server-side commands (/obtain — issue #334): checked against the SERVER's cheats
        // flag, answered to the sender only. Everything else — '/' lines included, as on
        // main before #334 — relays to everyone.
        if (commands.handleCommand(sp, text, ctx)) {
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
