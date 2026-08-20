package com.stonebreak.ui;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.vram.VramPlan;
import com.openmason.engine.vram.VramPlans;
import com.stonebreak.rendering.UI.masonryUI.MStatPanel;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import org.joml.Vector3f;
import org.joml.Vector4f;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.rendering.sbo.SBOBlockBridge;
import com.stonebreak.player.Player;
import com.stonebreak.player.Camera;
import com.stonebreak.world.World;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.rendering.DebugRenderer;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.nav.Path;
import com.stonebreak.mobs.entities.ai.nav.AirPathAgent;
import com.stonebreak.mobs.entities.ai.nav.PathAgent;
import com.stonebreak.mobs.goose.Goose;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.server.IntegratedServer;
import java.util.List;
import java.util.ArrayDeque;

// OpenGL imports for GPU information
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

public class DebugOverlay {
    private boolean visible = false;

    // FPS averaging
    private static final int FPS_SAMPLE_SIZE = 60; // Average over 60 frames
    private ArrayDeque<Float> fpsHistory = new ArrayDeque<>(FPS_SAMPLE_SIZE);
    private float averageFPS = 0.0f;

    // GPU information cache (queried once to avoid repeated OpenGL calls)
    private String gpuVendor = null;
    private String gpuRenderer = null;
    private String gpuVersion = null;
    private boolean gpuInfoQueried = false;

    // VRAM query extension constants
    private static final int GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX = 0x9047;
    private static final int GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX = 0x9048;
    private static final int GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;
    private static final int TEXTURE_FREE_MEMORY_ATI = 0x87FC;

    // VRAM tracking
    private enum VramSource { NONE, NVIDIA, AMD }
    private VramSource vramSource = null;
    private long vramTotalKB = 0; // 0 if unknown

    // MasonryUI for the right-side debug panel and left-side resource panel. Lazily built once a Renderer exists.
    private MasonryUI masonryUI = null;

    // Cached panels — rebuilt periodically rather than every frame. Reading
    // MXBeans + GPU snapshot + building MStatPanel structures every frame
    // generates measurable churn while F3 is open; values change slowly enough
    // that 4 Hz is plenty for the resource panels. The debug panel gets a
    // faster cadence (20 Hz) so player position feels responsive.
    private static final long RESOURCE_PANEL_REBUILD_INTERVAL_MS = 250L;
    private static final long DEBUG_PANEL_REBUILD_INTERVAL_MS    =  50L;
    private long lastResourcePanelRebuildMs = 0L;
    private long lastDebugPanelRebuildMs    = 0L;
    private MStatPanel cachedRamPanel = null;
    private MStatPanel cachedVramPanel = null;
    private MStatPanel cachedDebugPanel = null;

    public DebugOverlay() {
    }

    public boolean isVisible() {
        return visible;
    }

    public void toggleVisibility() {
        visible = !visible;
    }

    /**
     * Forces the debug overlay to hide. Called automatically when leaving
     * gameplay (world exit or opening the settings menu).
     */
    public void hide() {
        visible = false;
    }

    /**
     * Returns a one-line summary of the block the player is looking at.
     * Used by the compact debug panel (MStatPanel). Returns null when nothing
     * is targeted.
     */
    private String getTargetedBlockSummary(Player player) {
        Camera camera = player.getCamera();
        World world = Game.getWorld();

        if (camera == null || world == null) return null;

        Vector3f position = player.getPosition();
        Vector3f rayOrigin = new Vector3f(position.x, position.y + 1.6f, position.z);
        Vector3f rayDirection = camera.getFront();

        for (float d = 0; d < 6.0f; d += 0.05f) {
            Vector3f point = new Vector3f(rayDirection).mul(d).add(rayOrigin);
            int bx = (int) Math.floor(point.x);
            int by = (int) Math.floor(point.y);
            int bz = (int) Math.floor(point.z);

            BlockType bt = world.getBlockAt(bx, by, bz);
            if (bt != null && bt != BlockType.AIR) {
                Renderer renderer = Game.getRenderer();
                SBOBlockBridge bridge = renderer != null ? renderer.getSBOBlockBridge() : null;
                String model = (bridge != null && bridge.isSBOBlock(bt)) ? "SBO" : "Mesh";
                return String.format("%s [%s] ~ (%d,%d,%d)", bt.name(), model, bx, by, bz);
            }
        }
        return null;
    }

