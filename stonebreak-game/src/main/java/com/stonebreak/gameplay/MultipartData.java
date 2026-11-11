package com.stonebreak.gameplay;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A block's neighbour-driven shape ({@code multipart} in an SBO): a list of parts, each one of the
 * SBO's own states, drawn whenever its conditions on the six neighbours all hold. A fence is a post
 * with no conditions plus one arm per side ({@code north: tag:stonebreak:fence_connectable}). Rounded
 * leaves are flat faces, edge strips and corner caps keyed on which neighbours are full cubes.
 *
 * <p>The data says nothing about geometry: every part's mesh is an ordinary SBO state, authored
 * like any other. {@code com.stonebreak.blocks.multipart.MultipartShape} compiles this table.
 *
 * @param parts the parts, in draw order; never null, never empty for a stored table
 */
public record MultipartData(List<Part> parts) {

    /** Most distinct (side, test) pairs one block may test: 2^12 = 4096 resolved shapes. */
    public static final int MAX_ATOMS = 12;

    /** Tallest a collision box may reach, in blocks above the cell floor (a fence's 1.5). */
    public static final float MAX_BOX_HEIGHT = 1.5f;

    public MultipartData {
        parts = parts == null ? Collections.emptyList() : List.copyOf(parts);
    }

    /** The six neighbours, in the chunk mesher's face order (the ordinal is the face index). */
    public enum Side {
        UP(0, 1, 0), DOWN(0, -1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), EAST(1, 0, 0), WEST(-1, 0, 0);

        public final int dx;
        public final int dy;
        public final int dz;

        Side(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        /** The manifest key: {@code "up"}, {@code "north"}... */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The side for a manifest key, or null when it names none. */
        public static Side ofKey(String key) {
            if (key == null) return null;
            for (Side s : values()) {
                if (s.key().equals(key.strip().toLowerCase(Locale.ROOT))) return s;
            }
            return null;
        }
    }

    /** What a condition asks of a neighbour. */
    public enum Test {
        /** The neighbour fills its cell as a cube (the mesher's cube class). */
        FULL_CUBE,
        /** The neighbour is this very block type. */
        SAME,
        /** The neighbour lists {@link Condition#tag()} among its {@code connectTags}. */
        TAG
    }

    /**
     * One test on one neighbour. Written {@code full_cube}, {@code same} or {@code tag:<id>}, with a
     * leading {@code !} to negate.
     *
     * @param test   what to ask
     * @param tag    the tag for {@link Test#TAG}; null otherwise
     * @param negate true to require the test to fail
     */
    public record Condition(Test test, String tag, boolean negate) {

        private static final String TAG_PREFIX = "tag:";

        public Condition {
            Objects.requireNonNull(test, "test");
            if (test == Test.TAG) {
                tag = tag == null ? "" : tag.strip();
                if (tag.isEmpty()) throw new IllegalArgumentException("tag condition needs a tag");
            } else {
                tag = null;
            }
        }

        /** Parses {@code [!]full_cube | [!]same | [!]tag:<id>}. */
        public static Condition parse(String text) {
            String t = text == null ? "" : text.strip();
            boolean negate = t.startsWith("!");
            if (negate) t = t.substring(1).strip();
            if (t.equalsIgnoreCase("full_cube")) return new Condition(Test.FULL_CUBE, null, negate);
            if (t.equalsIgnoreCase("same")) return new Condition(Test.SAME, null, negate);
            if (t.regionMatches(true, 0, TAG_PREFIX, 0, TAG_PREFIX.length())) {
                return new Condition(Test.TAG, t.substring(TAG_PREFIX.length()), negate);
            }
            throw new IllegalArgumentException("unknown neighbour condition '" + text + "'");
        }

        /** The manifest form, the inverse of {@link #parse}. */
        public String encode() {
            String body = switch (test) {
                case FULL_CUBE -> "full_cube";
                case SAME -> "same";
                case TAG -> TAG_PREFIX + tag;
            };
            return negate ? "!" + body : body;
        }
    }

    /**
     * A collision box in cell space: x and z in {@code [0, 1]}, y in {@code [0, MAX_BOX_HEIGHT]}.
     */
    public record Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        public Box {
            check(minX, maxX, 1f, "x");
            check(minY, maxY, MAX_BOX_HEIGHT, "y");
            check(minZ, maxZ, 1f, "z");
        }

        private static void check(float min, float max, float limit, String axis) {
            if (!Float.isFinite(min) || !Float.isFinite(max) || min < 0f || max > limit || min >= max) {
                throw new IllegalArgumentException("box " + axis + " range [" + min + ", " + max
                        + "] must satisfy 0 <= min < max <= " + limit);
            }
        }
    }

    /**
     * One part: an SBO state drawn when every listed side passes its condition. A side not listed
     * matches anything, so a part with no conditions is always drawn.
     *
     * @param state the SBO state whose model this part draws; never blank
     * @param when  per-side conditions; never null
     * @param boxes collision boxes this part adds; never null, may be empty
     */
    public record Part(String state, Map<Side, Condition> when, List<Box> boxes) {
        public Part {
            Objects.requireNonNull(state, "state");
            state = state.strip();
            if (state.isEmpty()) throw new IllegalArgumentException("part state must not be blank");
            EnumMap<Side, Condition> copy = new EnumMap<>(Side.class);
            if (when != null) copy.putAll(when);
            when = Collections.unmodifiableMap(copy);
            boxes = boxes == null ? Collections.emptyList() : List.copyOf(boxes);
        }
    }
}
