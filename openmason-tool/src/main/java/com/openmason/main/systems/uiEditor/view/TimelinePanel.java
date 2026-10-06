package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.AnimEvent;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.main.systems.menus.animationEditor.panels.TimelineLayout;
import com.openmason.main.systems.uiEditor.command.AnimationCommands;
import com.openmason.main.systems.uiEditor.command.UiCommand;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.timeline.ClipEdits;
import com.openmason.main.systems.uiEditor.timeline.ClipEdits.KeyId;
import com.openmason.main.systems.uiEditor.timeline.ClipEdits.TrackId;
import com.openmason.main.systems.uiEditor.timeline.TimelineSession;
import com.openmason.main.systems.uiEditor.timeline.TimelineViewState;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import com.openmason.main.systems.uiEditor.view.widgets.ValueFields;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiKey;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Timeline (#295): authors the document's clips and previews them on the designer.
 *
 * <ul>
 *   <li>clip bar: pick, add, rename, delete; duration, loop mode; transport and snap;</li>
 *   <li>tracks: one row per (element, property), labels select their element (target navigation);
 *       keys are diamonds: click / Ctrl+click select, drag on empty track area box-selects (Ctrl adds),
 *       dragging a key moves the selection (one undo step, snaps unless Alt),
 *       double-click keys the shown value; an events row below;</li>
 *   <li>ruler: click/drag scrubs; Ctrl+wheel zooms at the mouse, Shift+wheel pans;</li>
 *   <li>keys: Delete, Ctrl+C / Ctrl+V (at the playhead), K keys the selected track, Space plays.</li>
 * </ul>
 * Scrubbing and playing only pose the design runtime (see {@code TimelineScrubber}): nothing is
 * written to the document and no script or host action runs. The view (open clip, zoom, scroll,
 * snap) is editor metadata in {@code editor/timeline.json}, never part of a clip.
 */
final class TimelinePanel {

    static final String TITLE = "Timeline###uiTimeline";

    private static final float LABEL_WIDTH = 220f;
    private static final float ROW_HEIGHT = 22f;
    private static final float RULER_HEIGHT = 20f;
    private static final float PAD = 10f;
    private static final float DIAMOND = 5.5f;
    private static final float[] SNAPS = {0, 24, 30, 60};

    private enum Drag { NONE, SCRUB, KEYS, EVENT, BOX }

    private final UiEditorContext ctx;
    private final StateMachineSection machines;
    private final Map<UiEditorDocument, TimelineSession> sessions = new HashMap<>();
    private final ImString newId = new ImString(64);
    private final ImString eventName = new ImString(64);
    private boolean loopPreview;
    // gesture
    private Drag drag = Drag.NONE;
    private float pressX;
    private float pressY;
    private float boxOriginY;
    private boolean boxMoved;
    private final java.util.Set<KeyId> boxBase = new java.util.LinkedHashSet<>();
    private UiAnimationClip pressClip;
    private List<KeyId> pressKeys = List.of();
    private int pressEvent = -1;
    private double contextTime;
    private TrackId contextTrack;
    private int focusFrames;

    TimelinePanel(UiEditorContext ctx) {
        this.ctx = ctx;
        this.machines = new StateMachineSection();
    }

    void render() {
        UiEditorDocument doc = ctx.doc();
        sessions.keySet().removeIf(d -> {
            boolean gone = d != doc && !ctx.service.documents().contains(d);
            return gone;
        });
        TimelineSession session = doc == null ? null : sessions.computeIfAbsent(doc, d -> new TimelineSession());
        if (ctx.timelineRequest != null) {
            focusFrames = 3; // a docked tab behind another only renders once it is in front
        }
        if (focusFrames > 0) {
            focusFrames--;
            ImGui.setNextWindowFocus();
        }
        if (!ImGui.begin(TITLE)) {
            ImGui.end();
            releaseAll(); // the designer shows the authored state while the Timeline is out of sight
            return;
        }
        if (doc == null) {
            EditorWidgets.emptyState("No document", "Open or create a UI document to animate it.");
            ImGui.end();
            return;
        }
        boolean focused = ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows);
        ctx.noteFocus();
        DesignerRuntime rt = ctx.runtime();
        UiDocumentInstance ui = rt == null ? null : rt.instance();
        boolean design = rt != null && rt.mode() == DesignerRuntime.Mode.DESIGN;
        TimelineViewState view = ctx.view(doc).timeline;
        if (ctx.timelineRequest != null) {
            String[] req = ctx.timelineRequest.split("@", 2);
            view.activeClip = req[0];
            session.setPlayhead(req.length > 1 ? Double.parseDouble(req[1]) : 0);
            ctx.timelineRequest = null;
        }
        UiAnimationClip clip = activeClip(doc, view);
        session.retain(clip);
        session.scrubber.attach(design ? ui : null);

        clipBar(doc, session, view, clip);
        if (clip != null) {
            transport(session, clip, design, view);
            ImGui.separator();
            float inspector = 300f;
            ImGui.beginChild("##tlBody", Math.max(200f, ImGui.getContentRegionAvailX() - inspector - 8f), 0, false);
            body(doc, session, view, clip, ui);
            ImGui.endChild();
            ImGui.sameLine();
            ImGui.beginChild("##tlSide", 0, 0, true);
            keyInspector(doc, session, clip, ui);
            ImGui.spacing();
            machines.render(doc, ui, design);
            ImGui.endChild();
            if (design) {
                if (!session.scrubber.isPlaying()) {
                    session.scrubber.show(clip, session.playhead());
                } else {
                    session.setPlayhead(session.scrubber.time());
                }
            } else {
                session.scrubber.release();
            }
            if (focused) {
                keys(doc, session, clip, ui);
            }
        } else {
            session.scrubber.release();
            ImGui.spacing();
            machines.render(doc, ui, design);
        }
        ImGui.end();
    }

    /** Hands every channel the Timeline posed back to the cascade. */
    void releaseAll() {
        sessions.values().forEach(s -> s.scrubber.release());
    }

    private UiAnimationClip activeClip(UiEditorDocument doc, TimelineViewState view) {
        Map<String, UiAnimationClip> clips = doc.archive().animations();
        if (view.activeClip == null || !clips.containsKey(view.activeClip)) {
            view.activeClip = clips.isEmpty() ? null : clips.keySet().iterator().next();
        }
        return view.activeClip == null ? null : clips.get(view.activeClip);
    }

    // ── clip bar ────────────────────────────────────────────────────────────

    private void clipBar(UiEditorDocument doc, TimelineSession session, TimelineViewState view, UiAnimationClip clip) {
        ImGui.setNextItemWidth(180f);
        if (ImGui.beginCombo("##clip", clip == null ? "(no clips)" : clip.id())) {
            for (String id : doc.archive().animations().keySet()) {
                if (ImGui.selectable(id, id.equals(view.activeClip))) {
                    view.activeClip = id;
                    session.selection.clear();
                    session.setPlayhead(0);
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine();
        if (ImGui.button("New Clip")) {
            newId.set(uniqueId(doc.archive().animations().keySet(), "clip"));
            ImGui.openPopup("##newClip");
        }
        if (ImGui.beginPopup("##newClip")) {
            ImGui.text("Clip id");
            ImGui.setNextItemWidth(200f);
            ImGui.inputText("##newClipId", newId);
            if (ImGui.button("Create")) {
                String id = newId.get().trim();
                if (run(doc, AnimationCommands.addClip(id, 1))) {
                    view.activeClip = id;
                    session.setPlayhead(0);
                }
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
        if (clip == null) {
            ImGui.sameLine();
            ImGui.textDisabled("Clips animate this document's elements; state machines play them.");
            status(doc);
            return;
        }
        ImGui.sameLine();
        if (ImGui.button("Rename")) {
            newId.set(clip.id());
            ImGui.openPopup("##renameClip");
        }
        if (ImGui.beginPopup("##renameClip")) {
            ImGui.setNextItemWidth(200f);
            ImGui.inputText("##renameId", newId);
            if (ImGui.button("Rename")) {
                String id = newId.get().trim();
                if (!id.equals(clip.id()) && run(doc, AnimationCommands.renameClip(clip.id(), id))) {
                    view.activeClip = id;
                }
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine();
            ImGui.textDisabled("state machines and graph nodes follow; Lua is not rewritten");
            ImGui.endPopup();
        }
        ImGui.sameLine();
        if (ImGui.button("Delete")) {
            run(doc, AnimationCommands.removeClip(clip.id()));
        }
        ImGui.sameLine();
        ImGui.textDisabled("|");
        ImGui.sameLine();
        float[] d = {(float) clip.duration()};
        ImGui.setNextItemWidth(80f);
        if (ImGui.dragFloat("s##duration", d, 0.01f, 0.01f, 3600f, "%.2f")) {
            run(doc, AnimationCommands.editClip("Set duration", clip.id(), "duration",
                c -> ClipEdits.setDuration(c, d[0])));
        }
        if (ImGui.isItemDeactivated()) {
            doc.endInteraction();
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(100f);
        if (ImGui.beginCombo("##loop", clip.loop().wire())) {
            for (LoopMode m : LoopMode.values()) {
                if (ImGui.selectable(m.wire(), m == clip.loop())) {
                    run(doc, AnimationCommands.editClip("Set loop mode", clip.id(), null, c -> ClipEdits.setLoop(c, m)));
                }
            }
            ImGui.endCombo();
        }
        status(doc);
    }

    private static void status(UiEditorDocument doc) {
        String msg = doc.lastMessage();
        if (msg != null && !msg.isBlank()) {
            ImGui.sameLine();
            ImGui.textDisabled("  " + msg);
        }
    }

    private void transport(TimelineSession session, UiAnimationClip clip, boolean design, TimelineViewState view) {
        if (!design) {
            ImGui.textDisabled("Preview is running the screen: scrubbing works in Design mode (F5 returns).");
            return;
        }
        if (ImGui.button("|<")) {
            session.scrubber.pause();
            session.setPlayhead(0);
        }
        ImGui.sameLine();
        boolean playing = session.scrubber.isPlaying();
        if (ImGui.button(playing ? "Pause" : "Play")) {
            togglePlay(session, clip);
        }
        ImGui.sameLine();
        if (ImGui.checkbox("Loop preview", loopPreview)) {
            loopPreview = !loopPreview;
            if (playing) {
                session.scrubber.play(clip, loopPreview);
            }
        }
        ImGui.sameLine();
        ImGui.text(String.format("%s / %s s", ClipEdits.format(session.playhead()), ClipEdits.format(clip.duration())));
        ImGui.sameLine();
        ImGui.textDisabled("|  Snap");
        ImGui.sameLine();
        ImGui.setNextItemWidth(90f);
        if (ImGui.beginCombo("##snap", view.snapFps <= 0 ? "off" : (int) view.snapFps + " fps")) {
            for (float f : SNAPS) {
                if (ImGui.selectable(f <= 0 ? "off" : (int) f + " fps", f == view.snapFps)) {
                    view.snapFps = f;
                }
            }
            ImGui.endCombo();
        }
    }

    private void togglePlay(TimelineSession session, UiAnimationClip clip) {
        if (session.scrubber.isPlaying()) {
            session.scrubber.pause();
            session.setPlayhead(session.scrubber.time());
        } else {
            session.scrubber.play(clip, loopPreview);
        }
    }

    // ── tracks ──────────────────────────────────────────────────────────────

    private void body(UiEditorDocument doc, TimelineSession session, TimelineViewState view, UiAnimationClip clip,
                      UiDocumentInstance ui) {
        List<AnimTrack> tracks = clip.tracks();
        TimelineViewState.ClipView cv = view.clip(clip.id());
        ImVec2 origin = ImGui.getCursorScreenPos();
        float avail = Math.max(LABEL_WIDTH + 80f, ImGui.getContentRegionAvailX());
        float barX0 = origin.x + LABEL_WIDTH;
        float barX1 = origin.x + avail - PAD;
        int rows = tracks.size() + 1; // + events
        float height = RULER_HEIGHT + rows * ROW_HEIGHT;
        TimelineLayout layout = new TimelineLayout(barX0, barX1 - barX0, (float) clip.duration(), cv.zoom, cv.scroll);
        cv.scroll = layout.visibleStart();

        ImDrawList dl = ImGui.getWindowDrawList();
        ruler(dl, layout, clip, origin.y, barX0, barX1);
        for (int i = 0; i < tracks.size(); i++) {
            track(dl, layout, session, tracks.get(i), ui, origin.x, rowY(origin.y, i), barX0, barX1);
        }
        events(dl, layout, clip, origin.x, rowY(origin.y, tracks.size()), barX0, barX1);
        float px = layout.timeToX((float) session.playhead());
        if (px >= barX0 - 1 && px <= barX1 + 1) {
            dl.addLine(px, origin.y, px, origin.y + height, ImGui.getColorU32(ImGuiCol.PlotLinesHovered), 2f);
        }
        ImGui.invisibleButton("##tlSurface", avail, Math.max(1f, height));
        input(doc, session, view, clip, layout, ui, origin, barX0, barX1);
        contextMenu(doc, session, clip, ui);
        tooltip(clip, layout, origin, barX0);
        addTrackRow(doc, session, clip, ui);
    }

    private static float rowY(float originY, int row) {
        return originY + RULER_HEIGHT + row * ROW_HEIGHT;
    }

    private static void ruler(ImDrawList dl, TimelineLayout layout, UiAnimationClip clip, float y0, float barX0,
                              float barX1) {
        float y1 = y0 + RULER_HEIGHT;
        dl.addRectFilled(barX0, y0, barX1, y1, ImGui.getColorU32(ImGuiCol.MenuBarBg));
        float pps = (barX1 - barX0) / Math.max(1e-4f, layout.visibleLength());
        float step = 10f;
        for (float s : new float[]{0.01f, 0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f}) {
            if (s * pps >= 60f) {
                step = s;
                break;
            }
        }
        int tick = ImGui.getColorU32(ImGuiCol.Border);
        int text = ImGui.getColorU32(ImGuiCol.TextDisabled);
        float first = (float) Math.floor(layout.visibleStart() / step) * step;
        for (float t = first; t <= layout.visibleEnd() + 1e-5f; t += step) {
            if (t < -1e-5f || t > clip.duration() + 1e-5f) {
                continue;
            }
            float x = layout.timeToX(t);
            dl.addLine(x, y0 + RULER_HEIGHT * 0.45f, x, y1, tick, 1f);
            dl.addText(x + 3f, y0 + 2f, text, step < 1f ? String.format("%.2fs", t) : String.format("%.0fs", t));
        }
        dl.addLine(barX0, y1, barX1, y1, tick, 1f);
    }

    private void track(ImDrawList dl, TimelineLayout layout, TimelineSession session, AnimTrack t,
                       UiDocumentInstance ui, float x0, float y0, float barX0, float barX1) {
        TrackId id = TrackId.of(t);
        float mid = y0 + ROW_HEIGHT * 0.5f;
        UiElement el = ui == null ? null : ui.find(t.target());
        boolean missing = ui != null && el == null;
        boolean selected = id.equals(session.selectedTrack);
        if (selected) {
            dl.addRectFilled(x0, y0, barX1, y0 + ROW_HEIGHT, ImGui.getColorU32(ImGuiCol.Header));
        }
        String who = el != null && el.name() != null ? el.name() : t.target();
        String label = (missing ? "? " : "") + who + "  " + t.property().substring(t.property().indexOf(':') + 1);
        int color = missing ? ImGui.getColorU32(ImGuiCol.TextDisabled) : ImGui.getColorU32(ImGuiCol.Text);
        dl.addText(x0 + 4f, mid - ImGui.getTextLineHeight() * 0.5f, color, label);
        dl.addRectFilled(barX0, y0 + 2f, barX1, y0 + ROW_HEIGHT - 2f, ImGui.getColorU32(ImGuiCol.FrameBg), 3f);
        List<AnimKey> keys = t.keys();
        for (int i = 0; i < keys.size(); i++) {
            float x = layout.timeToX((float) keys.get(i).time());
            if (i + 1 < keys.size()) {
                float nx = layout.timeToX((float) keys.get(i + 1).time());
                dl.addLine(x, mid, nx, mid, ImGui.getColorU32(ImGuiCol.Separator), 2f);
            }
            if (!layout.isTimeVisible((float) keys.get(i).time())) {
                continue;
            }
            boolean sel = session.selection.contains(new KeyId(id, keys.get(i).time()));
            int c = ImGui.getColorU32(sel ? ImGuiCol.PlotHistogram : ImGuiCol.CheckMark);
            dl.addQuadFilled(x, mid - DIAMOND, x + DIAMOND, mid, x, mid + DIAMOND, x - DIAMOND, mid, c);
        }
    }

    private static void events(ImDrawList dl, TimelineLayout layout, UiAnimationClip clip, float x0, float y0,
                               float barX0, float barX1) {
        float mid = y0 + ROW_HEIGHT * 0.5f;
        dl.addText(x0 + 4f, mid - ImGui.getTextLineHeight() * 0.5f, ImGui.getColorU32(ImGuiCol.TextDisabled),
            "Events");
        dl.addRectFilled(barX0, y0 + 2f, barX1, y0 + ROW_HEIGHT - 2f, ImGui.getColorU32(ImGuiCol.FrameBg), 3f);
        int c = ImGui.getColorU32(ImGuiCol.PlotLines);
        for (AnimEvent e : clip.events()) {
            if (!layout.isTimeVisible((float) e.time())) {
                continue;
            }
            float x = layout.timeToX((float) e.time());
            dl.addTriangleFilled(x - 5f, y0 + 3f, x + 5f, y0 + 3f, x, y0 + 11f, c);
            float w = ImGui.calcTextSize(e.name()).x;
            float tx = x + 6f + w > barX1 ? x - 6f - w : x + 6f; // names near the end sit left of their marker
            dl.addText(tx, y0 + 4f, ImGui.getColorU32(ImGuiCol.Text), e.name());
        }
    }

    // ── input ───────────────────────────────────────────────────────────────

    private void input(UiEditorDocument doc, TimelineSession session, TimelineViewState view, UiAnimationClip clip,
                       TimelineLayout layout, UiDocumentInstance ui, ImVec2 origin, float barX0, float barX1) {
        TimelineViewState.ClipView cv = view.clip(clip.id());
        float mx = ImGui.getIO().getMousePosX();
        float my = ImGui.getIO().getMousePosY();
        boolean hovered = ImGui.isItemHovered();
        float wheel = ImGui.getIO().getMouseWheel();
        if (hovered && wheel != 0) {
            if (ImGui.getIO().getKeyCtrl()) {
                float anchor = layout.xToTime(mx);
                float z = TimelineLayout.clampZoom(cv.zoom * (float) Math.pow(1.2, wheel));
                cv.zoom = z;
                cv.scroll = TimelineLayout.scrollForZoomAnchor(anchor, mx, barX0, barX1 - barX0, z, (float) clip.duration());
            } else if (ImGui.getIO().getKeyShift()) {
                cv.scroll = TimelineLayout.clampScroll(cv.scroll - wheel * layout.visibleLength() * 0.15f, cv.zoom,
                    (float) clip.duration());
            }
        }
        int tracks = clip.tracks().size();
        int row = my < origin.y + RULER_HEIGHT ? -1 : (int) ((my - origin.y - RULER_HEIGHT) / ROW_HEIGHT);
        boolean alt = ImGui.getIO().getKeyAlt();
        double snap = alt ? 0 : view.snapFps;
        if (hovered && ImGui.isMouseClicked(1)) {
            contextTime = TimelineSession.snap(layout.xToTime(mx), snap);
            contextTrack = row >= 0 && row < tracks ? TrackId.of(clip.tracks().get(row)) : null;
            ImGui.openPopup("##tlContext");
        }
        if (hovered && ImGui.isMouseDoubleClicked(0) && mx >= barX0 && row >= 0) {
            double t = TimelineSession.snap(layout.xToTime(mx), snap);
            session.setPlayhead(t);
            if (row < tracks) {
                run(doc, session.keyAtPlayhead(clip, TrackId.of(clip.tracks().get(row)), ui));
            } else {
                contextTime = t;
                eventName.set("");
                ImGui.openPopup("##addEvent");
            }
            drag = Drag.NONE;
        } else if (ImGui.isItemActivated()) {
            press(doc, session, clip, layout, origin, barX0, mx, my, row, snap);
        } else if (ImGui.isItemActive()) {
            dragging(doc, session, clip, layout, mx, snap);
        }
        if (ImGui.isItemDeactivated()) {
            if (drag == Drag.KEYS || drag == Drag.EVENT) {
                doc.endInteraction();
            }
            if (drag == Drag.BOX && !boxMoved) { // a plain click on an empty lane scrubs there
                session.scrubber.pause();
                session.setPlayhead(TimelineSession.snap(layout.xToTime(pressX), snap));
            }
            drag = Drag.NONE;
        }
        addEventPopup(doc, clip);
    }

    private void press(UiEditorDocument doc, TimelineSession session, UiAnimationClip clip, TimelineLayout layout,
                       ImVec2 origin, float barX0, float mx, float my, int row, double snap) {
        pressX = mx;
        pressY = my;
        drag = Drag.NONE;
        if (my < origin.y + RULER_HEIGHT) {
            drag = Drag.SCRUB;
            session.scrubber.pause();
            session.setPlayhead(TimelineSession.snap(layout.xToTime(mx), snap));
            return;
        }
        int tracks = clip.tracks().size();
        if (row < 0 || row > tracks) {
            return;
        }
        if (row == tracks) { // events row
            pressEvent = eventNear(clip, layout, mx);
            if (pressEvent >= 0) {
                pressClip = clip;
                drag = Drag.EVENT;
            }
            return;
        }
        AnimTrack t = clip.tracks().get(row);
        TrackId id = TrackId.of(t);
        session.selectedTrack = id;
        if (mx < barX0) { // label: target navigation
            doc.select(List.of(t.target()));
            ctx.frameSelectionRequest = true;
            return;
        }
        AnimKey hit = keyNear(t, layout, mx);
        if (hit == null) { // empty lane: a marquee when dragged, a scrub when just clicked
            boxBase.clear();
            if (ImGui.getIO().getKeyCtrl()) {
                boxBase.addAll(session.selection);
            } else {
                session.selection.clear();
            }
            boxOriginY = origin.y;
            boxMoved = false;
            drag = Drag.BOX;
            return;
        }
        KeyId k = new KeyId(id, hit.time());
        if (ImGui.getIO().getKeyCtrl()) {
            if (!session.selection.remove(k)) {
                session.selection.add(k);
            }
            return;
        }
        if (!session.selection.contains(k)) {
            session.selection.clear();
            session.selection.add(k);
        }
        session.scrubber.pause();
        session.setPlayhead(hit.time());
        pressClip = clip;
        pressKeys = List.copyOf(session.selection);
        drag = Drag.KEYS;
    }

    private void dragging(UiEditorDocument doc, TimelineSession session, UiAnimationClip clip, TimelineLayout layout,
                          float mx, double snap) {
        switch (drag) {
            case SCRUB -> session.setPlayhead(TimelineSession.snap(layout.xToTime(mx), snap));
            case KEYS -> {
                if (!ImGui.isMouseDragging(0, 3f)) {
                    return;
                }
                double dt = layout.deltaXToDeltaTime(mx - pressX);
                double anchor = pressKeys.getFirst().time();
                dt = TimelineSession.snap(anchor + dt, snap) - anchor;
                UiAnimationClip from = pressClip;
                List<KeyId> keys = pressKeys;
                double shift = dt;
                try {
                    ClipEdits.Moved moved = ClipEdits.moveKeys(from, keys, shift);
                    if (run(doc, AnimationCommands.editClip("Move keys", clip.id(), "move", c -> moved.clip()))) {
                        session.selection.clear();
                        session.selection.addAll(moved.keys());
                        session.setPlayhead(moved.keys().getFirst().time());
                    }
                } catch (IllegalArgumentException blocked) {
                    // landing on another key: stay where the last legal position was
                }
            }
            case BOX -> {
                if (!ImGui.isMouseDragging(0, 3f)) {
                    return;
                }
                boxMoved = true;
                float my = ImGui.getIO().getMousePosY();
                float x0 = Math.min(pressX, mx);
                float x1 = Math.max(pressX, mx);
                float y0 = Math.min(pressY, my);
                float y1 = Math.max(pressY, my);
                session.selection.clear();
                session.selection.addAll(boxBase);
                for (int row = 0; row < clip.tracks().size(); row++) {
                    float mid = rowY(boxOriginY, row) + ROW_HEIGHT * 0.5f;
                    if (mid < y0 || mid > y1) {
                        continue;
                    }
                    AnimTrack t = clip.tracks().get(row);
                    for (AnimKey k : t.keys()) {
                        float kx = layout.timeToX((float) k.time());
                        if (kx >= x0 && kx <= x1) {
                            session.selection.add(new KeyId(TrackId.of(t), k.time()));
                        }
                    }
                }
                ImDrawList dl = ImGui.getWindowDrawList();
                dl.addRectFilled(x0, y0, x1, y1, ImGui.getColorU32(ImGuiCol.CheckMark, 0.15f));
                dl.addRect(x0, y0, x1, y1, ImGui.getColorU32(ImGuiCol.CheckMark, 0.8f));
            }
            case EVENT -> {
                if (!ImGui.isMouseDragging(0, 3f) || pressEvent < 0 || pressEvent >= pressClip.events().size()) {
                    return;
                }
                double t = TimelineSession.snap(pressClip.events().get(pressEvent).time()
                    + layout.deltaXToDeltaTime(mx - pressX), snap);
                int index = pressEvent;
                UiAnimationClip from = pressClip;
                run(doc, AnimationCommands.editClip("Move event", clip.id(), "event",
                    c -> ClipEdits.moveEvent(from, index, Math.clamp(t, 0, from.duration()))));
            }
            default -> {
            }
        }
    }

    private void contextMenu(UiEditorDocument doc, TimelineSession session, UiAnimationClip clip,
                             UiDocumentInstance ui) {
        if (!ImGui.beginPopup("##tlContext")) {
            return;
        }
        if (contextTrack != null) {
            if (ImGui.menuItem("Key here")) {
                session.setPlayhead(contextTime);
                run(doc, session.keyAtPlayhead(clip, contextTrack, ui));
            }
            if (ImGui.menuItem("Select element")) {
                doc.select(List.of(contextTrack.target()));
                ctx.frameSelectionRequest = true;
            }
            if (ImGui.menuItem("Delete track")) {
                TrackId t = contextTrack;
                run(doc, AnimationCommands.editClip("Delete track", clip.id(), null, c -> ClipEdits.removeTrack(c, t)));
            }
            ImGui.separator();
        }
        if (ImGui.menuItem("Copy keys", "Ctrl+C", false, !session.selection.isEmpty())) {
            session.copy(clip);
        }
        if (ImGui.menuItem("Paste keys at playhead", "Ctrl+V", false, TimelineSession.canPaste())) {
            run(doc, session.paste(clip));
        }
        if (ImGui.menuItem("Delete keys", "Delete", false, !session.selection.isEmpty())) {
            run(doc, session.deleteSelected(clip));
        }
        ImGui.separator();
        if (ImGui.menuItem("Add event here")) {
            eventName.set("");
            ImGui.endPopup();
            ImGui.openPopup("##addEvent");
            return;
        }
        for (int i = 0; i < clip.events().size(); i++) {
            AnimEvent e = clip.events().get(i);
            if (Math.abs(e.time() - contextTime) < 0.05 && ImGui.menuItem("Delete event '" + e.name() + "'")) {
                int index = i;
                run(doc, AnimationCommands.editClip("Delete event", clip.id(), null, c -> ClipEdits.removeEvent(c, index)));
            }
        }
        ImGui.endPopup();
    }

    private void addEventPopup(UiEditorDocument doc, UiAnimationClip clip) {
        if (ImGui.beginPopup("##addEvent")) {
            ImGui.text("Event at " + ClipEdits.format(contextTime) + " s");
            ImGui.setNextItemWidth(180f);
            ImGui.inputText("##eventName", eventName);
            if (ImGui.button("Add")) {
                double t = contextTime;
                String name = eventName.get();
                run(doc, AnimationCommands.editClip("Add event", clip.id(), null, c -> ClipEdits.addEvent(c, t, name)));
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    private void tooltip(UiAnimationClip clip, TimelineLayout layout, ImVec2 origin, float barX0) {
        if (drag != Drag.NONE || !ImGui.isItemHovered()) {
            return;
        }
        float mx = ImGui.getIO().getMousePosX();
        float my = ImGui.getIO().getMousePosY();
        int row = my < origin.y + RULER_HEIGHT ? -1 : (int) ((my - origin.y - RULER_HEIGHT) / ROW_HEIGHT);
        if (mx < barX0 || row < 0 || row >= clip.tracks().size()) {
            return;
        }
        AnimKey k = keyNear(clip.tracks().get(row), layout, mx);
        if (k != null) {
            ImGui.setTooltip(ClipEdits.format(k.time()) + " s   " + ValueFields.display(k.value()) + "   "
                + k.easing().wire());
        }
    }

    private void addTrackRow(UiEditorDocument doc, TimelineSession session, UiAnimationClip clip, UiDocumentInstance ui) {
        String primary = doc.primary();
        UiElement el = primary == null || ui == null ? null : ui.find(primary);
        if (el == null || primary.indexOf('/') >= 0) {
            ImGui.textDisabled("Select an element of this document to add a track.");
            return;
        }
        ImGui.textDisabled("Add track for");
        ImGui.sameLine();
        ImGui.text(el.name() != null ? el.name() : el.key());
        ImGui.sameLine();
        ImGui.setNextItemWidth(220f);
        if (ImGui.beginCombo("##addTrack", "property...")) {
            for (String target : TimelineKeyFields.animatable(el)) {
                boolean used = ClipEdits.track(clip, new TrackId(el.key(), target)) != null;
                if (ImGui.selectable(target, false, used ? imgui.flag.ImGuiSelectableFlags.Disabled : 0)) {
                    TrackId id = new TrackId(el.key(), target);
                    UiCommand add = session.keyAtPlayhead(clip, id, ui);
                    if (add == null) {
                        add = AnimationCommands.editClip("Add track", clip.id(), null,
                            c -> ClipEdits.addTrack(c, el.key(), target));
                    }
                    run(doc, add);
                }
            }
            ImGui.endCombo();
        }
    }

    // ── key inspector ───────────────────────────────────────────────────────

    private void keyInspector(UiEditorDocument doc, TimelineSession session, UiAnimationClip clip,
                              UiDocumentInstance ui) {
        EditorWidgets.caption("Key");
        if (session.selection.isEmpty()) {
            ImGui.textDisabled("Click a key; double-click a track to add one.");
            return;
        }
        if (session.selection.size() > 1) {
            ImGui.text(session.selection.size() + " keys");
            easingCombo(doc, session, clip, null);
            return;
        }
        KeyId k = session.selection.iterator().next();
        AnimTrack t = ClipEdits.track(clip, k.track());
        AnimKey key = t == null ? null : t.keys().stream().filter(x -> Math.abs(x.time() - k.time()) <= ClipEdits.EPS)
            .findFirst().orElse(null);
        if (key == null) {
            return;
        }
        ImGui.text(k.track().property());
        float[] time = {(float) key.time()};
        ImGui.setNextItemWidth(120f);
        if (ImGui.dragFloat("time (s)##kt", time, 0.005f, 0f, (float) clip.duration(), "%.3f")) {
            try {
                ClipEdits.Moved moved = ClipEdits.moveKeys(clip, List.of(k), time[0] - key.time());
                if (run(doc, AnimationCommands.editClip("Move key", clip.id(), "keytime", c -> moved.clip()))) {
                    session.selection.clear();
                    session.selection.addAll(moved.keys());
                }
            } catch (IllegalArgumentException e) {
                doc.setLastMessage(e.getMessage());
            }
        }
        if (ImGui.isItemDeactivated()) {
            doc.endInteraction();
        }
        UiElement el = ui == null ? null : ui.find(k.track().target());
        ValueFields.Result r = TimelineKeyFields.field("tlkey", k.track().property(), key.value(), el, doc.archive(), 160f);
        if (r.changed() && r.value() != null) {
            run(doc, AnimationCommands.editClip("Set key value", clip.id(), "keyvalue",
                c -> ClipEdits.setValue(c, k, r.value())));
        }
        if (r.ended()) {
            doc.endInteraction();
        }
        CurveField.Result curve = CurveField.edit("tlkey", key.easing(), key.bezier(), 200f);
        if (curve.changed()) {
            run(doc, AnimationCommands.editClip("Set easing", clip.id(), "curve", c -> {
                UiAnimationClip out = ClipEdits.setEasing(c, k, curve.easing());
                return curve.bezier() == null ? out : ClipEdits.setCurve(out, k, curve.bezier());
            }));
        }
        if (curve.ended()) {
            doc.endInteraction();
        }
        ImGui.textDisabled("Easing shapes the segment to the next key.");
    }

    private void easingCombo(UiEditorDocument doc, TimelineSession session, UiAnimationClip clip, UiEasing current) {
        ImGui.setNextItemWidth(140f);
        if (ImGui.beginCombo("easing##ke", current == null ? "--" : current.wire())) {
            for (UiEasing e : UiEasing.values()) {
                if (ImGui.selectable(e.wire(), e == current)) {
                    run(doc, session.setEasing(clip, e));
                }
            }
            ImGui.endCombo();
        }
        ImGui.textDisabled("Easing shapes the segment to the next key.");
    }

    // ── keys ────────────────────────────────────────────────────────────────

    private void keys(UiEditorDocument doc, TimelineSession session, UiAnimationClip clip, UiDocumentInstance ui) {
        if (ImGui.getIO().getWantTextInput()) {
            return;
        }
        boolean ctrl = ImGui.getIO().getKeyCtrl();
        if (ImGui.isKeyPressed(ImGuiKey.Delete) || ImGui.isKeyPressed(ImGuiKey.Backspace)) {
            ctx.claimKeys();
            run(doc, session.deleteSelected(clip));
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.C)) {
            ctx.claimKeys();
            int n = session.copy(clip);
            doc.setLastMessage(n == 0 ? "Nothing selected to copy" : "Copied " + n + (n == 1 ? " key" : " keys"));
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.V)) {
            ctx.claimKeys();
            run(doc, session.paste(clip));
        } else if (ctrl && (ImGui.isKeyPressed(ImGuiKey.X) || ImGui.isKeyPressed(ImGuiKey.D))) {
            ctx.claimKeys(); // element cut/duplicate must not fire while the Timeline has focus
        } else if (!ctrl && ImGui.isKeyPressed(ImGuiKey.Space)) {
            ctx.claimKeys();
            togglePlay(session, clip);
        } else if (!ctrl && ImGui.isKeyPressed(ImGuiKey.K) && session.selectedTrack != null) {
            ctx.claimKeys();
            run(doc, session.keyAtPlayhead(clip, session.selectedTrack, ui));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static boolean run(UiEditorDocument doc, UiCommand c) {
        return c != null && doc.execute(c);
    }

    private static AnimKey keyNear(AnimTrack t, TimelineLayout layout, float mx) {
        AnimKey best = null;
        float bestD = DIAMOND + 2f;
        for (AnimKey k : t.keys()) {
            float d = Math.abs(layout.timeToX((float) k.time()) - mx);
            if (d <= bestD) {
                best = k;
                bestD = d;
            }
        }
        return best;
    }

    private static int eventNear(UiAnimationClip clip, TimelineLayout layout, float mx) {
        for (int i = 0; i < clip.events().size(); i++) {
            if (Math.abs(layout.timeToX((float) clip.events().get(i).time()) - mx) <= 6f) {
                return i;
            }
        }
        return -1;
    }

    private static String uniqueId(Set<String> taken, String base) {
        if (!taken.contains(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            if (!taken.contains(base + "_" + i)) {
                return base + "_" + i;
            }
        }
    }

    /** Clips of the document, for the state-machine section's pickers. */
    static List<String> clipIds(UiEditorDocument doc) {
        return new ArrayList<>(doc.archive().animations().keySet());
    }
}