    /**
     * Returns a one-line summary of the water block the player is looking at.
     * Returns null when not looking at water.
     */
    private String getWaterStateSummary(Player player) {
        Camera camera = player.getCamera();
        World world = Game.getWorld();

        if (camera == null || world == null) return null;

        Vector3f position = player.getPosition();
        Vector3f rayOrigin = new Vector3f(position.x, position.y + 1.6f, position.z);
        Vector3f rayDirection = camera.getFront();

        for (float d = 0; d < 5.0f; d += 0.05f) {
            Vector3f point = new Vector3f(rayDirection).mul(d).add(rayOrigin);
            int bx = (int) Math.floor(point.x);
            int by = (int) Math.floor(point.y);
            int bz = (int) Math.floor(point.z);

            BlockType bt = world.getBlockAt(bx, by, bz);
            if (bt == BlockType.WATER) {
                int value = world.getWaterLevelAt(bx, by, bz);
                String type = switch (value) {
                    case com.stonebreak.world.chunk.ChunkWaterLayer.SOURCE -> "Source";
                    case com.stonebreak.world.chunk.ChunkWaterLayer.FALLING -> "Falling";
                    default -> "Flowing " + value;
                };
                String queued = (world.getWaterSim() != null)
                        ? String.format(" (%d queued)", world.getWaterSim().getQueuedUpdateCount())
                        : "";
                return String.format("Water %s%s ~ (%d,%d,%d)", type, queued, bx, by, bz);
            }
            if (bt != null && bt != BlockType.AIR) break;
        }
        return null;
    }

    /**
     * Updates the average FPS calculation with the current frame's FPS.
     */
    private void updateAverageFPS() {
        float currentFPS = 1.0f / Game.getDeltaTime();

        // Add current FPS to history
        fpsHistory.addLast(currentFPS);

        // Remove oldest FPS if we exceed sample size
        if (fpsHistory.size() > FPS_SAMPLE_SIZE) {
            fpsHistory.removeFirst();
        }

        // Calculate average
        if (!fpsHistory.isEmpty()) {
            float sum = 0.0f;
            for (Float fps : fpsHistory) {
                sum += fps;
            }
            averageFPS = sum / fpsHistory.size();
        }
    }

    /**
     * Queries GPU information from OpenGL.
     * Only queries once and caches the results to avoid repeated OpenGL calls.
     */
    private void queryGPUInfo() {
        if (gpuInfoQueried) {
            return; // Already queried, use cached values
        }

        try {
            // Query GPU information from OpenGL
            gpuVendor = glGetString(GL_VENDOR);
            gpuRenderer = glGetString(GL_RENDERER);
            gpuVersion = glGetString(GL_VERSION);

            // Clean up the strings (remove null terminators and extra whitespace)
            if (gpuVendor != null) {
                gpuVendor = gpuVendor.trim();
            }
            if (gpuRenderer != null) {
                gpuRenderer = gpuRenderer.trim();
            }
            if (gpuVersion != null) {
                gpuVersion = gpuVersion.trim();
            }

            gpuInfoQueried = true;
        } catch (Exception e) {
            // If OpenGL query fails, set error messages
            gpuVendor = "Error querying GPU";
            gpuRenderer = "Error querying GPU";
            gpuVersion = "Error querying OpenGL version";
            gpuInfoQueried = true; // Don't try again
        }
    }

    /**
     * Detects which VRAM-query extension is available and caches total VRAM.
     */
    private void detectVramSource() {
        if (vramSource != null) {
            return;
        }
        try {
            GLCapabilities caps = GL.getCapabilities();
            if (caps.GL_NVX_gpu_memory_info) {
                vramSource = VramSource.NVIDIA;
                int dedicatedKB = glGetInteger(GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX);
                vramTotalKB = dedicatedKB > 0 ? dedicatedKB : glGetInteger(GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX);
            } else if (caps.GL_ATI_meminfo) {
                vramSource = VramSource.AMD;
                vramTotalKB = 0; // AMD extension only reports free memory
            } else {
                vramSource = VramSource.NONE;
            }
        } catch (Exception e) {
            vramSource = VramSource.NONE;
        }
    }

