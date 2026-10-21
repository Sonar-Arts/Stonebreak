package com.stonebreak.network.client;

/**
 * Client-side bookkeeping for the prefs/progress round trip (issue #334). Each
 * {@code PlayerPrefsC2S} is numbered; the server answers every one with a
 * {@code ProgressS2C} acking the last seq it processed. The pools in an echo were computed
 * from the spend ledger of the report it acks, so they may only overwrite the client's pools
 * when that report is still the client's current state:
 * <ul>
 *   <li>the echo acks the LATEST report sent ({@code ackSeq >= lastSentSeq}) — an echo of an
 *       older report arriving after the player spent again would reset the pool to its
 *       pre-spend value and let the player spend twice; and</li>
 *   <li>the ledger has not changed locally since that report was sent — a spend made after
 *       the latest report is not reflected in its echo either.</li>
 * </ul>
 * A stale echo still carries authoritative level/xp; only its pools are skipped, and the next
 * report's echo brings them (including any level-up grants) back in step.
 *
 * <p>Touched only on the client tick thread.
 */
final class PrefsSequencer {

    private int lastSentSeq = 0;
    private int ledgerAtLastSend = 0;

    /**
     * Numbers the next report and remembers the ledger version it carries.
     *
     * @param ledgerVersion {@code CharacterStats.getLedgerVersion()} at serialization time
     * @return the seq to send (1, 2, 3, ...)
     */
    int nextSeq(int ledgerVersion) {
        lastSentSeq++;
        ledgerAtLastSend = ledgerVersion;
        return lastSentSeq;
    }

    /** Seq of the latest report sent; 0 before the first. */
    int lastSentSeq() {
        return lastSentSeq;
    }

    /**
     * True when an echo acking {@code ackSeq} may overwrite the pools of a client whose
     * ledger is now at {@code currentLedgerVersion}. Before the first report nothing is in
     * flight, so the server's pools apply as-is.
     */
    boolean poolsCurrent(int ackSeq, int currentLedgerVersion) {
        if (lastSentSeq == 0) {
            return true;
        }
        return ackSeq >= lastSentSeq && currentLedgerVersion == ledgerAtLastSend;
    }
}
