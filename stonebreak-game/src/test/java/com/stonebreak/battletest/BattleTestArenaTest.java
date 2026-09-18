package com.stonebreak.battletest;

import static org.junit.jupiter.api.Assertions.*;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.player.physics.CollisionHandler;
import com.stonebreak.player.physics.GroundChecker;
import com.stonebreak.player.state.PhysicsState;
import org.junit.jupiter.api.Test;

class BattleTestArenaTest {
    @Test
    void shippedModelIncludesTheRestoredShelfAndSurroundings() throws Exception {
        var mesh = BattleTestMesh.load(BattleTestArena.load());
        assertEquals(49572, mesh.authoredTriangles());
        assertTrue(mesh.vertices().length / BattleTestMesh.STRIDE / 3 > mesh.authoredTriangles());
        float minX = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < mesh.vertices().length; i += BattleTestMesh.STRIDE) {
            minX = Math.min(minX, mesh.vertices()[i]);
            maxY = Math.max(maxY, mesh.vertices()[i + 1]);
            for (int j = 0; j < BattleTestMesh.STRIDE; j++)
                assertTrue(Float.isFinite(mesh.vertices()[i + j]));
        }
        assertTrue(minX <= -250);
        assertTrue(maxY > 90);
    }
    @Test
    void bothSpawnsLandOnTheCourtAndHaveHeadroom() throws Exception {
        var arena = BattleTestArena.load();
        var world = new BattleTestWorld(arena);
        try {
            assertEquals(18, arena.playerSpawn().position().distance(arena.archonSpawn().position()), .001);
            for (var spawn : new BattleTestArena.Spawn[] {arena.playerSpawn(), arena.archonSpawn()}) {
                var state = new PhysicsState();
                state.getPosition().set(spawn.position());
                var collision = new CollisionHandler(state, world);
                assertFalse(collision.isPlayerInsideSolidBlock());
                state.getPosition().y = .01f;
                state.getVelocity().y = -1;
                collision.resolveY();
                assertEquals(.024f, state.getPosition().y, .0001);
                assertTrue(state.isOnGround());
                new GroundChecker(state, collision).check();
                assertTrue(state.isOnGround());
            }
            assertEquals(BlockType.AIR, world.getBlockAt(0, 0, 0));
            assertFalse(world.setBlockAt(0, 0, 0, BlockType.STONE));
            assertEquals(0, world.getLoadedChunkCount());
        } finally {
            world.cleanup();
        }
    }
    @Test
    void coverBlocksMovementAndCentralGateRemainsOpen() throws Exception {
        var world = new BattleTestWorld(BattleTestArena.load());
        try {
            var state = new PhysicsState();
            state.getPosition().set(7.3f, .024f, 5);
            state.getVelocity().x = 3;
            var collision = new CollisionHandler(state, world);
            collision.resolveX();
            assertTrue(state.getPosition().x < 7.3f);
            assertEquals(0, state.getVelocity().x);
            state.getPosition().set(0, .024f, -13.8f);
            state.getVelocity().set(0, 0, -3);
            collision.resolveZ();
            assertEquals(-13.8f, state.getPosition().z, .0001);
        } finally {
            world.cleanup();
        }
    }
    @Test
    void entranceStepsAreWalkableInBothAxes() throws Exception {
        var world = new BattleTestWorld(BattleTestArena.load());
        try {
            var state = new PhysicsState();
            state.getPosition().set(0, -.195f, 16.1f);
            state.setOnGround(true);
            state.getVelocity().z = -2;
            var collision = new CollisionHandler(state, world);
            collision.resolveZ();
            assertEquals(.01f, state.getPosition().y, .001);
            assertEquals(-2, state.getVelocity().z);
        } finally {
            world.cleanup();
        }
    }
    @Test
    void sceneCoverBlocksCameraAndMeleeSightRays() throws Exception {
        var world = new BattleTestWorld(BattleTestArena.load());
        try {
            var raycast = new com.stonebreak.player.interaction.RaycastEngine(
                new PhysicsState(), new com.stonebreak.player.Camera(), world);
            float hit = raycast.distanceToFirstSolid(
                new org.joml.Vector3f(5, .8f, 5), new org.joml.Vector3f(1, 0, 0), 10);
            assertEquals(2.4f, hit, .1f);
            assertEquals(Float.MAX_VALUE,
                raycast.distanceToFirstSolid(
                    new org.joml.Vector3f(0, 1, 8), new org.joml.Vector3f(0, 0, -1), 16));
        } finally {
            world.cleanup();
        }
    }
}
