package com.stonebreak.ui.debug;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import static org.lwjgl.opengl.GL11.*;

/**
 * Queries and caches GPU identity (vendor / renderer / GL version) and the
 * vendor VRAM-info extensions (NVX / ATI) for the debug overlay. GL calls are
 * made lazily on the render thread and the identity strings are cached after
 * the first query so the overlay does not hit the driver every frame.
 */
public final class GpuInfoProbe {

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

    /**
     * Queries GPU information from OpenGL.
     * Only queries once and caches the results to avoid repeated OpenGL calls.
     */
    public void queryGPUInfo() {
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

    /** Cached GL_VENDOR string, or {@code null} before {@link #queryGPUInfo()}. */
    public String gpuVendor() {
        return gpuVendor;
    }

    /** Cached GL_RENDERER string, or {@code null} before {@link #queryGPUInfo()}. */
    public String gpuRenderer() {
        return gpuRenderer;
    }

    /** Cached GL_VERSION string, or {@code null} before {@link #queryGPUInfo()}. */
    public String gpuVersion() {
        return gpuVersion;
    }

    /** Total dedicated VRAM in bytes, or 0 when no extension has reported it (yet). */
    public long systemTotalBytes() {
        return vramTotalKB > 0 ? vramTotalKB * 1024L : 0L;
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
    String getVramText() {
        StringBuilder out = new StringBuilder();

        GpuMemoryTracker.Snapshot snap = GpuMemoryTracker.getInstance().snapshot();
        long trackedTotal = snap.totalBytes();
        out.append(String.format("VRAM (Game): %s\n", DebugFormat.formatBytes(trackedTotal)));

        // Per-category breakdown: only show non-zero categories.
        for (GpuMemoryTracker.Category c : GpuMemoryTracker.Category.values()) {
            long bytes = snap.bytesOf(c);
            if (bytes <= 0) continue;
            long count = snap.countOf(c);
            out.append(String.format("  %s: %s (%d)\n",
                DebugFormat.shortCategoryName(c), DebugFormat.formatBytes(bytes), count));
        }

        // System-wide GPU reading for context — labelled clearly so it's not
        // mistaken for our process footprint.
        out.append(getSystemVramText());
        return out.toString();
    }

    /** System-wide VRAM line — all processes combined, not just this one. */
    @SuppressWarnings("unused") // legacy text helper — superseded by systemVramSummary()
    String getSystemVramText() {
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

    /** Short string for the system VRAM reading (or N/A). */
    public String systemVramSummary() {
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
}
