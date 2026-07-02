package com.openmason.main.systems.rendering.model.gmr.subrenders;

import org.joml.Vector3f;

/**
 * Central color/size constants for edit-mode mesh overlays (vertices, edges, faces).
 * Values follow Blender's default dark theme so the viewport reads the same way:
 * unselected elements are near-black, selected elements are orange (#FF8500 family),
 * and the active element (last selected) is white.
 *
 * <p>Vectors are exposed as constants and must not be mutated by callers.
 */
public final class MeshOverlayTheme {

    private MeshOverlayTheme() {
    }

    // ── Vertices ──
    /** Unselected vertex (Blender: black). */
    public static final Vector3f VERTEX = new Vector3f(0.0f, 0.0f, 0.0f);
    /** Selected vertex (Blender vertex_select: #FF8500). */
    public static final Vector3f VERTEX_SELECT = new Vector3f(1.0f, 0.522f, 0.0f);
    /** Active vertex — last selected (Blender: white). */
    public static final Vector3f VERTEX_ACTIVE = new Vector3f(1.0f, 1.0f, 1.0f);
    /** Hovered vertex pre-highlight (not a Blender concept; light orange for affordance). */
    public static final Vector3f VERTEX_HOVER = new Vector3f(1.0f, 0.75f, 0.35f);
    /** Vertex modified by an operation but not selected (tool-specific, muted gold). */
    public static final Vector3f VERTEX_MODIFIED = new Vector3f(0.85f, 0.7f, 0.25f);

    // ── Edges ──
    /** Unselected edit-mode wire (Blender wire_edit: near-black). */
    public static final Vector3f EDGE = new Vector3f(0.03f, 0.03f, 0.03f);
    /** Selected edge (Blender edge_select: #FFA000). */
    public static final Vector3f EDGE_SELECT = new Vector3f(1.0f, 0.627f, 0.0f);
    /** Active edge — last selected (Blender: white). */
    public static final Vector3f EDGE_ACTIVE = new Vector3f(1.0f, 1.0f, 1.0f);
    /** Hovered edge pre-highlight. */
    public static final Vector3f EDGE_HOVER = new Vector3f(1.0f, 0.78f, 0.3f);

    // ── Faces ──
    /** Selected face fill (Blender face_select: orange, translucent). */
    public static final Vector3f FACE_SELECT_FILL = new Vector3f(1.0f, 0.522f, 0.0f);
    public static final float FACE_SELECT_ALPHA = 0.28f;
    /** Active face fill (Blender editmesh_active: white, faint). */
    public static final Vector3f FACE_ACTIVE_FILL = new Vector3f(1.0f, 1.0f, 1.0f);
    public static final float FACE_ACTIVE_ALPHA = 0.18f;
    /** Hovered face fill pre-highlight. */
    public static final Vector3f FACE_HOVER_FILL = new Vector3f(1.0f, 0.78f, 0.3f);
    public static final float FACE_HOVER_ALPHA = 0.14f;
    /** Selected face boundary outline. */
    public static final Vector3f FACE_SELECT_OUTLINE = new Vector3f(1.0f, 0.627f, 0.0f);
    /** Active face boundary outline. */
    public static final Vector3f FACE_ACTIVE_OUTLINE = new Vector3f(1.0f, 1.0f, 1.0f);
    /** Hovered face boundary outline. */
    public static final Vector3f FACE_HOVER_OUTLINE = new Vector3f(1.0f, 0.78f, 0.3f);

    // ── Face dots (face-mode centroids, Blender facedot) ──
    /** Unselected face dot. */
    public static final Vector3f FACE_DOT = new Vector3f(0.0f, 0.0f, 0.0f);
    /** Selected face dot (Blender face_dot: #FF8500). */
    public static final Vector3f FACE_DOT_SELECT = new Vector3f(1.0f, 0.522f, 0.0f);
    /** Active face dot. */
    public static final Vector3f FACE_DOT_ACTIVE = new Vector3f(1.0f, 1.0f, 1.0f);
    /** Face dot point size in pixels. */
    public static final float FACE_DOT_SIZE = 5.0f;
    /** Hovered face dot size in pixels. */
    public static final float FACE_DOT_HOVER_SIZE = 6.5f;
}
