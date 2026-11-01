package com.openmason.engine.net.server;

import com.openmason.engine.net.protocol.Packet;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Broadcasts reach only connections the game admitted (past the handshake). */
class BroadcastFilterTest {

    private record Ping() implements Packet { }

    @Test
    void broadcastsSkipConnectionsTheFilterRejects() {
        ConnectionRegistry registry = new ConnectionRegistry();
        ServerConnection admitted = mock(ServerConnection.class);
        ServerConnection handshaking = mock(ServerConnection.class);
        registry.add(admitted);
        registry.add(handshaking);
        registry.setBroadcastFilter(c -> c == admitted);

        registry.broadcast(new Ping(), false);
        registry.broadcastExcept(mock(ServerConnection.class), new Ping(), false);

        verify(admitted, org.mockito.Mockito.times(2)).send(any(), anyBoolean());
        verify(handshaking, never()).send(any(), anyBoolean());
    }
}
