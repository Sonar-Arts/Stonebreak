package com.stonebreak.battletest;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import java.util.List;
import org.joml.Vector3f;

/** Disposable scene world: no terrain generation, block edits, chunk streaming or save service. */
public final class BattleTestWorld extends World {
    private final BattleTestArena arena;
    public BattleTestWorld(BattleTestArena arena) {
        super(new WorldConfiguration(), 0L, true);
        this.arena = arena;
        setSpawnPosition(arena.playerSpawn().position());
    }
    public BattleTestArena arena() {
        return arena;
    }
    @Override
    public BlockType getBlockAt(int x, int y, int z) {
        return BlockType.AIR;
    }
    @Override
    public boolean isChunkRenderableAt(int x, int z) {
        return true;
    }
    @Override
    public boolean setBlockAt(int x, int y, int z, BlockType type, boolean edited, String state) {
        return false;
    }
    @Override
    public List<float[]> getStaticCollisionBoxes(Vector3f position, float range) {
        return arena.collisionBoxes()
            .stream()
            .filter(b -> b.near(position, range))
            .map(BattleTestArena.Box::bounds)
            .toList();
    }
}
