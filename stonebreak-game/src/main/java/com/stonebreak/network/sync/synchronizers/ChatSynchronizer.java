package com.stonebreak.network.sync.synchronizers;

import com.stonebreak.core.Game;
import com.stonebreak.network.protocol.Packet;
import com.stonebreak.network.server.IntegratedServer;
import com.stonebreak.network.server.RemoteClient;
import com.stonebreak.network.sync.SyncContext;
import com.stonebreak.network.sync.SyncEvent;
import com.stonebreak.network.sync.SyncMode;
import com.stonebreak.network.sync.Synchronizer;

/**
 * Routes chat between peers.
 * Client: local submission → ChatMessageC2S to server.
 * Host: receives C2S, attaches sender's username, broadcasts S2C to all.
 *       Local submission goes straight to S2C broadcast.
 * Both: inbound S2C is appended to the local chat history.
 */
public final class ChatSynchronizer implements Synchronizer {

    @Override
    public boolean handlesInbound(Packet packet) {
        return packet instanceof Packet.ChatMessageC2S
                || packet instanceof Packet.ChatMessageS2C;
    }

    @Override
    public void applyInbound(Packet packet, Integer originId, SyncContext ctx) {
        switch (packet) {
            case Packet.ChatMessageC2S c -> {
                if (originId == null) return;
                String name = lookupUsername(originId);
                Packet.ChatMessageS2C out = new Packet.ChatMessageS2C(originId, name, sanitize(c.text()));
                appendLocal(out);
                ctx.broadcast(out);
            }
            case Packet.ChatMessageS2C s -> appendLocal(s);
            default -> {}
        }
    }

    @Override
    public boolean handlesLocal(SyncEvent event) {
        return event instanceof SyncEvent.ChatSubmitted;
    }

    @Override
    public void emitLocal(SyncEvent event, SyncContext ctx) {
        if (!(event instanceof SyncEvent.ChatSubmitted c)) return;
        String text = sanitize(c.text());
        if (text.isEmpty()) return;

        if (ctx.mode() == SyncMode.HOST) {
            String name = com.stonebreak.config.Settings.getInstance().getMultiplayerUsername();
            Packet.ChatMessageS2C s2c = new Packet.ChatMessageS2C(0, name, text);
            appendLocal(s2c);
            ctx.broadcast(s2c);
        } else if (ctx.mode() == SyncMode.CLIENT) {
            ctx.broadcast(new Packet.ChatMessageC2S(text));
            // Optimistic local echo so the sender sees their own message immediately.
            String name = com.stonebreak.config.Settings.getInstance().getMultiplayerUsername();
            appendLocal(new Packet.ChatMessageS2C(ctx.localPlayerId(), name, text));
        }
    }

    private void appendLocal(Packet.ChatMessageS2C msg) {
        if (Game.getInstance().getChatSystem() == null) return;
        Game.getInstance().getChatSystem().addMessage("<" + msg.senderName() + "> " + msg.text());
    }

    private static String sanitize(String text) {
        if (text == null) return "";
        // Strip control chars, cap length.
        String cleaned = text.replaceAll("[\\p{Cntrl}]", "").trim();
        return cleaned.length() > 256 ? cleaned.substring(0, 256) : cleaned;
    }

    private static String lookupUsername(int playerId) {
        // Best-effort: ask the server for the client's name.
        // SyncContext doesn't expose RemoteClient directly, so we go through MultiplayerSession.
        IntegratedServer srv = com.stonebreak.network.MultiplayerSession.getServer();
        if (srv != null) {
            RemoteClient rc = srv.getClient(playerId);
            if (rc != null) return rc.getUsername();
        }
        return "Player" + playerId;
    }
}
