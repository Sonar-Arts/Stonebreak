package com.openmason.main.systems.menus.animationEditor.state;

import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;

/**
 * Mutable per-session state for the Animation Editor window. Owns the loaded
 * clip, playback transport state, and selection.
 */
public final class AnimationEditorState {

    private AnimationClip clip = AnimationClip.blank();
    private float playhead = 0f;          // seconds
    private boolean playing = false;
    private String selectedPartId;        // currently focused track in the inspector
    private int selectedKeyframeIndex = -1;
    private String filePath;              // null if never saved
    private boolean dirty = false;

    public AnimationClip clip() { return clip; }
    public void setClip(AnimationClip clip) {
        this.clip = clip != null ? clip : AnimationClip.blank();
        this.playhead = 0f;
        this.playing = false;
        this.selectedKeyframeIndex = -1;
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

    public String selectedPartId() { return selectedPartId; }
    public void setSelectedPartId(String id) {
        this.selectedPartId = id;
        this.selectedKeyframeIndex = -1;
    }

    public int selectedKeyframeIndex() { return selectedKeyframeIndex; }
    public void setSelectedKeyframeIndex(int index) { this.selectedKeyframeIndex = index; }

    public String filePath() { return filePath; }
    public void setFilePath(String path) { this.filePath = path; }

    public boolean dirty() { return dirty; }
    public void markDirty() { this.dirty = true; }
    public void markClean() { this.dirty = false; }
}
