package com.openmason.main.systems.menus.animationEditor.data;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A single animation track — a part's pose timeline expressed as an ordered
 * list of {@link Keyframe}s. Keeps keyframes sorted by time at all times so
 * sampling is a simple binary search.
 *
 * <p>Mutable on purpose: the editor edits in place and lets the command
 * history capture before/after snapshots.
 */
public final class Track {

    private final String partId;
    private final List<Keyframe> keyframes = new ArrayList<>();
    /** Optional name hint loaded from disk; used by rebind-by-name fallback. Not authoritative. */
    private String partNameHint;

    public Track(String partId) {
        if (partId == null || partId.isBlank()) {
            throw new IllegalArgumentException("partId required");
        }
        this.partId = partId;
    }

    public String partId() {
        return partId;
    }

    public String partNameHint() { return partNameHint; }
    public void setPartNameHint(String name) { this.partNameHint = name; }

    public List<Keyframe> keyframes() {
        return keyframes;
    }

    public boolean isEmpty() {
        return keyframes.isEmpty();
    }

    public int size() {
        return keyframes.size();
    }

    /**
     * Insert (or replace) a keyframe. If a keyframe already exists at the same
     * time (within {@code 1e-4} seconds) it is replaced; otherwise the new
     * keyframe is inserted in sorted order.
     *
     * @return the index at which the keyframe now sits
     */
    public int upsert(Keyframe kf) {
        for (int i = 0; i < keyframes.size(); i++) {
            float existingTime = keyframes.get(i).time();
            if (Math.abs(existingTime - kf.time()) < 1e-4f) {
                keyframes.set(i, kf);
                return i;
            }
            if (existingTime > kf.time()) {
                keyframes.add(i, kf);
                return i;
            }
        }
        keyframes.add(kf);
        return keyframes.size() - 1;
    }

    public Keyframe get(int index) {
        return keyframes.get(index);
    }

    public void set(int index, Keyframe kf) {
        keyframes.set(index, kf);
    }

    /**
     * Re-sort after a keyframe's time has been mutated.
     */
    public void resort() {
        keyframes.sort((a, b) -> Float.compare(a.time(), b.time()));
    }

    public boolean removeAt(int index) {
        if (index < 0 || index >= keyframes.size()) return false;
        keyframes.remove(index);
        return true;
    }

    /**
     * Sample the track at {@code t} seconds, producing an interpolated pose.
     *
     * <p>If the track is empty, returns null. If {@code t} is before the first
     * keyframe, the first pose is held; after the last, the last pose is held.
     * Between two keyframes a linear blend is applied (the sole curve in v1).
     */
    public Sample sample(float t) {
        if (keyframes.isEmpty()) {
            return null;
        }
        if (t <= keyframes.get(0).time()) {
            return Sample.from(keyframes.get(0));
        }
        Keyframe last = keyframes.get(keyframes.size() - 1);
        if (t >= last.time()) {
            return Sample.from(last);
        }
        // Find bracketing keyframes — small N, linear scan is fine.
        for (int i = 0; i < keyframes.size() - 1; i++) {
            Keyframe a = keyframes.get(i);
            Keyframe b = keyframes.get(i + 1);
            if (t >= a.time() && t <= b.time()) {
                float span = b.time() - a.time();
                float u = span > 1e-6f ? (t - a.time()) / span : 0f;
                return new Sample(
                        lerp(a.position(), b.position(), u),
                        lerp(a.rotation(), b.rotation(), u),
                        lerp(a.scale(), b.scale(), u)
                );
            }
        }
        return Sample.from(last);
    }

    private static Vector3f lerp(Vector3f a, Vector3f b, float u) {
        return new Vector3f(
                a.x + (b.x - a.x) * u,
                a.y + (b.y - a.y) * u,
                a.z + (b.z - a.z) * u
        );
    }

    /**
     * A snapshot pose sampled from a track at a specific time.
     */
    public record Sample(Vector3f position, Vector3f rotation, Vector3f scale) {
        static Sample from(Keyframe kf) {
            return new Sample(
                    new Vector3f(kf.position()),
                    new Vector3f(kf.rotation()),
                    new Vector3f(kf.scale())
            );
        }
    }
}
