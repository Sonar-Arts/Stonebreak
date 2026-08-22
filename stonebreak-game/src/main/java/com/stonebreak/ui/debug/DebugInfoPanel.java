package com.stonebreak.ui.debug;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.UI.masonryUI.MStatPanel;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.joml.Vector3f;

import static com.stonebreak.ui.debug.DebugFormat.getCardinalDirection;
import static com.stonebreak.ui.debug.DebugFormat.truncate;

/**
 * The right-hand "Debug Info" card: player position, chunk coords, facing,
 * terrain noise channels, targeted block/water, world + network counters,
 * graphics pipeline stats and GPU identity. Reads its numbers through
 * {@link DebugDiagnostics} and {@link GpuInfoProbe}.
 */
public final class DebugInfoPanel implements DebugPanel {

    private final DebugDiagnostics diagnostics;
    private final GpuInfoProbe gpu;

    public DebugInfoPanel(DebugDiagnostics diagnostics, GpuInfoProbe gpu) {
        this.diagnostics = diagnostics;
        this.gpu = gpu;
    }

    /**
     * Builds the right-side debug info card with player position, chunk coords,
     * facing direction, block/biome info, FPS, and GPU details — rendered in the
     * same stone-surface style as the RAM/VRAM panels.
     */
    @Override
    public MStatPanel build() {
        Player player = Game.getPlayer();
        World world = Game.getWorld();

        if (player == null || world == null) {
            return new MStatPanel("Debug").row("Player or World unavailable", "");
        }

        Vector3f pos = player.getPosition();
        int x = (int) Math.floor(pos.x);
        int y = (int) Math.floor(pos.y);
        int z = (int) Math.floor(pos.z);
        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        BiomeType biome = world.terrain().getBiomeAt(x, z);
        String facing = getCardinalDirection(player.getCamera().getFront());
        BlockType blockBelow = world.getBlockAt(x, y - 1, z);
        String blockName = blockBelow != null ? blockBelow.name() : "Unknown";

        // Noise channels driving terrain shape
        float continentalness = world.terrain().getContinentalnessAt(x, z);
        float erosion = world.terrain().getErosionAt(x, z);
        float peaksValleys = world.terrain().getPeaksValleysAt(x, z);
        int baseHeight = world.terrain().getBaseHeightAt(x, z);
        int shapedHeight = world.terrain().getShapedHeightAt(x, z);
        int finalHeight = world.terrain().getFinalTerrainHeightAt(x, z);

        // Targeted block info
        String targetedLine = diagnostics.getTargetedBlockSummary(player);

        MStatPanel panel = new MStatPanel("Debug Info")
            .row("XYZ", String.format("%d / %d / %d", x, y, z))
            .row("Chunk", String.format("%d %d in %d %d", x & 15, z & 15, chunkX, chunkZ))
            .row("Facing", facing);

        panel.section("Terrain");
        panel.row("Noise Backend", DebugDiagnostics.noiseBackendSummary());
        panel.row("Block Below", blockName);
        panel.row("Biome", biome.name());
        panel.row("Temperature", String.format("%.3f", world.terrain().getTemperatureAt(x, z)));
        panel.row("Moisture", String.format("%.3f", world.terrain().getMoistureAt(x, z)));
        panel.row("Continentalness", String.format("%.3f", continentalness));
        panel.row("Erosion", String.format("%.3f", erosion));
        panel.row("Peaks/Valleys", String.format("%.3f", peaksValleys));
        panel.row("Height", String.format("%d base / %d shaped (%+d) / %d final (%+d detail)",
                baseHeight, shapedHeight, shapedHeight - baseHeight,
                finalHeight, finalHeight - shapedHeight));

        // Targeted block + water (conditionally shown)
        if (targetedLine != null) {
            panel.section("Target");
            panel.row("Looking At", targetedLine);
        }
        String waterLine = diagnostics.getWaterStateSummary(player);
        if (waterLine != null) {
            panel.section("Water");
            panel.row("Looking At", waterLine);
        }

        panel.section("World");
        panel.row("FPS", String.format("%.0f (avg)", diagnostics.averageFPS()));
        panel.row("Chunks", String.format("%d loaded", world.getLoadedChunkCount()));
        panel.row("Pending Mesh", String.valueOf(world.getPendingMeshBuildCount()));
        panel.row("Pending GL", String.valueOf(world.getPendingGLUploadCount()));
        panel.row("Chunk Flow", diagnostics.chunkPipelineSummary());
        if (com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isEnabled()) {
            var regions = com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.getInstance();
            panel.row("Chunk Draws", String.format("%d cmds / %d region draws / %d legacy",
                regions.publishedCommands(), regions.publishedRegionDraws(),
                regions.publishedLegacyDraws()));
            if (com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isGpuCullEnabled()) {
                panel.row("GPU Cull", String.format("%d cmds / %d regions / %d pre-culled",
                    regions.publishedGpuCommands(), regions.publishedGpuRegionDraws(),
                    regions.publishedGpuPreCulledRegions()));
            }
            var lodBatcher = com.stonebreak.rendering.gameWorld.fastlod.FastLodRegionBatcher.active();
            if (lodBatcher != null) {
                panel.row("LOD Draws", String.format("%d cmds / %d region draws",
                    lodBatcher.publishedCommands(), lodBatcher.publishedRegionDraws()));
            }
        }
        panel.row("Nav", DebugDiagnostics.navigationSummary(world));
        com.stonebreak.world.TimeOfDay clock = Game.getTimeOfDay();
        if (clock != null) {
            panel.row("Time", clock.getTimeString());
        }
        if (com.stonebreak.network.MultiplayerSession.isInWorld()) {
            int rtt = com.stonebreak.network.MultiplayerSession.lastRttMs();
            panel.section("Network");
            panel.row("Mode", com.stonebreak.network.MultiplayerSession.getMode().name());
            panel.row("Ping", rtt >= 0 ? rtt + " ms" : "…");
            com.stonebreak.network.client.ClientWorldView cwv =
                com.stonebreak.network.MultiplayerSession.getClient();
            if (cwv != null) {
                panel.row("Entity Shadows", String.valueOf(cwv.trackedEntityShadows()));
            }
        }

        panel.section("Graphics");
        try {
            var meshStats = com.stonebreak.world.chunk.api.mightyMesh.MmsAPI.getInstance().getStatistics();
            if (meshStats.getMeshesGenerated() > 0) {
                panel.row("Mesh Gen", String.format("%.0f us avg (%d built)",
                    meshStats.getAverageGenerationTimeMicros(), meshStats.getMeshesGenerated()));
            }
        } catch (Exception ignored) {
            // MMS not initialized yet — row simply absent.
        }
        long greedyIn = com.openmason.engine.voxel.mms.mmsGeometry.MmsGreedyMesher.quadsIn();
        if (greedyIn > 0) {
            long greedyOut = com.openmason.engine.voxel.mms.mmsGeometry.MmsGreedyMesher.quadsOut();
            panel.row("Greedy Mesh", String.format("%,d -> %,d quads (-%.0f%%)",
                greedyIn, greedyOut, 100.0 * (greedyIn - greedyOut) / greedyIn));
        }
        if (com.stonebreak.world.generation.TerrainGenStats.chunkCount() > 0) {
            panel.row("Terrain Gen", String.format("%.0f us avg (%d chunks, %s)",
                com.stonebreak.world.generation.TerrainGenStats.averageMicros(),
                com.stonebreak.world.generation.TerrainGenStats.chunkCount(),
                com.stonebreak.world.generation.TerrainGenStats.modeSummary()));
        }
        gpu.queryGPUInfo();
        panel.row("GPU", truncate(gpu.gpuRenderer() != null ? gpu.gpuRenderer() : "Unknown", 30));
        panel.row("Vendor", truncate(gpu.gpuVendor() != null ? gpu.gpuVendor() : "Unknown", 25));
        panel.row("OpenGL", gpu.gpuVersion() != null ? gpu.gpuVersion() : "Unknown");

        panel.section("Debug");
        panel.row("Path Visual", "ON");

        return panel;
    }
}
