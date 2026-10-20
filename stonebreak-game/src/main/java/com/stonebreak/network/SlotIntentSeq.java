package com.stonebreak.network;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Numbers this client's furnace and workbench slot intents (issue #353). One counter for the
 * whole process, so a number is never reused for a block — not across UI reopens and not
 * after a chunk reload replaced the block's display state. The server echoes the last number
 * it processed from us as {@code BlockStateS2C.ack}; an echo whose ack is older than our
 * latest send was built before that send arrived.
 */
public final class SlotIntentSeq {

    private static final AtomicInteger LAST = new AtomicInteger();

    private SlotIntentSeq() {}

    /** The next intent number; always positive (0 means "no intent" on the wire). */
    public static int next() {
        return LAST.updateAndGet(n -> n == Integer.MAX_VALUE ? 1 : n + 1);
    }
}
