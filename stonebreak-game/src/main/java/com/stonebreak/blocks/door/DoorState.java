package com.stonebreak.blocks.door;

/**
 * Immutable parsed form of a door block's per-position state string.
 *
 * <p>Format: {@code door:state=<Open|Closed>;facing=<NORTH|SOUTH|EAST|WEST>}
 * — same {@code prefix:key=value;...} convention as the furnace state string.
 * The {@code state} value is the SBO state name (must match the state names
 * authored in {@code SB_Oak_Door.sbo}), which selects the animation clip; the
 * facing is fixed at placement and orients the dynamically drawn model.
 *
 * <p>Unknown keys are ignored and missing keys fall back to defaults
 * (Closed / NORTH), so the format is forward-compatible.
 */
public record DoorState(String renderState, Facing facing) {

    public static final String STATE_PREFIX = "door:";
    /** SBO state names — must match the states inside the door's .sbo. */
    public static final String OPEN = "Open";
    public static final String CLOSED = "Closed";

    /**
     * Which cell edge the closed panel rests against. {@code yawDegrees} spins
     * the model about the cell's vertical center axis; since the renderer's
     * base transform rotates about the model origin (the cell corner), the
     * anchor offset re-positions that origin so the rotation is effectively
     * center-of-cell: {@code position = corner + C - R(yaw)*C, C = (0.5, 0, 0.5)}.
     */
    public enum Facing {
        NORTH(0f, 0f, 0f),    // panel on the min-Z edge
        WEST(90f, 0f, 1f),    // min-X edge
        SOUTH(180f, 1f, 1f),  // max-Z edge
        EAST(270f, 1f, 0f);   // max-X edge

        private final float yawDegrees;
        private final float anchorOffsetX;
        private final float anchorOffsetZ;

        Facing(float yawDegrees, float anchorOffsetX, float anchorOffsetZ) {
            this.yawDegrees = yawDegrees;
            this.anchorOffsetX = anchorOffsetX;
            this.anchorOffsetZ = anchorOffsetZ;
        }

        public float yawDegrees() { return yawDegrees; }
        public float anchorOffsetX() { return anchorOffsetX; }
        public float anchorOffsetZ() { return anchorOffsetZ; }

        public static Facing fromString(String s) {
            if (s != null) {
                for (Facing f : values()) {
                    if (f.name().equalsIgnoreCase(s)) return f;
                }
            }
            return NORTH;
        }

        /**
         * The cell edge nearest to a viewer at {@code (fromX, fromZ)} —
         * placement puts the closed panel against the edge the placer is
         * standing on, so the door hugs the opening from their side.
         */
        public static Facing nearestEdge(double fromX, double fromZ, int cellX, int cellZ) {
            double dx = fromX - (cellX + 0.5);
            double dz = fromZ - (cellZ + 0.5);
            if (Math.abs(dx) > Math.abs(dz)) {
                return dx > 0 ? EAST : WEST;
            }
            return dz > 0 ? SOUTH : NORTH;
        }
    }

    public DoorState {
        renderState = OPEN.equalsIgnoreCase(renderState) ? OPEN : CLOSED;
        facing = facing == null ? Facing.NORTH : facing;
    }

    /** True when {@code raw} is a door state string. */
    public static boolean isDoorState(String raw) {
        return raw != null && raw.startsWith(STATE_PREFIX);
    }

    /**
     * Parse a raw state string; tolerant of nulls, foreign prefixes and
     * unknown keys — anything unreadable falls back to Closed / NORTH.
     */
    public static DoorState parse(String raw) {
        String state = CLOSED;
        Facing facing = Facing.NORTH;
        if (isDoorState(raw)) {
            for (String pair : raw.substring(STATE_PREFIX.length()).split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                switch (key) {
                    case "state" -> state = value;
                    case "facing" -> facing = Facing.fromString(value);
                    default -> { /* forward-compat: ignore unknown keys */ }
                }
            }
        }
        return new DoorState(state, facing);
    }

    public String toStateString() {
        return STATE_PREFIX + "state=" + renderState + ";facing=" + facing.name();
    }

    public boolean isOpen() {
        return OPEN.equals(renderState);
    }

    /** The opposite door state with the same facing. */
    public DoorState toggled() {
        return new DoorState(isOpen() ? CLOSED : OPEN, facing);
    }

    /** Initial state written at placement: closed, panel on the placer's edge. */
    public static DoorState placed(double placerX, double placerZ, int cellX, int cellZ) {
        return new DoorState(CLOSED, Facing.nearestEdge(placerX, placerZ, cellX, cellZ));
    }

    // ---------- model-tied collision ----------

    /**
     * Transform a model-space AABB (from {@code AnimatedBlockShapes} — the
     * geometry posed at this state's clip end pose) into world space: the
     * facing rotation about the model origin, then the cell anchor offset.
     * Exactly the base transform the renderer places the model with, so the
     * box always wraps what is drawn.
     *
     * @param modelBox {@code {minX,minY,minZ,maxX,maxY,maxZ}} in model space
     * @return world-space {@code {minX,minY,minZ,maxX,maxY,maxZ}}
     */
    public float[] modelBoxToWorld(float[] modelBox, int blockX, int blockY, int blockZ) {
        float[] a = rotateByFacing(modelBox[0], modelBox[2]);
        float[] b = rotateByFacing(modelBox[3], modelBox[5]);
        float baseX = blockX + facing.anchorOffsetX();
        float baseZ = blockZ + facing.anchorOffsetZ();
        return new float[]{
                baseX + Math.min(a[0], b[0]), blockY + modelBox[1], baseZ + Math.min(a[1], b[1]),
                baseX + Math.max(a[0], b[0]), blockY + modelBox[4], baseZ + Math.max(a[1], b[1])
        };
    }

    /** Rotate a model-space (x, z) by the facing yaw (JOML rotateY convention). */
    private float[] rotateByFacing(float x, float z) {
        return switch (facing) {
            case NORTH -> new float[]{x, z};
            case WEST -> new float[]{z, -x};   // +90°
            case SOUTH -> new float[]{-x, -z}; // 180°
            case EAST -> new float[]{-z, x};   // 270°
        };
    }
}
