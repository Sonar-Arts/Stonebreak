package com.stonebreak.world.save.diagnostics;

import com.openmason.engine.voxel.world.StreamStats;
import com.stonebreak.core.Game;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.save.SaveService;

import java.nio.file.Path;

/**
 * Console diagnostics for the save system (F-key debug hooks). Since the cubic voxel runtime (#447) cubes are
 * saved by the AUTHORITATIVE world's native stream into its cv_store ({@code <world>/cubes}): these dumps report
 * that stream's save counters and a header check of the store ({@link CubeStoreCheck}), and the client view's
 * cube under a column.
 */
public final class SaveSystemDiagnostics {

    private SaveSystemDiagnostics() {
    }

    /** The world that saves: the integrated server's (singleplayer / host), else the game's world. */
    private static World savingWorld() {
        try {
            var server = MultiplayerSession.getServer();
            var level = server != null ? server.worldContext().serverLevel() : null;
            if (level != null) {
                return level.world();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no server in this session
        }
        return Game.getWorld();
    }

    public static void printDiagnostics() {
        System.out.println("\n=== SAVE SYSTEM DIAGNOSTICS ===");
        World world = savingWorld();
        Game game = Game.getInstance();
        SaveService saveService = game != null ? game.getSaveService() : null;
        if (saveService != null) {
            System.out.println("World path   : " + saveService.getWorldPath());
        }
        if (world == null) {
            System.out.println("World not loaded");
            System.out.println("=================================\n");
            return;
        }
        System.out.println("World mode   : " + world.mode());
        System.out.println("Cube store   : " + (world.cubeStoreDir() != null ? world.cubeStoreDir() : "none (not saved)"));
        System.out.println("Loaded cubes : " + world.getLoadedCubeCount());
        StreamStats stats = world.streamStats();
        if (stats != null) {
            System.out.printf("Stream       : loaded=%d generated=%d fromStore=%d saved=%d unloaded=%d errors=%d%n",
                stats.cubesLoaded(), stats.generatedTotal(), stats.loadedTotal(), stats.savedTotal(),
                stats.unloadedTotal(), stats.errorsTotal());
        }
        Path storeDir = world.cubeStoreDir();
        if (storeDir != null && storeDir.getParent() != null) {
            // Header-level check only: reading every record of a live world would stall the I/O it shares.
            CubeStoreCheck.Report report = CubeStoreCheck.check(storeDir.getParent(), null, false);
            System.out.println("Store check  : " + report);
            report.problems().stream().limit(20).forEach(p -> System.out.println("  PROBLEM " + p));
        }
        System.out.println("=================================\n");
    }

    /** Reports the cube holding block column (chunkX * 16, chunkZ * 16) at its surface, and its saved record. */
    /** Prints the column's surface cube state and its saved record at block column {@code (x, z)}. */
    public static void diagnoseCubeColumn(String worldName, long x, long z) {
        System.out.println("\n=== CUBE DIAGNOSTICS (" + worldName + " @ column " + x + "," + z + ") ===");
        World world = savingWorld();
        if (world == null) {
            System.out.println("World not loaded in memory");
            return;
        }
        long top = world.topY(x, z);
        Cube cube = world.getCubeAt(x, top - 1, z);
        System.out.println("Column top y : " + top + ", surface y: " + world.surfaceY(x, z));
        if (cube == null) {
            System.out.println("Surface cube not loaded");
        } else {
            System.out.println("Surface cube : " + cube.pos() + " payload states=" + cube.payloadStates().size()
                + " revision=" + world.voxels().cubeRevision(cube.cx(), cube.cy(), cube.cz())
                + " payloadDirty=" + cube.isPayloadDirty());
        }
        var store = world.cubeStore();
        if (store != null) {
            long cx = x >> 5;
            long cy = (top - 1) >> 5;
            long cz = z >> 5;
            try {
                byte[] record = store.readCube(cx, cy, cz);
                System.out.println("Saved record : " + (record == null ? "none (never saved or not yet)"
                    : record.length + " bytes"));
            } catch (RuntimeException e) {
                System.out.println("Saved record : UNREADABLE (" + e.getMessage() + ")");
            }
        }
    }

}
