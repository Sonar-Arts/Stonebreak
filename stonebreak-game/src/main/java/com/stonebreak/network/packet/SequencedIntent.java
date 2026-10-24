package com.stonebreak.network.packet;

/**
 * A client → server intent the client may already have applied to its own inventory mirror
 * (a placement, toss, arrow, bucket use, snow layer, eaten item, container click). {@code seq}
 * is a {@code SlotIntentSeq} number, or 0 for "not numbered" (an intent that changes nothing
 * the mirror predicts, or one built by a caller that never predicts).
 *
 * <p>The server records the number when it routes the packet — accepted, refused or queued —
 * and every {@code InventorySyncS2C} acknowledges the newest number recorded. A client that
 * has sent a newer intent skips a sync built before it, so the sync cannot hand back what the
 * prediction already spent; the client then asks for a fresh one ({@code InventoryResyncC2S}).
 */
public interface SequencedIntent {

    /** The intent's {@code SlotIntentSeq} number; 0 = not numbered. */
    int seq();
}
