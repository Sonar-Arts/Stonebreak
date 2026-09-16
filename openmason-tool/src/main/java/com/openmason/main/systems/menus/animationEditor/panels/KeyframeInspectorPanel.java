package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import imgui.ImGui;
import imgui.type.ImFloat;
import imgui.type.ImString;
import org.joml.Vector3f;

/**
 * Right-hand inspector — clip metadata (name/fps/duration) at the top, then
 * the selected keyframe's pose fields.
 *
 * <p><b>Buffer sync:</b> text/number widgets are repopulated from the clip
 * only when the field was <em>not</em> focused on the previous frame, so an
 * undo/redo or external mutation can't clobber an in-flight edit. Keyframe
 * buffers re-snapshot whenever the underlying immutable {@link Keyframe}
 * reference changes — covers selection changes, edits, undo, and redo.
 *
 * <p><b>Pose edits coalesce:</b> while a pose/time field is active the edit
 * is previewed live (no history), and one undoable edit command is committed
 * when the field deactivates — typing "12.5" is one undo step, not four.
 */
public final class KeyframeInspectorPanel {

    private final AnimationEditorController controller;

    private final ImString clipNameBuf = new ImString(64);
    private final ImFloat fpsBuf = new ImFloat(30f);
    private final ImFloat durationBuf = new ImFloat(1f);

    private final ImFloat kfTimeBuf = new ImFloat(0f);
    private final float[] kfPosArr = new float[3];
    private final float[] kfRotArr = new float[3];
    private final float[] kfScaleArr = new float[3];
    private Keyframe lastSnapshot = null;

    /** The keyframe as it was when the in-flight edit began; null when no edit is open. */
    private Keyframe editBefore = null;
    private String editPartId;
    private int editIndex = -1;

    // Active-last-frame flags so we can decide whether to overwrite the buffer
    // *before* re-rendering the widget on the current frame.
    private boolean nameActivePrev, fpsActivePrev, durationActivePrev;

    private final LayerPanel layerPanel;

    public KeyframeInspectorPanel(AnimationEditorController controller) {
        this.controller = controller;
        this.layerPanel = new LayerPanel(controller);
    }

    public void setFileDialogService(com.openmason.main.systems.menus.dialogs.FileDialogService service) {
        layerPanel.setFileDialogService(service);
    }

    public void render() {
        renderClipMeta();
        ImGui.spacing();
        layerPanel.render();
        ImGui.spacing();
        renderSelectedKeyframe();
    }

    // ---------- clip metadata ----------

    private void renderClipMeta() {
        ImGui.separatorText("Clip");
        AnimationClip clip = controller.state().clip();

        if (!nameActivePrev) clipNameBuf.set(clip.name());
        ImGui.inputText("Name", clipNameBuf);
        nameActivePrev = ImGui.isItemActive();
        if (ImGui.isItemDeactivatedAfterEdit()) {
            controller.setClipName(clipNameBuf.get());
        }
        AnimUI.tooltip("Clip name. SBE/SBO states bind clips by this name; blank names are ignored.");

        if (!fpsActivePrev) fpsBuf.set(clip.fps());
        ImGui.inputFloat("FPS", fpsBuf, 1f, 5f, "%.1f");
        fpsActivePrev = ImGui.isItemActive();
        if (ImGui.isItemDeactivatedAfterEdit()) {
            controller.setClipFps(fpsBuf.get());
        }

        if (!durationActivePrev) durationBuf.set(clip.duration());
        ImGui.inputFloat("Duration (s)", durationBuf, 0.1f, 1f, "%.3f");
        durationActivePrev = ImGui.isItemActive();
        if (ImGui.isItemDeactivatedAfterEdit()) {
            controller.setClipDuration(durationBuf.get());
        }

        int beyond = controller.keyframesBeyondDuration();
        if (beyond > 0) {
            ImGui.textColored(0.95f, 0.55f, 0.35f, 1f, beyond + " keyframe(s) beyond clip end");
            ImGui.sameLine();
            if (ImGui.smallButton("Trim")) {
                controller.trimKeyframesBeyondDuration();
            }
            AnimUI.tooltip("Delete every keyframe past the clip duration (one undo step). "
                    + "Until trimmed they are held as the end pose and still saved.");
        }

        if (clip.modelRef() != null && !clip.modelRef().isBlank()) {
            ImGui.textDisabled("model: " + clip.modelRef());
            AnimUI.tooltip("The model this clip was last saved against.");
        }
    }

    // ---------- selected keyframe ----------

