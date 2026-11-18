package com.stonebreak.mobs.goblin.camp;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.goblin.GoblinLoadout;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A broken piece holds up a camp's building for three minutes, and the clock waits out any enemy. */
class CampRepairTest {

    private static final BlockPos STAKE = new BlockPos(3, 10, 0);

    private static GoblinCamp campWithAStake() {
        GoblinCamp camp = new GoblinCamp(new BlockPos(0, 10, 0));
        camp.pieceStanding(STAKE, BlockType.PALISADE);
        return camp;
    }

    @Test
    void anUnbrokenCampBuildsFreely() {
        GoblinCamp camp = campWithAStake();
        assertFalse(camp.checkPieces(at -> BlockType.PALISADE));
        assertTrue(camp.readyToBuild(5.0f, false));
    }

    @Test
    void aBrokenPieceHoldsUpBuildingForThreeMinutes() {
        GoblinCamp camp = campWithAStake();
        assertTrue(camp.checkPieces(at -> BlockType.AIR));
        float waited = 0.0f;
        while (!camp.readyToBuild(5.0f, false)) {
            waited += 5.0f;
            assertTrue(waited < 1000.0f);
        }
        assertEquals(GoblinCamp.REPAIR_SECONDS, waited, 5.0f);
    }

    @Test
    void theClockStandsStillWhileAnEnemyIsAbout() {
        GoblinCamp camp = campWithAStake();
        camp.checkPieces(at -> BlockType.AIR);
        for (int i = 0; i < 100; i++) {
            assertFalse(camp.readyToBuild(5.0f, true), "nothing is built with an enemy in camp");
        }
        assertEquals(GoblinCamp.REPAIR_SECONDS, camp.repairWait(), 0.001f);
    }

    @Test
    void anEnemyAboutHoldsUpEvenAnUnbrokenCamp() {
        assertFalse(campWithAStake().readyToBuild(5.0f, true));
    }

    @Test
    void anUnloadedPieceIsNotBroken() {
        GoblinCamp camp = campWithAStake();
        assertFalse(camp.checkPieces(at -> null));
        assertTrue(camp.readyToBuild(5.0f, false));
    }

    @Test
    void aBrokenPieceIsForgottenOnceNoticed() {
        GoblinCamp camp = campWithAStake();
        camp.checkPieces(at -> BlockType.AIR);
        assertFalse(camp.checkPieces(at -> BlockType.AIR), "noticed once, not every look");
    }

    @Test
    void theWaitSurvivesASave() {
        GoblinCamp camp = campWithAStake();
        camp.addWaiting(GoblinLoadout.roll(new Random(1)));
        camp.setRepairWait(120.0f);
        GoblinCamp thawed = CampCodec.decode(camp.fire(), CampCodec.encode(camp));
        assertEquals(120.0f, thawed.repairWait(), 0.5f);
    }
}
