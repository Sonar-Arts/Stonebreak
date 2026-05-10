package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;

import java.util.List;

/**
 * Multi-row timeline. One row per part shows a horizontal track with the
 * playhead and keyframe diamonds. Clicking a row selects the part and either
 * snaps the playhead to a clicked keyframe or scrubs to the clicked time.
 */
public final class TimelinePanel {

    private static final float LABEL_WIDTH = 140f;
    private static final float ROW_HEIGHT = 24f;
    private static final float BAR_PADDING = 12f;
    private static final float DIAMOND_RADIUS = 5.5f;
    private static final float HIT_PIXELS = 6f;

    private static final int COL_TRACK_BG = ImGui.colorConvertFloat4ToU32(0.18f, 0.18f, 0.20f, 1f);
    private static final int COL_TRACK_AXIS = ImGui.colorConvertFloat4ToU32(0.30f, 0.30f, 0.34f, 1f);
    private static final int COL_PLAYHEAD = ImGui.colorConvertFloat4ToU32(1.00f, 0.60f, 0.20f, 1f);
    private static final int COL_KEYFRAME = ImGui.colorConvertFloat4ToU32(0.30f, 0.85f, 1.00f, 1f);
    private static final int COL_KEYFRAME_SELECTED = ImGui.colorConvertFloat4ToU32(1.00f, 0.85f, 0.30f, 1f);

    private final AnimationEditorController controller;

    public TimelinePanel(AnimationEditorController controller) {
        this.controller = controller;
    }

    public void render() {
        ImGui.separatorText("Timeline");

        ModelPartManager pm = controller.partManager();
        if (pm == null) {
            ImGui.textDisabled("No model bound.");
            return;
        }

        renderInsertButton(pm);
        ImGui.separator();

        AnimationClip clip = controller.state().clip();
        for (ModelPartDescriptor part : pm.getAllParts()) {
            renderRow(part, clip);
        }
    }

    private void renderInsertButton(ModelPartManager pm) {
        String selected = controller.state().selectedPartId();
        boolean canInsert = selected != null;
        AnimUI.beginDisabled(!canInsert);
        if (ImGui.button("+ Keyframe @ Playhead")) {
            controller.insertKeyframeAtPlayhead(selected);
        }
        AnimUI.endDisabled(!canInsert);
        AnimUI.tooltip(canInsert
                ? "Insert a keyframe on the selected part at the current playhead (K)."
                : "Select a part in the left panel first.");

        ImGui.sameLine();
        ImGui.textDisabled(canInsert
                ? "target: " + pm.getPartById(selected).map(ModelPartDescriptor::name).orElse(selected)
                : "(no part selected)");
    }

    private void renderRow(ModelPartDescriptor part, AnimationClip clip) {
        ImGui.pushID(part.id());

        ImGui.alignTextToFramePadding();
        ImGui.textUnformatted(part.name());
        ImGui.sameLine(LABEL_WIDTH);

        ImVec2 cursor = ImGui.getCursorScreenPos();
        float barWidth = Math.max(60f, ImGui.getContentRegionAvailX() - BAR_PADDING);
        float x0 = cursor.x;
        float y0 = cursor.y + 2f;
        float x1 = x0 + barWidth;
        float y1 = y0 + ROW_HEIGHT - 4f;

        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(x0, y0, x1, y1, COL_TRACK_BG, 3f);

        float yMid = (y0 + y1) * 0.5f;
        dl.addLine(x0 + 2, yMid, x1 - 2, yMid, COL_TRACK_AXIS, 1f);

        float duration = Math.max(0.0001f, clip.duration());
        float playheadX = x0 + (controller.state().playhead() / duration) * (x1 - x0);
        dl.addLine(playheadX, y0, playheadX, y1, COL_PLAYHEAD, 2f);

        Track track = clip.trackFor(part.id());
        if (track != null) {
            String selPart = controller.state().selectedPartId();
            int selKf = controller.state().selectedKeyframeIndex();
            for (int i = 0; i < track.size(); i++) {
                Keyframe kf = track.get(i);
                float kx = x0 + (kf.time() / duration) * (x1 - x0);
                int color = (part.id().equals(selPart) && i == selKf)
                        ? COL_KEYFRAME_SELECTED : COL_KEYFRAME;
                drawDiamond(dl, kx, yMid, DIAMOND_RADIUS, color);
            }
        }

        ImGui.invisibleButton("##bar_" + part.id(), barWidth, ROW_HEIGHT - 4f);
        if (ImGui.isItemClicked()) {
            handleClick(part.id(), track, x0, x1, duration);
        }

        ImGui.popID();
    }

    private void handleClick(String partId, Track track, float x0, float x1, float duration) {
        float mouseX = ImGui.getIO().getMousePosX();
        float u = clamp01((mouseX - x0) / Math.max(1f, (x1 - x0)));
        float clickedTime = u * duration;

        controller.state().setSelectedPartId(partId);

        int hit = findKeyframeNearPixel(track, x0, x1, duration, mouseX);
        if (hit >= 0) {
            controller.state().setSelectedKeyframeIndex(hit);
            controller.state().setPlayhead(track.get(hit).time());
        } else {
            controller.state().setPlayhead(clickedTime);
        }
        controller.applyCurrentPose();
    }

    private static int findKeyframeNearPixel(Track track, float x0, float x1, float duration, float mouseX) {
        if (track == null) return -1;
        for (int i = 0; i < track.size(); i++) {
            float kx = x0 + (track.get(i).time() / duration) * (x1 - x0);
            if (Math.abs(kx - mouseX) <= HIT_PIXELS) return i;
        }
        return -1;
    }

    private static void drawDiamond(ImDrawList dl, float cx, float cy, float r, int color) {
        dl.addQuadFilled(cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy, color);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
