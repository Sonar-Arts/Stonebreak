package com.openmason.main.systems.menus.animationEditor;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImString;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Single-window Animation Editor UI. Lays out a transport bar (top), a part
 * list (left), a timeline (center-bottom), and a keyframe inspector (right).
 *
 * <p>Mirrors {@code TextureCreatorImGui} in spirit: it is the
 * front door — owns the {@link AnimationEditorController}, runs per-frame UI,
 * delegates all mutations through the controller's command history.
 */
public final class AnimationEditorImGui {

    private static final Logger logger = LoggerFactory.getLogger(AnimationEditorImGui.class);
    private static final String WINDOW_TITLE = "Animation Editor";

    private final AnimationEditorController controller = new AnimationEditorController();
    private final ImBoolean visible = new ImBoolean(false);

    // Inspector input buffers (ImString/ImFloat are required by imgui-java).
    private final ImString clipNameBuf = new ImString(64);
    private final ImFloat fpsBuf = new ImFloat(30f);
    private final ImFloat durationBuf = new ImFloat(1f);
    private final ImBoolean loopBuf = new ImBoolean(true);

    private FileDialogService fileDialogService;

    public AnimationEditorImGui() {
        clipNameBuf.set(controller.state().clip().name());
        fpsBuf.set(controller.state().clip().fps());
        durationBuf.set(controller.state().clip().duration());
        loopBuf.set(controller.state().clip().loop());
    }

    public AnimationEditorController getController() {
        return controller;
    }

    /**
     * Inject the shared {@link FileDialogService} so save/load buttons can
     * invoke native file pickers instead of relying on text input.
     */
    public void setFileDialogService(FileDialogService service) {
        this.fileDialogService = service;
    }

    public void show() { visible.set(true); }
    public void hide() { visible.set(false); controller.endSession(); }
    public boolean isVisible() { return visible.get(); }

    /**
     * Bind to the active viewport's part manager. Should be called when the
     * editor is opened or whenever the loaded model changes.
     */
    public void bindViewport(ModelPartManager partManager) {
        controller.bindViewport(partManager);
    }

    /**
     * Per-frame entry point. Caller passes deltaTime so playback can advance.
     */
    public void render(float deltaTime) {
        if (!visible.get()) return;

        controller.tickPlayback(deltaTime);

        ImGui.setNextWindowSize(1100, 600, imgui.flag.ImGuiCond.FirstUseEver);
        if (!ImGui.begin(WINDOW_TITLE, visible, ImGuiWindowFlags.NoCollapse)) {
            ImGui.end();
            // Ensure the rest pose returns when the user closes the window.
            if (!visible.get()) controller.endSession();
            return;
        }

        // Open the session lazily so the model isn't disturbed until the editor is on screen.
        controller.beginSession();

        renderMenuBar();
        handleShortcuts();
        renderTransportBar();
        ImGui.separator();

        // Three-column layout: parts | timeline | inspector
        if (ImGui.beginTable("##animEditorLayout", 3,
                imgui.flag.ImGuiTableFlags.Resizable | imgui.flag.ImGuiTableFlags.BordersInnerV)) {
            ImGui.tableSetupColumn("Parts", imgui.flag.ImGuiTableColumnFlags.WidthStretch, 0.18f);
            ImGui.tableSetupColumn("Timeline", imgui.flag.ImGuiTableColumnFlags.WidthStretch, 0.55f);
            ImGui.tableSetupColumn("Inspector", imgui.flag.ImGuiTableColumnFlags.WidthStretch, 0.27f);

            ImGui.tableNextRow();
            ImGui.tableNextColumn();
            renderPartList();
            ImGui.tableNextColumn();
            renderTimeline();
            ImGui.tableNextColumn();
            renderInspector();

            ImGui.endTable();
        }

        ImGui.end();
        if (!visible.get()) controller.endSession();
    }

    // =================================================================
    // Top bar
    // =================================================================

