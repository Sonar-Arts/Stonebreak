package com.stonebreak.network.client.handlers;

import com.stonebreak.core.Game;
import com.stonebreak.network.packet.world.ChunkDataS2C;
import com.stonebreak.world.World;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Client-side: installs authoritative chunk snapshots into the local world.
 * Successor of the old {@code ChunkSynchronizer} CLIENT path.
 *
 * <p>Installs happen immediately on arrival — {@code World.installNetworkChunk}
 * creates its chunk slot synchronously, so there is nothing to wait for. (An
 * older version gated installs behind {@code world.getChunkAt(...) != null},
 * which deferred every payload at least one tick and pushed empty placeholder
 * chunks through the async load path for nothing.) Only payloads that arrive
 * before the world object itself exists are deferred.
 */
public final class ClientChunkHandler {

    private final Deque<ChunkDataS2C> pending = new ArrayDeque<>();

    public void apply(ChunkDataS2C cd) {
        World world = Game.getWorld();
        if (world == null) {
            pending.add(cd);
            return;
        }
        world.installNetworkChunk(cd.chunkX(), cd.chunkZ(), cd.payload());
    }

    public void tick() {
        if (pending.isEmpty()) {
            return;
        }
        World world = Game.getWorld();
        if (world == null) {
            return;
        }
        ChunkDataS2C cd;
        while ((cd = pending.poll()) != null) {
            world.installNetworkChunk(cd.chunkX(), cd.chunkZ(), cd.payload());
        }
    }

    public void onSessionEnd() {
        pending.clear();
    }
}
