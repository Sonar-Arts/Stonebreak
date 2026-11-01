package com.openmason.engine.ui.rendering;

import java.util.Objects;

/**
 * Where a Masonry frame is drawn: the render target/context contract shared by the game window,
 * an editor preview FBO and the CPU raster path.
 *
 * <ul>
 *   <li><b>Units.</b> Canvas units are framebuffer pixels: (0, 0) is the top-left of the target
 *       and {@code width x height} its size, whatever the storage origin. Masonry applies its
 *       own UI scale; {@link #pixelRatio} (framebuffer pixels per window coordinate) is
 *       informational for hosts that map pointer input.</li>
 *   <li><b>Color.</b> RGBA8, sRGB, <em>premultiplied</em> alpha. A host compositing the result
 *       with straight-alpha blending must paint an opaque background first or blend with
 *       {@code (ONE, ONE_MINUS_SRC_ALPHA)}.</li>
 *   <li><b>Origin.</b> How rows are stored. {@link Origin#BOTTOM_LEFT} is GL's natural order
 *       (default framebuffer; sample with V flipped). {@link Origin#TOP_LEFT} stores rows
 *       top-down, exactly like the CPU raster path, so an ImGui image uses UVs (0,0)-(1,1)
 *       for both preview paths.</li>
 *   <li><b>Ownership.</b> The target is described, not owned: whoever created the framebuffer
 *       deletes it, after the renderer stopped using it.</li>
 * </ul>
 *
 * @param framebufferId GL framebuffer name; 0 is the window's default framebuffer
 * @param policy        what happens to GL state when the paint ends
 */
public record UiRenderTarget(int framebufferId, int width, int height, float pixelRatio, Origin origin,
                             GlStatePolicy policy) {

    public enum Origin { TOP_LEFT, BOTTOM_LEFT }

    public UiRenderTarget {
        if (framebufferId < 0) {
            throw new IllegalArgumentException("framebufferId < 0");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Target size must be positive: " + width + "x" + height);
        }
        if (!(pixelRatio > 0f) || !Float.isFinite(pixelRatio)) {
            pixelRatio = 1f;
        }
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(policy, "policy");
    }

    /** The game window, with the legacy reset-to-baseline policy. */
    public static UiRenderTarget gameWindow(int width, int height, float pixelRatio) {
        return new UiRenderTarget(0, width, height, pixelRatio, Origin.BOTTOM_LEFT, GlStatePolicy.RESET_TO_BASELINE);
    }

    /** An offscreen framebuffer owned by an editor/host pass: rows top-down, state restored. */
    public static UiRenderTarget offscreen(int framebufferId, int width, int height, float pixelRatio) {
        return new UiRenderTarget(framebufferId, width, height, pixelRatio, Origin.TOP_LEFT, GlStatePolicy.RESTORE);
    }

    public UiRenderTarget withSize(int newWidth, int newHeight, float newPixelRatio) {
        return new UiRenderTarget(framebufferId, newWidth, newHeight, newPixelRatio, origin, policy);
    }

    /** True when a Skia surface built for {@code other} can draw this target unchanged. */
    boolean sameSurface(UiRenderTarget other) {
        return other != null && framebufferId == other.framebufferId && width == other.width
                && height == other.height && origin == other.origin;
    }
}
