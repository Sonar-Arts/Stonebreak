package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;

/**
 * Play / pause / stop / loop / playhead-scrubber / undo-redo row.
 *
 * <p>The loop and playhead buffers mirror the clip's truth each frame; both
 * widgets are non-text inputs so per-frame sync from the clip is safe.
 */
public final class TransportPanel {

    private static final float TRANSPORT_BUTTON_WIDTH = 64f;
    private static final float STOP_BUTTON_WIDTH = 50f;
    private static final float PLAYHEAD_SLIDER_WIDTH = 260f;

    private final AnimationEditorController controller;
    private final ImBoolean loopBuf = new ImBoolean(true);
    private final ImFloat playheadBuf = new ImFloat(0f);

    public TransportPanel(AnimationEditorController controller) {
        this.controller = controller;
    }

    public void render() {
        AnimationClip clip = controller.state().clip();
        boolean playing = controller.state().playing();

        if (ImGui.button(playing ? "Pause" : "Play", TRANSPORT_BUTTON_WIDTH, 0)) {
            controller.state().setPlaying(!playing);
        }
        AnimUI.tooltip("Toggle playback (Space).");

        ImGui.sameLine();
        if (ImGui.button("Stop", STOP_BUTTON_WIDTH, 0)) {
            controller.state().setPlaying(false);
            controller.state().setPlayhead(0f);
            controller.applyCurrentPose();
        }
        AnimUI.tooltip("Stop and rewind to t=0.");

        ImGui.sameLine();
        loopBuf.set(clip.loop());
        if (ImGui.checkbox("Loop", loopBuf)) {
            controller.setClipLoop(loopBuf.get());
        }
        AnimUI.tooltip("Loop playback when the playhead reaches the clip end.");

        ImGui.sameLine();
        ImGui.setNextItemWidth(PLAYHEAD_SLIDER_WIDTH);
        playheadBuf.set(controller.state().playhead());
        if (ImGui.sliderFloat("##playhead", playheadBuf.getData(), 0f, clip.duration(), "%.3f s")) {
            controller.state().setPlayhead(playheadBuf.get());
            controller.applyCurrentPose();
        }

        ImGui.sameLine();
        int frame = Math.round(controller.state().playhead() * clip.fps());
        int totalFrames = Math.round(clip.duration() * clip.fps());
        ImGui.textDisabled(String.format("%.3f / %.3f s   (frame %d / %d)",
                controller.state().playhead(), clip.duration(), frame, totalFrames));

        renderUndoRedo();
    }

    private void renderUndoRedo() {
        boolean canUndo = controller.history().canUndo();
        ImGui.sameLine();
        AnimUI.beginDisabled(!canUndo);
        if (ImGui.button("Undo")) controller.undo();
        AnimUI.endDisabled(!canUndo);
        AnimUI.tooltip("Undo last change (Ctrl+Z).");

        boolean canRedo = controller.history().canRedo();
        ImGui.sameLine();
        AnimUI.beginDisabled(!canRedo);
        if (ImGui.button("Redo")) controller.redo();
        AnimUI.endDisabled(!canRedo);
        AnimUI.tooltip("Redo (Ctrl+Y).");
    }
}
