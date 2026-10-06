package com.openmason.main.systems.uiEditor.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code editor/workspace.json}: the editor's per-document view (selection, zoom, pan, frame
 * size, UI scale, pixel ratio, designer-hidden and locked elements). Editor-only by the wire
 * contract, so runtimes ignore it; unknown fields from older or newer editors are ignored too.
 */
public final class WorkspaceStamp {

    public static final String ENTRY = OmuiFormat.EDITOR_DIR + "workspace.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkspaceStamp() {
    }

    /** The view state recorded in {@code doc}, or defaults; also restores the selection. */
    public static DocumentViewState restore(UiEditorDocument doc) {
        DocumentViewState v = new DocumentViewState();
        v.timeline = com.openmason.main.systems.uiEditor.timeline.TimelineViewState.restore(doc.archive());
        UiBytes bytes = doc.archive().editor().get(ENTRY);
        if (bytes == null) {
            return v;
        }
        try {
            JsonNode n = JSON.readTree(bytes.toArray());
            if (n.has("frame")) {
                v.frameWidth = clamp(n.path("frame").path("width").asInt(v.frameWidth), 16, 16384);
                v.frameHeight = clamp(n.path("frame").path("height").asInt(v.frameHeight), 16, 16384);
            }
            v.uiScale = (float) Math.max(0.25, Math.min(4, n.path("uiScale").asDouble(1)));
            v.pixelRatio = (float) Math.max(0.5, Math.min(4, n.path("pixelRatio").asDouble(1)));
            if (n.has("pan") && n.has("zoom")) {
                v.transform.set((float) n.path("zoom").asDouble(1), (float) n.path("pan").path(0).asDouble(0),
                    (float) n.path("pan").path(1).asDouble(0));
                v.fitPending = false;
                v.autoFit = false;
            }
            strings(n.path("hidden"), v.hidden);
            strings(n.path("locked"), v.locked);
            List<String> sel = new ArrayList<>();
            strings(n.path("selection"), sel);
            if (!sel.isEmpty() && doc.selection().isEmpty()) {
                doc.select(sel);
            }
        } catch (Exception ignored) {
            // a damaged editor entry only loses view state
        }
        return v;
    }

    /** The entry bytes for {@code doc}'s current view. */
    public static UiBytes stamp(UiEditorDocument doc, DocumentViewState v) {
        ObjectNode n = JSON.createObjectNode();
        ObjectNode frame = n.putObject("frame");
        frame.put("width", v.frameWidth);
        frame.put("height", v.frameHeight);
        put(n.putArray("hidden"), v.hidden);
        put(n.putArray("locked"), v.locked);
        ArrayNode pan = n.putArray("pan");
        pan.add(round(v.transform.panX()));
        pan.add(round(v.transform.panY()));
        n.put("pixelRatio", v.pixelRatio);
        put(n.putArray("selection"), doc.selection());
        n.put("uiScale", v.uiScale);
        n.put("zoom", round(v.transform.zoom() * 1000) / 1000.0);
        try {
            return UiBytes.copyOf(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(n));
        } catch (Exception e) {
            return null;
        }
    }

    private static void put(ArrayNode a, Collection<String> values) {
        Set<String> sorted = values instanceof List<?> ? null : new TreeSet<>(values);
        for (String s : sorted == null ? values : sorted) {
            a.add(s);
        }
    }

    private static void strings(JsonNode arr, Collection<String> out) {
        if (arr != null && arr.isArray()) {
            arr.forEach(e -> {
                if (e.isTextual()) {
                    out.add(e.asText());
                }
            });
        }
    }

    private static double round(double v) {
        return Math.round(v);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
