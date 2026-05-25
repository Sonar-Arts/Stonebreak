package com.openmason.engine.net.transport;

import com.openmason.engine.net.client.ClientConnection;
import com.openmason.engine.net.client.ClientInboundQueue;
import com.openmason.engine.net.client.NetworkClient;
import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.openmason.engine.net.protocol.PacketDirection;
import com.openmason.engine.net.protocol.PacketRegistry;
import com.openmason.engine.net.protocol.ProtocolPhase;
import com.openmason.engine.net.server.NetworkServer;
import com.openmason.engine.net.server.ServerConnection;
import com.openmason.engine.net.server.ServerInboundQueue;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * §5b — in-JVM Local-loopback integration: a server (Local listener only) and a client in
 * one process exchange packets both ways with no sockets, verifying POJO passthrough,
 * connection lifecycle, and the queue hand-off. Packets are drained on the test thread.
 */
@Timeout(5)
class LocalLoopbackTest {

    record Msg(int seq, String text) implements Packet {}

    private static final PacketCodec<Msg> MSG_CODEC = new PacketCodec<>() {
        @Override public void encode(ByteBuf out, Msg m) {
            out.writeInt(m.seq());
            ByteBufIO.writeString(out, m.text(), ByteBufIO.MAX_CHAT_CHARS);
        }
        @Override public Msg decode(ByteBuf in) {
            return new Msg(in.readInt(), ByteBufIO.readString(in, ByteBufIO.MAX_CHAT_CHARS));
        }
    };

    @Test
    void localLoopbackDeliversBothDirectionsInOrder() throws Exception {
        PacketRegistry reg = new PacketRegistry();
        // Local transport never serializes, but register for parity with the TCP path.
        reg.register(ProtocolPhase.HANDSHAKE, PacketDirection.SERVERBOUND, 1, Msg.class, MSG_CODEC);
        reg.register(ProtocolPhase.HANDSHAKE, PacketDirection.CLIENTBOUND, 1, Msg.class, MSG_CODEC);

        NetAddress local = NetAddress.local("loopback-" + System.nanoTime());
        NetworkServer server = new NetworkServer(reg);
        NetworkClient client = new NetworkClient(reg);
        try {
            server.start(local, null);
            ClientConnection conn = client.connect(local);

            // client → server
            conn.send(new Msg(1, "a"));
            conn.send(new Msg(2, "b"));
            conn.send(new Msg(3, "c"));

            List<String> serverGot = new ArrayList<>();
            ServerConnection[] serverSide = {null};
            pollUntil(() -> {
                server.inboundQueue().drain(e -> {
                    if (e.kind() == ServerInboundQueue.Kind.CONNECT) {
                        serverSide[0] = e.connection();
                    } else if (e.kind() == ServerInboundQueue.Kind.PACKET) {
                        serverGot.add(((Msg) e.packet()).text());
                    }
                });
                return serverGot.size() >= 3;
            });
            assertEquals(List.of("a", "b", "c"), serverGot);
            assertNotNull(serverSide[0], "server should have observed a CONNECT");

            // server → client
            serverSide[0].send(new Msg(10, "x"));
            serverSide[0].send(new Msg(11, "y"));

            List<String> clientGot = new ArrayList<>();
            pollUntil(() -> {
                client.inboundQueue().drain(e -> {
                    if (e.kind() == ClientInboundQueue.Kind.PACKET) {
                        clientGot.add(((Msg) e.packet()).text());
                    }
                });
                return clientGot.size() >= 2;
            });
            assertEquals(List.of("x", "y"), clientGot);
        } finally {
            client.shutdown();
            server.shutdown();
        }
    }

    static void pollUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 4_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Condition not met within timeout");
    }
}
