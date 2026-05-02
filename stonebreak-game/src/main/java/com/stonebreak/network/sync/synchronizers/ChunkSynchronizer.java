package com.stonebreak.network.sync.synchronizers;

import com.stonebreak.core.Game;
import com.stonebreak.network.protocol.NetworkChunkCodec;
import com.stonebreak.network.protocol.Packet;
import com.stonebreak.network.sync.SyncContext;
import com.stonebreak.network.sync.SyncEvent;
import com.stonebreak.network.sync.SyncMode;
import com.stonebreak.network.sync.Synchronizer;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Sends host-modified chunks to clients so non-deterministic divergence
 * (player builds, structure-edits, anything not reproducible from the seed)
 * is reflected on every joiner.
 *
 * <p>Strategy: every block change on the host marks the owning chunk as
 * "modified". When a client joins ({@link SyncEvent.PeerJoined}), the full
 * payload of every modified chunk is sent. Ongoing single-block edits are
 * still delivered by {@link BlockSynchronizer}, so this is a one-shot
 * snapshot per joiner — bandwidth scales with how much of the world has
 * been edited, not with view distance.
 */
public final class ChunkSynchronizer implements Synchronizer {

    /** Encoded {@code (cx, cz)} pairs of chunks the host has modified since session start. */
    private final Set<Long> modifiedChunks = new HashSet<>();
    /** Client-side: chunk pushes that arrived before the local world finished generating. */
    private final Deque<Packet.ChunkDataS2C> pending = new ArrayDeque<>();

    @Override
    public void onSessionStart(SyncContext ctx) {
        modifiedChunks.clear();
    }

    @Override
    public void onSessionEnd() {
        modifiedChunks.clear();
        pending.clear();
    }

    @Override
    public void tick(float deltaTime, SyncContext ctx) {
        if (pending.isEmpty() || Game.getWorld() == null) return;
        Iterator<Packet.ChunkDataS2C> it = pending.iterator();
        while (it.hasNext()) {
            Packet.ChunkDataS2C cd = it.next();
            Chunk chunk = Game.getWorld().getChunkAt(cd.chunkX(), cd.chunkZ());
            if (chunk == null) continue; // still loading; retry next tick
            Game.getWorld().installNetworkChunk(cd.chunkX(), cd.chunkZ(), cd.payload());
            it.remove();
        }
    }

    @Override
    public boolean handlesInbound(Packet packet) {
        return packet instanceof Packet.ChunkDataS2C;
    }

    @Override
    public void applyInbound(Packet packet, Integer originId, SyncContext ctx) {
        if (!(packet instanceof Packet.ChunkDataS2C cd)) return;
        World world = Game.getWorld();
        if (world == null) {
            pending.add(cd);
            return;
        }
        Chunk chunk = world.getChunkAt(cd.chunkX(), cd.chunkZ());
        if (chunk == null) {
            // Chunk is loading async; defer.
            pending.add(cd);
            return;
        }
        world.installNetworkChunk(cd.chunkX(), cd.chunkZ(), cd.payload());
    }

    @Override
    public boolean handlesLocal(SyncEvent event) {
        return event instanceof SyncEvent.BlockChanged
            || event instanceof SyncEvent.PeerJoined;
    }

    @Override
    public void emitLocal(SyncEvent event, SyncContext ctx) {
        if (ctx.mode() != SyncMode.HOST) return;
        switch (event) {
            case SyncEvent.BlockChanged b -> {
                int cx = Math.floorDiv(b.x(), WorldConfiguration.CHUNK_SIZE);
                int cz = Math.floorDiv(b.z(), WorldConfiguration.CHUNK_SIZE);
                modifiedChunks.add(packKey(cx, cz));
            }
            case SyncEvent.PeerJoined p -> sendModifiedChunksTo(p.playerId(), ctx);
            default -> {}
        }
    }

    private void sendModifiedChunksTo(int playerId, SyncContext ctx) {
        World world = Game.getWorld();
        if (world == null || modifiedChunks.isEmpty()) return;
        int sent = 0;
        for (long key : modifiedChunks) {
            int cx = unpackX(key);
            int cz = unpackZ(key);
            Chunk chunk = world.getChunkAt(cx, cz);
            if (chunk == null) continue; // not loaded right now; skip silently
            byte[] payload = NetworkChunkCodec.encode(chunk);
            ctx.sendTo(playerId, new Packet.ChunkDataS2C(cx, cz, payload));
            sent++;
        }
        if (sent > 0) {
            System.out.println("[CHUNK-SYNC] Sent " + sent + " modified chunks to player " + playerId);
        }
    }

    private static long packKey(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
    }
    private static int unpackX(long key) { return (int) (key >> 32); }
    private static int unpackZ(long key) { return (int) (key & 0xFFFFFFFFL); }
}
