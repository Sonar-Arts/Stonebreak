package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.network.packet.chat.ChatMessageC2S;
import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Chat lines starting with '/' that name no SERVER command relay as chat, as they did before
 * issue #334 added server-side commands — they are not swallowed as "Unknown server command".
 */
class ServerChatRelayTest {

    @Test
    void anUnownedSlashLineIsNotConsumed() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 4);
        assertFalse(new ServerCommandHandler().handleCommand(sp, "/shrug hi", mock(ServerWorldContext.class)));
    }

    @Test
    void anUnownedSlashLineIsRelayedToEveryone() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 4);
        sp.setUsername("Ann");
        ServerWorldContext ctx = mock(ServerWorldContext.class);

        new ServerChatHandler().handleChat(sp, new ChatMessageC2S("/shrug hi"), ctx);

        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(ctx).broadcast(captor.capture(), anyBoolean());
        ChatMessageS2C relayed = (ChatMessageS2C) captor.getValue();
        assertEquals("/shrug hi", relayed.text());
        assertEquals("Ann", relayed.senderName());
    }
}