    private void renderMenuBar() {
        // Inline file controls — native file dialogs are routed through the shared
        // FileDialogService so this matches the rest of the tool's open/save UX.
        if (ImGui.button("New")) {
            controller.newClip();
            syncBuffersFromClip();
        }
        ImGui.sameLine();
        if (ImGui.button("Load...")) {
            if (fileDialogService != null) {
                fileDialogService.showOpenOMADialog(path -> {
                    if (controller.load(path)) {
                        syncBuffersFromClip();
                    }
                });
            } else {
                logger.warn("File dialog service not available — cannot open .oma");
            }
        }
        ImGui.sameLine();
        if (ImGui.button("Save As...")) {
            promptSaveAs();
        }
        ImGui.sameLine();
        if (ImGui.button("Save")) {
            if (controller.state().filePath() != null) {
                controller.save();
            } else {
                promptSaveAs();
            }
        }
        ImGui.sameLine();
        ImGui.textDisabled(controller.state().dirty() ? "[modified]" : "[saved]");
        if (controller.state().filePath() != null) {
            ImGui.sameLine();
            ImGui.textDisabled("- " + controller.state().filePath());
        }
    }

    private void promptSaveAs() {
        if (fileDialogService == null) {
            logger.warn("File dialog service not available — cannot save .oma");
            return;
        }
        fileDialogService.showSaveOMADialog(controller::saveAs);
    }

    private void renderTransportBar() {
        AnimationClip clip = controller.state().clip();

        // Play / Pause / Stop
        boolean playing = controller.state().playing();
        if (ImGui.button(playing ? "Pause" : "Play", 70, 0)) {
            controller.state().setPlaying(!playing);
        }
        ImGui.sameLine();
        if (ImGui.button("Stop", 50, 0)) {
            controller.state().setPlaying(false);
            controller.state().setPlayhead(0f);
            controller.applyCurrentPose();
        }
        ImGui.sameLine();
        if (ImGui.checkbox("Loop", loopBuf)) {
            clip.setLoop(loopBuf.get());
            controller.state().markDirty();
        }
        ImGui.sameLine();
        ImGui.textDisabled(String.format("t = %.3f / %.3f s", controller.state().playhead(), clip.duration()));

        // Playhead scrubber.
        ImGui.sameLine();
        ImGui.setNextItemWidth(220);
        ImFloat ph = new ImFloat(controller.state().playhead());
        if (ImGui.sliderFloat("##playhead", ph.getData(), 0f, clip.duration(), "%.3f")) {
            controller.state().setPlayhead(ph.get());
            controller.applyCurrentPose();
        }
    }

    // =================================================================
    // Parts column
    // =================================================================

    private void renderPartList() {
        ImGui.textDisabled("Parts");
        ImGui.separator();

        ModelPartManager pm = controller.partManager();
        if (pm == null) {
            ImGui.textDisabled("No model bound.");
            return;
        }
        List<ModelPartDescriptor> parts = pm.getAllParts();
        if (parts.isEmpty()) {
            ImGui.textDisabled("Model has no parts.");
            return;
        }

        String selected = controller.state().selectedPartId();
        for (ModelPartDescriptor part : parts) {
            boolean isSel = part.id().equals(selected);
            int kfCount = controller.state().clip().trackFor(part.id()) != null
                    ? controller.state().clip().trackFor(part.id()).size() : 0;
            String label = String.format("%s  (%d)", part.name(), kfCount);
            if (ImGui.selectable(label, isSel)) {
                controller.state().setSelectedPartId(part.id());
            }
        }
    }

    // =================================================================
    // Timeline column
    // =================================================================

    private void renderTimeline() {
        ImGui.textDisabled("Timeline");
        ImGui.separator();

        AnimationClip clip = controller.state().clip();
        ModelPartManager pm = controller.partManager();
        if (pm == null) {
            ImGui.textDisabled("No model bound.");
            return;
        }

        // Insert keyframe at playhead button (operates on selected part).
        String selected = controller.state().selectedPartId();
        if (ImGui.button("+ Keyframe @ Playhead")) {
            if (selected != null) {
                controller.insertKeyframeAtPlayhead(selected);
            }
        }
        ImGui.sameLine();
        ImGui.textDisabled(selected != null
                ? "(target: " + shortName(pm, selected) + ")"
                : "(select a part first)");

        ImGui.separator();

        // One row per part with a track. Each row shows a horizontal bar
        // with keyframe diamonds.
        List<ModelPartDescriptor> parts = pm.getAllParts();
        float rowHeight = 24f;
        for (ModelPartDescriptor part : parts) {
            renderTimelineRow(part, clip, rowHeight);
        }
    }

