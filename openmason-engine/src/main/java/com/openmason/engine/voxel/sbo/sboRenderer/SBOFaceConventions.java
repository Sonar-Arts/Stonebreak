package com.openmason.engine.voxel.sbo.sboRenderer;

/**
 * Single source of truth for face ID conventions used across the SBO pipeline.
 *
 * <p>Three coordinate systems interact in the SBO rendering pipeline:
 * <ul>
 *   <li><b>GMR</b> (Generic Model Renderer) — used by Open Mason during editing and in SBO file face mappings</li>
 *   <li><b>MMS</b> (Mighty Mesh System) — used at runtime for chunk mesh generation and face culling</li>
 *   <li><b>Atlas</b> — string-based face names used by the texture atlas metadata</li>
 * </ul>
 *
 * <p>This class provides conversions between all three systems and named constants
 * to eliminate magic numbers throughout the codebase.
 */
public final class SBOFaceConventions {

    private SBOFaceConventions() {}

    // ── GMR Face IDs (Open Mason / SBO file format) ──

    /** GMR face 0: Front face (+Z direction) */
    public static final int GMR_FRONT = 0;
    /** GMR face 1: Back face (-Z direction) */
    public static final int GMR_BACK = 1;
    /** GMR face 2: Left face (-X direction) */
    public static final int GMR_LEFT = 2;
    /** GMR face 3: Right face (+X direction) */
    public static final int GMR_RIGHT = 3;
    /** GMR face 4: Top face (+Y direction) */
    public static final int GMR_TOP = 4;
    /** GMR face 5: Bottom face (-Y direction) */
    public static final int GMR_BOTTOM = 5;

    // ── MMS Face IDs (Runtime chunk mesh generation) ──

    /** MMS face 0: Top face (+Y direction) */
    public static final int MMS_TOP = 0;
    /** MMS face 1: Bottom face (-Y direction) */
    public static final int MMS_BOTTOM = 1;
    /** MMS face 2: North face (-Z direction) */
    public static final int MMS_NORTH = 2;
    /** MMS face 3: South face (+Z direction) */
    public static final int MMS_SOUTH = 3;
    /** MMS face 4: East face (+X direction) */
    public static final int MMS_EAST = 4;
    /** MMS face 5: West face (-X direction) */
    public static final int MMS_WEST = 5;

    /** Total number of faces on a block. */
    public static final int FACE_COUNT = 6;

    // ── Atlas Face Names ──

    public static final String ATLAS_TOP = "top";
    public static final String ATLAS_BOTTOM = "bottom";
    public static final String ATLAS_NORTH = "north";
    public static final String ATLAS_SOUTH = "south";
    public static final String ATLAS_EAST = "east";
    public static final String ATLAS_WEST = "west";

    // ── GMR → MMS conversion ──

    /**
     * Static mapping table: GMR face index → MMS face index.
     * Index by GMR face ID to get corresponding MMS face ID.
     */
    private static final int[] GMR_TO_MMS = {
            MMS_SOUTH,   // GMR 0 (FRONT +Z)  → MMS 3 (SOUTH +Z)
            MMS_NORTH,   // GMR 1 (BACK -Z)   → MMS 2 (NORTH -Z)
            MMS_WEST,    // GMR 2 (LEFT -X)    → MMS 5 (WEST -X)
            MMS_EAST,    // GMR 3 (RIGHT +X)   → MMS 4 (EAST +X)
            MMS_TOP,     // GMR 4 (TOP +Y)     → MMS 0 (TOP +Y)
            MMS_BOTTOM   // GMR 5 (BOTTOM -Y)  → MMS 1 (BOTTOM -Y)
    };

    /**
     * Convert a GMR face ID to the equivalent MMS face ID.
     *
     * @param gmrFaceId GMR face index (0-5)
     * @return MMS face index (0-5)
     */
    public static int gmrToMms(int gmrFaceId) {
        return GMR_TO_MMS[Math.clamp(gmrFaceId, 0, 5)];
    }

    // ── MMS → Atlas name conversion ──

    /** Atlas face names indexed by MMS face ID. */
    private static final String[] MMS_TO_ATLAS_NAME = {
            ATLAS_TOP,     // MMS 0
            ATLAS_BOTTOM,  // MMS 1
            ATLAS_NORTH,   // MMS 2
            ATLAS_SOUTH,   // MMS 3
            ATLAS_EAST,    // MMS 4
            ATLAS_WEST     // MMS 5
    };

    /**
     * Convert an MMS face ID to the atlas face name string.
     *
     * @param mmsFaceId MMS face index (0-5)
     * @return atlas face name ("top", "bottom", "north", "south", "east", "west")
     */
    public static String mmsToAtlasName(int mmsFaceId) {
        return MMS_TO_ATLAS_NAME[Math.clamp(mmsFaceId, 0, 5)];
    }

    // ── Atlas name → GMR conversion ──

    /**
     * Convert an atlas face name to the equivalent GMR face ID.
     *
     * @param atlasName atlas face name
     * @return GMR face index (0-5), or -1 if the name is not recognized
     */
    public static int atlasNameToGmr(String atlasName) {
        return switch (atlasName) {
            case ATLAS_SOUTH -> GMR_FRONT;   // +Z
            case ATLAS_NORTH -> GMR_BACK;    // -Z
            case ATLAS_WEST -> GMR_LEFT;     // -X
            case ATLAS_EAST -> GMR_RIGHT;    // +X
            case ATLAS_TOP -> GMR_TOP;       // +Y
            case ATLAS_BOTTOM -> GMR_BOTTOM; // -Y
            default -> -1;
        };
    }

    // ── Atlas name → MMS conversion (convenience) ──

    /**
     * Convert an atlas face name directly to the MMS face ID.
     *
     * @param atlasName atlas face name
     * @return MMS face index (0-5), or -1 if the name is not recognized
     */
    public static int atlasNameToMms(String atlasName) {
        return switch (atlasName) {
            case ATLAS_TOP -> MMS_TOP;
            case ATLAS_BOTTOM -> MMS_BOTTOM;
            case ATLAS_NORTH -> MMS_NORTH;
            case ATLAS_SOUTH -> MMS_SOUTH;
            case ATLAS_EAST -> MMS_EAST;
            case ATLAS_WEST -> MMS_WEST;
            default -> -1;
        };
    }
}
