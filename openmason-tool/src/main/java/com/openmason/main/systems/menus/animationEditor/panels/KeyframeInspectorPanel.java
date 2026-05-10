package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
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

    // Active-last-frame flags so we can decide whether to overwrite the buffer
    // *before* re-rendering the widget on the current frame.
    private boolean nameActivePrev, fpsActivePrev, durationActivePrev;

    public KeyframeInspectorPanel(AnimationEditorController controller) {
        this.controller = controller;
    }

    public void render() {
        renderClipMeta();
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
            return;
        }

        Keyframe kf = track.get(kfIdx);
        if (kf != lastSnapshot) {
            snapshotInto(kf);
            lastSnapshot = kf;
        }

        boolean changed = false;
        if (ImGui.inputFloat("Time (s)", kfTimeBuf, 0.05f, 0.25f, "%.3f")) changed = true;
        if (ImGui.inputFloat3("Position", kfPosArr)) changed = true;
        if (ImGui.inputFloat3("Rotation", kfRotArr)) changed = true;
        if (ImGui.inputFloat3("Scale", kfScaleArr)) changed = true;

        ImGui.textDisabled("Easing: " + kf.easing().name());

        if (changed) {
            controller.editKeyframe(partId, kfIdx, new Keyframe(
                    kfTimeBuf.get(),
                    new Vector3f(kfPosArr[0], kfPosArr[1], kfPosArr[2]),
                    new Vector3f(kfRotArr[0], kfRotArr[1], kfRotArr[2]),
                    new Vector3f(kfScaleArr[0], kfScaleArr[1], kfScaleArr[2]),
                    kf.easing()));
        }

        ImGui.spacing();
        if (ImGui.button("Delete Keyframe")) {
            controller.deleteKeyframe(partId, kfIdx);
            controller.state().setSelectedKeyframeIndex(-1);
        }
        AnimUI.tooltip("Remove this keyframe (Delete).");
    }

    private void snapshotInto(Keyframe kf) {
        kfTimeBuf.set(kf.time());
        AnimUI.copyVec3(kf.position(), kfPosArr);
        AnimUI.copyVec3(kf.rotation(), kfRotArr);
        AnimUI.copyVec3(kf.scale(), kfScaleArr);
    }
}
