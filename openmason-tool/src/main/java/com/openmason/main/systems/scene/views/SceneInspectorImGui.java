package com.openmason.main.systems.scene.views;

import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.engine.rendering.viewer.transform.TransformState;
import com.openmason.main.systems.scene.SceneDocument;
import com.openmason.main.systems.scene.SceneModelRef;
import com.openmason.main.systems.scene.SceneSelectionState;
import com.openmason.main.systems.scene.SceneViewerActions;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImString;

/**
 * Transform and source details for the selected scene instance.
 */
public class SceneInspectorImGui {

    public static final String WINDOW_TITLE = "Scene Inspector";

    private final SceneDocument document;
    private final SceneSelectionState selection;
    private final SceneViewerActions actions;
    private final ImBoolean visible;

    private final float[] position = new float[3];
    private final float[] rotation = new float[3];
    private final float[] scale = new float[3];
    private final ImString nameBuffer = new ImString(128);

    /** Instance the buffers currently mirror, so edits are not clobbered every frame. */
    private String boundInstanceId;

    public SceneInspectorImGui(SceneDocument document, SceneSelectionState selection,
                               SceneViewerActions actions, ImBoolean visible) {
        this.document = document;
        this.selection = selection;
        this.actions = actions;
        this.visible = visible;
    }

    public void render() {
        if (!visible.get()) {
            return;
        }
        if (ImGui.begin(WINDOW_TITLE, visible)) {
            ModelInstance instance = currentInstance();
            if (instance == null) {
                ImGui.textDisabled("No instance selected.");
                boundInstanceId = null;
            } else {
                renderInstance(instance);
            }
        }
        ImGui.end();
    }

    private ModelInstance currentInstance() {
        String primary = selection.primary();
        return primary == null ? null : document.scene().byId(primary);
    }

    private void renderInstance(ModelInstance instance) {
        syncBuffers(instance);

        if (selection.size() > 1) {
            ImGui.textDisabled(selection.size() + " selected — editing the primary");
        }

        if (ImGui.inputText("Name", nameBuffer)) {
            String typed = nameBuffer.get().trim();
            if (!typed.isEmpty() && !typed.equals(instance.name())) {
                instance.setName(typed);
                actions.markDirty();
            }
        }

        boolean locked = instance.isLocked();
        if (locked) {
            ImGui.textDisabled("Locked — unlock in the outliner to edit");
        }

        ImGui.beginDisabled(locked);
        TransformState transform = instance.transform();

        if (ImGui.dragFloat3("Position", position, 0.05f)) {
            transform.setPosition(position[0], position[1], position[2]);
            actions.markDirty();
        }
        if (ImGui.dragFloat3("Rotation", rotation, 0.5f)) {
            transform.setRotation(rotation[0], rotation[1], rotation[2]);
            actions.markDirty();
        }
        if (ImGui.dragFloat3("Scale", scale, 0.01f)) {
            transform.setScale(scale[0], scale[1], scale[2]);
            actions.markDirty();
        }
        ImGui.endDisabled();

        ImGui.separator();

        SceneModelRef ref = document.modelFor(instance);
        if (ref != null) {
            ImGui.text("Model");
            ImGui.textWrapped(ref.sourceName() != null ? ref.sourceName() : "(embedded)");
            ImGui.textDisabled(ref.status().name());

            if (ImGui.button("Edit Model...")) {
                actions.editSelectedModel();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Open this model in the Model Editor.\n"
                        + "Saving there updates every instance that places it.");
            }
        } else {
            ImGui.textDisabled("Model unavailable");
        }
    }

    /**
     * Refresh the edit buffers from the instance, but only when the selection changed —
     * otherwise a gizmo drag and a typed value fight each other every frame.
     */
    private void syncBuffers(ModelInstance instance) {
        boolean switched = !instance.id().equals(boundInstanceId);
        if (switched) {
            boundInstanceId = instance.id();
            nameBuffer.set(instance.name());
        }
        if (switched || !ImGui.isAnyItemActive()) {
            TransformState t = instance.transform();
            position[0] = t.getPositionX();
            position[1] = t.getPositionY();
            position[2] = t.getPositionZ();
            rotation[0] = t.getRotationX();
            rotation[1] = t.getRotationY();
            rotation[2] = t.getRotationZ();
            scale[0] = t.getScaleX();
            scale[1] = t.getScaleY();
            scale[2] = t.getScaleZ();
        }
    }
}
