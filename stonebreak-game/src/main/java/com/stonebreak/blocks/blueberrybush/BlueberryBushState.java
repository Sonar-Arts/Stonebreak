package com.stonebreak.blocks.blueberrybush;

/**
 * Parsed form of a blueberry bush's per-position state string.
 *
 * <p>Format: {@code blueberry_bush:state=<SBO state>;grow=<ticks>}. The {@code state} value is
 * the SBO state name, so {@code BlockRenderState.meshVariantKey} projects the string straight to
 * the stage's baked mesh. {@code grow} is the time left in the stage; it changes every save
 * interval but never affects the mesh. A ripe bush has no timer and omits it.
 *
 * <p>Unknown keys are ignored. A string with the wrong prefix or no readable stage parses to
 * {@code null}.
 */
public record BlueberryBushState(Stage stage, int ticksLeft) {

    public static final String STATE_PREFIX = "blueberry_bush:";
    /** Written for a stage with no timer (Ripe waits for a harvest). */
    public static final int NO_TIMER = -1;

    /**
     * Growth stages in order. Each name must match a state in {@code SB_Blueberry_Bush.sbo}.
     * The first four grow the bush; the last three ripen its berries.
     */
    public enum Stage {
        SPROUT("Sprout"),
        YOUNG("Young"),
        BUSHY("Bushy"),
        MATURE("Mature"),
        BERRIES_UNRIPE("Berries_Unripe"),
        BERRIES_RIPENING("Berries_Ripening"),
        BERRIES_RIPE("Berries_Ripe");

        private final String sboStateName;

        Stage(String sboStateName) {
            this.sboStateName = sboStateName;
        }

        public String sboStateName() {
            return sboStateName;
        }

        /** The stage this one grows into, or {@code null} for Ripe (it waits to be picked). */
        public Stage next() {
            return this == BERRIES_RIPE ? null : values()[ordinal() + 1];
        }

        /** True while the bush itself is still growing (before Mature). */
        public boolean isGrowing() {
            return ordinal() < MATURE.ordinal();
        }

        public static Stage fromSboName(String name) {
            for (Stage s : values()) {
                if (s.sboStateName.equalsIgnoreCase(name)) {
                    return s;
                }
            }
            return null;
        }
    }

    public BlueberryBushState {
        if (stage == null) {
            throw new IllegalArgumentException("stage");
        }
        ticksLeft = stage.next() == null ? NO_TIMER : Math.max(0, ticksLeft);
    }

    public boolean isRipe() {
        return stage == Stage.BERRIES_RIPE;
    }

    public static boolean isBushState(String raw) {
        return raw != null && raw.startsWith(STATE_PREFIX);
    }

    /** Parses a raw state string, or returns {@code null} when it names no bush stage. */
    public static BlueberryBushState parse(String raw) {
        if (!isBushState(raw)) {
            return null;
        }
        Stage stage = null;
        int ticks = 0;
        for (String pair : raw.substring(STATE_PREFIX.length()).split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            switch (key) {
                case "state" -> stage = Stage.fromSboName(value);
                case "grow" -> ticks = parseTicks(value);
                default -> { /* forward-compat: ignore unknown keys */ }
            }
        }
        return stage == null ? null : new BlueberryBushState(stage, ticks);
    }

    public String toStateString() {
        String base = STATE_PREFIX + "state=" + stage.sboStateName();
        return ticksLeft == NO_TIMER ? base : base + ";grow=" + ticksLeft;
    }

    private static int parseTicks(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0; // unreadable timer: grow on the next tick rather than never
        }
    }
}
