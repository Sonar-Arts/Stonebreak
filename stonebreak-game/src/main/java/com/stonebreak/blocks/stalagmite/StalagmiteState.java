package com.stonebreak.blocks.stalagmite;

import com.stonebreak.blocks.stairs.StairState.Facing;

/**
 * Parsed state of a stalagmite's anchor cell.
 *
 * <p>Format: {@code stalagmite:size=<1..3>;hanging=<true|false>;facing=<NORTH|SOUTH|EAST|WEST>}
 * — the {@code prefix:key=value;...} convention stairs and doors use. Unknown keys are
 * ignored, and the SBO's own state names ({@code Stalagmite1..3}) still parse as an upright,
 * south-facing stalagmite of that size, as does a missing state (a freshly placed item).
 *
 * <p>Like {@link com.stonebreak.blocks.stairs.StairState}, the canonical string doubles as
 * the stamp cache key: the renderer registers each size's SBO model flipped and rotated under
 * {@link #toStateString()}, so orientation needs no SBO states of its own. {@code facing}
 * reuses the stair {@link Facing} and its quarter turns; the models are authored facing SOUTH.
 */
public record StalagmiteState(int size, boolean hanging, Facing facing) {

    public static final String STATE_PREFIX = "stalagmite:";
    private static final String SBO_STATE_PREFIX = "Stalagmite";

    public StalagmiteState {
        size = Math.max(1, Math.min(Stalagmite.MAX_SIZE, size));
        facing = facing == null ? Facing.SOUTH : facing;
    }

    public static StalagmiteState parse(String raw) {
        if (raw == null) {
            return new StalagmiteState(1, false, Facing.SOUTH);
        }
        if (raw.startsWith(STATE_PREFIX)) {
            int size = 1;
            boolean hanging = false;
            Facing facing = Facing.SOUTH;
            for (String pair : raw.substring(STATE_PREFIX.length()).split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                switch (key) {
                    case "size" -> {
                        try {
                            size = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                            size = 1;
                        }
                    }
                    case "hanging" -> hanging = Boolean.parseBoolean(value);
                    case "facing" -> facing = Facing.fromString(value);
                    default -> { /* forward-compat */ }
                }
            }
            return new StalagmiteState(size, hanging, facing);
        }
        if (raw.length() == SBO_STATE_PREFIX.length() + 1 && raw.startsWith(SBO_STATE_PREFIX)) {
            int size = raw.charAt(SBO_STATE_PREFIX.length()) - '0';
            if (size >= 1 && size <= Stalagmite.MAX_SIZE) {
                return new StalagmiteState(size, false, Facing.SOUTH);
            }
        }
        return new StalagmiteState(1, false, Facing.SOUTH);
    }

    /** The SBO state whose model this size draws. */
    public String sboStateName() {
        return SBO_STATE_PREFIX + size;
    }

    /** +1 when the stalagmite extends upward from its anchor, −1 when it hangs down. */
    public int direction() {
        return hanging ? -1 : 1;
    }

    public StalagmiteState withSize(int newSize) {
        return new StalagmiteState(newSize, hanging, facing);
    }

    public String toStateString() {
        return STATE_PREFIX + "size=" + size + ";hanging=" + hanging + ";facing=" + facing.name();
    }

    /**
     * State written at placement: hanging when the underside of a block was clicked, turned to
     * face the way the placer is looking.
     */
    public static StalagmiteState placed(boolean hanging, float lookX, float lookZ) {
        return new StalagmiteState(1, hanging, Facing.fromLook(lookX, lookZ));
    }
}
