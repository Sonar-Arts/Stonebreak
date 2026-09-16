package com.openmason.main.systems.scene.views;

import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.engine.rendering.viewer.transform.TransformState;
import com.openmason.main.systems.scene.SceneDocument;
import com.openmason.main.systems.scene.SceneModelRef;
import com.openmason.main.systems.scene.SceneSelectionState;
import com.openmason.main.systems.scene.SceneViewerActions;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import org.joml.Vector3f;

/**
 * Transform and source details for the selected scene instance.
 *
 * <p>Typed transform edits apply live while a field is being dragged and land in the
 * undo history as one entry when the field is released — the same granularity as a
 * gizmo drag, so Ctrl+Z steps back one edit rather than one frame of dragging.
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

    /** Pose captured when a transform field became active; null between edits. */
    private Vector3f editStartPos;
    private Vector3f editStartRot;
    private Vector3f editStartScale;

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
                renderSceneSummary();
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

    private void renderSceneSummary() {
        ImGui.separator();
        ImGui.text(document.sceneName());
        ImGui.textDisabled(document.instances().size() + " instance(s), "
                + document.models().size() + " model(s)");
        if (!document.orphanInstances().isEmpty()) {
            ImGui.textDisabled(document.orphanInstances().size() + " placement(s) with a missing model");
        }
    }

    private void renderInstance(ModelInstance instance) {
        syncBuffers(instance);

        if (selection.size() > 1) {
            ImGui.textDisabled(selection.size() + " selected — editing the primary");
        }

        if (ImGui.inputText("Name", nameBuffer, ImGuiInputTextFlags.EnterReturnsTrue)
                || ImGui.isItemDeactivatedAfterEdit()) {
            actions.rename(instance, nameBuffer.get());
            nameBuffer.set(instance.name());
        }

        boolean locked = instance.isLocked();
        if (locked) {
            ImGui.textDisabled("Locked — unlock in the outliner to edit");
        }

        ImGui.beginDisabled(locked);
        TransformState transform = instance.transform();

        if (ImGui.dragFloat3("Position", position, 0.05f)) {
            transform.setPosition(position[0], position[1], position[2]);
        }
        trackEdit(instance, "Move ");
        if (ImGui.dragFloat3("Rotation", rotation, 0.5f)) {
            transform.setRotation(rotation[0], rotation[1], rotation[2]);
        }
        trackEdit(instance, "Rotate ");
        if (ImGui.dragFloat3("Scale", scale, 0.01f)) {
            transform.setScale(scale[0], scale[1], scale[2]);
        }
        trackEdit(instance, "Scale ");
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
     * Bracket the last-submitted transform field: capture the pose when it activates,
     * record one undo entry when it deactivates after an edit.
     */
    private void trackEdit(ModelInstance instance, String verb) {
        if (ImGui.isItemActivated()) {
            TransformState t = instance.transform();
            editStartPos = new Vector3f(t.getPositionX(), t.getPositionY(), t.getPositionZ());
            editStartRot = new Vector3f(t.getRotationX(), t.getRotationY(), t.getRotationZ());
            editStartScale = new Vector3f(t.getScaleX(), t.getScaleY(), t.getScaleZ());
        }
        if (ImGui.isItemDeactivatedAfterEdit() && editStartPos != null) {
            actions.commitTransform(instance, editStartPos, editStartRot, editStartScale,
                    verb + instance.name());
            editStartPos = null;
            editStartRot = null;
            editStartScale = null;
        } else if (ImGui.isItemDeactivated()) {
            editStartPos = null;
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
            editStartPos = null;
        }
        if (switched || !ImGui.isAnyItemActive()) {
            if (!switched) {
                nameBuffer.set(instance.name()); // an undo may have renamed it
            }
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
