package com.stonebreak.ui;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.UI.masonryUI.MStatPanel;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import org.joml.Vector3f;
import org.joml.Vector3i;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.blocks.Water;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.blocks.waterSystem.WaterSystem;
import com.stonebreak.player.Player;
import com.stonebreak.player.Camera;
import com.stonebreak.world.World;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.sbo.SBOBlockBridge;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.mobs.cow.CowAI;
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

    // MasonryUI for the left-side resource panels. Lazily built once a Renderer exists.
    private MasonryUI masonryUI = null;

    // Cached panels — rebuilt periodically rather than every frame. Reading
    // MXBeans + GPU snapshot + building MStatPanel structures every frame
    // generates measurable churn while F3 is open; values change slowly enough
    // that 4 Hz is plenty.
    private static final long PANEL_REBUILD_INTERVAL_MS = 250L;
    private long lastPanelRebuildMs = 0L;
    private MStatPanel cachedRamPanel = null;
    private MStatPanel cachedVramPanel = null;

    public DebugOverlay() {
    }

    public boolean isVisible() {
        return visible;
    }

    public void toggleVisibility() {
        visible = !visible;
    }

    public String getDebugText() {
        if (!visible) {
            return "";
        }

        Player player = Game.getPlayer();
        World world = Game.getWorld();

        if (player == null || world == null) {
            return "Player or World not available";
        }

        Vector3f pos = player.getPosition();
        int x = (int) Math.floor(pos.x);
        int y = (int) Math.floor(pos.y);
        int z = (int) Math.floor(pos.z);

        // Get chunk coordinates
        int chunkX = x >> 4; // Divide by 16
        int chunkZ = z >> 4; // Divide by 16

        // Get FPS
        updateAverageFPS();

        // Get world information
        int loadedChunks = world.getLoadedChunkCount();
        
        // Get block at player position
        BlockType blockBelow = world.getBlockAt(x, y - 1, z);
        String blockBelowName = blockBelow != null ? blockBelow.name() : "Unknown";

        // Get biome information at player position
        BiomeType biome = world.getBiomeAt(x, z);
        float temperature = world.getTemperatureAt(x, z);
        float moisture = world.getMoistureAt(x, z);

        // Noise channels driving terrain shape
        float continentalness = world.getContinentalnessAt(x, z);
        float erosion = world.getErosionAt(x, z);
        float peaksValleys = world.getPeaksValleysAt(x, z);
        int baseHeight = world.getBaseHeightAt(x, z);
        int shapedHeight = world.getShapedHeightAt(x, z);
        int finalHeight = world.getFinalTerrainHeightAt(x, z);

        // Calculate facing direction from camera's front vector
        Vector3f front = player.getCamera().getFront();
        String facing = getCardinalDirection(front);

        // Build debug text
        StringBuilder debug = new StringBuilder();
        debug.append("Stonebreak Debug (F3)\n");
        debug.append("─────────────────────\n");
        debug.append(String.format("XYZ: %d / %d / %d\n", x, y, z));
        debug.append(String.format("Chunk: %d %d in %d %d\n", x & 15, z & 15, chunkX, chunkZ));
        debug.append(String.format("Facing: %s\n", facing));
        debug.append(String.format("Block Below: %s\n", blockBelowName));
        debug.append(String.format("Biome: %s\n", biome.name()));
        debug.append(String.format("Temperature: %.3f\n", temperature));
        debug.append(String.format("Moisture: %.3f\n", moisture));
        debug.append(String.format("Continentalness: %.3f\n", continentalness));
        debug.append(String.format("Erosion: %.3f\n", erosion));
        debug.append(String.format("Peaks/Valleys: %.3f\n", peaksValleys));
        debug.append(String.format("Height: %d base / %d shaped (%+d) / %d final (%+d detail)\n",
                baseHeight, shapedHeight, shapedHeight - baseHeight,
                finalHeight, finalHeight - shapedHeight));
        debug.append("\n");

        // Targeted block info (what the player is looking at)
        String targetedBlockInfo = getTargetedBlockInfo(player);
        if (targetedBlockInfo != null) {
            debug.append(targetedBlockInfo);
            debug.append("\n");
        }
        debug.append(String.format("FPS: %.0f (avg)\n", averageFPS));
        debug.append(String.format("Chunks: %d loaded\n", loadedChunks));
        debug.append(String.format("Pending Mesh: %d\n", world.getPendingMeshBuildCount()));
        debug.append(String.format("Pending GL: %d\n", world.getPendingGLUploadCount()));
        debug.append("\n");

        // Add GPU information
        queryGPUInfo(); // Query GPU info if not already done
        debug.append("─── Graphics Card ───\n");
        debug.append(String.format("GPU: %s\n", gpuRenderer != null ? gpuRenderer : "Unknown"));
        debug.append(String.format("Vendor: %s\n", gpuVendor != null ? gpuVendor : "Unknown"));
        debug.append(String.format("OpenGL: %s\n", gpuVersion != null ? gpuVersion : "Unknown"));
        debug.append("\n");

        debug.append("Path Visualization: ON\n");

        // Add water state monitoring
        String waterInfo = getWaterStateInfo(player);
        if (waterInfo != null) {
            debug.append("\n");
            debug.append(waterInfo);
        }

        return debug.toString();
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
            case OTHER           -> "Other";
        };
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * Renders the debug overlay using the provided renderer.
     */
    public void render(UIRenderer uiRenderer) {
        if (!visible) {
            return;
        }

        String debugText = getDebugText();
        if (debugText.isEmpty()) {
            return;
        }

        // Get window dimensions from Game instance to calculate right side position
        int windowWidth = Game.getWindowWidth();

        String[] lines = debugText.split("\n");
        float rightMargin = 10; // Distance from right edge
        float y = 10; // Start position
        float lineHeight = 18;

        for (String line : lines) {
            if (line.contains("Stonebreak Debug")) {
                // Calculate text width and position from right side
                float textWidth = uiRenderer.getTextWidth(line, 20, "sans-bold");
                float x = windowWidth - rightMargin - textWidth;
                // Render title in yellow
                uiRenderer.drawText(line, x, y, "sans-bold", 20, 1.0f, 1.0f, 0.0f, 1.0f);
            } else {
                // Calculate text width and position from right side
                float textWidth = uiRenderer.getTextWidth(line, 16, "sans");
                float x = windowWidth - rightMargin - textWidth;
                // Render regular text
                uiRenderer.drawText(line, x, y, "sans", 16, 1.0f, 1.0f, 1.0f, 1.0f);
            }
            y += lineHeight;
        }

    }

    /**
     * Renders the RAM and VRAM resource cards using MasonryUI/Skija.
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

        // Refresh the panel data on a fixed cadence; render the cached panels
        // on every frame in between.
        long now = System.currentTimeMillis();
        if (cachedRamPanel == null || cachedVramPanel == null
                || now - lastPanelRebuildMs >= PANEL_REBUILD_INTERVAL_MS) {
            cachedRamPanel = buildRamPanel();
            cachedVramPanel = buildVramPanel();
            lastPanelRebuildMs = now;
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
     * Renders debug wireframes for entities (called after UI rendering).
     */
    public void renderWireframes(Renderer renderer) {
        if (!visible) {
            return;
        }
        
        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) {
            return;
        }
        
        // Get all cow entities
        List<Entity> cowEntities = entityManager.getEntitiesByType(EntityType.COW);
        
        
        // Render green wireframe bounding boxes for each cow
        for (Entity cow : cowEntities) {
            if (cow.isAlive()) {
                renderEntityBoundingBox(cow, renderer);

                // Render path wireframe if entity is a Cow
                if (cow instanceof Cow) {
                    renderCowPathWireframe((Cow) cow, renderer);
                }
            }
        }

        // Render sound emitters as yellow triangle wireframes
        renderer.renderSoundEmitters(true); // Debug mode is always true when in debug overlay
    }
    
    /**
     * Renders a green wireframe bounding box for an entity.
     */
    private void renderEntityBoundingBox(Entity entity, Renderer renderer) {
        Entity.BoundingBox boundingBox = entity.getBoundingBox();
        
        // Use green color for cow bounding boxes
        Vector3f green = new Vector3f(0.0f, 1.0f, 0.0f);
        
        // Use the renderer's modern wireframe rendering method
        renderer.renderWireframeBoundingBox(boundingBox, green);
    }
    
    /**
     * Renders a blue wireframe path for a cow's AI pathfinding.
     */
    private void renderCowPathWireframe(Cow cow, Renderer renderer) {
        CowAI cowAI = cow.getAI();
        if (cowAI == null) {
            return;
        }
        
        List<Vector3f> pathPoints = cowAI.getPathPoints();
        if (pathPoints == null || pathPoints.size() < 2) {
            return;
        }
        
        // Use blue color for path visualization
        Vector3f blue = new Vector3f(0.0f, 0.5f, 1.0f);
        
        // Use the renderer's path wireframe rendering method
        renderer.renderWireframePath(pathPoints, blue);
    }

    /**
     * Gets information about the block the player is looking at,
     * including whether it uses an SBO model or the legacy mesh system.
     */
    private String getTargetedBlockInfo(Player player) {
        Vector3f position = player.getPosition();
        Camera camera = player.getCamera();
        World world = Game.getWorld();

        if (camera == null || world == null) {
            return null;
        }

        Vector3f rayOrigin = new Vector3f(position.x, position.y + 1.6f, position.z);
        Vector3f rayDirection = camera.getFront();

        float maxDistance = 6.0f;
        float stepSize = 0.05f;

        for (float distance = 0; distance < maxDistance; distance += stepSize) {
            Vector3f point = new Vector3f(rayDirection).mul(distance).add(rayOrigin);

            int blockX = (int) Math.floor(point.x);
            int blockY = (int) Math.floor(point.y);
            int blockZ = (int) Math.floor(point.z);

            BlockType blockType = world.getBlockAt(blockX, blockY, blockZ);

            if (blockType != null && blockType != BlockType.AIR) {
                StringBuilder info = new StringBuilder();
                info.append("─── Targeted Block ───\n");
                info.append(String.format("Block: %s (%d, %d, %d)\n", blockType.name(), blockX, blockY, blockZ));

                Renderer renderer = Game.getRenderer();
                SBOBlockBridge bridge = renderer != null ? renderer.getSBOBlockBridge() : null;

                if (bridge != null && bridge.isSBOBlock(blockType)) {
                    info.append("Model: SBO\n");
                } else {
                    info.append("Model: Legacy Mesh\n");
                }

                return info.toString();
            }
        }

        return null;
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

    /**
     * Gets information about the water block the player is looking at.
     * @param player The player instance
     * @return Water state information string, or null if not looking at water
     */
    private String getWaterStateInfo(Player player) {
        Vector3i waterBlockPos = raycastForWater(player);
        if (waterBlockPos == null) {
            return null;
        }

        World world = Game.getWorld();
        if (world == null) {
            return null;
        }

        // Get water block data through the Water class
        WaterBlock waterBlock = Water.getWaterBlock(waterBlockPos.x, waterBlockPos.y, waterBlockPos.z);
        if (waterBlock == null) {
            return "Water State: No tracked water";
        }

        float fill = Water.getWaterLevel(waterBlockPos.x, waterBlockPos.y, waterBlockPos.z);
        float visualHeight = Water.getWaterVisualHeight(waterBlockPos.x, waterBlockPos.y, waterBlockPos.z);

        StringBuilder waterInfo = new StringBuilder();
        waterInfo.append("─── Water State Monitor ───\n");
        waterInfo.append(String.format("Looking at: Water (%d, %d, %d)\n",
            waterBlockPos.x, waterBlockPos.y, waterBlockPos.z));
        waterInfo.append(String.format("Level: %d (%s)\n",
            waterBlock.level(), waterBlock.isSource() ? "Source" : "Flowing"));
        waterInfo.append(String.format("Falling: %s\n", waterBlock.falling() ? "Yes" : "No"));
        waterInfo.append(String.format("Fill: %.3f\n", fill));
        waterInfo.append(String.format("Visual Height: %.3f\n", visualHeight));

        WaterSystem system = world.getWaterSystem();
        if (system != null) {
            waterInfo.append(String.format("Tracked Water Blocks: %d\n", system.getTrackedWaterCount()));
        }

        return waterInfo.toString();
    }

    /**
     * Performs a raycast specifically looking for water blocks.
     * @param player The player instance
     * @return Position of the first water block hit, or null if none found
     */
    private Vector3i raycastForWater(Player player) {
        Vector3f position = player.getPosition();
        Camera camera = player.getCamera();
        World world = Game.getWorld();

        if (camera == null || world == null) {
            return null;
        }

        // Start ray from player's eye level
        Vector3f rayOrigin = new Vector3f(position.x, position.y + 1.6f, position.z);
        Vector3f rayDirection = camera.getFront();

        float maxDistance = 5.0f;
        float stepSize = 0.05f;

        // Perform ray marching
        for (float distance = 0; distance < maxDistance; distance += stepSize) {
            Vector3f point = new Vector3f(rayDirection).mul(distance).add(rayOrigin);

            int blockX = (int) Math.floor(point.x);
            int blockY = (int) Math.floor(point.y);
            int blockZ = (int) Math.floor(point.z);

            BlockType blockType = world.getBlockAt(blockX, blockY, blockZ);

            // Look specifically for water blocks
            if (blockType == BlockType.WATER) {
                return new Vector3i(blockX, blockY, blockZ);
            }

            // Stop at solid blocks
            if (blockType != BlockType.AIR) {
                break;
            }
        }

        return null;
    }
}
