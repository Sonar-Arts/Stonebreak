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
        // isClientWorldReady (not a bare null check): during a world REBUILD (rejoin),
        // Game.getWorld() still returns the previous session's world — installing there
        // would silently lose the chunk (the server marks it sent exactly once).
        if (!Game.isClientWorldReady()) {
            pending.add(cd);
            return;
        }
        World world = Game.getWorld();
        if (!world.installNetworkChunk(cd.chunkX(), cd.chunkZ(), cd.payload(), cd.metaPayload())) {
            // Decode/install failed but the server marked this chunk sent — without a resync
            // request the hole is permanent (the version never changes for us again).
            com.stonebreak.network.MultiplayerSession.requestChunkResync(cd.chunkX(), cd.chunkZ());
        }
    }

    public void tick() {
        if (pending.isEmpty() || !Game.isClientWorldReady()) {
            return;
        }
        World world = Game.getWorld();
        ChunkDataS2C cd;
        while ((cd = pending.poll()) != null) {
            if (!world.installNetworkChunk(cd.chunkX(), cd.chunkZ(), cd.payload(), cd.metaPayload())) {
                com.stonebreak.network.MultiplayerSession.requestChunkResync(cd.chunkX(), cd.chunkZ());
            }
        }
    }

    public void onSessionEnd() {
        pending.clear();
    }
}
