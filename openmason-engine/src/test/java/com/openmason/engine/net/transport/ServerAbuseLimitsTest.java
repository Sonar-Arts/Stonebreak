package com.openmason.engine.net.transport;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.openmason.engine.net.protocol.PacketDirection;
import com.openmason.engine.net.protocol.PacketRegistry;
import com.openmason.engine.net.protocol.ProtocolPhase;
import com.openmason.engine.net.server.NetworkServer;
import com.openmason.engine.net.server.ServerConnection;
import com.openmason.engine.net.server.ServerInboundQueue;
import com.openmason.engine.net.server.ServerLimits;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static com.openmason.engine.net.transport.LocalLoopbackTest.pollUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #336: what one remote connection may cost the server. Each test drives a real TCP
 * listener with a hand-rolled socket client that misbehaves (never handshakes, floods, never
 * reads, sends malformed frames) and checks the server closes it.
 */
@Tag("integration")
@Timeout(20)
class ServerAbuseLimitsTest {

    /** The test protocol's handshake: the only packet that admits a connection. */
    record Hello(int n) implements Packet {}
    record Blob(byte[] data) implements Packet {}
    record Kick(String reason) implements Packet {}

    private static final int HELLO_ID = 1;
    private static final int BLOB_ID = 2;

    private static final PacketCodec<Hello> HELLO = new PacketCodec<>() {
        @Override public void encode(ByteBuf out, Hello h) { out.writeInt(h.n()); }
        @Override public Hello decode(ByteBuf in) { return new Hello(in.readInt()); }
    };
    private static final PacketCodec<Blob> BLOB = new PacketCodec<>() {
        @Override public void encode(ByteBuf out, Blob b) {
            ByteBufIO.writeByteArray(out, b.data(), ByteBufIO.MAX_CHUNK_BYTES);
        }
        @Override public Blob decode(ByteBuf in) {
            return new Blob(ByteBufIO.readByteArray(in, ByteBufIO.MAX_CHUNK_BYTES));
        }
    };
    private static final PacketCodec<Kick> KICK = new PacketCodec<>() {
        @Override public void encode(ByteBuf out, Kick k) {
            ByteBufIO.writeString(out, k.reason(), ByteBufIO.MAX_REASON_CHARS);
        }
        @Override public Kick decode(ByteBuf in) {
            return new Kick(ByteBufIO.readString(in, ByteBufIO.MAX_REASON_CHARS));
        }
    };

    private static PacketRegistry registry() {
        PacketRegistry reg = new PacketRegistry();
        reg.register(ProtocolPhase.PLAY, PacketDirection.SERVERBOUND, HELLO_ID, Hello.class, HELLO);
        reg.register(ProtocolPhase.PLAY, PacketDirection.SERVERBOUND, BLOB_ID, Blob.class, BLOB);
        reg.register(ProtocolPhase.PLAY, PacketDirection.CLIENTBOUND, 1, Blob.class, BLOB);
        reg.register(ProtocolPhase.PLAY, PacketDirection.CLIENTBOUND, 2, Kick.class, KICK);
        return reg;
    }

    /** Tight limits so each test trips them quickly. */
    private static ServerLimits limits(int perAddress, long admissionMillis, int idleSeconds,
                                       long maxOutbound, double packetsPerSecond, double packetBurst) {
        return new ServerLimits(64, perAddress, admissionMillis, idleSeconds, maxOutbound,
                64 * 1024, packetsPerSecond, packetBurst, 1024 * 1024, 4L * 1024 * 1024, 1024, 256);
    }

    private NetworkServer server;
    private final List<Socket> sockets = new ArrayList<>();

    private int start(ServerLimits limits) throws InterruptedException {
        server = new NetworkServer(registry(), limits);
        server.setAdmissionFilter(p -> p instanceof Hello);
        server.setKickPacketFactory(Kick::new);
        server.start(null, NetAddress.tcpBind(0));
        return ((InetSocketAddress) server.tcpBoundAddress()).getPort();
    }

