package com.stonebreak.ui.terrainMapper.handlers;

import com.stonebreak.rendering.UI.masonryUI.MCategoryButton;
import com.stonebreak.ui.terrainMapper.TerrainMapperLayout;
import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainMapper.managers.PreviewSnapshot;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager.ActiveField;
import com.stonebreak.ui.terrainMapper.managers.TerrainPreviewLoader;
import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerKind;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * Mouse dispatch for the terrain mapper. Order of priority on press:
 *   1. Footer buttons (back / create / simulate)
 *   2. Mode category buttons
 *   3. Sidebar text fields
 *   4. Map viewport (begin drag, clear field focus)
 *
 * Wheel inside the map zooms; elsewhere it's ignored. Move events update
 * widget hover, the map hover readout, and — if dragging — pan the viewport.
 */
public final class TerrainMouseHandler {

    private final TerrainMapperStateManager state;
    private final TerrainActionHandler actions;

    private boolean pendingSpawnSet;
    private float pressStartX;
    private float pressStartY;
    private static final float CLICK_THRESHOLD_PX = 4f;

    public TerrainMouseHandler(TerrainMapperStateManager state, TerrainActionHandler actions) {
        this.state = state;
        this.actions = actions;
    }

    // ─────────────────────────────────────────────── Move

    public void handleMouseMove(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        float mx = (float) mouseX;
        float my = (float) mouseY;
        TerrainMapperLayout layout = new TerrainMapperLayout(windowWidth, windowHeight);

        updateHoverStates(mx, my);

        if (state.isDragging()) {
            float dx = state.consumeDragDx(mx);
            float dy = state.consumeDragDy(my);
            state.getViewport().panBy(dx, dy);
        }

        updateHoverReadout(mx, my, layout);
    }

    // ─────────────────────────────────────────────── Click

    public void handleMouseClick(double mouseX, double mouseY, int windowWidth, int windowHeight,
                                 int button, int action) {
        if (button != GLFW_MOUSE_BUTTON_LEFT) return;
        float mx = (float) mouseX;
        float my = (float) mouseY;

        if (action == GLFW_RELEASE) {
            if (pendingSpawnSet) {
                float dx = mx - pressStartX;
                float dy = my - pressStartY;
                if (dx * dx + dy * dy < CLICK_THRESHOLD_PX * CLICK_THRESHOLD_PX) {
                    TerrainMapperLayout releaseLayout = new TerrainMapperLayout(windowWidth, windowHeight);
                    TerrainMapperLayout.Rect map = releaseLayout.map();
                    float cx = map.centerX();
                    float cz = map.y() + map.height() / 2f;
                    state.setSpawnPoint(
                            Math.round(state.getViewport().screenToWorldX(mx, cx)),
                            Math.round(state.getViewport().screenToWorldZ(my, cz))
                    );
                }
                pendingSpawnSet = false;
            }
            state.endDrag();
            return;
        }
        if (action != GLFW_PRESS) return;

        TerrainMapperLayout layout = new TerrainMapperLayout(windowWidth, windowHeight);

        if (state.getBackButton().handleClick(mx, my)) return;
        if (state.getCharacterButton().handleClick(mx, my)) return;
        if (state.getCreateButton().handleClick(mx, my)) return;
        if (state.getSimulateSeedButton().handleClick(mx, my)) return;

        for (MCategoryButton<VisualizerKind> mode : state.getModeButtons()) {
            if (mode.handleClick(mx, my)) return;
        }

        if (state.getSetSpawnButton().handleClick(mx, my)) return;
        if (state.getCenterOnSpawnButton().handleClick(mx, my)) return;

        if (layout.worldNameField().contains(mx, my)) {
            state.setActiveField(ActiveField.WORLD_NAME);
            return;
        }
        if (layout.seedField().contains(mx, my)) {
            state.setActiveField(ActiveField.SEED);
            return;
        }

        if (layout.map().contains(mx, my)) {
            state.setActiveField(ActiveField.NONE);
            state.beginDrag(mx, my);
            pendingSpawnSet = true;
            pressStartX = mx;
            pressStartY = my;
        }
    }

