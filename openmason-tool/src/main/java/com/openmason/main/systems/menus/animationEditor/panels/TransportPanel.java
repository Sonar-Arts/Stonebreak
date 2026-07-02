package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.mortar.core.MortarFrameResult;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.parts.MortarButton;
import com.openmason.main.systems.mortar.parts.MortarIconButton;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;

/**
 * Transport row: step/play/stop/undo/redo buttons (Mortar-painted when
 * available, ImGui fallback otherwise) followed by speed, loop, snap, and the
 * playhead scrubber — which stay ImGui widgets since Mortar has no
 * drag/combo channel.
 *
 * <p>The loop and playhead buffers mirror the clip's truth each frame; both
 * widgets are non-text inputs so per-frame sync from the clip is safe.
 */
public final class TransportPanel implements AutoCloseable {

    private static final float TRANSPORT_BUTTON_WIDTH = 64f;
    private static final float STOP_BUTTON_WIDTH = 50f;
    private static final float STEP_BUTTON_WIDTH = 28f;
    private static final float UNDO_BUTTON_WIDTH = 52f;
    private static final float BUTTON_HEIGHT = 26f;
    private static final float BUTTON_GAP = 6f;
    private static final float ROW_HEIGHT = BUTTON_HEIGHT + 2f;
    private static final float PLAYHEAD_SLIDER_WIDTH = 220f;
    private static final float SPEED_COMBO_WIDTH = 70f;

    private static final String[] SPEED_LABELS = {"0.25x", "0.5x", "1x", "2x"};
    private static final float[] SPEED_VALUES = {0.25f, 0.5f, 1f, 2f};

    private final AnimationEditorController controller;
    private final MortarRegion region = new MortarRegion();
    private final ImBoolean loopBuf = new ImBoolean(true);
    private final ImBoolean snapBuf = new ImBoolean(true);
    private final ImFloat playheadBuf = new ImFloat(0f);

    public TransportPanel(AnimationEditorController controller) {
        this.controller = controller;
    }

    public void render() {
        AnimationClip clip = controller.state().clip();
        boolean playing = controller.state().playing();

        if (region.isAvailable()) {
            renderMortarButtons(playing);
        } else {
            renderImGuiButtons(playing);
        }

        ImGui.sameLine();
        renderSharedControls(clip);
    }

    private void renderMortarButtons(boolean playing) {
        record Btn(String id, float width, MortarButton.Variant variant) {}
        Btn[] buttons = {
                new Btn("back", STEP_BUTTON_WIDTH, null),          // icon button
                new Btn("play", TRANSPORT_BUTTON_WIDTH, playing ? MortarButton.Variant.PRIMARY
                                                                : MortarButton.Variant.SECONDARY),
                new Btn("fwd", STEP_BUTTON_WIDTH, null),           // icon button
                new Btn("stop", STOP_BUTTON_WIDTH, MortarButton.Variant.SECONDARY),
                new Btn("undo", UNDO_BUTTON_WIDTH, MortarButton.Variant.SECONDARY),
                new Btn("redo", UNDO_BUTTON_WIDTH, MortarButton.Variant.SECONDARY),
        };

        float totalWidth = BUTTON_GAP * (buttons.length - 1);
        for (Btn b : buttons) totalWidth += b.width();

        region.begin(totalWidth, ROW_HEIGHT);
        float x = 0f;
        for (Btn b : buttons) {
            region.add(b.id(), x, 1f, b.width(), BUTTON_HEIGHT, switch (b.id()) {
                case "back" -> new MortarIconButton("<");
                case "fwd" -> new MortarIconButton(">");
                case "play" -> new MortarButton(playing ? "Pause" : "Play", b.variant());
                case "stop" -> new MortarButton("Stop", b.variant());
                case "undo" -> new MortarButton("Undo", b.variant());
                default -> new MortarButton("Redo", b.variant());
            });
            x += b.width() + BUTTON_GAP;
        }
        MortarFrameResult input = region.render();
        region.update(ImGui.getIO().getDeltaTime());

        if (input.isClicked("back")) controller.stepFrames(-1);
        if (input.isClicked("play")) controller.state().setPlaying(!playing);
        if (input.isClicked("fwd")) controller.stepFrames(1);
        if (input.isClicked("stop")) {
            controller.state().setPlaying(false);
            controller.state().setPlayhead(0f);
            controller.applyCurrentPose();
        }
        if (input.isClicked("undo")) controller.undo();
        if (input.isClicked("redo")) controller.redo();
    }

    private void renderImGuiButtons(boolean playing) {
        if (ImGui.button("<", STEP_BUTTON_WIDTH, 0)) {
            controller.stepFrames(-1);
        }
        AnimUI.tooltip("Step one frame back (Left arrow).");

        ImGui.sameLine();
        if (ImGui.button(playing ? "Pause" : "Play", TRANSPORT_BUTTON_WIDTH, 0)) {
            controller.state().setPlaying(!playing);
        }
        AnimUI.tooltip("Toggle playback (Space).");

        ImGui.sameLine();
        if (ImGui.button(">", STEP_BUTTON_WIDTH, 0)) {
            controller.stepFrames(1);
        }
        AnimUI.tooltip("Step one frame forward (Right arrow).");

        ImGui.sameLine();
        if (ImGui.button("Stop", STOP_BUTTON_WIDTH, 0)) {
            controller.state().setPlaying(false);
            controller.state().setPlayhead(0f);
            controller.applyCurrentPose();
        }
        AnimUI.tooltip("Stop and rewind to t=0.");

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

    /** Speed / loop / snap / scrubber / frame readout — always ImGui widgets. */
    private void renderSharedControls(AnimationClip clip) {
        ImGui.setNextItemWidth(SPEED_COMBO_WIDTH);
        int speedIdx = speedIndex(controller.state().playbackSpeed());
        if (ImGui.beginCombo("##speed", SPEED_LABELS[speedIdx])) {
            for (int i = 0; i < SPEED_LABELS.length; i++) {
                if (ImGui.selectable(SPEED_LABELS[i], i == speedIdx)) {
                    controller.state().setPlaybackSpeed(SPEED_VALUES[i]);
                }
            }
            ImGui.endCombo();
        }
        AnimUI.tooltip("Playback speed.");

        ImGui.sameLine();
        loopBuf.set(clip.loop());
        if (ImGui.checkbox("Loop", loopBuf)) {
            controller.setClipLoop(loopBuf.get());
        }
        AnimUI.tooltip("Loop playback when the playhead reaches the clip end.");

        ImGui.sameLine();
        snapBuf.set(controller.state().snapToFrames());
        if (ImGui.checkbox("Snap", snapBuf)) {
            controller.state().setSnapToFrames(snapBuf.get());
        }
        AnimUI.tooltip("Snap dragged keyframes to the frame grid (hold Alt to bypass).");

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
    }

    private static int speedIndex(float speed) {
        for (int i = 0; i < SPEED_VALUES.length; i++) {
            if (Math.abs(SPEED_VALUES[i] - speed) < 1e-3f) return i;
        }
        return 2; // 1x
    }

    @Override
    public void close() {
        region.close();
    }
}
