package com.stonebreak.network.client.handlers;

import com.stonebreak.core.Game;
import com.stonebreak.network.client.ClientCubeStreamer;
import com.stonebreak.network.packet.world.BlockStateNamesS2C;
import com.stonebreak.network.packet.world.CubeDataS2C;
import com.stonebreak.world.World;

/**
 * Client-side cube streaming against the game's client world (cubic voxel runtime, #447): a thin
 * {@code Game}-bound adapter over {@link ClientCubeStreamer}, which holds the logic (install queue, registry
 * mirror, requests, unload notices, flow control, audit).
 *
 * <p>Packets can arrive before the client world exists (the world builds after WelcomeS2C, and again on a
 * rejoin): they wait in the streamer's queue, and installs only run once {@code Game.isClientWorldReady()} -
 * during a rebuild {@code Game.getWorld()} still returns the previous session's world.
 */
public final class ClientChunkHandler {

    /** Main-thread time per frame for installing cube records; the rest wait for the next frame. */
    private static final long INSTALL_BUDGET_NS = 4_000_000L;

    private final ClientCubeStreamer streamer = new ClientCubeStreamer();

    public ClientCubeStreamer streamer() {
        return streamer;
    }

    public void apply(CubeDataS2C batch) {
        streamer.accept(batch);
    }

    public void applyNames(BlockStateNamesS2C names) {
        streamer.acceptNames(names);
    }

    /** See {@link ClientCubeStreamer#runAfterPendingInstall}. */
    public boolean runAfterPendingInstall(long x, long y, long z, Runnable action) {
        return streamer.runAfterPendingInstall(x, y, z, action);
    }

    /** Per frame: installs queued records under the frame budget. */
    public void tick() {
        World world = readyWorld();
        if (world != null) {
            streamer.pump(world, INSTALL_BUDGET_NS);
        }
    }

    /** 20 Hz client tick: requests, unload notices, ack, audit. */
    public void clientTick(ClientCubeStreamer.Sink sink) {
        World world = readyWorld();
        if (world != null) {
            streamer.clientTick(world, sink);
        }
    }

    /** Audits every installed cube now (pause-menu resync); returns how many, or 0 without a world. */
    public int auditAll(ClientCubeStreamer.Sink sink) {
        World world = readyWorld();
        return world == null ? 0 : streamer.sendAudit(world, sink, -1);
    }

    private static World readyWorld() {
        if (!Game.isClientWorldReady()) {
            return null;
        }
        World world = Game.getWorld();
        return world != null && world.isRenderOnly() ? world : null;
    }

    public void onSessionEnd() {
        streamer.reset();
    }
}
