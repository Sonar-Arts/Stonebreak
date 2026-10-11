package com.stonebreak.ui.terrainMapper.handlers;

import com.openmason.engine.ui.masonry.MCategoryButton;
import com.stonebreak.ui.terrainMapper.TerrainMapperLayout;
import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager.ActiveField;
import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerKind;

/**
 * The terrain mapper's pointer rules, addressed by target: the document's actions (#301) press a
 * named button or field, or press, drag, release and wheel on the map. A press on the map unfocuses
 * the fields and begins a drag; a release that barely moved sets the spawn. The wheel zooms inside
 * the map only. Moves update the map hover readout and, while dragging, pan the viewport.
 */
public final class TerrainMouseHandler {

    private final TerrainMapperStateManager state;

    private boolean pendingSpawnSet;
    private float pressStartX;
    private float pressStartY;
    private static final float CLICK_THRESHOLD_PX = 4f;

    public TerrainMouseHandler(TerrainMapperStateManager state) {
        this.state = state;
    }

    // ─────────────────────────────────────────────── The document's pointer (#301)

    /**
     * A press on a named control: {@code back}, {@code character}, {@code create}, {@code simulate},
     * {@code spawn}, {@code center} or {@code mode:<VisualizerKind>}. A disabled button takes the press
     * and does nothing. @return false for an unknown name
     */
    public boolean pressButton(String name) {
        if (name.startsWith("mode:")) {
            for (MCategoryButton<VisualizerKind> mode : state.getModeButtons()) {
                if (mode.tag().name().equals(name.substring(5))) {
                    mode.click();
                    return true;
                }
            }
            return false;
        }
        var button = switch (name) {
            case "back" -> state.getBackButton();
            case "character" -> state.getCharacterButton();
            case "create" -> state.getCreateButton();
            case "simulate" -> state.getSimulateSeedButton();
            case "spawn" -> state.getSetSpawnButton();
            case "center" -> state.getCenterOnSpawnButton();
            default -> null;
        };
        if (button == null) return false;
        button.click();
        return true;
    }

    /** A press on a text field: it takes the keyboard. */
    public void pressField(ActiveField field) {
        state.setActiveField(field);
    }

    /** A left press on the map at window pixel {@code (x, y)}: unfocuses the fields, starts a drag. */
    public void pressMap(float x, float y) {
        state.setActiveField(ActiveField.NONE);
        state.beginDrag(x, y);
        pendingSpawnSet = true;
        pressStartX = x;
        pressStartY = y;
    }

    /** The left button came up anywhere: ends a drag; a press that barely moved sets the spawn. */
    public void release(float x, float y, int windowWidth, int windowHeight) {
        if (pendingSpawnSet) {
            float dx = x - pressStartX;
            float dy = y - pressStartY;
            if (dx * dx + dy * dy < CLICK_THRESHOLD_PX * CLICK_THRESHOLD_PX) {
                TerrainMapperLayout.Rect map = new TerrainMapperLayout(windowWidth, windowHeight).map();
                float cx = map.centerX();
                float cz = map.y() + map.height() / 2f;
                state.setSpawnPoint(
                        Math.round(state.getViewport().screenToWorldX(x, cx)),
                        Math.round(state.getViewport().screenToWorldZ(y, cz))
                );
            }
            pendingSpawnSet = false;
        }
        state.endDrag();
    }

    /** The pointer moved: pans while dragging and refreshes the map readout. */
    public void move(float x, float y, int windowWidth, int windowHeight) {
        if (state.isDragging()) {
            float dx = state.consumeDragDx(x);
            float dy = state.consumeDragDy(y);
            state.getViewport().panBy(dx, dy);
        }
        updateHoverReadout(x, y, new TerrainMapperLayout(windowWidth, windowHeight));
    }

    /** The wheel at window pixel {@code (x, y)}: zooms about it, on the map only. */
    public void wheel(double mouseX, double mouseY, double delta, int windowWidth, int windowHeight) {
        if (delta == 0.0) return;
        float mx = (float) mouseX;
        float my = (float) mouseY;
        TerrainMapperLayout layout = new TerrainMapperLayout(windowWidth, windowHeight);
        TerrainMapperLayout.Rect map = layout.map();
        if (!map.contains(mx, my)) return;
        float factor = delta > 0
                ? TerrainMapperConfig.ZOOM_STEP
                : 1f / TerrainMapperConfig.ZOOM_STEP;
        state.getViewport().zoomAt(mx, my, map.centerX(), map.y() + map.height() / 2f, factor);
        state.markZoomInteracting();
    }

    // ─────────────────────────────────────────────── Internals

    private void updateHoverReadout(float mx, float my, TerrainMapperLayout layout) {
        TerrainMapperLayout.Rect map = layout.map();
        if (!map.contains(mx, my)) {
            state.clearHoverValue();
            return;
        }
        float centerX = map.centerX();
        float centerZ = map.y() + map.height() / 2f;
        int worldX = Math.round(state.getViewport().screenToWorldX(mx, centerX));
        int worldZ = Math.round(state.getViewport().screenToWorldZ(my, centerZ));
        NoiseVisualizer visualizer = state.getVisualizers().get(state.getActiveVisualizer());
        if (visualizer == null) {
            state.clearHoverValue();
            return;
        }
        float raw = visualizer.sample(worldX, worldZ);
        state.setHoverValue(worldX, worldZ, raw);
    }
}