    private void renderSelectedKeyframe() {
        ImGui.separatorText("Selected Keyframe");

        String partId = controller.state().selectedPartId();
        int kfIdx = controller.state().selectedKeyframeIndex();
        AnimationClip clip = controller.state().clip();
        Track track = partId != null ? clip.trackFor(partId) : null;

        if (track == null || kfIdx < 0 || kfIdx >= track.size()) {
            ImGui.textWrapped("Click a keyframe diamond on the timeline to edit it.");
            lastSnapshot = null;
            editBefore = null;
            return;
        }

        int selectionCount = controller.state().selection().size();
        if (selectionCount > 1) {
            ImGui.textDisabled("(" + selectionCount + " selected — pose fields edit the primary)");
        }

        Keyframe kf = track.get(kfIdx);
        // Re-snapshot on any external change, but not while our own live
        // preview is rewriting the keyframe reference every frame.
        if (kf != lastSnapshot && editBefore == null) {
            snapshotInto(kf);
            lastSnapshot = kf;
        }

        boolean changed = false;
        boolean anyActive = false;
        boolean anyDeactivated = false;
        if (ImGui.inputFloat("Time (s)", kfTimeBuf, 0.05f, 0.25f, "%.3f")) changed = true;
        anyActive |= ImGui.isItemActive();
        anyDeactivated |= ImGui.isItemDeactivatedAfterEdit();
        if (ImGui.inputFloat3("Position", kfPosArr)) changed = true;
        anyActive |= ImGui.isItemActive();
        anyDeactivated |= ImGui.isItemDeactivatedAfterEdit();
        if (ImGui.inputFloat3("Rotation", kfRotArr)) changed = true;
        anyActive |= ImGui.isItemActive();
        anyDeactivated |= ImGui.isItemDeactivatedAfterEdit();
        if (ImGui.inputFloat3("Scale", kfScaleArr)) changed = true;
        anyActive |= ImGui.isItemActive();
        anyDeactivated |= ImGui.isItemDeactivatedAfterEdit();

        renderEasingCombo(kf, selectionCount);

        if (changed) {
            if (editBefore == null) {
                editBefore = kf;
                editPartId = partId;
                editIndex = kfIdx;
            }
            Keyframe edited = bufferedKeyframe(kf.easing());
            if (anyActive) {
                // Live preview only: keep the same index so the pose tracks the
                // field, commit the time move once the field deactivates.
                controller.previewKeyframeEdit(partId, kfIdx, edited.withTime(editBefore.time()));
                lastSnapshot = edited.withTime(editBefore.time());
            } else {
                // +/- step buttons deactivate immediately: commit right away.
                commitEdit(edited);
            }
        } else if (anyDeactivated && editBefore != null) {
            commitEdit(bufferedKeyframe(kf.easing()));
        } else if (!anyActive && editBefore != null) {
            // Field lost focus without an "after edit" event (e.g. Escape): revert preview.
            controller.previewKeyframeEdit(editPartId, editIndex, editBefore);
            lastSnapshot = null;
            editBefore = null;
        }

        ImGui.spacing();
        if (ImGui.button(selectionCount > 1 ? "Delete Selected (" + selectionCount + ")" : "Delete Keyframe")) {
            if (selectionCount > 1) {
                controller.deleteSelectedKeyframes();
            } else {
                controller.deleteKeyframe(partId, kfIdx);
                controller.state().setSelectedKeyframeIndex(-1);
            }
        }
        AnimUI.tooltip("Remove the selected keyframe(s) (Delete).");
    }

    /** Put the pre-edit keyframe back silently, then record one real edit command. */
    private void commitEdit(Keyframe after) {
        if (editBefore == null) return;
        controller.previewKeyframeEdit(editPartId, editIndex, editBefore);
        controller.editKeyframe(editPartId, editIndex, after);
        // The edit may have retimed the key: re-point the primary selection.
        Track track = controller.state().clip().trackFor(editPartId);
        if (track != null) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i) == after || Math.abs(track.get(i).time() - after.time()) < 1e-4f) {
                    controller.state().setSelectedKeyframeIndex(i);
                    break;
                }
            }
        }
        lastSnapshot = null;
        editBefore = null;
        editIndex = -1;
    }

    private Keyframe bufferedKeyframe(Easing easing) {
        return new Keyframe(
                Math.max(0f, kfTimeBuf.get()),
                new Vector3f(kfPosArr[0], kfPosArr[1], kfPosArr[2]),
                new Vector3f(kfRotArr[0], kfRotArr[1], kfRotArr[2]),
                new Vector3f(kfScaleArr[0], kfScaleArr[1], kfScaleArr[2]),
                easing);
    }

    /**
     * Easing dropdown. With a multi-selection the change applies to every
     * selected keyframe as one undo step.
     */
    private void renderEasingCombo(Keyframe kf, int selectionCount) {
        Easing current = kf.easing();
        String label = selectionCount > 1 ? "Easing (all selected)" : "Easing";
        if (ImGui.beginCombo(label, current.name())) {
            for (Easing easing : Easing.values()) {
                if (ImGui.selectable(easing.name(), easing == current) && easing != current) {
                    controller.setSelectionEasing(easing);
                }
            }
            ImGui.endCombo();
        }
        AnimUI.tooltip("Interpolation curve leading into the next keyframe. STEP holds this pose "
                + "until the next keyframe snaps in.");
    }

    private void snapshotInto(Keyframe kf) {
        kfTimeBuf.set(kf.time());
        AnimUI.copyVec3(kf.position(), kfPosArr);
        AnimUI.copyVec3(kf.rotation(), kfRotArr);
        AnimUI.copyVec3(kf.scale(), kfScaleArr);
    }
}
