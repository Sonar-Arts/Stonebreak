package com.stonebreak.network;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Numbers this client's container slot actions (issue #353, #334) and every other intent the
 * inventory mirror predicts — placements and bucket uses, tosses, arrows, snow layers, eaten
 * items, the death drop ({@code SequencedIntent}). One counter for the whole process, so a
 * number is never reused for a block — not across UI reopens and not after a chunk reload
 * replaced the block's display state. The server echoes the last number it processed from us
 * as {@code BlockStateS2C.ack} (per furnace/workbench) and {@code InventorySyncS2C.ack} (per
 * player); an echo whose ack is older than our latest send was built before that send arrived.
 *
 * <p>Take a number only when the packet is actually sent: a number the server never sees
 * makes every later sync look stale for the rest of the session.
 */
public final class SlotIntentSeq {

    private static final AtomicInteger LAST = new AtomicInteger();
    /** {@link #LAST} when the current session started: numbers at or below it were sent to
     *  an earlier server, which this session's server never acknowledges. */
    private static volatile int sessionFloor;

    private SlotIntentSeq() {}

    /** False while our newest intent is one the server always answers with a sync. */
    private static volatile boolean lastUnanswered;

    /**
     * The next intent number for an intent the server does NOT always answer with a sync (an
     * accepted placement, snow layer, toggle or arrow): a stale sync skipped while it is our
     * newest must be followed by an {@code InventoryResyncC2S}. Always positive (0 means "no
     * intent" on the wire).
     */
    public static int next() {
        lastUnanswered = true;
        return LAST.updateAndGet(n -> n == Integer.MAX_VALUE ? 1 : n + 1);
    }

    /**
     * The next intent number for an intent every outcome of which the server answers with an
     * acknowledging sync (container actions, eating, tosses, the death drop): a skipped stale
     * sync needs no resync request — that answer is already on its way.
     */
    public static int nextAnswered() {
        lastUnanswered = false;
        return LAST.updateAndGet(n -> n == Integer.MAX_VALUE ? 1 : n + 1);
    }

    /** Whether a sync skipped now needs a resync request (our newest intent goes unanswered). */
    public static boolean skippedSyncNeedsResync() {
        return lastUnanswered;
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
