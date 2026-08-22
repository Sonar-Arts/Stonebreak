package com.stonebreak.blocks.torch;

import org.joml.Vector3f;

/**
 * Immutable parsed form of a placed torch's per-position state string.
 *
 * <p>Format: {@code torch:state=<Ground|Side>;facing=<NORTH|SOUTH|EAST|WEST>}
 * — the same {@code prefix:key=value;...} convention as doors and stairs. The
 * {@code state} value is the SBO state name (must match the states authored in
 * {@code SB_Torch_Placed.sbo}) and selects the clip the renderer plays; the
 * {@code facing} names the WALL a side torch hangs from and is only meaningful
 * for the {@code Side} state (ground torches always parse as NORTH).
 *
 * <p>The torch model is authored centered on the cell's vertical axis (x/z in
 * [-0.5, 0.5]) with the {@code Side} pose leaning off the min-Z wall, so
 * rotating the model about its own origin IS rotating it about the cell center
 * and every facing is a pure yaw: NORTH 0°, WEST 90°, SOUTH 180°, EAST 270°
 * (JOML {@code rotateY}: +90° maps model −Z onto −X).
 */
public record TorchState(String renderState, Facing facing) {

    public static final String STATE_PREFIX = "torch:";
    /** SBO state names — must match the states inside SB_Torch_Placed.sbo. */
    public static final String GROUND = "Ground";
    public static final String SIDE = "Side";

    /** Half-width of the targeting / collision box (the model is only 2 px wide). */
    private static final float TARGET_HALF = 0.1875f;

    /** The wall a side torch is attached to, expressed as the cell edge it touches. */
    public enum Facing {
        NORTH(0f, 0, -1),   // hangs from the block at −Z
        WEST(90f, -1, 0),   // hangs from the block at −X
        SOUTH(180f, 0, 1),  // hangs from the block at +Z
        EAST(270f, 1, 0);   // hangs from the block at +X

        private final float yawDegrees;
        private final int wallDx;
        private final int wallDz;

        Facing(float yawDegrees, int wallDx, int wallDz) {
            this.yawDegrees = yawDegrees;
            this.wallDx = wallDx;
            this.wallDz = wallDz;
        }

        public float yawDegrees() { return yawDegrees; }
        /** Offset from the torch cell to the block it hangs from. */
        public int wallDx() { return wallDx; }
        public int wallDz() { return wallDz; }

        public static Facing fromString(String s) {
            if (s != null) {
                for (Facing f : values()) {
                    if (f.name().equalsIgnoreCase(s)) return f;
                }
            }
            return NORTH;
        }

        /**
         * The facing for a torch placed against the side face whose outward
         * normal is {@code (nx, nz)}: the wall lies opposite the normal.
         */
        public static Facing fromSideNormal(int nx, int nz) {
            if (nx > 0) return WEST;
            if (nx < 0) return EAST;
            if (nz > 0) return NORTH;
            return SOUTH;
        }
    }

    public TorchState {
        renderState = SIDE.equalsIgnoreCase(renderState) ? SIDE : GROUND;
        facing = (facing == null || GROUND.equals(renderState)) ? Facing.NORTH : facing;
    }

    public static TorchState ground() {
        return new TorchState(GROUND, Facing.NORTH);
    }

    public static TorchState side(Facing facing) {
        return new TorchState(SIDE, facing);
    }

    /**
     * The state a torch takes when placed onto the face of a block whose outward
     * normal is {@code (nx, ny, nz)}, or {@code null} when that face cannot hold
     * a torch (the underside of a block).
     */
    public static TorchState forPlacementNormal(int nx, int ny, int nz) {
        if (ny > 0) return ground();
        if (ny < 0) return null;
        return side(Facing.fromSideNormal(nx, nz));
    }

    /** True when {@code raw} is a torch state string. */
    public static boolean isTorchState(String raw) {
        return raw != null && raw.startsWith(STATE_PREFIX);
    }

    /**
     * Parse a raw state string; tolerant of nulls, foreign prefixes and unknown
     * keys — anything unreadable falls back to a ground torch.
     */
    public static TorchState parse(String raw) {
        String state = GROUND;
        Facing facing = Facing.NORTH;
        if (isTorchState(raw)) {
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
        return new TorchState(state, facing);
    }

    public String toStateString() {
        return STATE_PREFIX + "state=" + renderState + ";facing=" + facing.name();
    }

    public boolean isSide() {
        return SIDE.equals(renderState);
    }

    /** Yaw the renderer spins the (cell-centered) model by. */
    public float yawDegrees() {
        return isSide() ? facing.yawDegrees() : 0f;
    }

    /** Offset from the torch cell to the block that supports it (below, or the wall). */
    public int supportDx() { return isSide() ? facing.wallDx() : 0; }
    public int supportDy() { return isSide() ? 0 : -1; }
    public int supportDz() { return isSide() ? facing.wallDz() : 0; }

    /**
     * World-space targeting/breaking box {@code {minX,minY,minZ,maxX,maxY,maxZ}}.
     * Deliberately fatter than the 2 px model so the torch is hittable — the
     * ground torch is a 6 px column, the side torch a slanted 6 px box hugging
     * its wall (the model leans 22.5° into the cell from 1 px inside the wall).
     */
    public float[] worldAabb(int x, int y, int z) {
        float cx = x + 0.5f;
        float cz = z + 0.5f;
        if (!isSide()) {
            return new float[]{cx - TARGET_HALF, y, cz - TARGET_HALF,
                               cx + TARGET_HALF, y + 0.625f, cz + TARGET_HALF};
        }
        // Authored (NORTH): base at z ≈ −0.44, tip reaching z ≈ −0.09, y 0.13..0.76.
        float near = -0.5f;   // wall edge
        float far = -0.05f;   // how far into the cell the lean reaches
        float minY = y + 0.125f;
        float maxY = y + 0.8125f;
        return switch (facing) {
            case NORTH -> new float[]{cx - TARGET_HALF, minY, cz + near, cx + TARGET_HALF, maxY, cz + far};
            case SOUTH -> new float[]{cx - TARGET_HALF, minY, cz - far, cx + TARGET_HALF, maxY, cz - near};
            case WEST -> new float[]{cx + near, minY, cz - TARGET_HALF, cx + far, maxY, cz + TARGET_HALF};
            case EAST -> new float[]{cx - far, minY, cz - TARGET_HALF, cx - near, maxY, cz + TARGET_HALF};
        };
    }

    /**
     * World-space position of the glowing ember — where the light is emitted
     * from. Ground: top of the 10 px stick. Side: the leaning stick's head,
     * ~0.2 blocks off the wall.
     */
    public Vector3f emberPosition(int x, int y, int z, Vector3f out) {
        float cx = x + 0.5f;
        float cz = z + 0.5f;
        if (!isSide()) {
            return out.set(cx, y + 0.6f, cz);
        }
        // Authored (NORTH): ember center at roughly (0, 0.68, −0.22).
        float off = 0.22f;
        float ey = y + 0.68f;
        return switch (facing) {
            case NORTH -> out.set(cx, ey, cz - off);
            case SOUTH -> out.set(cx, ey, cz + off);
            case WEST -> out.set(cx - off, ey, cz);
            case EAST -> out.set(cx + off, ey, cz);
        };
    }
}
