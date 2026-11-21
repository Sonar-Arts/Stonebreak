package com.stonebreak.blocks.furnace;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #369: a chunk re-stream used to replace every {@link FurnaceState} in it, so an open
 * furnace screen stayed bound to a detached object — its progress froze and its next edit
 * sent stale slots to the server. The registry now updates existing entries in place, like
 * the workbench registry, and the UI re-reads the update the same way it reads an echo.
 */
class FurnaceStateRegistryReStreamTest {

    private static final int Y = 20;
    private static final BlockPos POS = new BlockPos(3, Y, 5);

    private World world;
    private FurnaceStateRegistry registry;

    @BeforeEach
    void freshRegistry() {
        world = World.createDetached(new WorldConfiguration(), 1L);
        world.setBlockAt(POS.x(), POS.y(), POS.z(), BlockType.FURNACE);
        registry = new FurnaceStateRegistry(null);
    }

    @AfterEach
    void close() {
        world.cleanup();
    }

    /** What the server streams for a lit furnace holding {@code ingredients} with {@code cook} progress. */
    private static String serverState(int ingredients, int output, int cook) {
        FurnaceState s = FurnaceState.fromStateString(POS, FurnaceState.STATE_PREFIX
                + "state=Lit;burn=80;burnTotal=100;cook=" + cook);
        s.setIngredient(new ItemStack(BlockType.COBBLESTONE, ingredients));
        s.setOutput(new ItemStack(BlockType.STONE, output));
        return s.toStateString();
    }

    private void stream(String state) {
        world.setBlockStateAt(POS.x(), POS.y(), POS.z(), state);
        registry.onCubeInstalled(world.getCubeAt(POS.x(), POS.y(), POS.z()));
    }

    @Test
    void aReStreamKeepsTheBoundStateAndAppliesTheNewValues() {
        stream(serverState(4, 0, 10));
        FurnaceState bound = registry.get(POS);
        assertNotNull(bound);
        int[] notified = {0};
        bound.setChangeListener(() -> notified[0]++); // the open UI's observer

        stream(serverState(3, 1, 40));

        assertSame(bound, registry.get(POS), "the open UI's object is still the registry's entry");
        assertEquals(3, bound.getIngredient().getCount());
        assertEquals(1, bound.getOutput().getCount());
        assertEquals(40, bound.getCookProgress());
        assertEquals(1, notified[0], "the UI hears about the re-stream like an echo");
        assertNotNull(bound.consumePreEchoSlots(), "the UI re-baselines instead of re-sending");
    }

    @Test
    void aReStreamDuringAnUnacknowledgedEditKeepsThePredictedSlots() {
        stream(serverState(4, 0, 10));
        FurnaceState bound = registry.get(POS);
        bound.setIngredient(new ItemStack(BlockType.COBBLESTONE, 2)); // local edit...
        bound.markSent(7);                                    // ...sent, not yet acknowledged

        stream(serverState(4, 0, 30));

        assertSame(bound, registry.get(POS));
        assertEquals(2, bound.getIngredient().getCount(), "a re-stream carries no ack: slots stay");
        assertEquals(30, bound.getCookProgress(), "server-owned timers still apply");
    }

    @Test
    void theEchoMarkerFollowsTheStreamedValue() {
        String first = serverState(4, 0, 10);
        stream(first);
        FurnaceState s = registry.get(POS);
        assertFalse(s.echoDue(first), "a freshly streamed value is not echoed again");

        String second = serverState(3, 1, 40);
        stream(second);
        assertSame(s, registry.get(POS));
        assertFalse(s.echoDue(second), "the marker moved to the re-streamed value");
        assertTrue(s.echoDue(serverState(2, 2, 0)), "a real change after it still echoes");
    }
}
