package com.stonebreak.ui;

import com.stonebreak.rendering.Renderer;
import com.openmason.engine.ui.masonry.MStatPanel;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.stonebreak.ui.debug.DebugDiagnostics;
import com.stonebreak.ui.debug.DebugInfoPanel;
import com.stonebreak.ui.debug.DebugPanel;
import com.stonebreak.ui.debug.GpuInfoProbe;
import com.stonebreak.ui.debug.MobPathWireframeDrawer;
import com.stonebreak.ui.debug.RamPanel;
import com.stonebreak.ui.debug.UiBudgetPanel;
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
    private final UiBudgetPanel uiBudgetPanel = new UiBudgetPanel();
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
    private MStatPanel cachedUiBudgetPanel = null;

    /** The shipped document: {@code ui/documents/debug_overlay.sbui} (#301). */
    public static final String DOCUMENT_ID = "debug_overlay";

    /** The cards on screen: RAM, VRAM, the UI budgets (null without a monitored document) and the debug info. */
    public record Cards(MStatPanel ram, MStatPanel vram, MStatPanel uiBudget, MStatPanel debug) {
    }

    private final com.stonebreak.ui.runtime.screens.PresentationSlot slot =
        new com.stonebreak.ui.runtime.screens.PresentationSlot();
    private boolean installed;
    private Cards cards;

    public DebugOverlay() {
    }

    /** Installs (or, with null, removes) the document presentation; the legacy cards are the default. */
    public void setPresentation(com.stonebreak.ui.runtime.screens.ScreenPresentation p) {
        installed = true;
        slot.install(p);
    }

    /**
     * Main thread, every frame (#301): the F3 document shows while the overlay is visible, in every state,
     * above everything (an overlay-layer document the frame renderer paints last).
     */
    public void sync() {
        if (visible && !installed) {
            setPresentation(new com.stonebreak.ui.runtime.screens.PresentedDocument(DOCUMENT_ID,
                com.stonebreak.ui.runtime.screens.DocumentScreenHost.get(),
                com.stonebreak.ui.runtime.screens.DocumentScreen.Options.overlay()));
        }
        slot.setVisible(visible);
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
        refresh();
        if (slot.showing()) {
            com.stonebreak.ui.runtime.GameUiHost.get().debug().publish(cards);
            if (slot.paint(sw, sh)) {
                return; // the F3 document (#301)
            }
        }
        if (masonryUI == null) {
            masonryUI = new MasonryUI(renderer.getSkijaBackend());
        }
        if (!masonryUI.isAvailable()) return;
        paintLegacy(masonryUI, cards, sw, sh);
    }

    /** Rebuilds the cards on their cadences (FPS every frame, resources 4 Hz, debug info 20 Hz). */
    private void refresh() {
        // Update FPS average every frame (not gated by cache interval).
        diagnostics.updateAverageFPS();

        // Refresh resource panels on a slower cadence; they only change slowly.
        long now = System.currentTimeMillis();
        if (cachedRamPanel == null || cachedVramPanel == null
                || now - lastResourcePanelRebuildMs >= RESOURCE_PANEL_REBUILD_INTERVAL_MS) {
            cachedRamPanel = ramPanel.build();
            cachedVramPanel = vramPanel.build();
            // UI document budgets (#296): only while a document is monitored
            cachedUiBudgetPanel = uiBudgetPanel.hasContent() ? uiBudgetPanel.build() : null;
            lastResourcePanelRebuildMs = now;
        }

        // Refresh the debug panel on a faster cadence so player position feels responsive.
        if (cachedDebugPanel == null
                || now - lastDebugPanelRebuildMs >= DEBUG_PANEL_REBUILD_INTERVAL_MS) {
            cachedDebugPanel = debugPanel.build();
            lastDebugPanelRebuildMs = now;
        }
        if (cards == null || cards.ram() != cachedRamPanel || cards.vram() != cachedVramPanel
                || cards.uiBudget() != cachedUiBudgetPanel || cards.debug() != cachedDebugPanel) {
            cards = new Cards(cachedRamPanel, cachedVramPanel, cachedUiBudgetPanel, cachedDebugPanel);
        }
    }

    /**
     * The legacy F3 cards: RAM then VRAM (then the UI budgets) stacked 10 px from the top-left, 8 px
     * apart, 280 px wide; the debug info 10 px from the top-right. Never scaled.
     */
    public static void paintLegacy(MasonryUI masonryUI, Cards cards, int sw, int sh) {
        if (!masonryUI.beginFrame(sw, sh, 1.0f)) return;
        try {
            float leftMargin = 10f;
            float panelWidth = 280f;
            float gap = 8f;
            float y = 10f;

            float ramHeight = cards.ram().render(masonryUI, leftMargin, y, panelWidth);
            y += ramHeight + gap;

            float vramHeight = cards.vram().render(masonryUI, leftMargin, y, panelWidth);
            if (cards.uiBudget() != null) {
                cards.uiBudget().render(masonryUI, leftMargin, y + vramHeight + gap, panelWidth);
            }

            // Right-side debug info panel
            float rightX = sw - leftMargin - panelWidth;
            cards.debug().render(masonryUI, rightX, 10f, panelWidth);

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
