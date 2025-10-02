package com.stonebreak.world.save;

/**
 * Test runner for SaveSystemDiagnostics.
 * Runs diagnostics for the "test 3" world at coordinates X=23, Y=71, Z=37.
 */
public class DiagnosticRunner {

    public static void main(String[] args) {
        System.out.println("Starting Save System Diagnostic for 'test 3' world...\n");

        // Convert world coordinates to chunk coordinates
        // Chunk coordinates are world coordinates divided by 16 (chunk size)
        int worldX = 23;
        int worldY = 71;  // Y coordinate is not used for chunk lookup, but included for reference
        int worldZ = 37;

        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);

        System.out.println("World coordinates: (" + worldX + ", " + worldY + ", " + worldZ + ")");
        System.out.println("Chunk coordinates: (" + chunkX + ", " + chunkZ + ")");
        System.out.println("Local block position in chunk: (" +
            Math.floorMod(worldX, 16) + ", " + worldY + ", " +
            Math.floorMod(worldZ, 16) + ")");
        System.out.println();

        String worldName = "test 3";

        // Run comprehensive diagnostic
        SaveSystemDiagnostics.diagnoseChunkLoading(worldName, chunkX, chunkZ);

        System.out.println("\n");

        // Run quick diagnostic
        System.out.println("Running quick diagnostic check...\n");
        SaveSystemDiagnostics.quickDiagnostic();

        System.out.println("\n");

        // Optional: Test round-trip save/load
        System.out.println("Testing save/load round-trip for chunk (" + chunkX + ", " + chunkZ + ")...\n");
        SaveSystemDiagnostics.testSaveLoadRoundTrip(worldName, chunkX, chunkZ);
    }
}
