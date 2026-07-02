package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import com.openmason.main.systems.menus.animationEditor.state.KeyframeSelection;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-row timeline with a time ruler, zoom/scroll, keyframe dragging, and
 * box selection.
 *
 * <p>All rows are drawn with the window draw list and covered by a single
 * invisible button so drags and marquees can cross rows. Interactions:
 * <ul>
 *   <li>Click a diamond — select it (Ctrl toggles membership)</li>
 *   <li>Drag a diamond — move the whole selection; snaps to frames unless Alt</li>
 *   <li>Drag on empty track area — box select (Ctrl adds)</li>
 *   <li>Click/drag the ruler — scrub the playhead</li>
 *   <li>Ctrl+wheel — zoom at mouse; Shift+wheel — pan; plain wheel — vertical scroll</li>
 * </ul>
 */
public final class TimelinePanel {

    private static final float LABEL_WIDTH = 140f;
    private static final float ROW_HEIGHT = 24f;
    private static final float RULER_HEIGHT = 20f;
    private static final float BAR_PADDING = 12f;
    private static final float DIAMOND_RADIUS = 5.5f;
    private static final float HIT_PIXELS = 6f;
    private static final float DRAG_THRESHOLD = 4f;

    private enum DragMode { NONE, PENDING_KEYS, DRAG_KEYS, PENDING_BOX, BOX_SELECT, SCRUB }

    /** One keyframe being dragged: its track, original time, and pose. */
    private record DragKey(String partId, float origTime, Keyframe keyframe) {}

    private final AnimationEditorController controller;

    // ---- interaction state (persists across frames during a gesture) ----
    private DragMode dragMode = DragMode.NONE;
    private float pressX, pressY;
    private String pressedPartId;
    private int pressedKfIndex = -1;
    private final List<DragKey> dragSet = new ArrayList<>();
    private final Map<String, List<Keyframe>> dragBeforeByPart = new LinkedHashMap<>();
    private float dragDeltaTime;
    private String contextPartId;   // row under the last right-click

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
        List<ModelPartDescriptor> parts = new ArrayList<>(pm.getAllParts());

        ImGui.beginChild("##timelineBody");

        ImVec2 origin = ImGui.getCursorScreenPos();
        float availWidth = Math.max(LABEL_WIDTH + 60f + BAR_PADDING, ImGui.getContentRegionAvailX());
        float barX0 = origin.x + LABEL_WIDTH;
        float barX1 = origin.x + availWidth - BAR_PADDING;
        float totalHeight = RULER_HEIGHT + parts.size() * ROW_HEIGHT;

        TimelineLayout layout = new TimelineLayout(
                barX0, barX1 - barX0, clip.duration(),
                controller.state().timelineZoom(), controller.state().timelineScrollSec());

        ImDrawList dl = ImGui.getWindowDrawList();
        drawRuler(dl, layout, clip, origin.y, barX0, barX1);
        for (int row = 0; row < parts.size(); row++) {
            drawRow(dl, layout, clip, parts.get(row), origin.x, rowY0(origin.y, row), barX0, barX1);
        }
        drawPlayhead(dl, layout, origin.y, totalHeight);

        ImGui.invisibleButton("##timelineSurface", availWidth, Math.max(1f, totalHeight));
        handleInput(layout, clip, parts, origin, barX0, barX1);
        renderContextMenu();

        // Overlays draw after input so they reflect this frame's gesture state.
        drawDragGhosts(dl, layout, clip, parts, origin.y);
        drawMarquee(dl);

