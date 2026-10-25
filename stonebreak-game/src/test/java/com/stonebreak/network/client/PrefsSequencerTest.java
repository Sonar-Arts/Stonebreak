package com.stonebreak.network.client;

import com.stonebreak.player.CharacterStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stale progression echoes (issue #334 review): an echo of an older prefs report must not
 * reset the client's pools after it spent again, or the client spends the same point twice
 * and every later report is refused for the session.
 */
class PrefsSequencerTest {

    @Test
    void beforeAnyReportTheServerPoolsApply() {
        assertTrue(new PrefsSequencer().poolsCurrent(0, 5));
    }

    @Test
    void anEchoOfAnOlderReportDoesNotOverwritePools() {
        CharacterStats cs = new CharacterStats(null);   // 1 CP
        PrefsSequencer seq = new PrefsSequencer();

        int p0 = seq.nextSeq(cs.getLedgerVersion());     // report P0: nothing spent
        cs.spendCpOnAbility("berserker:p0:0");                // spend after P0
        int p1 = seq.nextSeq(cs.getLedgerVersion());     // report P1: 1 CP spent

        // P0's echo (remainingCp 1) arrives after P1 went out: applying it would hand the
        // client its spent point back.
        assertFalse(seq.poolsCurrent(p0, cs.getLedgerVersion()));
        assertTrue(seq.poolsCurrent(p1, cs.getLedgerVersion()), "the latest report's echo applies");
    }

    @Test
    void anEchoOfTheLatestReportIsStaleIfTheLedgerMovedSince() {
        CharacterStats cs = new CharacterStats(null);
        PrefsSequencer seq = new PrefsSequencer();
        int p0 = seq.nextSeq(cs.getLedgerVersion());
        cs.investSkillPoint("stealth");                    // not yet reported
        assertFalse(seq.poolsCurrent(p0, cs.getLedgerVersion()));
    }

    @Test
    void poolOnlyServerUpdatesDoNotCountAsLedgerChanges() {
        CharacterStats cs = new CharacterStats(null);
        PrefsSequencer seq = new PrefsSequencer();
        int p0 = seq.nextSeq(cs.getLedgerVersion());
        cs.applyServerProgression(2, 0, 2, 4, 2, 27);     // a level-up echo
        assertTrue(seq.poolsCurrent(p0, cs.getLedgerVersion()));
        assertEquals(1, seq.lastSentSeq());
    }
}
