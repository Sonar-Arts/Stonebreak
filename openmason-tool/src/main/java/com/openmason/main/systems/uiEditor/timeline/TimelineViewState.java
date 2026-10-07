package com.openmason.main.systems.uiEditor.timeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.main.systems.menus.animationEditor.panels.TimelineLayout;

import java.util.Map;
import java.util.TreeMap;

/**
 * The Timeline's display state of one document (#295): which clip is open, each clip's zoom and
 * scroll, and the snap rate. Editor-only and kept apart from the clips, in
 * {@code editor/timeline.json}; runtimes never read it and it never changes a clip's bytes.
 */
public final class TimelineViewState {

    /** The entry the view state is saved to. */
    public static final String ENTRY = OmuiFormat.EDITOR_DIR + "timeline.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Zoom (1 = the clip fits) and scroll in seconds of one clip's view. */
    public static final class ClipView {
        public float zoom = 1f;
        public float scroll;
    }

    public String activeClip;
    /** Keys and the playhead snap to this many frames per second (0 = off). */
    public float snapFps = 60f;
    private final Map<String, ClipView> clips = new TreeMap<>();

    public ClipView clip(String id) {
        return clips.computeIfAbsent(id, k -> new ClipView());
    }

    /** Forgets views of clips the document no longer has. */
    public void prune(java.util.Set<String> ids) {
        clips.keySet().retainAll(ids);
        if (activeClip != null && !ids.contains(activeClip)) {
            activeClip = null;
        }
    }

    public static TimelineViewState restore(OmuiArchive archive) {
        TimelineViewState v = new TimelineViewState();
        UiBytes bytes = archive.editor().get(ENTRY);
        if (bytes == null) {
            return v;
        }
        try {
            JsonNode n = JSON.readTree(bytes.toArray());
            v.activeClip = n.path("activeClip").isTextual() ? n.path("activeClip").asText() : null;
            v.snapFps = (float) Math.max(0, Math.min(1000, n.path("snapFps").asDouble(60)));
            n.path("clips").fields().forEachRemaining(e -> {
                ClipView c = v.clip(e.getKey());
                c.zoom = TimelineLayout.clampZoom((float) e.getValue().path("zoom").asDouble(1));
                c.scroll = (float) Math.max(0, e.getValue().path("scroll").asDouble(0));
            });
            v.prune(archive.animations().keySet());
        } catch (Exception ignored) {
            // a damaged editor entry only loses the Timeline's view
        }
        return v;
    }

    /** The entry bytes, or null when there is nothing to record (no clips). */
    public UiBytes stamp(OmuiArchive archive) {
        prune(archive.animations().keySet());
        if (archive.animations().isEmpty()) {
            return null;
        }
        ObjectNode n = JSON.createObjectNode();
        if (activeClip != null) {
            n.put("activeClip", activeClip);
        }
        ObjectNode cs = n.putObject("clips");
        clips.forEach((id, c) -> {
            ObjectNode o = cs.putObject(id);
            o.put("scroll", Math.round(c.scroll * 1000) / 1000.0);
            o.put("zoom", Math.round(c.zoom * 1000) / 1000.0);
        });
        n.put("snapFps", snapFps);
        try {
            return UiBytes.copyOf(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(n));
        } catch (Exception e) {
            return null;
        }
    }
}
