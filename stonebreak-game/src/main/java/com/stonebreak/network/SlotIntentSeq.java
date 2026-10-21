package com.stonebreak.network;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Numbers this client's container slot actions (issue #353, #334). One counter for the
 * whole process, so a number is never reused for a block — not across UI reopens and not
 * after a chunk reload replaced the block's display state. The server echoes the last number
 * it processed from us as {@code BlockStateS2C.ack} (per furnace/workbench) and
 * {@code InventorySyncS2C.ack} (per player); an echo whose ack is older than our latest send
 * was built before that send arrived.
 */
public final class SlotIntentSeq {

    private static final AtomicInteger LAST = new AtomicInteger();
    /** {@link #LAST} when the current session started: numbers at or below it were sent to
     *  an earlier server, which this session's server never acknowledges. */
    private static volatile int sessionFloor;

    private SlotIntentSeq() {}

    /** The next intent number; always positive (0 means "no intent" on the wire). */
    public static int next() {
        return LAST.updateAndGet(n -> n == Integer.MAX_VALUE ? 1 : n + 1);
    }

    /** Called when a client session starts: earlier numbers are never acknowledged again. */
    public static void markSessionStart() {
        sessionFloor = LAST.get();
    }

    /**
     * True when a per-player echo stamped {@code ack} reflects every action this session
     * sent (or none was sent), so its slots may replace the local prediction.
     */
    public static boolean isCurrent(int ack) {
        int last = LAST.get();
        return last == sessionFloor || ack >= last;
    }
}