    /**
     * Builds the VRAM section of the debug text. Headline number is the
     * tracker's per-process total (what Stonebreak itself owns); the system
     * reading is shown as smaller context so the two aren't confused.
     */
    @SuppressWarnings("unused") // retained for callers/tests; left panel reads tracker directly
    private String getVramText() {
        StringBuilder out = new StringBuilder();

        GpuMemoryTracker.Snapshot snap = GpuMemoryTracker.getInstance().snapshot();
        long trackedTotal = snap.totalBytes();
        out.append(String.format("VRAM (Game): %s\n", formatBytes(trackedTotal)));

        // Per-category breakdown: only show non-zero categories.
        for (GpuMemoryTracker.Category c : GpuMemoryTracker.Category.values()) {
            long bytes = snap.bytesOf(c);
            if (bytes <= 0) continue;
            long count = snap.countOf(c);
            out.append(String.format("  %s: %s (%d)\n",
                shortCategoryName(c), formatBytes(bytes), count));
        }

        // System-wide GPU reading for context — labelled clearly so it's not
        // mistaken for our process footprint.
        out.append(getSystemVramText());
        return out.toString();
    }

    /** System-wide VRAM line — all processes combined, not just this one. */
    @SuppressWarnings("unused") // legacy text helper — superseded by systemVramSummary()
    private String getSystemVramText() {
        detectVramSource();
        try {
            switch (vramSource) {
                case NVIDIA -> {
                    int freeKB = glGetInteger(GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX);
                    if (vramTotalKB > 0) {
                        long usedMB = (vramTotalKB - freeKB) / 1024;
                        long totalMB = vramTotalKB / 1024;
                        return String.format("GPU System: %d/%d MB\n", usedMB, totalMB);
                    }
                    return String.format("GPU Free: %d MB\n", freeKB / 1024);
                }
                case AMD -> {
                    int freeKB = glGetInteger(TEXTURE_FREE_MEMORY_ATI);
                    return String.format("GPU Free: %d MB\n", freeKB / 1024);
                }
                default -> {
                    return "GPU System: N/A\n";
                }
            }
        } catch (Exception e) {
            return "GPU System: N/A\n";
        }
    }

    private static String shortCategoryName(GpuMemoryTracker.Category c) {
        return switch (c) {
            case CHUNK_MESH      -> "Chunk Meshes";
            case BUFFER_POOL_IDLE-> "Idle Pool";
            case TEXTURE_ATLAS   -> "Tex Atlas";
            case ENTITY_MESH     -> "Entity Meshes";
            case PLAYER_GEOMETRY -> "Player Geom";
            case SHADOW_MAP      -> "Shadow Maps";
            case OTHER           -> "Other";
        };
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s != null ? s : "";
        if (maxLen <= 3) return "...";
        return s.substring(0, maxLen - 3) + "...";
    }

    /**
     * Renders the RAM, VRAM, and debug info cards using MasonryUI/Skija.
     *
     * <p>Called from the main render loop <em>outside</em> the NanoVG UI frame,
     * because Skija has its own GL state bracketing.
     */
    public void renderResourcePanels(com.stonebreak.rendering.Renderer renderer, int sw, int sh) {
        if (!visible || renderer == null) return;
        if (masonryUI == null) {
            masonryUI = new MasonryUI(renderer.getSkijaBackend());
        }
        if (!masonryUI.isAvailable()) return;

        // Update FPS average every frame (not gated by cache interval).
        updateAverageFPS();

        // Refresh resource panels on a slower cadence; they only change slowly.
        long now = System.currentTimeMillis();
        if (cachedRamPanel == null || cachedVramPanel == null
                || now - lastResourcePanelRebuildMs >= RESOURCE_PANEL_REBUILD_INTERVAL_MS) {
            cachedRamPanel = buildRamPanel();
            cachedVramPanel = buildVramPanel();
            lastResourcePanelRebuildMs = now;
        }

        // Refresh the debug panel on a faster cadence so player position feels responsive.
        if (cachedDebugPanel == null
                || now - lastDebugPanelRebuildMs >= DEBUG_PANEL_REBUILD_INTERVAL_MS) {
            cachedDebugPanel = buildDebugPanel();
            lastDebugPanelRebuildMs = now;
        }

        if (!masonryUI.beginFrame(sw, sh, 1.0f)) return;
        try {
            float leftMargin = 10f;
            float panelWidth = 280f;
            float gap = 8f;
            float y = 10f;

            float ramHeight = cachedRamPanel.render(masonryUI, leftMargin, y, panelWidth);
            y += ramHeight + gap;

            cachedVramPanel.render(masonryUI, leftMargin, y, panelWidth);

            // Right-side debug info panel
            float rightX = sw - leftMargin - panelWidth;
            cachedDebugPanel.render(masonryUI, rightX, 10f, panelWidth);

            masonryUI.renderOverlays();
        } finally {
            masonryUI.endFrame();
        }
    }