    private Socket connect(int port) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        sockets.add(s);
        return s;
    }

    @AfterEach
    void tearDown() {
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        if (server != null) {
            server.shutdown();
        }
    }

    /** One length-prefixed frame: varint id + body (+ {@code trailing} junk bytes). */
    private static byte[] frame(int id, byte[] body, int trailing) {
        ByteBuf payload = Unpooled.buffer();
        ByteBufIO.writeVarInt(payload, id);
        payload.writeBytes(body);
        payload.writeZero(trailing);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int len = payload.readableBytes();
        out.write(len >>> 24);
        out.write(len >>> 16);
        out.write(len >>> 8);
        out.write(len);
        byte[] bytes = new byte[len];
        payload.readBytes(bytes);
        payload.release();
        out.writeBytes(bytes);
        return out.toByteArray();
    }

    private static byte[] hello(int n) {
        return frame(HELLO_ID, new byte[] {(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n}, 0);
    }

    /** True when the server closed the socket within {@code millis} (reads to EOF, discarding data). */
    private static boolean closedWithin(Socket s, int millis) throws IOException {
        s.setSoTimeout(millis);
        InputStream in = s.getInputStream();
        byte[] buf = new byte[8192];
        long deadline = System.currentTimeMillis() + millis;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (in.read(buf) < 0) {
                    return true;
                }
            }
            return false;
        } catch (SocketTimeoutException stillOpen) {
            return false;
        } catch (IOException reset) {
            return true; // connection reset = closed
        }
    }

    /** Drains the server queue into a list (envelope kinds + packets). */
    private List<ServerInboundQueue.Envelope> drain() {
        List<ServerInboundQueue.Envelope> out = new ArrayList<>();
        server.inboundQueue().drain(out::add);
        return out;
    }

    @Test
    void strangerThatNeverHandshakesIsClosedAtTheAdmissionDeadline() throws Exception {
        int port = start(limits(8, 200, 60, 1 << 20, 600, 2400));
        Socket s = connect(port);

        assertTrue(closedWithin(s, 3000), "a silent connection must not be held open");
    }

    @Test
    void packetsBeforeTheHandshakeAreDropped() throws Exception {
        int port = start(limits(8, 5000, 60, 1 << 20, 600, 2400));
        Socket s = connect(port);
        OutputStream out = s.getOutputStream();
        out.write(frame(BLOB_ID, new byte[] {3, 1, 2, 3}, 0)); // a stranger's world traffic
        out.write(hello(7));
        out.flush();

        List<Packet> packets = new ArrayList<>();
        pollUntil(() -> {
            for (ServerInboundQueue.Envelope e : drain()) {
                if (e.kind() == ServerInboundQueue.Kind.PACKET) {
                    packets.add(e.packet());
                }
            }
            return !packets.isEmpty();
        });
        assertEquals(List.of(new Hello(7)), packets, "only the handshake reaches the tick");
        assertFalse(closedWithin(s, 300), "an admitted connection stays open");
    }

    @Test
    void connectionsOverThePerAddressCapAreRefused() throws Exception {
        int port = start(limits(2, 5000, 60, 1 << 20, 600, 2400));
        Socket a = connect(port);
        Socket b = connect(port);
        a.getOutputStream().write(hello(1));
        b.getOutputStream().write(hello(2));
        // Children go active on different event loops: wait until the server holds both, or
        // the third socket could be counted first and one of these refused instead.
        int[] admitted = {0};
        pollUntil(() -> {
            for (ServerInboundQueue.Envelope e : drain()) {
                if (e.kind() == ServerInboundQueue.Kind.PACKET) {
                    admitted[0]++;
                }
            }
            return admitted[0] == 2;
        });
        Socket c = connect(port);

        assertTrue(closedWithin(c, 3000), "the third connection from one address is refused");
        assertFalse(closedWithin(a, 200), "connections under the cap stay");
        assertFalse(closedWithin(b, 200), "connections under the cap stay");

        a.close();
        Thread.sleep(500); // let the server see the close
        Socket d = connect(port); // a slot freed up
        d.getOutputStream().write(hello(3));
        assertFalse(closedWithin(d, 500), "a closed connection releases its slot");
    }

    @Test
    void floodingClientIsKicked() throws Exception {
        int port = start(limits(8, 5000, 60, 1 << 20, 20, 50));
        Socket s = connect(port);
        OutputStream out = s.getOutputStream();
        ByteArrayOutputStream burst = new ByteArrayOutputStream();
        for (int i = 0; i < 500; i++) {
            burst.writeBytes(hello(i));
        }
        out.write(burst.toByteArray());
        out.flush();

        assertTrue(closedWithin(s, 5000), "a client over the packet rate is kicked");
        int[] packets = {0};
        boolean[] disconnected = {false};
        pollUntil(() -> {
            for (ServerInboundQueue.Envelope e : drain()) {
                if (e.kind() == ServerInboundQueue.Kind.PACKET) {
                    packets[0]++;
                } else if (e.kind() == ServerInboundQueue.Kind.DISCONNECT) {
                    disconnected[0] = true;
                }
            }
            return disconnected[0];
        });
        assertTrue(packets[0] <= 60, "nothing much past the burst reached the tick: " + packets[0]);
    }

    @Test
    void frameWithTrailingBytesClosesTheConnection() throws Exception {
        int port = start(limits(8, 5000, 60, 1 << 20, 600, 2400));
        Socket s = connect(port);
        s.getOutputStream().write(hello(1));
        s.getOutputStream().write(frame(HELLO_ID, new byte[] {0, 0, 0, 2}, 3)); // 3 junk bytes

        assertTrue(closedWithin(s, 3000), "a frame is exactly one packet");
        List<Packet> packets = new ArrayList<>();
        pollUntil(() -> {
            boolean gone = false;
            for (ServerInboundQueue.Envelope e : drain()) {
                if (e.kind() == ServerInboundQueue.Kind.PACKET) {
                    packets.add(e.packet());
                } else if (e.kind() == ServerInboundQueue.Kind.DISCONNECT) {
                    gone = true;
                }
            }
            return gone;
        });
        assertEquals(List.of(new Hello(1)), packets, "the malformed frame decoded to nothing");
    }

    @Test
    void oversizedFrameClosesTheConnection() throws Exception {
        int port = start(limits(8, 5000, 60, 1 << 20, 600, 2400));
        Socket s = connect(port);
        OutputStream out = s.getOutputStream();
        out.write(hello(1));
        int len = 1024 * 1024; // past the 64 KiB serverbound cap of these limits
        out.write(new byte[] {(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len});
        out.write(new byte[16 * 1024]);
        out.flush();

        assertTrue(closedWithin(s, 3000), "a client may not make the server buffer a huge frame");
    }

    @Test
    void silentAdmittedConnectionTimesOut() throws Exception {
        int port = start(limits(8, 5000, 1, 1 << 20, 600, 2400));
        Socket s = connect(port);
        s.getOutputStream().write(hello(1));

        assertTrue(closedWithin(s, 5000), "a connection that sends nothing is closed when idle");
    }

    @Test
    void peerThatNeverReadsIsClosedPastTheOutboundCap() throws Exception {
        int port = start(limits(8, 5000, 60, 512 * 1024, 600, 2400));
        Socket s = new Socket();
        sockets.add(s);
        s.setReceiveBufferSize(16 * 1024);
        s.connect(new InetSocketAddress("127.0.0.1", port));
        s.getOutputStream().write(hello(1));

        ServerConnection[] conn = {null};
        pollUntil(() -> {
            for (ServerInboundQueue.Envelope e : drain()) {
                if (e.kind() == ServerInboundQueue.Kind.PACKET) {
                    conn[0] = e.connection();
                }
            }
            return conn[0] != null;
        });
        assertNotNull(conn[0]);

        // Never-dropped traffic to a client that is not reading: the server must give up on it
        // instead of buffering without bound.
        byte[] payload = new byte[64 * 1024];
        long sent = 0;
        long deadline = System.currentTimeMillis() + 10_000;
        while (conn[0].isActive() && System.currentTimeMillis() < deadline) {
            if (conn[0].send(new Blob(payload), false)) {
                sent += payload.length;
            }
            Thread.sleep(1);
        }
        assertFalse(conn[0].isActive(), "closed after " + sent + " bytes queued");
        assertTrue(sent < 256L * 1024 * 1024, "closed long before the heap filled: " + sent);
    }
}
