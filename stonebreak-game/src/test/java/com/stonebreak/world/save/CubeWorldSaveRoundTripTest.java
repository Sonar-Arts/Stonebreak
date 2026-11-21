package com.stonebreak.world.save;

import com.openmason.engine.voxel.world.CubePos;
import com.openmason.engine.voxel.world.StreamCenter;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.NativeTerrain;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.diagnostics.CubeStoreCheck;
import com.stonebreak.world.save.io.CubeRegionFiles;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.WorldData;
import org.joml.Vector3d;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The save pipeline on the native cube runtime (#447, 3B-S): a generated + edited authoritative world saved
 * through {@link SaveService} (blocks, water / snow / state layers, payload states, a save-hook blob section, the
 * player at a far double position) comes back after a reopen; save points are durable at once (the committed
 * region headers on disk list the cube - what a crash right after would keep); an autosave taken while the world
 * streams runs its blob flush on the OWNER thread; close runs the save hooks once, cleanup never.
 * Needs libstonebreak_worldgen (SBW ABI 3) - skipped without it.
 */
@Tag("integration")
@Timeout(300)
class CubeWorldSaveRoundTripTest {

    private static final long SEED = 4242L;
    private static final long TIMEOUT_NANOS = 120_000_000_000L;
    private static final String HOOK_SECTION = "test-hook";

    private static World open(Path dir) {
        return World.createHeadless(new WorldConfiguration(4, 4), SEED, TerrainGeneratorType.STANDARD,
            TerrainScalePreset.ONE_TO_FOUR, dir);
    }

    private static void requireNatives() {
        assumeTrue(NativeTerrain.isAvailable(), () -> "worldgen natives: "
            + com.stonebreak.world.generation.NativeCubeGenerator.unavailableReason());
    }

    private static void tickUntil(World world, Queue<Runnable> ownerTasks, BooleanSupplier done, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (!done.getAsBoolean()) {
            world.tickStream();
            drain(ownerTasks);
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what + "; stats " + world.streamStats());
            }
            Thread.sleep(2);
        }
    }

    private static void drain(Queue<Runnable> tasks) {
        if (tasks == null) {
            return;
        }
        Runnable r;
        while ((r = tasks.poll()) != null) {
            r.run();
        }
    }

    private static StreamCenter centerAt(long x, long y, long z) {
        return new StreamCenter(x, y, z, 48, 48, World.BAND_BELOW, World.BAND_ABOVE);
    }

    private static SaveService service(Path dir, World world, PlayerData player, Executor owner) {
        SaveService svc = new SaveService(dir.toString());
        WorldData data = WorldData.builder().seed(SEED).worldName(dir.getFileName().toString()).build();
        svc.initialize(data, null, world);
        svc.setWorldTimeSource(new TimeOfDay(0));
        svc.setWorldOwner(owner);
        if (player != null) {
            svc.bindPlayerSource(name -> player, Runnable::run);
        }
        return svc;
    }

    /** Committed (on-disk) cube keys of the store: what survives a crash right now. */
    private static boolean committed(Path worldDir, long cx, long cy, long cz) throws Exception {
        for (Path f : CubeRegionFiles.regionFiles(worldDir.resolve(WorldFormat.CUBES_DIR))) {
            if (!f.getFileName().toString().endsWith(".cvr")) {
                continue;
            }
            long[] keys = CubeRegionFiles.read(f).cubeKeys();
            for (int i = 0; i < keys.length; i += 3) {
                if (keys[i] == cx && keys[i + 1] == cy && keys[i + 2] == cz) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void generatedAndEditedWorldRoundTripsThroughTheSaveService(@TempDir Path tmp) throws Exception {
        requireNatives();
        Path dir = Files.createDirectories(tmp.resolve("RoundTrip"));
        long x = 7, z = 9;
        long top;
        Vector3d far = new Vector3d(4_294_967_296.5, 70.25, -8_589_934_592.75);
        PlayerData player = PlayerData.builder().worldName("RoundTrip").position(far).health(13f).build();
        AtomicInteger hookCalls = new AtomicInteger();

        World world = open(dir);
        SaveService svc = service(dir, world, player, Runnable::run);
        try {
            world.addSaveHook((w, cube) -> hookCalls.incrementAndGet());
            world.setStreamCenters(List.of(centerAt(x, world.topY(x, z), z)));
            tickUntil(world, null, () -> world.isColumnReadyAt(x, z), "the viewer's column READY");

            top = world.topY(x, z);
            long y = top;
            assertTrue(world.setBlockAt(x, y, z, BlockType.BRICKS_BLOCK));
            world.setBlockStateAt(x, y, z, "test:state=v1;payload=xyz");
            assertTrue(world.setBlockAt(x + 1, y, z, BlockType.WATER));
            assertTrue(world.setWaterValueAt(x + 1, y, z, 3));
            assertTrue(world.setBlockAt(x + 2, y, z, BlockType.SNOW));
            world.getSnowLayerManager().setSnowLayers(x + 2, y, z, 5);
            Cube cube = world.getCubeAt(x, y, z);
            assertNotNull(cube);
            cube.putBlobSection(HOOK_SECTION, "hello".getBytes(StandardCharsets.UTF_8));

            svc.saveAll().get(60, TimeUnit.SECONDS);

            // Durable at once: the committed header on disk lists the edited cube, the block-state table and the
            // records are there, and the store checks clean (read back + decoded + blob parsed).
            assertTrue(committed(dir, x >> 5, y >> 5, z >> 5), "the edited cube is committed after the save point");
            assertTrue(Files.readString(dir.resolve("cubes").resolve(World.BLOCK_STATES_FILE)).contains("v1"));
            CubeStoreCheck.Report report = CubeStoreCheck.check(dir, world.cubeStore(), false);
            assertTrue(report.healthy(), report.problems().toString());
            assertTrue(report.cubeRecords() > 0 && report.recordsRead() == report.cubeRecords(), report.toString());
            assertTrue(Files.exists(dir.resolve("player.json")) && Files.exists(dir.resolve("metadata.json")));
        } finally {
            svc.close();
            world.cleanup();
        }
        assertTrue(hookCalls.get() >= 1, "save hooks ran at the save point");

        // Offline check (store closed): every record reads back and decodes.
        CubeStoreCheck.Report offline = CubeStoreCheck.check(dir, null, true);
        assertTrue(offline.healthy(), offline.problems().toString());

        World again = open(dir);
        try (SaveService svc2 = new SaveService(dir.toString())) {
            SaveService.LoadResult lr = svc2.loadWorld().get(30, TimeUnit.SECONDS);
            assertTrue(lr.isSuccess(), lr.getError());
            assertEquals(WorldFormat.CURRENT_VERSION, lr.getWorldData().getFormatVersion());
            assertEquals(far, lr.getPlayerData().getPosition(), "player.json keeps the double position");
            assertEquals(13f, lr.getPlayerData().getHealth());

            long t = top;
            again.setStreamCenters(List.of(centerAt(x, t, z)));
            tickUntil(again, null, () -> again.isLoaded(x, t, z) && again.isLoaded(x + 2, t, z),
                "the edited cube to load from the store");
            assertEquals(BlockType.BRICKS_BLOCK, again.getBlockAt(x, t, z));
            assertEquals("test:state=v1;payload=xyz", again.getBlockStateAt(x, t, z), "payload + variant state");
            assertEquals(BlockType.WATER, again.getBlockAt(x + 1, t, z));
            assertEquals(3, again.getWaterValueAt(x + 1, t, z));
            assertEquals(BlockType.SNOW, again.getBlockAt(x + 2, t, z));
            assertEquals(5, again.getSnowLayers(x + 2, t, z));
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8),
                again.getCubeAt(x, t, z).blobSection(HOOK_SECTION), "game blob sections come back");
            assertEquals(t + 1, again.topY(x, z));
        } finally {
            again.cleanup();
        }
    }

    @Test
    void autosaveWhileStreamingFlushesBlobsOnTheOwnerThreadAndIsDurable(@TempDir Path tmp) throws Exception {
        requireNatives();
        Path dir = Files.createDirectories(tmp.resolve("Autosave"));
        Queue<Runnable> ownerTasks = new ConcurrentLinkedQueue<>();
        Thread owner = Thread.currentThread();
        AtomicReference<Thread> hookThread = new AtomicReference<>();
        AtomicInteger hookCalls = new AtomicInteger();
        long x = 3, z = -5;

        World world = open(dir);
        SaveService svc = service(dir, world, null, ownerTasks::add);
        try {
            world.addSaveHook((w, cube) -> {
                hookThread.set(Thread.currentThread());
                hookCalls.incrementAndGet();
            });
            world.setStreamCenters(List.of(centerAt(x, world.topY(x, z), z)));
            tickUntil(world, ownerTasks, () -> world.isColumnReadyAt(x, z), "the first column READY");
            long top = world.topY(x, z);
            assertTrue(world.setBlockAt(x, top, z, BlockType.BRICKS_BLOCK));
            int callsBefore = hookCalls.get();

            // The viewer keeps moving (cubes install and unload) while the autosave runs on its own thread.
            CompletableFuture<Void> autosave = CompletableFuture.runAsync(svc::performAutoSave);
            long step = 0;
            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            while (!autosave.isDone() || svc.inFlightAutoSaveForTesting() != null
                    && !svc.inFlightAutoSaveForTesting().isDone()) {
                step++;
                world.setStreamCenters(List.of(centerAt(x + step / 4, top, z)));
                world.tickStream();
                drain(ownerTasks);
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("autosave never finished; stats " + world.streamStats());
                }
                Thread.sleep(2);
            }
            autosave.get(1, TimeUnit.SECONDS);
            CompletableFuture<Void> inFlight = svc.inFlightAutoSaveForTesting();
            if (inFlight != null) {
                inFlight.get(30, TimeUnit.SECONDS);
            }

            assertTrue(hookCalls.get() > callsBefore, "the autosave ran the save hooks");
            assertSame(owner, hookThread.get(), "save hooks run on the owner thread, never on the AutoSave thread");
            assertTrue(committed(dir, x >> 5, top >> 5, z >> 5), "the autosave made the edit durable");
            assertTrue(Files.exists(dir.resolve("metadata.json")));
        } finally {
            svc.close();
            world.cleanup();
        }
    }

    @Test
    void closeRunsTheSaveHooksOnceAndCleanupNever(@TempDir Path tmp) throws Exception {
        requireNatives();
        Path dir = Files.createDirectories(tmp.resolve("CloseOrder"));
        AtomicInteger hookCalls = new AtomicInteger();
        AtomicReference<Boolean> alive = new AtomicReference<>(true);
        long x = 0, z = 0;
        CubePos[] edited = new CubePos[1];

        World world = open(dir);
        // No world owner bound: autosaves would only commit; close flushes inline.
        SaveService svc = service(dir, world, null, null);
        world.addSaveHook((w, cube) -> {
            hookCalls.incrementAndGet();
            if (cube.pos().equals(edited[0])) {
                // the "entity manager" writes its snapshot; once torn down it would write an empty one
                cube.putBlobSection("entities-like", alive.get() ? new byte[]{1, 2, 3} : new byte[0]);
            }
        });
        world.setStreamCenters(List.of(centerAt(x, world.topY(x, z), z)));
        tickUntil(world, null, () -> world.isColumnReadyAt(x, z), "the column READY");
        long top = world.topY(x, z);
        edited[0] = CubePos.ofBlock(x, top - 1, z);

        svc.close();                 // final save point (inline): hooks run while the owner state is alive
        int afterClose = hookCalls.get();
        assertTrue(afterClose > 0, "close ran the save hooks");
        alive.set(false);            // entityManager.cleanup()
        world.cleanup();             // must not run the hooks again
        assertEquals(afterClose, hookCalls.get(), "World.cleanup() never runs save hooks");

        World again = open(dir);
        try {
            again.setStreamCenters(List.of(centerAt(x, top, z)));
            tickUntil(again, null, () -> again.getCube(edited[0].x(), edited[0].y(), edited[0].z()) != null,
                "the edited cube to load");
            assertArrayEquals(new byte[]{1, 2, 3},
                again.getCube(edited[0].x(), edited[0].y(), edited[0].z()).blobSection("entities-like"),
                "the snapshot taken at close survived the teardown");
        } finally {
            again.cleanup();
        }
    }
}