        ImGui.endChild();
    }

    // ====================== drawing ======================

    private static float rowY0(float originY, int row) {
        return originY + RULER_HEIGHT + row * ROW_HEIGHT;
    }

    private void drawRuler(ImDrawList dl, TimelineLayout layout, AnimationClip clip,
                           float y0, float barX0, float barX1) {
        float y1 = y0 + RULER_HEIGHT;
        dl.addRectFilled(barX0, y0, barX1, y1, AnimEditorTheme.rulerBg());

        float pps = (barX1 - barX0) / Math.max(1e-4f, layout.visibleLength());
        float step = pickTickStep(pps);
        int tick = AnimEditorTheme.rulerTick();
        int text = AnimEditorTheme.rulerText();

        float first = (float) Math.floor(layout.visibleStart() / step) * step;
        for (float t = first; t <= layout.visibleEnd() + 1e-5f; t += step) {
            if (t < -1e-5f || t > clip.duration() + 1e-5f) continue;
            float x = layout.timeToX(t);
            if (x < barX0 - 1f || x > barX1 + 1f) continue;
            dl.addLine(x, y0 + RULER_HEIGHT * 0.45f, x, y1, tick, 1f);
            dl.addText(x + 3f, y0 + 2f, text, formatTime(t, step));
            // minor ticks
            float minor = step / 5f;
            for (int i = 1; i < 5; i++) {
                float mt = t + i * minor;
                if (mt > layout.visibleEnd() || mt > clip.duration()) break;
                float mx = layout.timeToX(mt);
                dl.addLine(mx, y0 + RULER_HEIGHT * 0.75f, mx, y1, tick, 1f);
            }
        }
        dl.addLine(barX0, y1, barX1, y1, tick, 1f);
    }

    /** Tick label spacing: the smallest "nice" step that keeps labels >= ~60px apart. */
    private static float pickTickStep(float pixelsPerSecond) {
        float[] steps = {0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 30f, 60f};
        for (float s : steps) {
            if (s * pixelsPerSecond >= 60f) return s;
        }
        return steps[steps.length - 1];
    }

    private static String formatTime(float t, float step) {
        return step < 1f ? String.format("%.2fs", t) : String.format("%.0fs", t);
    }

    private void drawRow(ImDrawList dl, TimelineLayout layout, AnimationClip clip,
                         ModelPartDescriptor part, float originX, float y0,
                         float barX0, float barX1) {
        float top = y0 + 2f;
        float bottom = y0 + ROW_HEIGHT - 2f;
        float yMid = (top + bottom) * 0.5f;

        // When the clip is an OVERLAY, rows outside its part mask are dimmed —
        // the overlay won't drive them in-game.
        boolean dimmed = isOutsideOverlayMask(clip, part.name());
        float dim = dimmed ? 0.35f : 1f;

        boolean isSelectedPart = part.id().equals(controller.state().selectedPartId());
        int labelColor = isSelectedPart ? AnimEditorTheme.labelText() : AnimEditorTheme.labelTextDim();
        dl.addText(originX + 4f, yMid - ImGui.getTextLineHeight() * 0.5f,
                dimmed ? AnimEditorTheme.withAlpha(labelColor, dim) : labelColor, part.name());

        dl.addRectFilled(barX0, top, barX1, bottom,
                dimmed ? AnimEditorTheme.withAlpha(AnimEditorTheme.trackBg(), 0.5f)
                       : AnimEditorTheme.trackBg(), 3f);
        dl.addLine(barX0 + 2f, yMid, barX1 - 2f, yMid,
                dimmed ? AnimEditorTheme.withAlpha(AnimEditorTheme.trackAxis(), dim)
                       : AnimEditorTheme.trackAxis(), 1f);

        Track track = clip.trackFor(part.id());
        if (track == null) return;

        KeyframeSelection selection = controller.state().selection();
        boolean dragging = dragMode == DragMode.DRAG_KEYS;
        for (int i = 0; i < track.size(); i++) {
            Keyframe kf = track.get(i);
            if (!layout.isTimeVisible(kf.time())) continue;
            // While dragging, the moving keys render as ghosts only.
            if (dragging && isDragged(part.id(), kf.time())) continue;
            float kx = layout.timeToX(kf.time());
            boolean selected = selection.contains(part.id(), kf.time());
            int color = selected ? AnimEditorTheme.keyframeSelected() : AnimEditorTheme.keyframe();
            drawDiamond(dl, kx, yMid, DIAMOND_RADIUS,
                    dimmed ? AnimEditorTheme.withAlpha(color, dim) : color);
        }
    }

    /** True when the clip is an OVERLAY whose mask excludes this part (empty mask = all). */
    private static boolean isOutsideOverlayMask(AnimationClip clip, String partName) {
        if (clip.layerType() != com.openmason.engine.format.oma.AnimLayerMeta.LayerType.OVERLAY) {
            return false;
        }
        if (clip.maskParts().isEmpty()) return false;
        for (String mask : clip.maskParts()) {
            if (mask.equalsIgnoreCase(partName)) return false;
        }
        return true;
    }

    private void drawPlayhead(ImDrawList dl, TimelineLayout layout, float y0, float totalHeight) {
        float t = controller.state().playhead();
        if (!layout.isTimeVisible(t)) return;
        float x = layout.timeToX(t);
        int color = AnimEditorTheme.playhead();
        dl.addLine(x, y0, x, y0 + totalHeight, color, 2f);
        // Handle triangle on the ruler
        dl.addTriangleFilled(x - 5f, y0, x + 5f, y0, x, y0 + 8f, color);
    }

    private void drawDragGhosts(ImDrawList dl, TimelineLayout layout, AnimationClip clip,
                                List<ModelPartDescriptor> parts, float originY) {
        if (dragMode != DragMode.DRAG_KEYS) return;
        int ghost = AnimEditorTheme.ghost();
        for (DragKey key : dragSet) {
            int row = rowOfPart(parts, key.partId());
            if (row < 0) continue;
            float t = ghostTime(key.origTime(), clip);
            float kx = layout.timeToX(t);
            float yMid = rowY0(originY, row) + ROW_HEIGHT * 0.5f;
            drawDiamond(dl, kx, yMid, DIAMOND_RADIUS, ghost);
        }
    }

    private void drawMarquee(ImDrawList dl) {
        if (dragMode != DragMode.BOX_SELECT) return;
        float mx = ImGui.getIO().getMousePosX();
        float my = ImGui.getIO().getMousePosY();
        float x0 = Math.min(pressX, mx), x1 = Math.max(pressX, mx);
        float y0 = Math.min(pressY, my), y1 = Math.max(pressY, my);
        dl.addRectFilled(x0, y0, x1, y1, AnimEditorTheme.marqueeFill());
        dl.addRect(x0, y0, x1, y1, AnimEditorTheme.marqueeBorder());
    }

    private static void drawDiamond(ImDrawList dl, float cx, float cy, float r, int color) {
        dl.addQuadFilled(cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy, color);
    }

    // ====================== input ======================

    private void handleInput(TimelineLayout layout, AnimationClip clip,
                             List<ModelPartDescriptor> parts, ImVec2 origin,
                             float barX0, float barX1) {
        handleWheel(layout, clip, barX0, barX1);

        float mouseX = ImGui.getIO().getMousePosX();
        float mouseY = ImGui.getIO().getMousePosY();

        if (ImGui.isItemHovered() && ImGui.isMouseClicked(1)) {
            int row = rowAt(origin.y, parts.size(), mouseY);
            contextPartId = row >= 0 ? parts.get(row).id() : null;
        }

        if (ImGui.isItemActivated()) {
            beginGesture(layout, clip, parts, origin, barX0, mouseX, mouseY);
        }

        if (ImGui.isItemActive()) {
            updateGesture(layout, clip, mouseX);
        }

        if (ImGui.isItemDeactivated()) {
            endGesture(layout, clip, parts, origin, mouseX, mouseY);
            dragMode = DragMode.NONE;
        }
    }

    private void handleWheel(TimelineLayout layout, AnimationClip clip, float barX0, float barX1) {
        if (!ImGui.isItemHovered() && !ImGui.isWindowHovered()) return;
        float wheel = ImGui.getIO().getMouseWheel();
        if (wheel == 0f) return;

        boolean ctrl = ImGui.getIO().getKeyCtrl();
        boolean shift = ImGui.getIO().getKeyShift();
        if (ctrl) {
            float mouseX = ImGui.getIO().getMousePosX();
            float anchorTime = layout.xToTime(mouseX);
            float factor = (float) Math.pow(1.2, wheel);
            float newZoom = TimelineLayout.clampZoom(controller.state().timelineZoom() * factor);
            controller.state().setTimelineZoom(newZoom);
            controller.state().setTimelineScrollSec(TimelineLayout.scrollForZoomAnchor(
                    anchorTime, mouseX, barX0, barX1 - barX0, newZoom, clip.duration()));
        } else if (shift) {
            float pan = -wheel * layout.visibleLength() * 0.15f;
            controller.state().setTimelineScrollSec(controller.state().timelineScrollSec() + pan);
        }
        // Plain wheel falls through to the child's native vertical scroll.
    }

    private void beginGesture(TimelineLayout layout, AnimationClip clip,
                              List<ModelPartDescriptor> parts, ImVec2 origin,
                              float barX0, float mouseX, float mouseY) {
        pressX = mouseX;
        pressY = mouseY;
        pressedPartId = null;
        pressedKfIndex = -1;
        dragSet.clear();
        dragBeforeByPart.clear();
        dragDeltaTime = 0f;

        // Ruler band → scrub immediately.
        if (mouseY < origin.y + RULER_HEIGHT) {
            dragMode = DragMode.SCRUB;
            scrubTo(layout, mouseX);
            return;
        }

        int row = rowAt(origin.y, parts.size(), mouseY);
        if (row < 0) {
            dragMode = DragMode.NONE;
            return;
        }
        ModelPartDescriptor part = parts.get(row);
        pressedPartId = part.id();

        // Label region → select the part.
        if (mouseX < barX0) {
            controller.state().setSelectedPartId(part.id());
            dragMode = DragMode.NONE;
            return;
        }

        Track track = clip.trackFor(part.id());
        int hit = findKeyframeNearPixel(layout, track, mouseX);
        boolean ctrl = ImGui.getIO().getKeyCtrl();

        if (hit >= 0) {
            pressedKfIndex = hit;
            Keyframe kf = track.get(hit);
            if (ctrl) {
                controller.state().selection().toggle(
                        new KeyframeSelection.KeyRef(part.id(), kf.time()));
                dragMode = DragMode.NONE;
                return;
            }
            if (!controller.state().selection().contains(part.id(), kf.time())) {
                controller.state().setSelectedPartId(part.id());
                controller.state().setSelectedKeyframeIndex(hit);
            } else {
                // Clicking inside an existing multi-selection keeps it; just
                // repoint the primary for the inspector.
                KeyframeSelection keep = snapshotSelection();
                controller.state().selectKeyframes(keep, part.id(), hit);
            }
            controller.state().setPlayhead(kf.time());
            controller.applyCurrentPose();
            dragMode = DragMode.PENDING_KEYS;
        } else {
            dragMode = DragMode.PENDING_BOX;
        }
    }

    private void updateGesture(TimelineLayout layout, AnimationClip clip, float mouseX) {
        switch (dragMode) {
            case SCRUB -> scrubTo(layout, mouseX);
            case PENDING_KEYS -> {
                if (ImGui.isMouseDragging(0, DRAG_THRESHOLD)) {
                    startKeyDrag(clip);
                }
            }
            case PENDING_BOX -> {
                if (ImGui.isMouseDragging(0, DRAG_THRESHOLD)) {
                    dragMode = DragMode.BOX_SELECT;
                }
            }
            case DRAG_KEYS -> dragDeltaTime = layout.deltaXToDeltaTime(mouseX - pressX);
            default -> { }
        }
    }

    private void startKeyDrag(AnimationClip clip) {
        List<KeyframeSelection.ResolvedKey> resolved =
                controller.state().selection().resolve(clip);
        if (resolved.isEmpty()) return;
        for (KeyframeSelection.ResolvedKey key : resolved) {
            dragSet.add(new DragKey(key.partId(), key.keyframe().time(), key.keyframe()));
            Track track = clip.trackFor(key.partId());
            if (track != null) {
                dragBeforeByPart.computeIfAbsent(key.partId(), id -> List.copyOf(track.keyframes()));
            }
        }
        dragMode = DragMode.DRAG_KEYS;
    }

    private void endGesture(TimelineLayout layout, AnimationClip clip,
                            List<ModelPartDescriptor> parts, ImVec2 origin,
                            float mouseX, float mouseY) {
        switch (dragMode) {
            case DRAG_KEYS -> commitKeyDrag(clip);
            case BOX_SELECT -> commitBoxSelect(layout, clip, parts, origin, mouseX, mouseY);
            case PENDING_BOX -> {
                // Plain click on empty track area: select part + scrub.
                if (pressedPartId != null) {
                    controller.state().setSelectedPartId(pressedPartId);
                }
                scrubTo(layout, mouseX);
            }
            default -> { }
        }
    }

    private void commitKeyDrag(AnimationClip clip) {
        if (dragSet.isEmpty() || Math.abs(dragDeltaTime) < 1e-6f) return;

        Map<String, List<Keyframe>> afterByPart = new LinkedHashMap<>();
        for (var entry : dragBeforeByPart.entrySet()) {
            List<Keyframe> after = new ArrayList<>();
            for (Keyframe kf : entry.getValue()) {
                if (isDragged(entry.getKey(), kf.time())) {
                    after.add(kf.withTime(ghostTime(kf.time(), clip)));
                } else {
                    after.add(kf);
                }
            }
            afterByPart.put(entry.getKey(), after);
        }
        controller.moveKeyframes(dragBeforeByPart, afterByPart);

        // Rebuild the selection at the new times.
        KeyframeSelection moved = new KeyframeSelection();
        String primaryPart = null;
        float primaryTime = -1f;
        for (DragKey key : dragSet) {
            float t = ghostTime(key.origTime(), clip);
            moved.add(new KeyframeSelection.KeyRef(key.partId(), t));
            if (key.partId().equals(pressedPartId) && primaryPart == null) {
                primaryPart = key.partId();
                primaryTime = t;
            }
        }
        if (primaryPart == null && !dragSet.isEmpty()) {
            DragKey first = dragSet.get(0);
            primaryPart = first.partId();
            primaryTime = ghostTime(first.origTime(), clip);
        }
        int primaryIndex = indexAtTime(clip, primaryPart, primaryTime);
        controller.state().selectKeyframes(moved, primaryPart, primaryIndex);
    }

    private void commitBoxSelect(TimelineLayout layout, AnimationClip clip,
                                 List<ModelPartDescriptor> parts, ImVec2 origin,
                                 float mouseX, float mouseY) {
        float x0 = Math.min(pressX, mouseX), x1 = Math.max(pressX, mouseX);
        float y0 = Math.min(pressY, mouseY), y1 = Math.max(pressY, mouseY);

        boolean ctrl = ImGui.getIO().getKeyCtrl();
        KeyframeSelection result = ctrl ? snapshotSelection() : new KeyframeSelection();
        String primaryPart = null;
        int primaryIndex = -1;

        for (int row = 0; row < parts.size(); row++) {
            float yMid = rowY0(origin.y, row) + ROW_HEIGHT * 0.5f;
            if (yMid < y0 || yMid > y1) continue;
            ModelPartDescriptor part = parts.get(row);
            Track track = clip.trackFor(part.id());
            if (track == null) continue;
            for (int i = 0; i < track.size(); i++) {
                Keyframe kf = track.get(i);
                float kx = layout.timeToX(kf.time());
                if (kx < x0 || kx > x1) continue;
                result.add(new KeyframeSelection.KeyRef(part.id(), kf.time()));
                if (primaryPart == null) {
                    primaryPart = part.id();
                    primaryIndex = i;
                }
            }
        }

        if (result.isEmpty()) {
            controller.state().setSelectedKeyframeIndex(-1);
            return;
        }
        if (primaryPart == null) {
            // Ctrl-add with nothing new hit: keep the existing primary.
            primaryPart = controller.state().selectedPartId();
            primaryIndex = controller.state().selectedKeyframeIndex();
        }
        controller.state().selectKeyframes(result, primaryPart, primaryIndex);
    }

    private void renderContextMenu() {
        if (ImGui.beginPopupContextItem("##timelineContext")) {
            boolean hasSelection = !controller.state().selection().isEmpty();
            boolean hasClipboard = !controller.state().clipboard().isEmpty();

            if (ImGui.menuItem("Copy", "Ctrl+C", false, hasSelection)) {
                controller.copySelection();
            }
            if (ImGui.menuItem("Paste at Playhead", "Ctrl+V", false, hasClipboard)) {
                controller.pasteAtPlayhead();
            }
            if (ImGui.menuItem("Delete Selected", "Del", false, hasSelection)) {
                controller.deleteSelectedKeyframes();
            }
            if (contextPartId != null) {
                ImGui.separator();
                boolean hasTrack = controller.state().clip().trackFor(contextPartId) != null;
                if (ImGui.menuItem("Delete Track", null, false, hasTrack)) {
                    controller.deleteTrack(contextPartId);
                }
            }
            ImGui.endPopup();
        }
    }

    // ====================== helpers ======================

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

    private void scrubTo(TimelineLayout layout, float mouseX) {
        controller.state().setPlaying(false);
        controller.state().setPlayhead(layout.xToTime(mouseX));
        controller.applyCurrentPose();
    }

    /** The time a dragged keyframe lands at: offset, clamped, frame-snapped unless Alt. */
    private float ghostTime(float origTime, AnimationClip clip) {
        float t = origTime + dragDeltaTime;
        boolean snap = controller.state().snapToFrames() && !ImGui.getIO().getKeyAlt();
        if (snap) t = TimelineLayout.snap(t, clip.fps());
        return Math.min(Math.max(t, 0f), clip.duration());
    }

    private boolean isDragged(String partId, float time) {
        for (DragKey key : dragSet) {
            if (key.partId().equals(partId)
                    && Math.abs(key.origTime() - time) < KeyframeSelection.TIME_EPS) {
                return true;
            }
        }
        return false;
    }

    private KeyframeSelection snapshotSelection() {
        KeyframeSelection copy = new KeyframeSelection();
        for (KeyframeSelection.KeyRef ref : controller.state().selection().all()) {
            copy.add(ref);
        }
        return copy;
    }

    private static int rowAt(float originY, int rowCount, float mouseY) {
        int row = (int) Math.floor((mouseY - originY - RULER_HEIGHT) / ROW_HEIGHT);
        return (row >= 0 && row < rowCount) ? row : -1;
    }

    private static int rowOfPart(List<ModelPartDescriptor> parts, String partId) {
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).id().equals(partId)) return i;
        }
        return -1;
    }

    private int findKeyframeNearPixel(TimelineLayout layout, Track track, float mouseX) {
        if (track == null) return -1;
        int best = -1;
        float bestDist = HIT_PIXELS + 1f;
        for (int i = 0; i < track.size(); i++) {
            if (!layout.isTimeVisible(track.get(i).time())) continue;
            float dist = Math.abs(layout.timeToX(track.get(i).time()) - mouseX);
            if (dist <= HIT_PIXELS && dist < bestDist) {
                best = i;
                bestDist = dist;
            }
        }
        return best;
    }

    private static int indexAtTime(AnimationClip clip, String partId, float time) {
        if (partId == null) return -1;
        Track track = clip.trackFor(partId);
        if (track == null) return -1;
        for (int i = 0; i < track.size(); i++) {
            if (Math.abs(track.get(i).time() - time) < KeyframeSelection.TIME_EPS) return i;
        }
        return -1;
    }
}
