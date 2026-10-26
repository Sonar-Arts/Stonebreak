package com.stonebreak.network.server;

import com.openmason.engine.net.client.NetworkClient;
import com.openmason.engine.net.protocol.ProtocolVersion;
import com.openmason.engine.net.protocol.codec.WireCompression;
import com.openmason.engine.net.transport.NetAddress;
import com.stonebreak.network.StonebreakProtocol;
import com.stonebreak.network.packet.handshake.HandshakeC2S;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issue #347: starting and stopping servers, joining, being refused and failing to connect leave
 * no Netty event-loop threads behind. Event loops shut down gracefully (asynchronously), so the
 * count is given time to settle back to where it started.
 */
@Tag("integration")
@Tag("slow")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class NettyThreadLeakTest {

    private static final int CYCLES = 5;

    @TempDir
    Path home;

    /** Netty's event-loop threads (DefaultThreadFactory names them after their group's class). */
    private static long nettyThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(n -> n.startsWith("nioEventLoopGroup-") || n.startsWith("defaultEventLoopGroup-"))
                .count();
    }

    private static int unusedPort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    @Test
    void serversJoinsRefusalsAndFailedConnectsLeaveNoEventLoopsBehind() throws Exception {
        assumeTrue(ServerHarness.isolatedHomeAvailable(), "worlds would land in XDG_DATA_HOME");
        long before = nettyThreads();

        for (int cycle = 0; cycle < CYCLES; cycle++) {
            try (ServerHarness server = ServerHarness.boot(home, "leak-347");
                 HarnessClient joined = HarnessClient.join(server, "Leak" + cycle);
                 HarnessClient refused = HarnessClient.connect(server)) {
                refused.send(new HandshakeC2S(ProtocolVersion.CURRENT - 1, "Old" + cycle, "",
                        WireCompression.localDecodeMask()));
                assertTrue(refused.awaitDisconnect(HarnessClient.PROMPT), "the old client was kicked");
                assertTrue(joined.isConnected());
            }
            // A join that never connects: nothing listens on the port.
            NetworkClient failed = new NetworkClient(StonebreakProtocol.registry());
            int port = unusedPort();
            assertThrows(Exception.class, () -> failed.connect(NetAddress.tcp("127.0.0.1", port)));
            failed.shutdown();
        }

        boolean settled = ServerHarness.await(() -> nettyThreads() <= before, Duration.ofSeconds(60));
        assertTrue(settled, "event-loop threads still alive: " + nettyThreads() + " (started with " + before
                + "): " + List.copyOf(Thread.getAllStackTraces().keySet().stream().map(Thread::getName)
                        .filter(n -> n.contains("EventLoop")).toList()));
    }
}
