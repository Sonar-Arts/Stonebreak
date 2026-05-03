package com.openmason.main.systems.menus.animationEditor.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single named animation clip.
 *
 * <p>Owns playback metadata (fps, duration, looping), a reference to the model
 * the clip targets ({@code modelRef}), and one {@link Track} per animated part
 * keyed by partId.
 */
public final class AnimationClip {

    private String name;
    private float fps;
    private float duration;
    private boolean loop;
    private String modelRef;
    private final Map<String, Track> tracks = new LinkedHashMap<>();

    public AnimationClip(String name, float fps, float duration, boolean loop, String modelRef) {
        this.name = name != null ? name : "untitled";
        this.fps = fps > 0 ? fps : 30f;
        this.duration = duration > 0 ? duration : 1f;
        this.loop = loop;
        this.modelRef = modelRef;
    }

    public static AnimationClip blank() {
        return new AnimationClip("untitled", 30f, 1f, true, null);
    }

    public String name() { return name; }
    public void setName(String name) { this.name = name; }

    public float fps() { return fps; }
    public void setFps(float fps) { this.fps = Math.max(1f, fps); }

    public float duration() { return duration; }
    public void setDuration(float duration) { this.duration = Math.max(0.05f, duration); }

    public boolean loop() { return loop; }
    public void setLoop(boolean loop) { this.loop = loop; }

    public String modelRef() { return modelRef; }
    public void setModelRef(String modelRef) { this.modelRef = modelRef; }

    public Map<String, Track> tracks() { return tracks; }

    public Track trackFor(String partId) {
        return tracks.get(partId);
    }

    public Track ensureTrack(String partId) {
        return tracks.computeIfAbsent(partId, Track::new);
    }

    public void removeTrack(String partId) {
        tracks.remove(partId);
    }
}
