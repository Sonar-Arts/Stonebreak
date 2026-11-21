package com.stonebreak.ui.debug;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.openmason.engine.ui.masonry.MStatPanel;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.config.TerrainScaleOption;
import com.stonebreak.world.generation.NativeCubeGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

import static com.stonebreak.ui.debug.DebugFormat.getCardinalDirection;
import static com.stonebreak.ui.debug.DebugFormat.truncate;

/**
 * The right-hand "Debug Info" card: player position, cube coords, facing,
 * surface/top Y + generator column, targeted block/water, cube-stream + network counters,
 * nearby mobs' stealth perception, graphics pipeline stats and GPU identity. Reads its numbers through
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

        var pos = player.getPosition();
        long x = (long) Math.floor(pos.x);
        long y = (long) Math.floor(pos.y);
        long z = (long) Math.floor(pos.z);
        long cubeX = x >> WorldConfiguration.CUBE_SHIFT;
        long cubeY = y >> WorldConfiguration.CUBE_SHIFT;
        long cubeZ = z >> WorldConfiguration.CUBE_SHIFT;
        int edge = WorldConfiguration.CUBE_EDGE - 1;

        String facing = getCardinalDirection(player.getCamera().getFront());
        BlockType blockBelow = world.getBlockAt(x, y - 1, z);
        String blockName = blockBelow != null ? blockBelow.name() : "Unknown";

        // Targeted block info
        String targetedLine = diagnostics.getTargetedBlockSummary(player);

        MStatPanel panel = new MStatPanel("Debug Info")
            .row("XYZ", String.format("%.2f / %.2f / %.2f", (double) pos.x, (double) pos.y, (double) pos.z))
            .row("Block", String.format("%d / %d / %d", x, y, z))
            .row("Cube", String.format("%d %d %d in %d %d %d%s", x & edge, y & edge, z & edge, cubeX, cubeY, cubeZ,
                world.isLoaded(x, y, z) ? "" : (world.isKnown(x, y, z) ? " (known air)" : " (missing)")))
            .row("Facing", facing);

        panel.section("Terrain");
        panel.row("Surface", String.format("sky %d / top %d, column %s", world.surfaceY(x, z), world.topY(x, z),
            world.isColumnReadyAt(x, z) ? "READY" : "loading"));
        panel.row("Noise Backend", DebugDiagnostics.noiseBackendSummary());
        panel.row("Block Below", blockName);
        try {
            NativeCubeGenerator.ColumnInfo column = world.terrain().queryColumn(x, z);
            BiomeType[] biomes = BiomeType.values();
            int b = column.biome();
            panel.row("Biome", b >= 0 && b < biomes.length ? biomes[b].name() : "#" + b);
            panel.row("Generator", String.format("h %d / carved %d / water %s, scale %s", column.height(),
                column.carvedHeight(), column.waterLevel() == NativeCubeGenerator.NO_WATER ? "-"
                    : String.valueOf(column.waterLevel()),
                TerrainScaleOption.label(world.getTerrainScale()))
                + (world.getGeneratorType() == com.stonebreak.world.generation.TerrainGeneratorType.DIFFUSION
                    ? ", climate " + world.getClimateLayout().value() : ""));
        } catch (RuntimeException e) {
            // Generator library missing: the rows are simply absent.
        }

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
        var stream = world.streamStats();
        panel.row("Cubes", DebugDiagnostics.streamCubesSummary(stream));
        panel.row("Stream", diagnostics.chunkPipelineSummary());
        panel.row("Stream Tick", DebugDiagnostics.streamTickSummary(stream));
        var server = DebugDiagnostics.serverStreamStats();
        if (server != null) {
            panel.row("Server Cubes", DebugDiagnostics.streamCubesSummary(server));
            panel.row("Server Stream", diagnostics.serverPipelineSummary());
            panel.row("Server Tick", DebugDiagnostics.streamTickSummary(server));
        }
        panel.row("View", String.format("%d blocks out, %d up/down",
            world.getConfig().getHorizontalRenderRadiusBlocks(), world.getConfig().getVerticalRenderDistance()));
        // Terrain draws: cube meshes, cube/region draws, GPU cull, visibility walk + missing + proxies, Hi-Z,
        // handover, LOD, uploads (3B-R).
        for (String[] r : com.stonebreak.rendering.gameWorld.TerrainRenderStats.rows()) {
            panel.row(r[0], r[1]);
        }
        com.stonebreak.fx.FxRuntime fx = com.stonebreak.fx.Fx.installed();
        if (fx != null) {
            // Particle VRAM is the "Particles" row of the VRAM panel (GpuMemoryTracker PARTICLES).
            panel.row("Particles", !com.stonebreak.fx.Fx.enabled() ? "off"
                : String.format("%,d / %,d live, %d FX, %.2f ms sim", fx.liveParticles(), fx.budget(),
                    fx.instanceCount(), fx.lastUpdateNanos() / 1e6));
        }
        panel.row("Nav", DebugDiagnostics.navigationSummary(world));
        com.stonebreak.world.TimeOfDay clock = Game.getTimeOfDay();
        if (clock != null) {
            panel.row("Time", clock.getTimeString());
            float phase = com.stonebreak.world.MoonCycle.phase(clock);
            panel.row("Moon", String.format("%s %d%% (day %d)",
                com.stonebreak.world.MoonCycle.phaseName(phase),
                Math.round(com.stonebreak.world.MoonCycle.illuminatedFraction(phase) * 100f), clock.getDay()));
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

        PerceptionDebug.appendSection(panel);

        panel.section("Graphics");
        if (stream != null && stream.meshedTotal() > 0) {
            panel.row("Mesh Builds", String.format("%,d built, %d pending, %,d KiB waiting, %d stale",
                stream.meshedTotal(), stream.meshesPending(), stream.meshBytesPending() >> 10,
                stream.staleMeshesTotal()));
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