    private void renderTimelineRow(ModelPartDescriptor part, AnimationClip clip, float rowHeight) {
        ImGui.pushID(part.id());

        // Row label (left).
        ImGui.alignTextToFramePadding();
        ImGui.textUnformatted(part.name());
        ImGui.sameLine(120);

        // Bar: full remaining width, fixed height. We draw using the foreground
        // draw-list so keyframe diamonds sit on top of the row background.
        ImVec2 cursor = ImGui.getCursorScreenPos();
        float barWidth = Math.max(60f, ImGui.getContentRegionAvailX() - 20f);
        float x0 = cursor.x;
        float y0 = cursor.y + 2f;
        float x1 = x0 + barWidth;
        float y1 = y0 + rowHeight - 4f;

        ImDrawList dl = ImGui.getWindowDrawList();
        int bgCol = ImGui.colorConvertFloat4ToU32(0.18f, 0.18f, 0.20f, 1f);
        int axCol = ImGui.colorConvertFloat4ToU32(0.30f, 0.30f, 0.34f, 1f);
        dl.addRectFilled(x0, y0, x1, y1, bgCol, 3f);

        // Mid-line for visual reference.
        float yMid = (y0 + y1) * 0.5f;
        dl.addLine(x0 + 2, yMid, x1 - 2, yMid, axCol, 1f);

        // Playhead vertical line.
        float duration = Math.max(0.0001f, clip.duration());
        float playheadX = x0 + (controller.state().playhead() / duration) * (x1 - x0);
        int phCol = ImGui.colorConvertFloat4ToU32(1.0f, 0.6f, 0.2f, 1.0f);
        dl.addLine(playheadX, y0, playheadX, y1, phCol, 2f);

        // Diamond markers per keyframe.
        Track track = clip.trackFor(part.id());
        if (track != null) {
            int diamondCol = ImGui.colorConvertFloat4ToU32(0.30f, 0.85f, 1.0f, 1.0f);
            int selDiamondCol = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.30f, 1.0f);
            String selPart = controller.state().selectedPartId();
            int selKf = controller.state().selectedKeyframeIndex();

            for (int i = 0; i < track.size(); i++) {
                Keyframe kf = track.get(i);
                float kx = x0 + (kf.time() / duration) * (x1 - x0);
                int color = (part.id().equals(selPart) && i == selKf) ? selDiamondCol : diamondCol;
                drawDiamond(dl, kx, yMid, 5.5f, color);
            }
        }

        // Invisible button covers the bar area to capture clicks.
        ImGui.invisibleButton("##bar_" + part.id(), barWidth, rowHeight - 4f);
        if (ImGui.isItemClicked()) {
            float mouseX = ImGui.getIO().getMousePosX();
            float u = (mouseX - x0) / Math.max(1f, (x1 - x0));
            u = Math.max(0f, Math.min(1f, u));
            float clickedTime = u * duration;

            // Click selects the part; double-click on a diamond region selects/inserts a keyframe.
            controller.state().setSelectedPartId(part.id());

            // Find a keyframe near the click (within 6px).
            int hit = findKeyframeNearPixel(track, x0, x1, duration, mouseX, 6f);
            if (hit >= 0) {
                controller.state().setSelectedKeyframeIndex(hit);
                controller.state().setPlayhead(track.get(hit).time());
                controller.applyCurrentPose();
            } else {
                controller.state().setPlayhead(clickedTime);
                controller.applyCurrentPose();
            }
        }

