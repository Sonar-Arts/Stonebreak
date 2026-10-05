package com.openmason.main.systems.uiPreview;

import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.rendering.PreviewMapping;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiResources;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiMouseButton;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import io.github.humbleui.skija.Typeface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Previews a real {@code .omui}/{@code .sbui} document inside Open Mason (#287) through
 * {@link GameUiDocuments}, the host the game window uses, so styles, layout and pseudo-state
 * rules render identically in both; only the target differs (raster upload or GPU framebuffer).
 * Pointer hover and presses route through {@link PreviewMapping} into {@code :hover} and
 * {@code :active}. "Reload" re-reads the file and keeps each surviving element's instance state,
 * the live path component and document edits take.
 *
 * <p>Enabled with {@code -Dopenmason.uidoc.preview=<file>} (or {@code =true} to start empty).
 * The UI Editor (#293) will host this view as its canvas.
 */
public final class UiDocumentPreviewPanel implements AutoCloseable {

    public static final String PROPERTY = "openmason.uidoc.preview";
    public static final boolean ENABLED = System.getProperty(PROPERTY) != null;

    private static final Logger logger = LoggerFactory.getLogger(UiDocumentPreviewPanel.class);
    private static final int BACKDROP = 0xFF203040;

    private final ImBoolean visible = new ImBoolean(true);
    private final ImInt zoom = new ImInt(1);
    private final ImInt pathChoice = new ImInt(1);
    private final float[] uiScale = {1f};
    private final ImString file = new ImString(1024);
    private Typeface typeface;
    private MasonryPreview preview;
    private UiDocumentView view;
    private String status = "";
    private boolean failed;
    private boolean wasDown;

    public UiDocumentPreviewPanel() {
        String initial = System.getProperty(PROPERTY, "");
        if (!initial.isBlank() && !"true".equals(initial)) {
            file.set(initial);
        }
    }

    public void render() {
        if (!visible.get() || failed) {
            return;
        }
        if (preview == null && !open()) {
            return;
        }
        ImGui.setNextWindowSize(900, 640, ImGuiCond.FirstUseEver);
        if (ImGui.begin("UI Document Preview", visible)) {
            controls();
            if (view != null) {
                int z = Math.max(1, zoom.get());
                int w = (int) (Math.max(64, ImGui.getContentRegionAvailX()) / z);
                int h = (int) (Math.max(64, ImGui.getContentRegionAvailY()) / z);
                preview.draw(w, h, z, this::paint);
                route();
            }
        }
        ImGui.end();
    }

    private void controls() {
        ImGui.setNextItemWidth(420);
        ImGui.inputText("File", file);
        ImGui.sameLine();
        if (ImGui.button("Load")) {
            load();
        }
        ImGui.sameLine();
        if (ImGui.button("Reload") && view != null) {
            reload();
        }
        ImGui.radioButton("GPU framebuffer", pathChoice, 0);
        ImGui.sameLine();
        ImGui.radioButton("Raster upload", pathChoice, 1);
        preview.setPath(pathChoice.get() == 0 ? MasonryPreview.Path.GPU : MasonryPreview.Path.RASTER);
        ImGui.sameLine();
        ImGui.setNextItemWidth(100);
        ImGui.sliderInt("Zoom", zoom.getData(), 1, 4);
        ImGui.sameLine();
        ImGui.setNextItemWidth(120);
        ImGui.sliderFloat("UI scale", uiScale, 0.5f, 3f);
        if (!status.isEmpty()) {
            ImGui.textWrapped(status);
        }
    }

    private void paint(MasonryUI ui, int[] size) {
        ui.canvas().clear(BACKDROP);
        view.render(ui, size[0], size[1], uiScale[0], 1f);
    }

    private void route() {
        boolean down = ImGui.isMouseDown(ImGuiMouseButton.Left);
        if (ImGui.isItemHovered()) {
            PreviewMapping m = preview.mapping();
            float cx = m.canvasX(ImGui.getMousePosX());
            float cy = m.canvasY(ImGui.getMousePosY());
            if (down && !wasDown) {
                view.pointerDown(cx, cy);
            } else if (!down && wasDown) {
                UiElement clicked = view.pointerUp(cx, cy);
                if (clicked != null) {
                    status = "clicked " + clicked.key();
                }
            } else {
                view.pointerMove(cx, cy);
            }
            UiElement under = view.instance().hitTest(cx, cy);
            if (under != null) {
                ImGui.setTooltip(under.type() + " [" + under.key() + "]");
            }
        } else {
            view.pointerLeave();
        }
        wasDown = down;
    }

    private void load() {
        try {
            if (view != null) {
                view.close();
                view = null;
            }
            Typeface tf = typeface;
            view = GameUiDocuments.open(Path.of(file.get().trim()), () -> tf);
            status = summary("Loaded", view.instance());
        } catch (Exception e) {
            status = "Cannot load: " + e.getMessage();
            logger.warn("UI document preview: cannot load {}", file.get(), e);
        }
    }

    private void reload() {
        try {
            UiDocumentInstance.ReloadReport r = GameUiDocuments.reload(view, Path.of(file.get().trim()));
            status = summary("Reloaded (kept " + r.kept().size() + ", added " + r.added().size() + ", dropped "
                + r.dropped().size() + ")", view.instance());
        } catch (Exception e) {
            status = "Cannot reload: " + e.getMessage();
        }
    }

    private static String summary(String head, UiDocumentInstance ui) {
        StringBuilder sb = new StringBuilder(head).append(": ").append(ui.elements().size()).append(" elements");
        for (UiRuntimeDiagnostic d : ui.diagnostics()) {
            sb.append("\n").append(d);
        }
        return sb.toString();
    }

    private boolean open() {
        try {
            typeface = GameUiResources.loadTypeface();
            preview = new MasonryPreview(typeface, MasonryPreview.Path.RASTER);
            if (!file.get().isBlank()) {
                load();
            }
            return true;
        } catch (Exception e) {
            failed = true;
            logger.error("UI document preview unavailable", e);
            return false;
        }
    }

    @Override
    public void close() {
        if (view != null) {
            view.close();
            view = null;
        }
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
