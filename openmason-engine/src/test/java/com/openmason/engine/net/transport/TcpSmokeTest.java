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

import java.net.InetSocketAddress;
import java.util.Random;

import static com.openmason.engine.net.transport.LocalLoopbackTest.pollUntil;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * §5c — TCP smoke test: bind an ephemeral port, connect a real NioSocketChannel, and
 * round-trip a chunk-sized packet in both directions. Exercises the full remote pipeline —
 * length framing, the packet encoder/decoder, and {@link ByteBufIO} bounds.
 */
@Timeout(5)
class TcpSmokeTest {

    record Blob(byte[] data) implements Packet {}

    private static final PacketCodec<Blob> BLOB_CODEC = new PacketCodec<>() {
        @Override public void encode(ByteBuf out, Blob b) {
            ByteBufIO.writeByteArray(out, b.data(), ByteBufIO.MAX_CHUNK_BYTES);
        }
        @Override public Blob decode(ByteBuf in) {
            return new Blob(ByteBufIO.readByteArray(in, ByteBufIO.MAX_CHUNK_BYTES));
        }
    };

    @Test
    void tcpRoundTripsAChunkSizedPacket() throws Exception {
        PacketRegistry reg = new PacketRegistry();
        // Channels start in PLAY (see NetChannelInitializer); register there so no phase flip is needed.
        reg.register(ProtocolPhase.PLAY, PacketDirection.SERVERBOUND, 1, Blob.class, BLOB_CODEC);
        reg.register(ProtocolPhase.PLAY, PacketDirection.CLIENTBOUND, 1, Blob.class, BLOB_CODEC);

        NetworkServer server = new NetworkServer(reg);
        NetworkClient client = new NetworkClient(reg);
        try {
            server.start(null, NetAddress.tcpBind(0));
            int port = ((InetSocketAddress) server.tcpBoundAddress()).getPort();
            ClientConnection conn = client.connect(NetAddress.tcp("127.0.0.1", port));

            byte[] payload = new byte[5000];
            new Random(1).nextBytes(payload);
            conn.send(new Blob(payload));

            ServerConnection[] serverSide = {null};
            byte[][] serverGot = {null};
            pollUntil(() -> {
                server.inboundQueue().drain(e -> {
                    if (e.kind() == ServerInboundQueue.Kind.CONNECT) {
                        serverSide[0] = e.connection();
                    } else if (e.kind() == ServerInboundQueue.Kind.PACKET) {
                        serverGot[0] = ((Blob) e.packet()).data();
                    }
                });
                return serverGot[0] != null;
            });
            assertNotNull(serverSide[0], "server should have observed a CONNECT");
            assertArrayEquals(payload, serverGot[0], "server received corrupted chunk payload");

            // echo back
            serverSide[0].send(new Blob(serverGot[0]));

            byte[][] clientGot = {null};
            pollUntil(() -> {
                client.inboundQueue().drain(e -> {
                    if (e.kind() == ClientInboundQueue.Kind.PACKET) {
                        clientGot[0] = ((Blob) e.packet()).data();
                    }
                });
                return clientGot[0] != null;
            });
            assertArrayEquals(payload, clientGot[0], "client received corrupted chunk payload");
        } finally {
            client.shutdown();
            server.shutdown();
        }
    }
}
