package com.stonebreak.blocks.blueberrybush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.openmason.engine.format.sbo.SBOFormat;
import com.stonebreak.blocks.BlockRenderState;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.blueberrybush.BlueberryBushState.Stage;
import com.stonebreak.blocks.registry.BlockRegistry;

class BlueberryBushStateTest {

    @Test
    void roundTripsStageAndTimer() {
        BlueberryBushState s = new BlueberryBushState(Stage.BUSHY, 1234);
        assertEquals("blueberry_bush:state=Bushy;grow=1234", s.toStateString());
        assertEquals(s, BlueberryBushState.parse(s.toStateString()));
    }

    @Test
    void ripeHasNoTimer() {
        BlueberryBushState ripe = new BlueberryBushState(Stage.BERRIES_RIPE, 500);
        assertEquals(BlueberryBushState.NO_TIMER, ripe.ticksLeft());
        assertEquals("blueberry_bush:state=Berries_Ripe", ripe.toStateString());
        assertTrue(BlueberryBushState.parse(ripe.toStateString()).isRipe());
    }

    @Test
    void foreignOrUnreadableStringsParseToNull() {
        assertNull(BlueberryBushState.parse(null));
        assertNull(BlueberryBushState.parse("grow:600"));
        assertNull(BlueberryBushState.parse("blueberry_bush:state=Banana;grow=5"));
    }

    @Test
    void unknownKeysAreIgnored() {
        BlueberryBushState s = BlueberryBushState.parse("blueberry_bush:grow=7;future=x;state=Young");
        assertEquals(new BlueberryBushState(Stage.YOUNG, 7), s);
    }

    @Test
    void stagesRunInOrderAndStopAtRipe() {
        Stage s = Stage.SPROUT;
        int steps = 0;
        while (s.next() != null) {
            s = s.next();
            steps++;
        }
        assertEquals(Stage.BERRIES_RIPE, s);
        assertEquals(6, steps);
        assertTrue(Stage.BUSHY.isGrowing());
        assertFalse(Stage.MATURE.isGrowing());
    }

    /** The timer is volatile payload: only the stage may pick the mesh. */
    @Test
    void meshVariantIsTheSboStateName() {
        for (Stage stage : Stage.values()) {
            String raw = new BlueberryBushState(stage, 99).toStateString();
            assertEquals(stage.sboStateName(), BlockRenderState.meshVariantKey(raw));
        }
        assertFalse(BlockRenderState.affectsMesh(
            new BlueberryBushState(Stage.YOUNG, 10).toStateString(),
            new BlueberryBushState(Stage.YOUNG, 9).toStateString()));
        assertTrue(BlockRenderState.affectsMesh(
            new BlueberryBushState(Stage.YOUNG, 10).toStateString(),
            new BlueberryBushState(Stage.BUSHY, 10).toStateString()));
    }

    /** Breaking a bush drops its berries only when ripe; any earlier stage drops nothing. */
    @Test
    void onlyARipeBushDropsWhenBroken() {
        for (Stage stage : Stage.values()) {
            String raw = new BlueberryBushState(stage, 50).toStateString();
            assertEquals(stage == Stage.BERRIES_RIPE,
                com.stonebreak.util.DropUtil.dropsInState(BlockType.BLUEBERRY_BUSH, raw), stage.name());
        }
        assertFalse(com.stonebreak.util.DropUtil.dropsInState(BlockType.BLUEBERRY_BUSH, null),
            "a bush with no state is a fresh sprout");
        assertTrue(com.stonebreak.util.DropUtil.dropsInState(BlockType.DIRT, null), "other blocks are not gated");
    }

    /** A stage with no matching SBO state would silently draw the default (Sprout) model. */
    @Test
    void everyStageHasAStateInTheSbo() {
        SBOFormat.Document manifest = BlockRegistry.getInstance()
            .getById(BlockType.BLUEBERRY_BUSH.getId()).orElseThrow()
            .sboData().manifest();
        Set<String> sboStates = manifest.states().stream()
            .map(SBOFormat.StateEntry::name).collect(Collectors.toSet());
        for (Stage stage : Stage.values()) {
            assertTrue(sboStates.contains(stage.sboStateName()), "SBO lacks state " + stage.sboStateName());
        }
    }
}
