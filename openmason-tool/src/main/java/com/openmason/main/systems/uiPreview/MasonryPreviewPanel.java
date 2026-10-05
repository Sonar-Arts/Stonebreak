package com.openmason.main.systems.uiPreview;

import com.openmason.engine.ui.masonry.MButton;
import com.openmason.engine.ui.masonry.MDropdown;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MToggle;
import com.openmason.engine.ui.masonry.MVitalBar;
import com.openmason.engine.ui.masonry.MasonryEnvironment;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.rendering.PreviewMapping;
import com.stonebreak.ui.runtime.GameUiResources;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import io.github.humbleui.skija.Typeface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Developer window showing Stonebreak's own Masonry widgets inside Open Mason through
 * {@link MasonryPreview}: the same drawing code, font and SBT texture as the game, on either
 * the GPU or raster path, with zoom, UI scale and pointer hover routed through the preview
 * mapping. Proof surface for #286 until the UI Editor (#293) hosts real documents.
 *
 * <p>Enabled with {@code -Dopenmason.masonry.preview=true}.
 */
public final class MasonryPreviewPanel implements AutoCloseable {

    public static final boolean ENABLED = Boolean.getBoolean("openmason.masonry.preview");

    private static final Logger logger = LoggerFactory.getLogger(MasonryPreviewPanel.class);
    private static final int BACKDROP = 0xFF203040;
    private static final String HEART = "/ui/HUD/Health Icon/SB_Full_Health_Icon.sbt";

    private final ImBoolean visible = new ImBoolean(true);
    private final ImInt zoom = new ImInt(2);
    private final ImInt pathChoice = new ImInt(1);
    private final float[] uiScale = {1f};
    private final MButton button = new MButton("Resume Game");
    private final MToggle toggle = new MToggle("Wireframes", true);
    private final MDropdown dropdown = new MDropdown("Quality", new String[] {"Low", "High"}).itemHeight(20);
    private Typeface typeface;
    private MasonryPreview preview;
    private boolean failed;

    public void render() {
        if (!visible.get() || failed) {
            return;
        }
        if (preview == null && !open()) {
            return;
        }
        ImGui.setNextWindowSize(640, 420, imgui.flag.ImGuiCond.FirstUseEver);
        if (ImGui.begin("Masonry Preview", visible)) {
            controls();
            int z = Math.max(1, zoom.get());
            int w = (int) (Math.max(64, ImGui.getContentRegionAvailX()) / z);
            int h = (int) (Math.max(64, ImGui.getContentRegionAvailY()) / z);
            preview.draw(w, h, z, (ui, size) -> paint(ui, size[0], size[1]));
            if (ImGui.isItemHovered()) {
                PreviewMapping m = preview.mapping();
                float cx = m.canvasX(ImGui.getMousePosX());
                float cy = m.canvasY(ImGui.getMousePosY());
                button.updateHover(cx, cy);
                toggle.updateHover(cx, cy);
                ImGui.setTooltip(String.format("canvas %.1f, %.1f", cx, cy));
            } else {
                button.setHovered(false);
                toggle.setHovered(false);
            }
        }
        ImGui.end();
    }

    private void controls() {
        ImGui.text("Path:");
        ImGui.sameLine();
        ImGui.radioButton("GPU framebuffer", pathChoice, 0);
        ImGui.sameLine();
        ImGui.radioButton("Raster upload", pathChoice, 1);
        preview.setPath(pathChoice.get() == 0 ? MasonryPreview.Path.GPU : MasonryPreview.Path.RASTER);
        ImGui.setNextItemWidth(120);
        ImGui.sliderInt("Zoom", zoom.getData(), 1, 4);
        ImGui.sameLine();
        ImGui.setNextItemWidth(120);
        if (ImGui.sliderFloat("UI scale", uiScale, 0.5f, 2f)) {
            float s = uiScale[0];
            MasonryEnvironment.installUiScale(() -> s);
        }
    }

    private void paint(MasonryUI ui, int w, int h) {
        ui.canvas().clear(BACKDROP);
        button.scaleText(true).bounds(16, 16, 170, 40).render(ui);
        toggle.bounds(16, 66, 160, 24).render(ui);
        new MVitalBar().label("HP").value(70).max(100).fillColor(0xFF3366FF).bounds(16, 100, 160, 20).render(ui);
        dropdown.bounds(200, 16, 90, 24);
        if (!dropdown.isOpen()) {
            dropdown.open();
        }
        dropdown.render(ui);
        MTexture heart = GameUiResources.texture(HEART);
        if (heart != null) {
            MPainter.drawImage(ui.canvas(), heart.image(), 200, 100, heart.width() * 2f, heart.height() * 2f);
        }
        ui.renderOverlays();
    }

    private boolean open() {
        try {
            typeface = GameUiResources.loadTypeface();
            preview = new MasonryPreview(typeface, MasonryPreview.Path.RASTER);
            return true;
        } catch (Exception e) {
            failed = true;
            logger.error("Masonry preview unavailable", e);
            return false;
        }
    }

    @Override
    public void close() {
        if (preview != null) {
            preview.close();
            preview = null;
        }
        if (typeface != null) {
            typeface.close();
            typeface = null;
        }
    }
}
