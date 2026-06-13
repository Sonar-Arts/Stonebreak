package com.openmason.main.systems.mortar.theme;

import imgui.ImVec4;

/**
 * Color packing for Skija paint. <strong>Skia colors are ARGB</strong>
 * (0xAARRGGBB) — unlike ImGui's {@code ImColor}/{@code ColorConvertFloat4ToU32}
 * which pack <em>ABGR</em>. This is the single place MortarUI converts theme
 * colors (ImGui {@link ImVec4}, components in [0,1]) into the ARGB ints Skija
 * expects, so the byte-order mismatch lives in exactly one file.
 *
 * <p>Do not feed values produced here into ImGui draw calls, and do not feed
 * {@code ImGuiComponents.themeColorU32} (ABGR) results into Skija paint.</p>
 */
public final class Argb {

    private Argb() {
    }

    /** Pack 0..1 components into 0xAARRGGBB. */
    public static int of(float r, float g, float b, float a) {
        int ai = component(a);
        int ri = component(r);
        int gi = component(g);
        int bi = component(b);
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    /** Pack an {@link ImVec4} (x=r, y=g, z=b, w=a) into 0xAARRGGBB. */
    public static int of(ImVec4 c) {
        return of(c.x, c.y, c.z, c.w);
    }

    /** Same color with an explicit alpha in [0,1], ignoring the source alpha. */
    public static int withAlpha(ImVec4 c, float alpha) {
        return of(c.x, c.y, c.z, alpha);
    }

    /** Replace the alpha byte of an existing ARGB color. */
    public static int withAlpha(int argb, float alpha) {
        return (component(alpha) << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Scale RGB toward white ({@code factor>0}) or black ({@code factor<0}) by
     * {@code |factor|} in [0,1], preserving alpha. Used for hover/press tints.
     */
    public static int shade(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        if (factor >= 0.0f) {
            float f = clamp01(factor);
            r = (int) (r + (255 - r) * f);
            g = (int) (g + (255 - g) * f);
            b = (int) (b + (255 - b) * f);
        } else {
            float f = clamp01(-factor);
            r = (int) (r * (1.0f - f));
            g = (int) (g * (1.0f - f));
            b = (int) (b * (1.0f - f));
        }
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Linear blend from {@code from} to {@code to} by {@code t} in [0,1] (all channels). */
    public static int lerp(int from, int to, float t) {
        float f = clamp01(t);
        int a = lerpByte(from >>> 24, to >>> 24, f);
        int r = lerpByte(from >>> 16, to >>> 16, f);
        int g = lerpByte(from >>> 8, to >>> 8, f);
        int b = lerpByte(from, to, f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpByte(int from, int to, float t) {
        int a = from & 0xFF;
        int b = to & 0xFF;
        return (int) (a + (b - a) * t) & 0xFF;
    }

    private static int component(float v) {
        int i = Math.round(clamp01(v) * 255.0f);
        return i & 0xFF;
    }

    private static float clamp01(float v) {
        if (v < 0.0f) {
            return 0.0f;
        }
        return v > 1.0f ? 1.0f : v;
    }
}
