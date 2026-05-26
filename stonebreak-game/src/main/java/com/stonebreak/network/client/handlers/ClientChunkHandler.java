package com.stonebreak.network.client.handlers;

import com.stonebreak.core.Game;
import com.stonebreak.network.packet.world.ChunkDataS2C;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Client-side: installs authoritative chunk snapshots into the local world. Chunks that
 * arrive before the local world has generated the target chunk are deferred and drained on
 * later ticks. Successor of the old {@code ChunkSynchronizer} CLIENT path.
 */
public final class ClientChunkHandler {

    private final Deque<ChunkDataS2C> pending = new ArrayDeque<>();

    public void apply(ChunkDataS2C cd) {
        World world = Game.getWorld();
        if (world == null) {
            pending.add(cd);
            return;
        }
        Chunk chunk = world.getChunkAt(cd.chunkX(), cd.chunkZ());
        if (chunk == null) {
            pending.add(cd);
            return;
        }
        world.installNetworkChunk(cd.chunkX(), cd.chunkZ(), cd.payload());
    }

    public void tick() {
        if (pending.isEmpty() || Game.getWorld() == null) {
            return;
        }
        World world = Game.getWorld();
        Iterator<ChunkDataS2C> it = pending.iterator();
        while (it.hasNext()) {
            ChunkDataS2C cd = it.next();
            if (world.getChunkAt(cd.chunkX(), cd.chunkZ()) == null) {
                continue;
            }
            world.installNetworkChunk(cd.chunkX(), cd.chunkZ(), cd.payload());
            it.remove();
        }
    }

    public void onSessionEnd() {
        pending.clear();
    }
}
