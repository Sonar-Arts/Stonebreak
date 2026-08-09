package com.stonebreak.blocks.stairs;

/**
 * Immutable parsed form of a stair block's per-position state string.
 *
 * <p>Format: {@code stairs:facing=<NORTH|SOUTH|EAST|WEST>} — the same
 * {@code prefix:key=value;...} convention the furnace and door states use.
 * Unknown keys are ignored and a missing facing falls back to SOUTH, so the
 * format is forward-compatible and a stair placed before this system existed
 * still renders (as the model's authored orientation).
 *
 * <p>{@code facing} is the direction the staircase <em>ascends</em> toward, so
 * a stair placed while looking north lets you walk up it heading north — the
 * tall, full-height side is the north side.
 *
 * <p>The state string doubles as the SBO stamp cache key: the renderer
 * registers one pre-rotated stamp per facing under {@link #toStateString()},
 * so the chunk mesher's existing "state name selects a mesh variant" path
 * orients stairs with no per-instance work.
 */
public record StairState(Facing facing) {

    public static final String STATE_PREFIX = "stairs:";

    /**
     * Which way the staircase climbs. {@code quarterTurns} is how far the
     * authored model must rotate about +Y to reach this facing; the model is
     * authored ascending toward +Z, which is SOUTH.
     */
    public enum Facing {
        SOUTH(0),
        EAST(1),
        NORTH(2),
        WEST(3);

        private final int quarterTurns;

        Facing(int quarterTurns) {
            this.quarterTurns = quarterTurns;
        }

        /** Quarter turns about +Y from the authored (SOUTH-ascending) model. */
        public int quarterTurns() {
            return quarterTurns;
        }

        public static Facing fromString(String s) {
            if (s != null) {
                for (Facing f : values()) {
                    if (f.name().equalsIgnoreCase(s)) return f;
                }
            }
            return SOUTH;
        }

        /**
         * The horizontal direction a viewer with this camera yaw is looking.
         * Yaw follows the camera convention used everywhere in the game:
         * {@code front = (cos yaw, _, sin yaw)}, so 0° is +X (east) and 90° is
         * +Z (south).
         */
        public static Facing fromYaw(float yawDegrees) {
            double yaw = Math.toRadians(yawDegrees);
            return fromLook((float) Math.cos(yaw), (float) Math.sin(yaw));
        }

        /** The horizontal direction a look vector points at. */
        public static Facing fromLook(float dx, float dz) {
            if (Math.abs(dx) > Math.abs(dz)) {
                return dx > 0 ? EAST : WEST;
            }
            return dz > 0 ? SOUTH : NORTH;
        }
    }

    public StairState {
        facing = facing == null ? Facing.SOUTH : facing;
    }

    /** True when {@code raw} is a stair state string. */
    public static boolean isStairState(String raw) {
        return raw != null && raw.startsWith(STATE_PREFIX);
    }

    /**
     * Parse a raw state string; tolerant of nulls, foreign prefixes and unknown
     * keys — anything unreadable falls back to SOUTH.
     */
    public static StairState parse(String raw) {
        Facing facing = Facing.SOUTH;
        if (isStairState(raw)) {
            for (String pair : raw.substring(STATE_PREFIX.length()).split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                if ("facing".equals(key)) {
                    facing = Facing.fromString(value);
                }
                // forward-compat: ignore unknown keys
            }
        }
        return new StairState(facing);
    }

    public String toStateString() {
        return stateStringFor(facing);
    }

    /** The state string — and stamp cache key — for a facing. */
    public static String stateStringFor(Facing facing) {
        return STATE_PREFIX + "facing=" + facing.name();
    }

    /** State written at placement: ascending the way the placer is looking. */
    public static StairState placedFromYaw(float placerYawDegrees) {
        return new StairState(Facing.fromYaw(placerYawDegrees));
    }

    /** Same, for callers that hold the placer's look vector rather than a yaw. */
    public static StairState placedFromLook(float lookX, float lookZ) {
        return new StairState(Facing.fromLook(lookX, lookZ));
    }

    // ---------- model-space queries ----------

    /**
     * Map a point given in this stair's placed cell (both coordinates in
     * {@code [0,1]}, relative to the cell's min corner) back into the authored
     * model's cell space, so shape lookups only ever need the one un-rotated
     * profile.
     *
     * @return {@code {x, z}} in the authored model's cell space
     */
    public float[] toModelCell(float cellX, float cellZ) {
        float u = cellX - 0.5f;
        float v = cellZ - 0.5f;
        // Inverse of the JOML rotateY the renderer bakes into the stamp.
        float[] rotated = switch (facing) {
            case SOUTH -> new float[]{u, v};
            case EAST -> new float[]{-v, u};
            case NORTH -> new float[]{-u, -v};
            case WEST -> new float[]{v, -u};
        };
        return new float[]{rotated[0] + 0.5f, rotated[1] + 0.5f};
    }
}