        ImGui.popID();
    }

    private static int findKeyframeNearPixel(Track track, float x0, float x1, float duration,
                                             float mouseX, float pixelTolerance) {
        if (track == null) return -1;
        for (int i = 0; i < track.size(); i++) {
            float kx = x0 + (track.get(i).time() / duration) * (x1 - x0);
            if (Math.abs(kx - mouseX) <= pixelTolerance) return i;
        }
        return -1;
    }

    private static void drawDiamond(ImDrawList dl, float cx, float cy, float r, int color) {
        dl.addQuadFilled(cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy, color);
    }

    // =================================================================
    // Inspector column
    // =================================================================

    private void renderInspector() {
        ImGui.textDisabled("Clip");
        ImGui.separator();

        AnimationClip clip = controller.state().clip();
        if (ImGui.inputText("Name", clipNameBuf)) {
            clip.setName(clipNameBuf.get());
            controller.state().markDirty();
        }
        if (ImGui.inputFloat("FPS", fpsBuf)) {
            clip.setFps(fpsBuf.get());
            controller.state().markDirty();
        }
        if (ImGui.inputFloat("Duration (s)", durationBuf)) {
            clip.setDuration(durationBuf.get());
            controller.state().markDirty();
        }

        ImGui.spacing();
        ImGui.textDisabled("Selected Keyframe");
        ImGui.separator();

        String partId = controller.state().selectedPartId();
        int kfIdx = controller.state().selectedKeyframeIndex();
        Track track = partId != null ? clip.trackFor(partId) : null;
        if (track == null || kfIdx < 0 || kfIdx >= track.size()) {
            ImGui.textWrapped("Click a keyframe diamond on the timeline to edit it.");
            return;
        }

        Keyframe kf = track.get(kfIdx);
        ImFloat timeBuf = new ImFloat(kf.time());
        float[] posArr = new float[]{kf.position().x, kf.position().y, kf.position().z};
        float[] rotArr = new float[]{kf.rotation().x, kf.rotation().y, kf.rotation().z};
        float[] scaleArr = new float[]{kf.scale().x, kf.scale().y, kf.scale().z};

        boolean changed = false;
        if (ImGui.inputFloat("Time (s)", timeBuf)) changed = true;
        if (ImGui.inputFloat3("Position", posArr)) changed = true;
        if (ImGui.inputFloat3("Rotation", rotArr)) changed = true;
        if (ImGui.inputFloat3("Scale", scaleArr)) changed = true;

        if (changed) {
            Keyframe edited = new Keyframe(
                    timeBuf.get(),
                    new Vector3f(posArr[0], posArr[1], posArr[2]),
                    new Vector3f(rotArr[0], rotArr[1], rotArr[2]),
                    new Vector3f(scaleArr[0], scaleArr[1], scaleArr[2]),
                    Easing.LINEAR
            );
            controller.editKeyframe(partId, kfIdx, edited);
        }

        ImGui.spacing();
        if (ImGui.button("Delete Keyframe")) {
            controller.deleteKeyframe(partId, kfIdx);
            controller.state().setSelectedKeyframeIndex(-1);
        }
    }

    // =================================================================
    // Helpers
    // =================================================================

    private void handleShortcuts() {
        if (!ImGui.isWindowFocused() && !ImGui.isWindowHovered()) return;

        boolean ctrl = ImGui.getIO().getKeyCtrl();
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.Z)) {
            controller.undo();
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.Y)) {
            controller.redo();
        }
        if (ImGui.isKeyPressed(ImGuiKey.Space)) {
            controller.state().setPlaying(!controller.state().playing());
        }
        if (ImGui.isKeyPressed(ImGuiKey.K)) {
            String partId = controller.state().selectedPartId();
            if (partId != null) controller.insertKeyframeAtPlayhead(partId);
        }
        if (ImGui.isKeyPressed(ImGuiKey.Delete)) {
            String partId = controller.state().selectedPartId();
            int idx = controller.state().selectedKeyframeIndex();
            if (partId != null && idx >= 0) {
                controller.deleteKeyframe(partId, idx);
                controller.state().setSelectedKeyframeIndex(-1);
            }
        }
    }

    private void syncBuffersFromClip() {
        AnimationClip c = controller.state().clip();
        clipNameBuf.set(c.name());
        fpsBuf.set(c.fps());
        durationBuf.set(c.duration());
        loopBuf.set(c.loop());
    }

    private static String shortName(ModelPartManager pm, String partId) {
        return pm.getPartById(partId).map(ModelPartDescriptor::name).orElse(partId);
    }
}
