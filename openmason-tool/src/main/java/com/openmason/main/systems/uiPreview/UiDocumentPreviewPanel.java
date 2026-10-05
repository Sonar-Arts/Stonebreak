package com.openmason.main.systems.uiPreview;

import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.access.AccessibilityTree;
import com.openmason.engine.ui.runtime.input.PreviewInput;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.main.platform.ToolInputTap;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiResources;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import io.github.humbleui.skija.Typeface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Previews a real {@code .omui}/{@code .sbui} document inside Open Mason (#287) through
 * {@link GameUiDocuments}, the host the game window uses, so styles, layout and pseudo-state
 * rules render identically in both; only the target differs (raster upload or GPU framebuffer).
 * Input (#288) goes through {@link PreviewInput} into the same router the game window uses:
 * pointer buttons and wheel over the image, and, while this window has keyboard focus, keys and
 * text from {@link ToolInputTap}. Input never comes from outside the displayed canvas, and while
 * the document uses the keyboard Open Mason's own shortcuts and ImGui navigation stand down.
 * "Reload" re-reads the file and keeps each surviving element's instance state, the live path
 * component and document edits take.
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
    private PreviewInput input;
    private boolean documentHasKeyboard;
    private boolean wasFocused;
    private float lastMouseX = Float.NaN;
    private float lastMouseY = Float.NaN;
    private boolean lastOver;
    private final List<int[]> pendingKeys = new ArrayList<>();
    private final List<Integer> pendingChars = new ArrayList<>();
    private final ToolInputTap.Listener tap = new ToolInputTap.Listener() {
        @Override
        public void key(int key, int action, int mods) {
            pendingKeys.add(new int[]{key, action, mods});
        }

        @Override
        public void character(int codePoint) {
            pendingChars.add(codePoint);
        }
    };

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
        int flags = documentHasKeyboard ? ImGuiWindowFlags.NoNavInputs : ImGuiWindowFlags.None;
        boolean focusedWindow = false;
        if (ImGui.begin("UI Document Preview", visible, flags)) {
            controls();
            if (view != null) {
                int z = Math.max(1, zoom.get());
                int w = (int) (Math.max(64, ImGui.getContentRegionAvailX()) / z);
                int h = (int) (Math.max(64, ImGui.getContentRegionAvailY()) / z);
                preview.draw(w, h, z, this::paint);
                route();
                focusedWindow = ImGui.isWindowFocused() && !ImGui.getIO().getWantTextInput();
                routeKeyboard(focusedWindow);
            }
        }
        ImGui.end();
        pendingKeys.clear();
        pendingChars.clear();
        documentHasKeyboard = focusedWindow && input != null && input.wantsKeyboard();
        if (documentHasKeyboard) {
            ImGui.setNextFrameWantCaptureKeyboard(true); // editor shortcuts stand down
        }
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
        if (input == null) {
            return;
        }
        input.setMapping(preview.mapping());
        boolean over = ImGui.isItemHovered();
        float mx = ImGui.getMousePosX();
        float my = ImGui.getMousePosY();
        int mods = mods();
        input.router().tick(ImGui.getIO().getDeltaTime());
        if (mx != lastMouseX || my != lastMouseY || over != lastOver) { // real moves only
            input.pointerMove(mx, my, over);
            lastMouseX = mx;
            lastMouseY = my;
            lastOver = over;
        }
        for (int b = 0; b < 3; b++) {
            if (ImGui.isMouseClicked(b)) {
                input.pointerButton(mx, my, over, b, true, mods);
            }
            if (ImGui.isMouseReleased(b)) {
                input.pointerButton(mx, my, over, b, false, mods);
                UiElement clicked = input.router().lastClick();
                if (b == 0 && clicked != null) {
                    status = "clicked " + clicked.key();
                }
            }
        }
        float wheel = ImGui.getIO().getMouseWheel();
        float wheelH = ImGui.getIO().getMouseWheelH();
        if (wheel != 0 || wheelH != 0) {
            input.wheel(mx, my, over, wheelH, wheel, mods);
        }
        if (over) {
            float cx = preview.mapping().canvasX(mx);
            float cy = preview.mapping().canvasY(my);
            UiElement under = view.instance().hitTest(cx, cy);
            if (under != null) {
                ImGui.setTooltip(under.type() + " [" + under.key() + "] " + AccessibilityTree.role(under));
            }
        }
    }

    /** Keys and text reach the document only while this window has keyboard focus. */
    private void routeKeyboard(boolean focusedWindow) {
        if (input == null) {
            return;
        }
        if (wasFocused && !focusedWindow) {
            input.router().windowFocusLost(); // like the game window losing focus: releases won't arrive
        }
        wasFocused = focusedWindow;
        if (!focusedWindow) {
            return;
        }
        for (int[] k : pendingKeys) {
            input.router().key(k[0], k[1], k[2]);
        }
        for (int cp : pendingChars) {
            input.router().text(cp);
        }
        UiElement f = input.router().focus().focused();
        if (f != null) {
            ImGui.text("Focus: " + f.key() + " (" + AccessibilityTree.role(f) + ") "
                + AccessibilityTree.name(f, AccessibilityTree.role(f)));
        }
    }

    private static int mods() {
        int m = 0;
        if (ImGui.getIO().getKeyShift()) {
            m |= MKeys.MOD_SHIFT;
        }
        if (ImGui.getIO().getKeyCtrl()) {
            m |= MKeys.MOD_CONTROL;
        }
        if (ImGui.getIO().getKeyAlt()) {
            m |= MKeys.MOD_ALT;
        }
        if (ImGui.getIO().getKeySuper()) {
            m |= MKeys.MOD_SUPER;
        }
        return m;
    }

    private void load() {
        try {
            if (view != null) {
                view.close();
                view = null;
                input = null;
            }
            Typeface tf = typeface;
            view = GameUiDocuments.open(Path.of(file.get().trim()), () -> tf);
            input = new PreviewInput(view.input());
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
            ToolInputTap.add(tap);
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
        ToolInputTap.remove(tap);
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
