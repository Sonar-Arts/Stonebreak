package com.stonebreak.ui.worldHud;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * One world-anchored HUD marker, already projected (#301): what the world HUD document shows,
 * produced by one rule (each collector's {@code collect}).
 *
 * <p>Positions are device pixels of the window. Text kinds ({@link Kind#DAMAGE}, {@link Kind#DOUBT},
 * {@link Kind#AWARENESS}, {@link Kind#NAMETAG}) are centred on {@code x} with their baseline at
 * {@code y}, at {@code size} (logical px: the font follows the UI scale), in {@code color} over
 * {@code shadow}. {@link Kind#PREY} anchors at its diamond's centre with {@code text} its name and
 * {@code fraction} its health; {@link Kind#TRAP} is a square of half-size {@code half} in {@code color}.
 *
 * @param id identity for list rows: the kind and its source (an entity, a damage number)
 */
public record WorldMarker(String id, Kind kind, float x, float y, String text, float size, int color, int shadow,
                          float fraction, float half) {

    public enum Kind {
        DAMAGE, PREY, TRAP, DOUBT, AWARENESS, NAMETAG;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static WorldMarker text(String id, Kind kind, float x, float baseline, String text, float size, int color,
                                   int shadow) {
        return new WorldMarker(id, kind, x, baseline, text, size, color, shadow, 0f, 0f);
    }

    /** Identity of a marker of {@code kind} sourced from {@code source} (an entity, a damage number). */
    public static String id(Kind kind, Object source) {
        return kind.wire() + ":" + Integer.toHexString(System.identityHashCode(source));
    }

    /**
     * The world-to-window projection every world marker uses ({@code proj × view} of the player's camera,
     * no depth test): the window position, or null when the point is behind the camera or outside the
     * view ({@code |ndc| > 1}).
     */
    public static float[] project(Matrix4f vp, float wx, float wy, float wz, int screenW, int screenH) {
        Vector4f clip = new Vector4f(wx, wy, wz, 1f);
        vp.transform(clip);
        if (clip.w <= 0f) return null;
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        if (Math.abs(ndcX) > 1f || Math.abs(ndcY) > 1f) return null;
        return new float[] {
            (ndcX + 1f) * 0.5f * screenW,
            (1f - ndcY) * 0.5f * screenH
        };
    }
}
