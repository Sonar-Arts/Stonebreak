package com.openmason.main.systems.menus.animationEditor.state;

import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Track;

/**
 * Mutable per-session state for the Animation Editor window. Owns the loaded
 * clip, playback transport state, timeline view (zoom/scroll/snap), and
 * selection.
 *
 * <p>Selection has two layers kept in sync: the <em>primary</em> selection
 * ({@code selectedPartId} + {@code selectedKeyframeIndex}) drives the
 * inspector and MCP tools, while {@link #selection()} holds the full
 * multi-selection for bulk timeline operations. Setting the primary keyframe
 * resets the multi-selection to just it; the timeline uses
 * {@link #selectKeyframes} to establish a multi-selection with a primary.
 */
public final class AnimationEditorState {

    private AnimationClip clip = AnimationClip.blank();
    private float playhead = 0f;          // seconds
    private boolean playing = false;
    private float playbackSpeed = 1f;     // 0.25x .. 2x
    private String selectedPartId;        // currently focused track in the inspector
    private int selectedKeyframeIndex = -1;
    private final KeyframeSelection selection = new KeyframeSelection();
    private final KeyframeClipboard clipboard = new KeyframeClipboard();
    private String filePath;              // null if never saved
    private boolean dirty = false;

    // Timeline view state
    private float timelineZoom = 1f;      // 1 = whole clip fits the bar
    private float timelineScrollSec = 0f; // left edge of the visible window
    private boolean snapToFrames = true;

    public AnimationClip clip() { return clip; }
    public void setClip(AnimationClip clip) {
        this.clip = clip != null ? clip : AnimationClip.blank();
        this.playhead = 0f;
        this.playing = false;
        this.selectedKeyframeIndex = -1;
        this.selection.clear();
        this.timelineZoom = 1f;
        this.timelineScrollSec = 0f;
    }

    public float playhead() { return playhead; }
    public void setPlayhead(float t) {
        if (clip == null) { this.playhead = 0f; return; }
        float max = clip.duration();
        if (t < 0f) t = 0f;
        if (t > max) t = clip.loop() ? (t % max) : max;
        this.playhead = t;
    }

    public boolean playing() { return playing; }
    public void setPlaying(boolean playing) { this.playing = playing; }

    public float playbackSpeed() { return playbackSpeed; }
    public void setPlaybackSpeed(float speed) {
        this.playbackSpeed = Math.min(Math.max(speed, 0.05f), 8f);
    }

    public String selectedPartId() { return selectedPartId; }
    public void setSelectedPartId(String id) {
        this.selectedPartId = id;
        this.selectedKeyframeIndex = -1;
        this.selection.clear();
    }

    public int selectedKeyframeIndex() { return selectedKeyframeIndex; }

    /**
     * Set the primary keyframe selection. Also resets the multi-selection to
     * just this keyframe (or clears it when {@code index < 0}).
     */
    public void setSelectedKeyframeIndex(int index) {
        this.selectedKeyframeIndex = index;
        selection.clear();
        if (index >= 0 && selectedPartId != null && clip != null) {
            Track track = clip.trackFor(selectedPartId);
            if (track != null && index < track.size()) {
                selection.set(new KeyframeSelection.KeyRef(selectedPartId, track.get(index).time()));
            }
        }
    }

    /**
     * Establish a multi-selection with an explicit primary keyframe, without
     * the single-ref reset of {@link #setSelectedKeyframeIndex}. Used by the
     * timeline for ctrl-click and box select.
     */
    public void selectKeyframes(KeyframeSelection newSelection, String primaryPartId, int primaryIndex) {
        selection.clear();
        for (KeyframeSelection.KeyRef ref : newSelection.all()) {
            selection.add(ref);
        }
        this.selectedPartId = primaryPartId;
        this.selectedKeyframeIndex = primaryIndex;
    }

    public KeyframeSelection selection() { return selection; }
    public KeyframeClipboard clipboard() { return clipboard; }

    public String filePath() { return filePath; }
    public void setFilePath(String path) { this.filePath = path; }

    public boolean dirty() { return dirty; }
    public void markDirty() { this.dirty = true; }
    public void markClean() { this.dirty = false; }

    // ---------- timeline view ----------

    public float timelineZoom() { return timelineZoom; }
    public void setTimelineZoom(float zoom) {
        // Bounds mirror TimelineLayout.MIN_ZOOM/MAX_ZOOM (state can't depend on panels).
        this.timelineZoom = Math.min(Math.max(zoom, 1f), 100f);
        this.timelineScrollSec = clampScroll(this.timelineScrollSec);
    }

    public float timelineScrollSec() { return timelineScrollSec; }
    public void setTimelineScrollSec(float scrollSec) {
        this.timelineScrollSec = clampScroll(scrollSec);
    }

    public boolean snapToFrames() { return snapToFrames; }
    public void setSnapToFrames(boolean snap) { this.snapToFrames = snap; }

    private float clampScroll(float scrollSec) {
        float duration = clip != null ? clip.duration() : 1f;
        float windowLen = duration / timelineZoom;
        float max = Math.max(0f, duration - windowLen);
        return Math.min(Math.max(scrollSec, 0f), max);
    }
}
