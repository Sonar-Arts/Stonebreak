package com.stonebreak.player.interaction;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.player.combat.AttackController;
import com.stonebreak.rendering.sbo.SBOBlockRegistry;
import com.stonebreak.world.World;
import org.joml.Vector3i;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #454: a block break makes progress only while gameplay input keeps holding it. Opening a screen (the
 * world actions stop calling {@code startBreaking}) cancels the break instead of finishing it behind the menu
 * under the frozen crosshair.
 */
class BlockBreakerHeldTest {

    private static final float DT = 1f / 60f;
    private static final Vector3i TARGET = new Vector3i(3, 10, -4);

    private World world;
    private BlockBreaker breaker;
    private float stoneSeconds;

    @BeforeEach
    void setUp() {
        SBOBlockRegistry registry = new SBOBlockRegistry();
        assumeTrue(registry.scanAndLoad() > 0, "no SBO blocks on the classpath");
        BlockRegistry.getInstance().loadFrom(registry);
        stoneSeconds = BlockType.STONE.getHardness();
        assumeTrue(stoneSeconds > 0.2f, "stone must take a while to break bare-handed");

        Game.getInstance();
        Game.setDeltaTimeForTesting(DT);

        RaycastEngine ray = mock(RaycastEngine.class);
        when(ray.raycast()).thenReturn(new Vector3i(TARGET));
        world = mock(World.class);
        when(world.getBlockAt(anyLong(), anyLong(), anyLong())).thenReturn(BlockType.STONE);
        breaker = new BlockBreaker(ray, mock(Inventory.class), mock(AttackController.class), world);
    }

    private int framesFor(float seconds) {
        return (int) Math.ceil(seconds / DT) + 10;
    }

    @Test
    void heldEveryFrameBreaksTheBlock() {
        for (int i = 0; i < framesFor(stoneSeconds); i++) {
            breaker.startBreaking();
            breaker.update();
        }
        verify(world).setBlockAt(anyLong(), anyLong(), anyLong(), any(BlockType.class), anyBoolean());
    }

    @Test
    void openingAScreenMidBreakCancelsIt() {
        // Mining for a while in PLAYING...
        for (int i = 0; i < 5; i++) {
            breaker.startBreaking();
            breaker.update();
        }
        assertNotNull(breaker.getBreakingBlock());
        // ...then the inventory opens: the world actions stop asking, the player update keeps ticking.
        for (int i = 0; i < framesFor(stoneSeconds) * 2; i++) {
            breaker.update();
        }
        assertNull(breaker.getBreakingBlock(), "the break (and the MINING flag) must be cancelled");
        assertEquals(0f, breaker.getBreakingProgress());
        verify(world, never()).setBlockAt(anyLong(), anyLong(), anyLong(), any(BlockType.class), anyBoolean());
    }

    @Test
    void anUpdateBeforeTheFirstHoldStillProgresses() {
        // Frame order is update (player) then input (world actions): the press lands after an update.
        breaker.update();
        breaker.startBreaking();
        breaker.update();
        assertNotNull(breaker.getBreakingBlock());
    }
}
