package com.stonebreak.blocks.multipart;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.gameplay.MultipartData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One block type's compiled {@link MultipartData}: the neighbour tests it makes, and for every
 * combination of their answers the parts it draws and the collision boxes it has.
 *
 * <p><b>Atoms.</b> Each distinct (side, test) pair the table uses is one atom, i.e. one bit of a
 * cell's <em>mask</em>. Rounded leaves ask {@code full_cube} of all six sides: six atoms, 64 masks.
 * A fence asks one tag of four sides: four atoms, 16 masks. Every mask is resolved here once, so
 * a cell costs one neighbour read per atom and an array lookup.
 *
 * <p>Negation does not add an atom: {@code !full_cube} reads the same bit as {@code full_cube}.
 *
 * <p><b>Facing.</b> The table is written in the model's own frame. A block whose model turns with
 * a stored facing (the spiked wall) passes its quarter turns to {@link #evaluate(Neighbours, int,
 * int, int, int)}: each side is turned the same way the stamp is
 * ({@code StampRotator.rotateY}), so "east" in the table is whatever the turned model's east
 * points at, and {@link #key(int, int)} names the composite baked turned that far.
 */
public final class MultipartShape {

    /** Prefix of the stamp-cache key a resolved shape is registered under. */
    public static final String KEY_PREFIX = "mp:";

    /** A block lookup around the cell being resolved, in whatever coordinates the caller uses. */
    @FunctionalInterface
    public interface Neighbours {
        BlockType at(long x, long y, long z);
    }

    private record Atom(MultipartData.Side side, MultipartData.Test test, String tag) {
    }

    private final BlockType owner;
    private final Predicate<BlockType> fullCube;
    private final Atom[] atoms;
    /** Per atom: for TAG atoms, which block ids carry the tag; null otherwise. */
    private final boolean[][] tagMembers;
    private final List<MultipartData.Part> parts;
    /** Per part: the atom bits it constrains, and the values they must have. */
    private final int[] partCare;
    private final int[] partWant;
    /** Per quarter turn, per mask: the stamp-cache key. */
    private final String[][] keys;
    /** Per quarter turn, per atom: the world offset its side points at, {dx, dy, dz}. */
    private final int[][][] offsets;
    /** Per mask: the drawn parts' states, in table order. */
    private final List<List<String>> states;
    /** Per quarter turn, per mask: 6 floats per collision box (min xyz, max xyz); empty when none. */
    private final float[][][] boxes;
    private final boolean anyBoxes;

    /**
     * @param owner    the block this shape belongs to ({@code same} compares against it)
     * @param data     its table
     * @param fullCube what counts as a full cube neighbour
     * @param tagsOf   a block's {@code connectTags} (never null)
     * @throws IllegalArgumentException when the table uses more than {@link MultipartData#MAX_ATOMS} atoms
     */
    public MultipartShape(BlockType owner, MultipartData data, Predicate<BlockType> fullCube,
                          Function<BlockType, List<String>> tagsOf) {
        this.owner = owner;
        this.fullCube = fullCube;
        this.parts = data.parts();

        Map<Atom, Integer> index = new LinkedHashMap<>();
        for (MultipartData.Part part : parts) {
            part.when().forEach((side, c) -> index.putIfAbsent(new Atom(side, c.test(), c.tag()), index.size()));
        }
        if (index.size() > MultipartData.MAX_ATOMS) {
            throw new IllegalArgumentException(owner + ": multipart tests " + index.size()
                    + " (side, test) pairs; at most " + MultipartData.MAX_ATOMS + " are allowed");
        }
        this.atoms = index.keySet().toArray(new Atom[0]);

        int maxId = 0;
        for (BlockType t : BlockType.values()) maxId = Math.max(maxId, t.getId());
        this.tagMembers = new boolean[atoms.length][];
        for (int a = 0; a < atoms.length; a++) {
            if (atoms[a].test() != MultipartData.Test.TAG) continue;
            boolean[] members = new boolean[maxId + 1];
            for (BlockType t : BlockType.values()) {
                if (t.getId() >= 0 && tagsOf.apply(t).contains(atoms[a].tag())) members[t.getId()] = true;
            }
            tagMembers[a] = members;
        }

        this.partCare = new int[parts.size()];
        this.partWant = new int[parts.size()];
        for (int p = 0; p < parts.size(); p++) {
            int care = 0;
            int want = 0;
            for (var e : parts.get(p).when().entrySet()) {
                MultipartData.Condition c = e.getValue();
                int bit = 1 << index.get(new Atom(e.getKey(), c.test(), c.tag()));
                care |= bit;
                if (!c.negate()) want |= bit;
            }
            if ((care & want) != want) throw new AssertionError();
            partCare[p] = care;
            partWant[p] = want;
        }

        this.offsets = new int[4][atoms.length][];
        for (int turns = 0; turns < 4; turns++) {
            for (int a = 0; a < atoms.length; a++) {
                MultipartData.Side side = atoms[a].side();
                offsets[turns][a] = turn(side.dx, side.dy, side.dz, turns);
            }
        }

        int masks = 1 << atoms.length;
        this.keys = new String[4][masks];
        this.states = new ArrayList<>(masks);
        this.boxes = new float[4][masks][];
        boolean any = false;
        for (int mask = 0; mask < masks; mask++) {
            keys[0][mask] = (KEY_PREFIX + mask).intern();
            for (int turns = 1; turns < 4; turns++) {
                keys[turns][mask] = (KEY_PREFIX + mask + "@" + turns).intern();
            }
            List<String> drawn = new ArrayList<>();
            List<MultipartData.Box> collision = new ArrayList<>();
            for (int p = 0; p < parts.size(); p++) {
                if ((mask & partCare[p]) == partWant[p]) {
                    // A state on several rows is one part with alternative conditions: draw it
                    // once, with the boxes of the first row that holds.
                    if (!drawn.contains(parts.get(p).state())) {
                        drawn.add(parts.get(p).state());
                        collision.addAll(parts.get(p).boxes());
                    }
                }
            }
            states.add(Collections.unmodifiableList(drawn));
            float[] flat = new float[collision.size() * 6];
            for (int b = 0; b < collision.size(); b++) {
                MultipartData.Box box = collision.get(b);
                System.arraycopy(new float[]{box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()},
                        0, flat, b * 6, 6);
            }
            boxes[0][mask] = flat;
            for (int turns = 1; turns < 4; turns++) {
                boxes[turns][mask] = turnBoxes(flat, turns);
            }
            any |= flat.length > 0;
        }
        this.anyBoxes = any;
    }

    public BlockType owner() {
        return owner;
    }

    /** Number of distinct neighbour combinations: {@code 2^atoms}. */
    public int maskCount() {
        return keys[0].length;
    }

    /** The mask of the cell at {@code (x, y, z)}: bit {@code a} set when atom {@code a}'s test passes. */
    public int evaluate(Neighbours neighbours, long x, long y, long z) {
        return evaluate(neighbours, x, y, z, 0);
    }

    /**
     * The mask of a cell whose model is turned {@code quarterTurns} × 90° about +Y: the table's
     * sides are read turned with it.
     */
    public int evaluate(Neighbours neighbours, long x, long y, long z, int quarterTurns) {
        int[][] turned = offsets[Math.floorMod(quarterTurns, 4)];
        int mask = 0;
        for (int a = 0; a < atoms.length; a++) {
            int[] o = turned[a];
            if (test(a, neighbours.at(x + o[0], y + o[1], z + o[2]))) mask |= 1 << a;
        }
        return mask;
    }

    /** {@code (dx, dy, dz)} turned like {@code StampRotator.rotateY}: one turn takes +Z (south) to +X (east). */
    static int[] turn(int dx, int dy, int dz, int quarterTurns) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> new int[]{dz, dy, -dx};
            case 2 -> new int[]{-dx, dy, -dz};
            case 3 -> new int[]{-dz, dy, dx};
            default -> new int[]{dx, dy, dz};
        };
    }

    private boolean test(int a, BlockType n) {
        return switch (atoms[a].test()) {
            case FULL_CUBE -> fullCube.test(n);
            case SAME -> n == owner;
            case TAG -> n != null && n.getId() >= 0 && n.getId() < tagMembers[a].length && tagMembers[a][n.getId()];
        };
    }

    /** The stamp-cache key the shape for {@code mask} is registered under. */
    public String key(int mask) {
        return keys[0][mask];
    }

    /** The key of {@code mask}'s shape turned {@code quarterTurns} × 90° about +Y. */
    public String key(int mask, int quarterTurns) {
        return keys[Math.floorMod(quarterTurns, 4)][mask];
    }

    /** The SBO states drawn for {@code mask}, in table order. */
    public List<String> states(int mask) {
        return states.get(mask);
    }

    /** Collision boxes for {@code mask}: 6 floats each (min xyz, max xyz, cell space). */
    public float[] boxes(int mask) {
        return boxes[0][mask];
    }

    /**
     * Collision boxes for {@code mask} of a cell turned {@code quarterTurns} × 90° about +Y, turned
     * about the cell centre the same way its stamp is, so they stay on the geometry they wrap.
     */
    public float[] boxes(int mask, int quarterTurns) {
        return boxes[Math.floorMod(quarterTurns, 4)][mask];
    }

    /** Cell-space boxes turned about the cell centre like {@link #turn}. */
    static float[] turnBoxes(float[] flat, int quarterTurns) {
        float[] out = new float[flat.length];
        for (int b = 0; b < flat.length; b += 6) {
            float[] a = turnPoint(flat[b], flat[b + 2], quarterTurns);
            float[] c = turnPoint(flat[b + 3], flat[b + 5], quarterTurns);
            out[b] = Math.min(a[0], c[0]);
            out[b + 1] = flat[b + 1];
            out[b + 2] = Math.min(a[1], c[1]);
            out[b + 3] = Math.max(a[0], c[0]);
            out[b + 4] = flat[b + 4];
            out[b + 5] = Math.max(a[1], c[1]);
        }
        return out;
    }

    /** A cell-space {@code (x, z)} turned about the cell centre: one turn takes +Z to +X, as {@link #turn}. */
    private static float[] turnPoint(float x, float z, int quarterTurns) {
        float dx = x - 0.5f;
        float dz = z - 0.5f;
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> new float[]{0.5f + dz, 0.5f - dx};
            case 2 -> new float[]{0.5f - dx, 0.5f - dz};
            case 3 -> new float[]{0.5f - dz, 0.5f + dx};
            default -> new float[]{x, z};
        };
    }

    /** True when some neighbour combination gives this block collision boxes. */
    public boolean hasBoxes() {
        return anyBoxes;
    }

    /** Every SBO state the table names, once each, in table order. */
    public List<String> partStates() {
        List<String> out = new ArrayList<>();
        for (MultipartData.Part part : parts) {
            if (!out.contains(part.state())) out.add(part.state());
        }
        return out;
    }
}
