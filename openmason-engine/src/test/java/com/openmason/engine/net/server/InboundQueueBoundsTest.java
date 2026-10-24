package com.openmason.engine.net.server;

import com.openmason.engine.net.protocol.Packet;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #336: the server's inbound queue is bounded per connection, and a drain always returns. */
class InboundQueueBoundsTest {

    private record Ping(int n) implements Packet { }

    private static final ServerLimits LIMITS = new ServerLimits(64, 8, 5_000, 60, 1 << 20,
            64 * 1024, 600, 2400, 1 << 20, 4 << 20, 8, 2);

    @Test
    void floodingConnectionStopsBeingReadUntilTheTickCatchesUp() {
        ServerInboundQueue queue = new ServerInboundQueue(LIMITS);
        EmbeddedChannel ch = new EmbeddedChannel();
        ServerConnection conn = new ServerConnection(ch);

        for (int i = 0; i < 8; i++) {
            queue.postPacket(conn, new Ping(i));
        }
        ch.runPendingTasks();
        assertTrue(conn.isReadPaused());
        assertFalse(ch.config().isAutoRead(), "reads pause at the pause depth");

        queue.drain(e -> { }, 5); // 3 left: still above the resume depth
        ch.runPendingTasks();
        assertFalse(ch.config().isAutoRead());

        queue.drain(e -> { }, 1); // 2 left: resume
        ch.runPendingTasks();
        assertFalse(conn.isReadPaused());
        assertTrue(ch.config().isAutoRead());
    }

    @Test
    void oneConnectionsBacklogDoesNotPauseAnother() {
        ServerInboundQueue queue = new ServerInboundQueue(LIMITS);
        EmbeddedChannel floodCh = new EmbeddedChannel();
        EmbeddedChannel quietCh = new EmbeddedChannel();
        ServerConnection flood = new ServerConnection(floodCh);
        ServerConnection quiet = new ServerConnection(quietCh);

        for (int i = 0; i < 20; i++) {
            queue.postPacket(flood, new Ping(i));
        }
        queue.postPacket(quiet, new Ping(-1));
        floodCh.runPendingTasks();
        quietCh.runPendingTasks();

        assertFalse(floodCh.config().isAutoRead());
        assertTrue(quietCh.config().isAutoRead());
    }

    @Test
    void drainHandlesOnlyWhatWasQueuedWhenItStarted() {
        ServerInboundQueue queue = new ServerInboundQueue(LIMITS);
        ServerConnection conn = new ServerConnection(new EmbeddedChannel());
        queue.postPacket(conn, new Ping(0));
        queue.postPacket(conn, new Ping(1));

        List<Integer> seen = new ArrayList<>();
        // Every handled packet "arrives" another one — a client sending as fast as the tick
        // drains used to keep the drain loop (and the whole tick) from ever returning.
        queue.drain(e -> {
            seen.add(((Ping) e.packet()).n());
            queue.postPacket(conn, new Ping(100 + seen.size()));
        });

        assertEquals(List.of(0, 1), seen);
        assertEquals(2, queue.depth(), "arrivals during the drain wait for the next one");
    }

    @Test
    void drainCapLeavesTheRestQueuedInOrder() {
        ServerInboundQueue queue = new ServerInboundQueue(LIMITS);
        ServerConnection conn = new ServerConnection(new EmbeddedChannel());
        for (int i = 0; i < 5; i++) {
            queue.postPacket(conn, new Ping(i));
        }
        List<Integer> seen = new ArrayList<>();

        queue.drain(e -> seen.add(((Ping) e.packet()).n()), 3);
        assertEquals(List.of(0, 1, 2), seen);
        queue.drain(e -> seen.add(((Ping) e.packet()).n()), 3);
        assertEquals(List.of(0, 1, 2, 3, 4), seen);
        assertEquals(0, queue.depth());
    }
}
