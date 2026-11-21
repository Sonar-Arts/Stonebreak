package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.voxel.world.CubeStream;
import com.openmason.engine.voxel.world.StreamCenter;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.network.client.ClientCubeStreamer;
import com.stonebreak.network.packet.handshake.WelcomeS2C;
import com.stonebreak.network.packet.player.ViewDistanceC2S;
import com.stonebreak.network.packet.world.BlockChangeS2C;
import com.stonebreak.network.packet.world.BlockStateNamesS2C;
import com.stonebreak.network.packet.world.CubeDataS2C;
import com.stonebreak.network.packet.world.MultiBlockChangeS2C;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.NativeTerrain;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cube streaming end to end (cubic voxel runtime, #447): a real {@link IntegratedServer} over loopback TCP and
 * clients that run what the game client runs — a REMOTE {@link World} ({@link World#createClientView}) fed by
 * {@link ClientCubeStreamer} — ticked on this (owner) thread. Checks that cubes arrive and match the server,
 * that the client's column becomes READY (column records give a REMOTE world its surface band), that known
 * air above the terrain reads as air without a cube, that the block-state registry is mirrored, that edits
 * replicate to every holder, that an unreplicated server write is re-sent on CV_EV_CHANGED, and that unloads
 * reach the server. Needs the worldgen natives.
 */
@Tag("integration")
class CubeStreamingIntegrationTest {

    private static final long TIMEOUT_NS = 90_000_000_000L;
    /** Small views keep the test quick: 4 chunks = 64 blocks horizontally, 64 vertically. */
    private static final int VIEW_CHUNKS = 4;
    private static final int VIEW_VERTICAL = 64;

    @TempDir
    Path home;

    private ServerHarness server;

    @BeforeEach
    void requireNatives() {
        assumeTrue(CubeStream.isAvailable(), "cv_stream unavailable");
        assumeTrue(NativeTerrain.isAvailable(), () -> "worldgen natives: "
            + com.stonebreak.world.generation.NativeCubeGenerator.unavailableReason());
    }

    @AfterEach
    void stop() throws InterruptedException {
        if (server != null) {
            server.close();
        }
    }

    /** One game client: the raw protocol connection plus a REMOTE client world and the cube streamer. */
    private static final class Rig implements AutoCloseable {
        final HarnessClient net;
        final World world;
        final ClientCubeStreamer streamer = new ClientCubeStreamer();
        final long spawnX, spawnY, spawnZ;
        int consumed;
        long nextClientTick;

        Rig(ServerHarness server, String name) throws InterruptedException {
            this(HarnessClient.join(server, name));
        }

        Rig(HarnessClient client) throws InterruptedException {
            this(client, null);
        }

        /** {@code viewerY}: the client stream's viewer height (null: the spawn's). */
        Rig(HarnessClient client, Long viewerY) throws InterruptedException {
            net = client;
            WelcomeS2C w = net.await(WelcomeS2C.class, HarnessClient.PROMPT).orElseThrow();
            spawnX = (long) Math.floor(w.spawnX());
            spawnY = (long) Math.floor(w.spawnY());
            spawnZ = (long) Math.floor(w.spawnZ());
            net.send(new ViewDistanceC2S(VIEW_CHUNKS, VIEW_VERTICAL));
            net.reportAtSpawn();
            world = World.createClientView(new WorldConfiguration(VIEW_CHUNKS, 4), w.worldSeed(),
                TerrainGeneratorType.STANDARD);
            world.setStreamCenters(List.of(new StreamCenter(spawnX, viewerY != null ? viewerY : spawnY, spawnZ,
                VIEW_CHUNKS * WorldConfiguration.CHUNK_SIZE, VIEW_VERTICAL, World.BAND_BELOW, World.BAND_ABOVE)));
        }

        /** One frame: received packets in order, the stream tick, installs, and (20 Hz) the client tick. */
        void frame() {
            List<Packet> all = net.received();
            for (; consumed < all.size(); consumed++) {
                Packet p = all.get(consumed);
                switch (p) {
                    case CubeDataS2C cd -> streamer.accept(cd);
                    case BlockStateNamesS2C n -> streamer.acceptNames(n);
                    case BlockChangeS2C b -> {
                        if (!streamer.runAfterPendingInstall(b.x(), b.y(), b.z(), () -> apply(b))) {
                            apply(b);
                        }
                    }
                    case MultiBlockChangeS2C m -> applyMulti(m);
                    default -> { }
                }
            }
            world.tickStream();
            streamer.pump(world, 8_000_000L);
            long now = System.nanoTime();
            if (now >= nextClientTick) {
                nextClientTick = now + 50_000_000L;
                streamer.clientTick(world, net::send);
            }
        }

        void apply(BlockChangeS2C b) {
            world.setBlockAt(b.x(), b.y(), b.z(), resolve(b.blockTypeId()), false);
        }

        void applyMulti(MultiBlockChangeS2C m) {
            for (int v : m.packed()) {
                int local = (v >>> 16) & 0xFFFF;
                world.setBlockAt((m.sectionX() << 4) + ((local >> 8) & 0xF), (m.sectionY() << 4) + ((local >> 4) & 0xF),
                    (m.sectionZ() << 4) + (local & 0xF), resolve((short) (v & 0xFFFF)), false);
            }
        }

        private static BlockType resolve(short id) {
            BlockType t = BlockType.getById(id & 0xFFFF);
            return t == null ? BlockType.AIR : t;
        }

        @Override
        public void close() {
            net.close();
            world.cleanup();
        }
    }

    private static void frameUntil(List<Rig> rigs, BooleanSupplier done, String what) throws InterruptedException {
        frameUntil(rigs, done, what, TIMEOUT_NS);
    }

    private static void frameUntil(List<Rig> rigs, BooleanSupplier done, String what, long timeoutNs)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutNs;
        while (!done.getAsBoolean()) {
            for (Rig r : rigs) {
                r.frame();
            }
            if (System.nanoTime() > deadline) {
                StringBuilder sb = new StringBuilder("timed out waiting for " + what);
                for (Rig r : rigs) {
                    sb.append("; client installed ").append(r.streamer.cubesInstalled())
                        .append(" queued ").append(r.streamer.queuedRecords())
                        .append(" stats ").append(r.world.streamStats());
                }
                throw new AssertionError(sb.toString());
            }
            Thread.sleep(2);
        }
    }

    /** Cubes the server believes {@code name} holds (read on the tick thread). */
    private int sentTo(String name) throws Exception {
        return server.onTick(() -> server.ctx().players().stream().filter(p -> name.equals(p.username()))
            .mapToInt(p -> p.cubeView().sent.size()).sum());
    }

    private int serverBlock(long x, long y, long z) throws Exception {
        return server.onTick(() -> server.ctx().world().getBlockIdAt(x, y, z));
    }

    @Test
    void singleplayerHostGetsCubesOverTheLocalChannel() throws Exception {
        server = ServerHarness.bootWithLocal(home, "local-447");
        try (Rig host = new Rig(HarnessClient.joinLocal(server, "Host"))) {
            List<Rig> one = List.of(host);
            frameUntil(one, () -> host.world.isColumnReadyAt(host.spawnX, host.spawnZ), "the spawn column READY");
            long x = host.spawnX, z = host.spawnZ;
            long top = host.world.topY(x, z);
            for (long y = top - 16; y < top; y++) {
                assertEquals(serverBlock(x, y, z), host.world.getBlockIdAt(x, y, z), "block at y " + y);
            }
            long ey = top + 1;
            server.onTick(() -> server.ctx().world().setBlockAt(x, ey, z, BlockType.COBBLESTONE));
            frameUntil(one, () -> host.world.getBlockAt(x, ey, z) == BlockType.COBBLESTONE, "the edit on the host");

            // A client copy that diverged (a lost delta) is repaired by the hash audit: the cube comes again.
            long ty = top - 3;
            int truth = serverBlock(x, ty, z);
            BlockType wrong = truth == BlockType.GRAVEL.getId() ? BlockType.SAND : BlockType.GRAVEL;
            host.world.setBlockAt(x, ty, z, wrong, false);
            long auditAt = System.nanoTime() + 2_600_000_000L; // past the server's post-send audit grace
            frameUntil(one, () -> System.nanoTime() > auditAt, "the audit grace");
            host.streamer.sendAudit(host.world, host.net::send, -1);
            frameUntil(one, () -> host.world.getBlockIdAt(x, ty, z) == truth, "the audit's re-send");
            assertNull(server.tickFailure(), "no server tick failed");
        }
    }

    @Test
    void cubesStreamToTwoClientsAndEditsReplicate() throws Exception {
        server = ServerHarness.boot(home, "cubes-447");
        try (Rig ann = new Rig(server, "Ann"); Rig ben = new Rig(server, "Ben")) {
            List<Rig> both = List.of(ann, ben);
            frameUntil(both, () -> ann.world.isColumnReadyAt(ann.spawnX, ann.spawnZ)
                && ben.world.isColumnReadyAt(ben.spawnX, ben.spawnZ), "the spawn column READY on both clients");

            // The terrain the client holds is the server's, cell for cell, through the surface.
            long x = ann.spawnX, z = ann.spawnZ;
            long top = ann.world.topY(x, z);
            assertEquals(server.onTick(() -> server.ctx().world().topY(x, z)).longValue(), top,
                "the client's column top comes from the server's column record");
            for (long y = top - 24; y < top + 4; y++) {
                assertTrue(ann.world.isLoaded(x, y, z) || ann.world.isKnown(x, y, z), "cell " + y + " known");
                assertEquals(serverBlock(x, y, z), ann.world.getBlockIdAt(x, y, z), "block at y " + y);
            }

            // Far above the surface: known air from the column record, no cube needed.
            long sky = Math.min(WorldConfiguration.MAX_Y - 1, top + 200);
            assertTrue(!ann.world.isLoaded(x, sky, z) && ann.world.isKnown(x, sky, z), "known air above the top");
            assertEquals(BlockType.AIR, ann.world.getBlockAt(x, sky, z));

            // The block-state registry is mirrored (the fixed names at least, in the server's order).
            List<String> serverNames = server.onTick(() -> server.ctx().world().blockStates().names());
            assertEquals(serverNames, ann.world.blockStates().names().subList(0, serverNames.size()));

            // An authoritative edit reaches every holder as a delta.
            long ey = top;
            server.onTick(() -> server.ctx().world().setBlockAt(x, ey, z, BlockType.COBBLESTONE));
            frameUntil(both, () -> ann.world.getBlockAt(x, ey, z) == BlockType.COBBLESTONE
                && ben.world.getBlockAt(x, ey, z) == BlockType.COBBLESTONE, "the edit on both clients");

            // Building in KNOWN AIR (above the column top, no cube anywhere): the server creates the cube, the
            // raised top goes out as a fresh column record, and the clients' bands pull the new cube in.
            long ay = top + 40;
            server.onTick(() -> {
                World w = server.ctx().world();
                com.stonebreak.network.server.handlers.ServerBlockHandler.ensureEditableCube(w, x, ay, z);
                return w.setBlockAt(x, ay, z, BlockType.COBBLESTONE);
            });
            assertEquals(BlockType.COBBLESTONE.getId(), serverBlock(x, ay, z), "the server built in known air");
            frameUntil(both, () -> ann.world.getBlockAt(x, ay, z) == BlockType.COBBLESTONE
                && ben.world.getBlockAt(x, ay, z) == BlockType.COBBLESTONE, "the sky block on both clients");

            // A server write that replicates NO delta (setBlockRaw: tree growth, stamps) moves the held cube's
            // revision; the server stream (NO_MESH, no REMESHED) reports it as CV_EV_CHANGED and the cube is
            // re-sent to every holder. The revision sweep is off, so the CHANGED path alone must do it - within a few
            // seconds (the client's hash audit, 16 cubes / 10 s, would repair it too, but only after ~40 s here).
            long ry = top - 2;
            int before = serverBlock(x, ry, z);
            BlockType raw = before == BlockType.BRICKS_BLOCK.getId() ? BlockType.GRAVEL : BlockType.BRICKS_BLOCK;
            com.stonebreak.network.server.handlers.ServerChunkHandler.setRevisionSweepForTests(false);
            try {
                server.onTick(() -> {
                    server.ctx().world().setBlockRaw(x, ry, z, raw);
                    return null;
                });
                frameUntil(both, () -> ann.world.getBlockAt(x, ry, z) == raw && ben.world.getBlockAt(x, ry, z) == raw,
                    "the unreplicated write re-sent to both clients", 5_000_000_000L);
            } finally {
                com.stonebreak.network.server.handlers.ServerChunkHandler.setRevisionSweepForTests(true);
            }

            // A client that unloads everything tells the server, which forgets its cubes.
            ann.world.setStreamCenters(List.of());
            frameUntil(both, () -> {
                try {
                    return ann.world.getLoadedCubeCount() == 0 && sentTo("Ann") == 0;
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }, "Ann's unloads on the server");
            assertTrue(sentTo("Ben") > 0, "Ben keeps his");
            assertNull(server.tickFailure(), "no server tick failed");
        }
    }

    /**
     * A viewer flown far above the world (y 1000, above the ellipsoid's reach): the REMOTE world has no surface
     * callback, so the column's band - what keeps the terrain loaded at any height - needs the server's column
     * record. Before the fix nothing asked for it (records came only with cubes the ellipsoid wanted), so a
     * column reached at altitude never loaded; and the server dropped every position report above y 896, so its
     * stream center stayed behind. Now the client asks for the top cube of each unknown column of its disc and
     * the server answers with the record; the report at y 1000 is accepted.
     */
    @Test
    void viewerAboveTheWorldStillLoadsTheSurfaceBand() throws Exception {
        server = ServerHarness.bootWithLocal(home, "above-world");
        long highY = WorldConfiguration.MAX_Y + 360;
        try (Rig host = new Rig(HarnessClient.joinLocal(server, "Host"), highY)) {
            host.net.send(new com.stonebreak.network.packet.player.PlayerStateC2S(host.spawnX + 0.5, highY,
                host.spawnZ + 0.5, 0f, -89f, (short) 0));
            List<Rig> one = List.of(host);
            frameUntil(one, () -> host.world.isColumnReadyAt(host.spawnX, host.spawnZ),
                "the spawn column READY for a viewer at y " + highY);
            assertTrue(host.world.voxels().topRange(host.spawnX >> 5, host.spawnZ >> 5).known(), "record arrived");
            double serverY = server.onTick(() -> server.ctx().players().stream()
                .filter(p -> "Host".equals(p.username())).mapToDouble(ServerPlayer::y).findFirst().orElse(-1));
            assertEquals(highY, serverY, 1e-6, "the server accepted the report above the world");
            assertNull(server.tickFailure(), "no server tick failed");
        }
    }
}
