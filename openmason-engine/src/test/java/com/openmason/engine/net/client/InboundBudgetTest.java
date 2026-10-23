package com.openmason.engine.net.client;

import com.openmason.engine.net.protocol.Packet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's inbound queue spreads a backlog over frames (a time budget per drain) and
 * pauses network reads while the backlog is deep, instead of growing without bound and
 * applying everything in one frame.
 */
class InboundBudgetTest {

    private record Ping(int n) implements Packet { }

    @Test
    void aSpentBudgetLeavesTheRestForTheNextDrain() {
        ClientInboundQueue q = new ClientInboundQueue();
        for (int i = 0; i < 10; i++) {
            q.postPacket(new Ping(i));
        }
        List<Integer> seen = new ArrayList<>();
        q.drain(e -> seen.add(((Ping) e.packet()).n()), 0L); // budget already spent
        assertEquals(List.of(0), seen, "always handles one, in order");
        assertEquals(9, q.depth());

        q.drain(e -> seen.add(((Ping) e.packet()).n()));
        assertEquals(10, seen.size());
        assertEquals(0, q.depth());
    }

    @Test
    void aDeepBacklogPausesReadsUntilItDrains() {
        ClientInboundQueue q = new ClientInboundQueue();
        List<Boolean> gate = new ArrayList<>();
        q.setReadGate(gate::add);
        for (int i = 0; i < ClientInboundQueue.READ_PAUSE_DEPTH; i++) {
            q.postPacket(new Ping(i));
        }
        assertEquals(List.of(false), gate, "reads pause once, at the high-water mark");

        AtomicInteger handled = new AtomicInteger();
        q.drain(e -> handled.incrementAndGet());
        assertEquals(ClientInboundQueue.READ_PAUSE_DEPTH, handled.get());
        assertEquals(List.of(false, true), gate, "and resume once drained");
        assertFalse(gate.isEmpty());
        assertTrue(gate.get(1));
    }
}
