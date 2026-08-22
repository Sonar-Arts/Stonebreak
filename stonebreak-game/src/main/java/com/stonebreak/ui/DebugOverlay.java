package com.stonebreak.ui;

import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.masonryUI.MStatPanel;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.debug.DebugDiagnostics;
import com.stonebreak.ui.debug.DebugInfoPanel;
import com.stonebreak.ui.debug.DebugPanel;
import com.stonebreak.ui.debug.GpuInfoProbe;
import com.stonebreak.ui.debug.MobPathWireframeDrawer;
import com.stonebreak.ui.debug.RamPanel;
import com.stonebreak.ui.debug.VramPanel;

/**
 * The F3 debug overlay coordinator: owns the visibility toggle, the MasonryUI
 * surface, the panel rebuild cadences and the on-screen layout. The numbers
 * come from {@link com.stonebreak.ui.debug.DebugPanel} collaborators and the
 * entity wireframes from {@link MobPathWireframeDrawer}.
 */
public class DebugOverlay {
    private boolean visible = false;

    // Collaborators: diagnostics gathering, GPU queries, panel builders, wireframes.
    private final DebugDiagnostics diagnostics = new DebugDiagnostics();
    private final GpuInfoProbe gpuInfo = new GpuInfoProbe();
    private final DebugPanel ramPanel = new RamPanel();
    private final DebugPanel vramPanel = new VramPanel(gpuInfo);
    private final DebugPanel debugPanel = new DebugInfoPanel(diagnostics, gpuInfo);
    private final MobPathWireframeDrawer wireframes = new MobPathWireframeDrawer();

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
        diagnostics.updateAverageFPS();

        // Refresh resource panels on a slower cadence; they only change slowly.
        long now = System.currentTimeMillis();
        if (cachedRamPanel == null || cachedVramPanel == null
                || now - lastResourcePanelRebuildMs >= RESOURCE_PANEL_REBUILD_INTERVAL_MS) {
            cachedRamPanel = ramPanel.build();
            cachedVramPanel = vramPanel.build();
            lastResourcePanelRebuildMs = now;
        }

        // Refresh the debug panel on a faster cadence so player position feels responsive.
        if (cachedDebugPanel == null
                || now - lastDebugPanelRebuildMs >= DEBUG_PANEL_REBUILD_INTERVAL_MS) {
            cachedDebugPanel = debugPanel.build();
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
     * Renders debug wireframes for entities (called after UI rendering):
     * behaviour-coloured model outlines plus each mob's planned route. See
     * {@link MobPathWireframeDrawer}.
     */
    public void renderWireframes(Renderer renderer) {
        if (!visible) {
            return;
        }
        wireframes.render(renderer);
    }
}
