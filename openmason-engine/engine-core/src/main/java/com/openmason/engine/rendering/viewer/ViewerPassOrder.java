package com.openmason.engine.rendering.viewer;

/**
 * Standard {@link ViewerPass#order()} values.
 *
 * <p>The numbers mirror the model editor's original hardcoded pass sequence one-for-one,
 * including its half-step: socket previews sit between the content and mesh-overlay
 * tiers, which is why the scale is spaced in hundreds rather than 1..n.
 */
public final class ViewerPassOrder {

    /** Infinite grid backdrop. */
    public static final int GRID = 100;

    /** The models themselves. */
    public static final int CONTENT = 200;

    /** Depth-tested extras drawn with the content (e.g. socket test models). */
    public static final int CONTENT_OVERLAY = 250;

    /** Editing overlays: vertices, edges, faces, tool previews. */
    public static final int MESH_OVERLAY = 300;

    /** Transform gizmo. */
    public static final int GIZMO = 400;

    /** X-ray overlays drawn last: bones, attachment markers. */
    public static final int XRAY_OVERLAY = 500;

    private ViewerPassOrder() {
        throw new AssertionError("ViewerPassOrder is a constants holder");
    }
}
