package com.stonebreak.blocks.palisade;

import com.stonebreak.blocks.stairs.StairState.Facing;

/**
 * Parsed state of a spiked wall (the {@code stonebreak:palisade} block): which way its stakes lean.
 *
 * <p>Format: {@code palisade:facing=<NORTH|SOUTH|EAST|WEST>} — the {@code prefix:key=value;...}
 * convention stairs and stalagmites use. {@code facing} is the direction the points lean TOWARD,
 * so a wall leans away from whoever put it up: a player places it leaning the way they look, and a
 * goblin camp leans its wall out of the cave mouth. Unknown keys are ignored and a missing or
 * foreign string parses as SOUTH, the authored lean, so walls placed before this existed still draw.
 *
 * <p>Like {@link com.stonebreak.blocks.stairs.StairState}, the canonical string doubles as the
 * stamp cache key: the renderer registers the SBO model turned by each facing's quarter turns under
 * {@link #stateStringFor}. The model is authored leaning toward +Z, which is SOUTH.
 */
public record PalisadeState(Facing facing) {

    public static final String STATE_PREFIX = "palisade:";

    public PalisadeState {
        facing = facing == null ? Facing.SOUTH : facing;
    }

    /** True when {@code raw} is a spiked-wall state string. */
    public static boolean isPalisadeState(String raw) {
        return raw != null && raw.startsWith(STATE_PREFIX);
    }

    /** Tolerant of nulls, foreign prefixes and unknown keys: anything unreadable leans SOUTH. */
    public static PalisadeState parse(String raw) {
        Facing facing = Facing.SOUTH;
        if (isPalisadeState(raw)) {
            for (String pair : raw.substring(STATE_PREFIX.length()).split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "facing".equals(pair.substring(0, eq).trim())) {
                    facing = Facing.fromString(pair.substring(eq + 1).trim());
                }
            }
        }
        return new PalisadeState(facing);
    }

    /** Leaning along the horizontal direction {@code (dx, dz)}: the placer's look, or out of a camp. */
    public static PalisadeState leaningToward(float dx, float dz) {
        return new PalisadeState(Facing.fromLook(dx, dz));
    }

    /** The spiked wall at a horizontal offset from the cell being placed: its facing, or null when there is none. */
    @FunctionalInterface
    public interface Neighbours {
        Facing facingAt(int dx, int dz);
    }

    /**
     * The facing for a wall placed where {@code neighbours} are: of the four, the one that leans
     * the most stretches to the walls it joins the same way those walls lean them, nearest the
     * placer's look {@code (lookX, lookZ)} among equals. A wall laid along a winding line while
     * the placer looks about keeps one lean down its whole length; with nothing to join it is
     * just {@link #leaningToward}.
     */
    public static PalisadeState placedBeside(float lookX, float lookZ, Neighbours neighbours) {
        java.util.List<int[]> joined = new java.util.ArrayList<>();
        java.util.List<Facing> theirs = new java.util.ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Facing f = (dx == 0 && dz == 0) ? null : neighbours.facingAt(dx, dz);
                // A corner joins only when neither side cell between is a wall (see SB_Palisade's table).
                if (f == null || (dx != 0 && dz != 0
                        && (neighbours.facingAt(dx, 0) != null || neighbours.facingAt(0, dz) != null))) {
                    continue;
                }
                joined.add(new int[]{dx, dz});
                theirs.add(f);
            }
        }
        Facing best = Facing.fromLook(lookX, lookZ);
        int bestScore = -1;
        float bestLook = Float.NEGATIVE_INFINITY;
        for (Facing f : Facing.values()) {
            int score = 0;
            for (int i = 0; i < joined.size(); i++) {
                int[] d = joined.get(i);
                if (java.util.Arrays.equals(leanAlong(f, d[0], d[1]), leanAlong(theirs.get(i), d[0], d[1]))) {
                    score++;
                }
            }
            int[] v = vector(f);
            float look = v[0] * lookX + v[1] * lookZ;
            if (score > bestScore || (score == bestScore && look > bestLook)) {
                best = f;
                bestScore = score;
                bestLook = look;
            }
        }
        return new PalisadeState(best);
    }

    /**
     * Which way a stretch of wall running along {@code (ax, az)} (a side or corner direction) leans
     * in a block with {@code facing}: to the side of the stretch the facing points at, or, for a
     * stretch running along the facing itself, to the facing's clockwise side. It depends on the
     * facing and the direction alone, so two neighbours with the same facing always lean the stretch
     * between them alike. SB_Palisade's multipart pieces are built to this rule (its generator,
     * {@code gen_diagonal_wall.py}, documents the same table).
     *
     * @return the lean as an unnormalised {@code {x, z}} direction
     */
    public static int[] leanAlong(Facing facing, int ax, int az) {
        int px = -az;
        int pz = ax; // a perpendicular of the stretch
        int[] f = vector(facing);
        int side = px * f[0] + pz * f[1];
        if (side == 0) {
            int[] cw = {-f[1], f[0]}; // clockwise seen from above: S -> W -> N -> E
            side = px * cw[0] + pz * cw[1];
        }
        return side > 0 ? new int[]{px, pz} : new int[]{-px, -pz};
    }

    private static int[] vector(Facing facing) {
        return switch (facing) {
            case SOUTH -> new int[]{0, 1};
            case EAST -> new int[]{1, 0};
            case NORTH -> new int[]{0, -1};
            case WEST -> new int[]{-1, 0};
        };
    }

    public String toStateString() {
        return stateStringFor(facing);
    }

    /** The state string — and stamp cache key — for a lean. */
    public static String stateStringFor(Facing facing) {
        return STATE_PREFIX + "facing=" + facing.name();
    }
}