    /**
     * Builds the RAM card. Combines:
     *   • Heap usage bar (used / max)
     *   • Per-pool heap breakdown (Eden / Survivor / Old, or ZGC pools)
     *   • Non-heap pools (Metaspace, Code Cache, etc.)
     *   • Direct + mapped buffer pools (where LWJGL native data lives)
     *   • GC stats (collections, total time)
     */
    private MStatPanel buildRamPanel() {
        Runtime runtime = Runtime.getRuntime();
        long maxBytes = runtime.maxMemory();
        long totalBytes = runtime.totalMemory();
        long freeBytes = runtime.freeMemory();
        long usedBytes = totalBytes - freeBytes;

        MStatPanel panel = new MStatPanel("RAM (JVM)")
            .usageBar(usedBytes, maxBytes,
                formatBytes(usedBytes) + " / " + formatBytes(maxBytes));

        // Heap pools — the "what's in the heap" breakdown.
        panel.section("Heap Pools");
        boolean anyHeap = false;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) continue;
            MemoryUsage u = pool.getUsage();
            if (u == null) continue;
            panel.row(shortPoolName(pool.getName()), formatBytes(u.getUsed()));
            anyHeap = true;
        }
        if (!anyHeap) panel.row("(none reported)", "");

        // Non-heap pools — Metaspace, Code Cache, Compressed Class.
        panel.section("Non-Heap");
        long nonHeapTotal = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.NON_HEAP) continue;
            MemoryUsage u = pool.getUsage();
            if (u == null) continue;
            nonHeapTotal += u.getUsed();
            panel.row(shortPoolName(pool.getName()), formatBytes(u.getUsed()));
        }
        panel.row("Total Non-Heap", formatBytes(nonHeapTotal));

        // Direct buffer pool — this is where LWJGL keeps native memory the JVM
        // owns but ZGC doesn't manage. Often 2nd biggest after heap for us.
        panel.section("Native Buffers");
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            String name = pool.getName(); // "direct" or "mapped"
            long used = pool.getMemoryUsed();
            long count = pool.getCount();
            panel.row(name, formatBytes(used < 0 ? 0 : used) + " (" + count + ")");
        }

        // GC stats — gives a hint at allocation pressure.
        long gcCollections = 0;
        long gcTimeMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = gc.getCollectionCount();
            long t = gc.getCollectionTime();
            if (c > 0) gcCollections += c;
            if (t > 0) gcTimeMs += t;
        }
        panel.section("GC");
        panel.row("Collections", String.valueOf(gcCollections));
        panel.row("Time Spent", gcTimeMs + " ms");
        panel.row("Loaded Classes",
            String.valueOf(ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()));
        return panel;
    }

    /**
     * Builds the VRAM card from {@link GpuMemoryTracker}. The bar communicates
     * "fraction of the GPU's dedicated VRAM that this process owns" when the
     * NV/ATI extension is available.
     */
    private MStatPanel buildVramPanel() {
        GpuMemoryTracker.Snapshot snap = GpuMemoryTracker.getInstance().snapshot();
        long trackedTotal = snap.totalBytes();
        long systemTotalBytes = vramTotalKB > 0 ? vramTotalKB * 1024L : 0L;

        MStatPanel panel = new MStatPanel("VRAM (Game)")
            .usageBar(trackedTotal, systemTotalBytes,
                systemTotalBytes > 0
                    ? formatBytes(trackedTotal) + " / " + formatBytes(systemTotalBytes)
                    : formatBytes(trackedTotal));

        // The active CEARL plan's pressure reading (only when it has a budget).
        VramPlan plan = VramPlans.active();
        long softBudget = plan.softBudgetBytes();
        if (softBudget > 0) {
            double pressure = (double) trackedTotal / softBudget;
            panel.row("Plan '" + plan.name() + "'",
                String.format("%.0f%% of %s", pressure * 100.0, formatBytes(softBudget)));
            var shed = plan.shedAt(pressure);
            if (!shed.isEmpty()) {
                panel.row("Pressure", "shed: " + String.join(", ", shed));
            }
        }

        panel.section("By Category");
        boolean anyCategory = false;
        for (GpuMemoryTracker.Category c : GpuMemoryTracker.Category.values()) {
            long bytes = snap.bytesOf(c);
            if (bytes <= 0) continue;
            panel.row(shortCategoryName(c),
                formatBytes(bytes) + " (" + snap.countOf(c) + ")");
            anyCategory = true;
        }
        if (!anyCategory) panel.row("(nothing tracked)", "");

        panel.section("System");
        panel.row("All processes", systemVramSummary());
        return panel;
    }

    /** Trims long pool names like "Compressed Class Space" → "Compressed Class". */
    private static String shortPoolName(String name) {
        if (name == null) return "?";
        // ZGC reports "ZGC Young Generation" / "ZGC Old Generation" — keep it tight.
        String n = name.replace("ZGC ", "")
                       .replace(" Generation", " Gen")
                       .replace(" Space", "")
                       .replace("CodeHeap '", "Code: ")
                       .replace("'", "");
        return n;
    }

    /** Short string for the system VRAM reading (or N/A). */
    private String systemVramSummary() {
        detectVramSource();
        try {
            switch (vramSource) {
                case NVIDIA -> {
                    int freeKB = glGetInteger(GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX);
                    if (vramTotalKB > 0) {
                        long usedMB = (vramTotalKB - freeKB) / 1024;
                        long totalMB = vramTotalKB / 1024;
                        return usedMB + "/" + totalMB + " MB";
                    }
                    return (freeKB / 1024) + " MB free";
                }
                case AMD -> {
                    int freeKB = glGetInteger(TEXTURE_FREE_MEMORY_ATI);
                    return (freeKB / 1024) + " MB free";
                }
                default -> { return "N/A"; }
            }
        } catch (Exception e) {
            return "N/A";
        }
    }

    /**
     * Builds the right-side debug info card with player position, chunk coords,
     * facing direction, block/biome info, FPS, and GPU details — rendered in the
     * same stone-surface style as the RAM/VRAM panels.
     */
    private MStatPanel buildDebugPanel() {
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
        String targetedLine = getTargetedBlockSummary(player);

        MStatPanel panel = new MStatPanel("Debug Info")
            .row("XYZ", String.format("%d / %d / %d", x, y, z))
            .row("Chunk", String.format("%d %d in %d %d", x & 15, z & 15, chunkX, chunkZ))
            .row("Facing", facing);

        panel.section("Terrain");
        panel.row("Noise Backend", noiseBackendSummary());
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
        String waterLine = getWaterStateSummary(player);
        if (waterLine != null) {
            panel.section("Water");
            panel.row("Looking At", waterLine);
        }

        panel.section("World");
        panel.row("FPS", String.format("%.0f (avg)", averageFPS));
        panel.row("Chunks", String.format("%d loaded", world.getLoadedChunkCount()));
        panel.row("Pending Mesh", String.valueOf(world.getPendingMeshBuildCount()));
        panel.row("Pending GL", String.valueOf(world.getPendingGLUploadCount()));
        panel.row("Chunk Flow", chunkPipelineSummary());
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
        panel.row("Nav", navigationSummary(world));
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
        queryGPUInfo();
        panel.row("GPU", truncate(gpuRenderer != null ? gpuRenderer : "Unknown", 30));
        panel.row("Vendor", truncate(gpuVendor != null ? gpuVendor : "Unknown", 25));
        panel.row("OpenGL", gpuVersion != null ? gpuVersion : "Unknown");

        panel.section("Debug");
        panel.row("Path Visual", "ON");

        return panel;
    }

    // Previous ChunkPipelineStats sample for per-second rate derivation.
    private long pipelineSampleNanos = 0L;
    private final long[] pipelineLast = new long[6];
    private final double[] pipelineRates = new double[6];

    /**
     * Per-second rates through the chunk pipeline stages
     * (gen → populate → stream → install → mesh → upload), derived from
     * frame-to-frame deltas of the {@code ChunkPipelineStats} totals.
     */
    private String chunkPipelineSummary() {
        long now = System.nanoTime();
        long[] totals = {
            com.stonebreak.world.chunk.utils.ChunkPipelineStats.GENERATED.sum(),
            com.stonebreak.world.chunk.utils.ChunkPipelineStats.POPULATED.sum(),
            com.stonebreak.world.chunk.utils.ChunkPipelineStats.STREAMED.sum(),
            com.stonebreak.world.chunk.utils.ChunkPipelineStats.INSTALLED.sum(),
            com.stonebreak.world.chunk.utils.ChunkPipelineStats.MESHED.sum(),
            com.stonebreak.world.chunk.utils.ChunkPipelineStats.UPLOADED.sum(),
        };
        // Refresh rates every ~500 ms so the row is readable, not flickering.
        if (pipelineSampleNanos == 0L || now - pipelineSampleNanos >= 500_000_000L) {
            double seconds = pipelineSampleNanos == 0L ? 0
                : (now - pipelineSampleNanos) / 1_000_000_000.0;
            for (int i = 0; i < totals.length; i++) {
                pipelineRates[i] = seconds > 0 ? (totals[i] - pipelineLast[i]) / seconds : 0;
                pipelineLast[i] = totals[i];
            }
            pipelineSampleNanos = now;
        }
        return String.format("gen %.0f pop %.0f str %.0f inst %.0f mesh %.0f gl %.0f /s",
            pipelineRates[0], pipelineRates[1], pipelineRates[2],
            pipelineRates[3], pipelineRates[4], pipelineRates[5]);
    }

    /** One-line world-gen noise backend status: Cenda native kernels vs classic Java. */
    private static String noiseBackendSummary() {
        if (com.stonebreak.world.generation.noise.TerrainNoise.backend()
                == com.stonebreak.world.generation.noise.TerrainNoise.Backend.NATIVE) {
            return "Cenda FastNoise2 (" + com.openmason.engine.cenda.CendaKernels.simdLevel() + ")";
        }
        return "Java (classic simplex)";
    }

    /**
     * Mob path-search load: how many searches are running, how many have run, how long they take,
     * and how many came back partial.
     *
     * <p>Partials are the number worth watching: a few are normal (mobs do aim at spots they cannot
     * reach), but a steady stream means either the expansion budget is too tight for the terrain or
     * something is asking for routes that do not exist.
     */
    private static String navigationSummary(com.stonebreak.world.World world) {
        // The searches happen on the authoritative world, alongside the AI that asks for them —
        // the render world's own service sits at zero forever. See navigationEntitySource().
        com.stonebreak.world.World searching = navigationWorld(world);
        com.stonebreak.mobs.entities.ai.nav.PathfindingService service = searching.pathfinding();
        if (service == null) {
            return "off";
        }
        var stats = service.stats();
        return String.format("%d searching / %d done @ %d µs / %d partial / %d rejected",
                stats.inFlight(), stats.completed(), stats.averageMicros(),
                stats.partial(), stats.rejected());
    }

    /** The world whose pathfinder the mobs actually use; falls back to the rendered one. */
    private static com.stonebreak.world.World navigationWorld(com.stonebreak.world.World rendered) {
        if (MultiplayerSession.hasIntegratedServer()) {
            IntegratedServer server = MultiplayerSession.getServer();
            if (server != null) {
                com.stonebreak.world.World authoritative = server.worldContext().world();
                if (authoritative != null) {
                    return authoritative;
                }
            }
        }
        return rendered;
    }

    /** The route a mob still has to walk. */
    private static final Vector4f PATH_COLOR = new Vector4f(0.2f, 0.6f, 1.0f, 1.0f);
    /** Where it is trying to get to. */
    private static final Vector4f GOAL_COLOR = new Vector4f(1.0f, 0.25f, 0.85f, 1.0f);

    /** Half-size of the cross drawn at a mob's destination. */
    private static final float GOAL_MARKER_SIZE = 0.4f;

    /**
     * How far a ground route is lifted off the surface for drawing.
     *
     * <p>A waypoint sits at exactly the height the mob's feet will rest — the top face of the block
     * it stands on. Drawn there, with the depth test on, the line is coplanar with that face and
     * z-fights it into invisibility, which is why ground routes never appeared while the geese's
     * mid-air ones did. Small enough that the line still reads as being on the ground.
     */
    private static final float ROUTE_GROUND_LIFT = 0.25f;

    /** Reused between frames so the overlay does not allocate a list per mob per frame. */
    private final List<Vector3f> pathScratch = new java.util.ArrayList<>();

    /**
     * Renders debug wireframes for entities (called after UI rendering).
     *
     * <p>Each mob is outlined by re-drawing its actual model mesh as a see-through wireframe, so
     * the overlay tracks the animated model exactly, coloured by what it is currently doing.
     *
     * <p>The lines show the route each mob has <em>planned</em> — the waypoints still ahead of it,
     * and a marker at its destination. That is the useful view: it says where a mob has decided to
     * go and how it intends to get there, so a mob pressed against a wall is immediately either a
     * routing bug (no path, or a path through the wall) or a steering one (a sensible path it is
     * failing to walk).
     */
    public void renderWireframes(Renderer renderer) {
        if (!visible) {
            return;
        }

        EntityManager rendered = Game.getEntityManager();
        if (rendered == null) {
            return;
        }

        // Wireframes go on the mobs actually on screen — the client's shadows.
        List<LivingEntity> renderedMobs = aiMobsOf(rendered);
        for (LivingEntity mob : renderedMobs) {
            renderer.renderEntityWireframe(mob, colorForState(mob));
        }

        // Planned routes — batched line drawing. Every AI-driven mob gets the same treatment, so
        // there is no per-mob code here and a future mob appears automatically.
        //
        // Both managers are drawn, rather than picking one. A mob's AI runs in exactly one of them
        // and contributes nothing from the other — a network shadow's route is permanently empty
        // because its AI is never ticked — so the union costs an empty pass and cannot silently
        // drop a source. Picking one would: replicated mobs navigate server-side, while
        // owner-local entities (the types that do not replicate) navigate here.
        DebugRenderer debug = renderer.getDebugRenderer();
        debug.beginBatch();
        try {
            drawRoutesOf(debug, renderedMobs);
            EntityManager authoritative = authoritativeEntitySource();
            if (authoritative != null && authoritative != rendered) {
                drawRoutesOf(debug, aiMobsOf(authoritative));
            }
        } finally {
            debug.endBatch();
        }

        // Sound emitters manage their own shader state — draw outside the batch.
        renderer.renderSoundEmitters(true);
    }

    /** The AI-driven living mobs of one manager. */
    private static List<LivingEntity> aiMobsOf(EntityManager manager) {
        List<LivingEntity> mobs = new java.util.ArrayList<>();
        for (Entity entity : manager.getAllEntities()) {
            if (entity.isAlive() && entity instanceof LivingEntity mob && mob.getAI() != null) {
                mobs.add(mob);
            }
        }
        return mobs;
    }

    private void drawRoutesOf(DebugRenderer debug, List<LivingEntity> mobs) {
        for (LivingEntity mob : mobs) {
            drawPlannedRoute(debug, mob);
            // A flying goose routes through the air domain instead, which the ground agent knows
            // nothing about — draw that too, or an airborne flock looks unnavigated.
            if (mob instanceof Goose goose && goose.flight().isAirborne()) {
                drawAirRoute(debug, goose.flight().route(), goose.getPosition());
            }
        }
    }

    /**
     * The authoritative server's entity manager, or {@code null} when this JVM has none.
     *
     * <p>Replicated mobs are simulated on the authoritative server world; what a client renders are
     * interpolated network shadows. A shadow is a real {@code Cow} or {@code Goose} and so builds a
     * {@code MobAI} in its constructor — which is why every {@code getAI() != null} guard passes —
     * but {@code EntityManager.update} skips AI for shadows, so its route is permanently empty and
     * its goose never leaves the ground. The state-coloured wireframes do work on shadows, because
     * behaviour state arrives over the wire; only the routes are missing.
     *
     * <p>Single-player and hosting clients run that server in this same JVM, so the real mobs are
     * reachable. A remote client has no access to them and honestly draws no routes for replicated
     * mobs; that is the same gap the server-side footstep sounds have.
     *
     * <p>The entity list is a {@code CopyOnWriteArrayList} handed out as a copy, so iterating it
     * off the server tick is safe. The per-agent fields read from it are not synchronised — a
     * marker may lag a frame or land between two updates, which for a debug overlay is the right
     * trade against putting a lock in the navigation hot path.
     */
    private static EntityManager authoritativeEntitySource() {
        if (!MultiplayerSession.hasIntegratedServer()) {
            return null;
        }
        IntegratedServer server = MultiplayerSession.getServer();
        return server == null ? null : server.worldContext().entityManager();
    }

    /**
     * Draws a flying mob's air route the same way, so a leader steering round a peak shows the
     * corridor it chose and the wingmen following it can be read against that line.
     */
    private void drawAirRoute(DebugRenderer debug, AirPathAgent route, Vector3f mobPosition) {
        Path path = route.path();
        if (!path.isEmpty()) {
            pathScratch.clear();
            pathScratch.add(new Vector3f(mobPosition));
            for (int i = route.cursor(); i < path.size(); i++) {
                pathScratch.add(path.waypoint(i, new Vector3f()));
            }
            debug.drawPath(pathScratch, PATH_COLOR);
        }

        if (route.hasGoal()) {
            Vector3f goal = route.goal(new Vector3f());
            pathScratch.clear();
            pathScratch.add(new Vector3f(goal.x, goal.y - GOAL_MARKER_SIZE, goal.z));
            pathScratch.add(new Vector3f(goal.x, goal.y + GOAL_MARKER_SIZE, goal.z));
            debug.drawPath(pathScratch, GOAL_COLOR);
        }
    }

    private void drawPlannedRoute(DebugRenderer debug, LivingEntity mob) {
        PathAgent nav = mob.getAI().nav();
        Vector3f position = mob.getPosition();
        Path path = nav.path();

        if (!path.isEmpty()) {
            pathScratch.clear();
            // Start at the mob's feet — where its route is measured from — rather than its origin,
            // which sits a leg-length higher and made the first leg dive into the ground.
            pathScratch.add(new Vector3f(position.x,
                    position.y - mob.getLegHeight() + ROUTE_GROUND_LIFT, position.z));
            for (int i = nav.cursor(); i < path.size(); i++) {
                Vector3f waypoint = path.waypoint(i, new Vector3f());
                waypoint.y += ROUTE_GROUND_LIFT;
                pathScratch.add(waypoint);
            }
            debug.drawPath(pathScratch, PATH_COLOR);
        }

        if (nav.hasGoal()) {
            Vector3f goal = nav.goal(new Vector3f());
            float y = goal.y + ROUTE_GROUND_LIFT;
            pathScratch.clear();
            pathScratch.add(new Vector3f(goal.x - GOAL_MARKER_SIZE, y, goal.z));
            pathScratch.add(new Vector3f(goal.x + GOAL_MARKER_SIZE, y, goal.z));
            debug.drawPath(pathScratch, GOAL_COLOR);

            pathScratch.clear();
            pathScratch.add(new Vector3f(goal.x, y, goal.z - GOAL_MARKER_SIZE));
            pathScratch.add(new Vector3f(goal.x, y, goal.z + GOAL_MARKER_SIZE));
            debug.drawPath(pathScratch, GOAL_COLOR);
        }
    }

    /**
     * Picks the wireframe colour for what a mob is currently doing, so the overlay doubles as an
     * at-a-glance behaviour readout. One palette for every mob.
     */
    private Vector4f colorForState(LivingEntity mob) {
        MobBehaviorState state = mob.getAI() != null
                ? mob.getAI().getCurrentState() : MobBehaviorState.IDLE;
        return switch (state) {
            case IDLE                -> new Vector4f(0.25f, 0.85f, 1.0f, 1.0f); // cyan
            case WANDERING           -> new Vector4f(0.30f, 1.0f, 0.35f, 1.0f); // green
            case GRAZING, WING_FLAP  -> new Vector4f(1.0f, 0.80f, 0.20f, 1.0f); // amber
            case SWIMMING            -> new Vector4f(0.20f, 0.55f, 1.0f, 1.0f); // blue
            case FLYING              -> new Vector4f(1.0f, 0.45f, 0.15f, 1.0f); // orange
        };
    }

    private String getCardinalDirection(Vector3f front) {
        // Calculate yaw from front vector
        // In OpenGL, -Z is forward, so we need to use atan2(-front.z, front.x)
        float yaw = (float) Math.toDegrees(Math.atan2(-front.z, front.x));
        
        // Normalize yaw to 0-360 degrees
        float normalizedYaw = ((yaw % 360) + 360) % 360;
        
        // Adjust for Minecraft coordinate system where:
        // North = -Z, South = +Z, East = +X, West = -X
        // Using 8 directions with 45-degree segments
        if (normalizedYaw >= 337.5 || normalizedYaw < 22.5) {
            return "East";
        } else if (normalizedYaw >= 22.5 && normalizedYaw < 67.5) {
            return "Northeast";
        } else if (normalizedYaw >= 67.5 && normalizedYaw < 112.5) {
            return "North";
        } else if (normalizedYaw >= 112.5 && normalizedYaw < 157.5) {
            return "Northwest";
        } else if (normalizedYaw >= 157.5 && normalizedYaw < 202.5) {
            return "West";
        } else if (normalizedYaw >= 202.5 && normalizedYaw < 247.5) {
            return "Southwest";
        } else if (normalizedYaw >= 247.5 && normalizedYaw < 292.5) {
            return "South";
        } else {
            return "Southeast";
        }
    }
}