    // ─────────────────────────────────────────────── Wheel

    public void handleMouseWheel(double mouseX, double mouseY, double delta) {
        if (delta == 0.0) return;
        float mx = (float) mouseX;
        float my = (float) mouseY;
        // Reconstruct the layout against the current window size. The caller
        // (Main's scroll callback) already has the GLFW context for the
        // dimensions; we just pull them from Game for consistency.
        TerrainMapperLayout layout = new TerrainMapperLayout(
                com.stonebreak.core.Game.getWindowWidth(),
                com.stonebreak.core.Game.getWindowHeight());
        TerrainMapperLayout.Rect map = layout.map();
        if (!map.contains(mx, my)) return;
        float factor = delta > 0
                ? TerrainMapperConfig.ZOOM_STEP
                : 1f / TerrainMapperConfig.ZOOM_STEP;
        state.getViewport().zoomAt(mx, my, map.centerX(), map.y() + map.height() / 2f, factor);
        state.markZoomInteracting();
    }

    // ─────────────────────────────────────────────── Internals

    private void updateHoverStates(float mx, float my) {
        state.getBackButton().updateHover(mx, my);
        state.getCharacterButton().updateHover(mx, my);
        state.getCreateButton().updateHover(mx, my);
        state.getSimulateSeedButton().updateHover(mx, my);
        for (MCategoryButton<VisualizerKind> mode : state.getModeButtons()) {
            mode.updateHover(mx, my);
            mode.setSelected(mode.tag() == state.getActiveVisualizer());
        }
        state.getSetSpawnButton().updateHover(mx, my);
        state.getCenterOnSpawnButton().updateHover(mx, my);
    }

    /**
     * Reads the hovered value out of the published preview snapshot rather than resampling.
     * This runs inside the GLFW cursor callback on the main thread, so it must never touch the
     * terrain bridge — a single {@code visualizer.sample()} here can block on a diffusion
     * inference and stall the whole game just from moving the mouse. Reading the snapshot also
     * guarantees the number in the footer is exactly the one behind the pixel under the cursor,
     * including while a fresh sample is still in flight.
     */
    private void updateHoverReadout(float mx, float my, TerrainMapperLayout layout) {
        TerrainMapperLayout.Rect map = layout.map();
        if (!map.contains(mx, my)) {
            state.clearHoverValue();
            return;
        }
        float centerX = map.centerX();
        float centerZ = map.y() + map.height() / 2f;
        float worldX = state.getViewport().screenToWorldX(mx, centerX);
        float worldZ = state.getViewport().screenToWorldZ(my, centerZ);
        float value = drawnValueAt(worldX, worldZ);
        if (Float.isNaN(value)) {
            // Cursor is over map area nothing on screen speaks for — blank, not stale.
            state.clearHoverValue();
            return;
        }
        state.setHoverValue(Math.round(worldX), Math.round(worldZ), value);
    }

    /**
     * The value behind the drawn pixel, read through the same two layers the renderer paints:
     * the running pass first, then the backdrop showing through wherever it hasn't reached.
     *
     * <p>A layer sampled by a different visualizer is skipped rather than read. Its numbers would
     * be formatted by whichever mode is selected now — a height reported as a biome — and the
     * renderer already dims that layer to say it isn't the answer to the question being asked.
     */
    private float drawnValueAt(float worldX, float worldZ) {
        NoiseVisualizer active = state.getVisualizers().get(state.getActiveVisualizer());
        TerrainPreviewLoader loader = state.getPreviewLoader();
        float value = valueFrom(loader.snapshot(), active, worldX, worldZ);
        if (!Float.isNaN(value)) return value;
        return valueFrom(loader.backdrop(), active, worldX, worldZ);
    }

    private static float valueFrom(PreviewSnapshot layer, NoiseVisualizer active, float worldX, float worldZ) {
        if (layer == null || layer.request().visualizer() != active) return Float.NaN;
        return layer.valueAt(worldX, worldZ);
    }
}
