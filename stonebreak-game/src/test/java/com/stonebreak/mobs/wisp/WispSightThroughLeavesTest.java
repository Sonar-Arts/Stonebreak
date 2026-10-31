package com.stonebreak.mobs.wisp;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.anim.AnimatedBlockRegistry;
import com.stonebreak.mobs.entities.FireBolt;
import com.stonebreak.player.interaction.RaycastEngine;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Wisp makes its victim out through leaves, and its orb drifts through them, so it can throw
 * from the canopy it hides in. Everything else still sees leaves as a wall.
 */
class WispSightThroughLeavesTest {

    private static final float TICK = 0.05f;

    /** Open air, but for a wall of {@code wall} filling the column x in [2, 4). */
    private static World wallAt(BlockType wall) {
        World world = mock(World.class);
        when(world.getAnimatedBlockRegistry()).thenReturn(new AnimatedBlockRegistry());
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            return x >= 2 && x < 4 ? wall : BlockType.AIR;
        });
        return world;
    }

    private static float sight(World world, boolean throughLeaves) {
        RaycastEngine ray = new RaycastEngine(null, null, world);
        return ray.distanceToFirstSolid(new Vector3f(0.5f, 64.5f, 0.5f), new Vector3f(1f, 0f, 0f), 8f,
                throughLeaves);
    }

    @Test
    void leavesBlockOrdinarySightButNotTheWisps() {
        World leaves = wallAt(BlockType.LEAVES);
        assertEquals(1.5f, sight(leaves, false), 0.15f);
        assertEquals(Float.MAX_VALUE, sight(leaves, true));
    }

    @Test
    void stoneStillBlocksTheWispsSight() {
        assertEquals(1.5f, sight(wallAt(BlockType.STONE), true), 0.15f);
    }

    @Test
    void everyKindOfLeafIsSeenThrough() {
        for (BlockType leaf : new BlockType[]{BlockType.LEAVES, BlockType.PINE_LEAVES, BlockType.ELM_LEAVES,
                BlockType.CYPRESS_LEAVES, BlockType.DRY_OAK_LEAVES}) {
            assertEquals(Float.MAX_VALUE, sight(wallAt(leaf), true), leaf.toString());
        }
    }

    private static boolean burstsOnWall(FireBolt bolt) {
        for (int i = 0; i < 40 && !bolt.isImpacted() && bolt.getPosition().x < 6f; i++) {
            bolt.update(TICK);
        }
        return bolt.isImpacted();
    }

    @Test
    void theOrbDriftsThroughLeaves() {
        World world = wallAt(BlockType.LEAVES);
        assertFalse(burstsOnWall(new WillOWispOrb(world, new Vector3f(0.5f, 64.5f, 0.5f), new Vector3f(1f, 0f, 0f))));
    }

    @Test
    void theOrbStillBurstsOnStone() {
        World world = wallAt(BlockType.STONE);
        assertTrue(burstsOnWall(new WillOWispOrb(world, new Vector3f(0.5f, 64.5f, 0.5f), new Vector3f(1f, 0f, 0f))));
    }

    @Test
    void aStaffBoltStillBurstsOnLeaves() {
        World world = wallAt(BlockType.LEAVES);
        assertTrue(burstsOnWall(new FireBolt(world, new Vector3f(0.5f, 64.5f, 0.5f), new Vector3f(1f, 0f, 0f))));
    }
}
