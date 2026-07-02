package com.openmason.main.systems.menus.animationEditor.data;

import com.openmason.engine.format.oma.AnimLayerMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single named animation clip.
 *
 * <p>Owns playback metadata (fps, duration, looping), a reference to the model
 * the clip targets ({@code modelRef}), one {@link Track} per animated part
 * keyed by partId, and layering metadata (BASE vs OVERLAY + part mask + fades)
 * for animation mixing. The layer type enum is the engine's
 * {@link AnimLayerMeta.LayerType} so tool and runtime never drift.
 */
public final class AnimationClip {

    private String name;
    private float fps;
    private float duration;
    private boolean loop;
    private String modelRef;
    private final Map<String, Track> tracks = new LinkedHashMap<>();

    // Layering metadata (format v1.1). Mask entries are part NAMES.
    private AnimLayerMeta.LayerType layerType = AnimLayerMeta.LayerType.BASE;
    private final List<String> maskParts = new ArrayList<>();
    private float fadeInSeconds = AnimLayerMeta.DEFAULT_FADE_SECONDS;
    private float fadeOutSeconds = AnimLayerMeta.DEFAULT_FADE_SECONDS;
    private int layerPriority = 0;

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

    // ---------- layering metadata ----------

    public AnimLayerMeta.LayerType layerType() { return layerType; }
    public void setLayerType(AnimLayerMeta.LayerType type) {
        this.layerType = type != null ? type : AnimLayerMeta.LayerType.BASE;
    }

    /** Part NAMES this clip owns as an overlay; empty = all parts. Live list. */
    public List<String> maskParts() { return maskParts; }
    public void setMaskParts(List<String> parts) {
        maskParts.clear();
        if (parts != null) {
            for (String p : parts) {
                if (p != null && !p.isBlank() && !containsIgnoreCase(maskParts, p)) {
                    maskParts.add(p);
                }
            }
        }
    }

    public float fadeInSeconds() { return fadeInSeconds; }
    public void setFadeInSeconds(float s) { this.fadeInSeconds = Math.max(0f, s); }

    public float fadeOutSeconds() { return fadeOutSeconds; }
    public void setFadeOutSeconds(float s) { this.fadeOutSeconds = Math.max(0f, s); }

    public int layerPriority() { return layerPriority; }
    public void setLayerPriority(int priority) { this.layerPriority = priority; }

    /** Snapshot of the layer metadata in the engine's runtime form. */
    public AnimLayerMeta layerMeta() {
        return new AnimLayerMeta(layerType, List.copyOf(maskParts),
                fadeInSeconds, fadeOutSeconds, layerPriority);
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String s : list) {
            if (s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }
}
