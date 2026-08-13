package com.openmason.main.systems.scene.views;

import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.main.systems.scene.SceneDocument;
import com.openmason.main.systems.scene.SceneSelectionState;
import com.openmason.main.systems.scene.SceneViewerController;
import com.openmason.main.systems.scene.SceneViewerUIState;
import com.openmason.main.systems.scene.dnd.ScenePayloads;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiWindowFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;

/**
 * The Scene Viewer window: toolbar, the rendered image, and the drop target.
 *
 * <p>Mirrors {@code ViewportMainView} — no visibility {@code ImBoolean}, so it has no
 * close button and cannot be lost from the centre tab bar.
 */
public class SceneViewerMainView {

    private static final Logger logger = LoggerFactory.getLogger(SceneViewerMainView.class);

    /** ImGui window title, and the DockBuilder key for the centre node. */
    public static final String WINDOW_TITLE = "Scene Viewer";

    private final SceneViewerUIState state;
    private final SceneViewerController controller;
    private final SceneDocument document;
    private final SceneSelectionState selection;
    private final SceneToolbarRenderer toolbar;
    private final com.openmason.main.systems.keybinds.KeybindRegistry keybinds =
            com.openmason.main.systems.keybinds.KeybindRegistry.getInstance();

    /** Invoked with (absolute .omo path, drop position) when a model is dragged in. */
    private BiConsumer<String, float[]> onModelDropped = (path, pos) -> { };

    private final ImVec2 viewportSize = new ImVec2();

    public SceneViewerMainView(SceneViewerUIState state, SceneViewerController controller,
                               SceneDocument document, SceneSelectionState selection,
                               SceneToolbarRenderer toolbar) {
        this.state = state;
        this.controller = controller;
        this.document = document;
        this.selection = selection;
        this.toolbar = toolbar;
    }

    public void setOnModelDropped(BiConsumer<String, float[]> callback) {
        this.onModelDropped = callback != null ? callback : (path, pos) -> { };
    }

    public void render() {
        if (ImGui.begin(WINDOW_TITLE, ImGuiWindowFlags.NoNavInputs)) {
            state.setSceneViewVisible(true);
            state.setSceneViewFocused(ImGui.isWindowFocused());
            handleShortcuts();
            toolbar.render();
            ImGui.separator();
            renderViewport();
        } else {
            state.setSceneViewVisible(false);
            state.setSceneViewFocused(false);
        }
        ImGui.end();
    }

    /**
     * Scene shortcuts, dispatched through the central keybind registry (context "scene",
     * see {@code SceneKeybindActions}) so they are rebindable, match modifiers exactly
     * ({@code ShortcutKey.isPressed} — Ctrl+Alt+Z / AltGr chords do not fire undo), and
     * cannot drive the model editor's identical chords in the same keypress (focus gate).
     *
     * <p>Further guards: never while the user is typing in a text field — the toolbar's
     * snap-increment field becomes an InputText with its own built-in Ctrl+Z — and never
     * mid-gizmo-drag, where an undo is invisibly stomped by the next drag frame and the
     * release would clear the redo stack, silently destroying the entry. Keys are polled
     * without auto-repeat so holding the chord fires once instead of draining history.
     */
    private void handleShortcuts() {
        if (!ImGui.isWindowFocused(imgui.flag.ImGuiFocusedFlags.RootAndChildWindows)) {
            return;
        }
        if (!com.openmason.main.systems.viewport.ViewportKeyboardShortcuts.shouldProcessShortcuts()) {
            return;
        }
        if (controller.gizmoRenderer().isDragging()) {
            return;
        }
        for (com.openmason.main.systems.keybinds.KeybindAction action : keybinds.getActionsByContext("scene")) {
            if (keybinds.getKeybind(action.getId()).isPressed(false)) {
                action.execute();
                return; // only execute the first matching shortcut
            }
        }
    }

    private void renderViewport() {
        ImGui.getContentRegionAvail(viewportSize);
        if (viewportSize.x < 64) viewportSize.x = 64;
        if (viewportSize.y < 64) viewportSize.y = 64;

        controller.resize((int) viewportSize.x, (int) viewportSize.y);
        controller.render();

        int texture = controller.getColorTexture();
        if (texture <= 0) {
            ImGui.textDisabled("Scene viewport not available");
            return;
        }

        ImVec2 imagePos = ImGui.getCursorScreenPos();
        // V-flipped, matching the model editor: the FBO's origin is bottom-left.
        ImGui.image(texture, viewportSize.x, viewportSize.y, 0, 1, 1, 0);

        handleDropTarget(imagePos);

        // Camera and gizmo first, through the model editor's own controllers. A drag on
        // either must not also register as a selection click.
        boolean hovered = ImGui.isWindowHovered();
        controller.viewportInput().handleInput(imagePos, viewportSize.x, viewportSize.y,
                hovered, controller.camera());
        if (!controller.viewportInput().consumedInput()) {
            handleClickSelection(imagePos);
        }
    }

    private void handleDropTarget(ImVec2 imagePos) {
        if (ImGui.beginDragDropTarget()) {
            Object payload = ImGui.acceptDragDropPayload(ScenePayloads.OMO_ASSET);
            if (payload instanceof String omoPath) {
                ImVec2 mouse = ImGui.getMousePos();
                onModelDropped.accept(omoPath,
                        new float[]{mouse.x - imagePos.x, mouse.y - imagePos.y});
            }
            ImGui.endDragDropTarget();
        }
    }

    private void handleClickSelection(ImVec2 imagePos) {
        if (!ImGui.isItemHovered() || !ImGui.isMouseReleased(0)) {
            return;
        }
        ImVec2 mouse = ImGui.getMousePos();
        float localX = mouse.x - imagePos.x;
        float localY = mouse.y - imagePos.y;

        try {
            controller.pickAt(localX, localY).ifPresentOrElse(hit -> {
                ModelInstance instance = hit.instance();
                if (ImGui.getIO().getKeyCtrl()) {
                    selection.toggle(instance.id());
                } else if (ImGui.getIO().getKeyShift()) {
                    selection.selectRangeTo(instance.id(), document.instances());
                } else {
                    selection.select(instance.id());
                }
                controller.setGizmoInstance(resolvePrimary());
            }, () -> {
                // Clicking empty space clears, unless the user is extending a selection.
                if (!ImGui.getIO().getKeyCtrl() && !ImGui.getIO().getKeyShift()) {
                    selection.clear();
                    controller.setGizmoInstance(null);
                }
            });
        } catch (Exception e) {
            logger.error("Scene pick failed", e);
        }
    }

    private ModelInstance resolvePrimary() {
        String primary = selection.primary();
        return primary == null ? null : document.scene().byId(primary);
    }
}
