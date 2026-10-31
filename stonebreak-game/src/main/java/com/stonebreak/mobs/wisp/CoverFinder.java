package com.stonebreak.mobs.wisp;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds somewhere for a Wisp to hide from one player: the far side of a tree trunk, out of their
 * line of sight, in the Shade. Trunks in thick forest score higher than lone trees, which is what
 * keeps the Wisp in the woods.
 *
 * <p>A scan, not a cached map — the forest it reads is a few hundred columns, and a Wisp only asks
 * when it is about to move.
 */
final class CoverFinder {

    /** How a spot is judged beyond being hidden and in the Shade. */
    enum Preference {
        /** Deep in the trees: lurking. */
        FOREST,
        /** Round behind where the victim is looking: repositioning to strike. */
        BEHIND_VICTIM,
        /** As far from the victim as the band allows: getting away. */
        AWAY,
        /**
         * Off to the side of or behind a victim who is tracking it, clear of where they last saw
         * it and of the way they are heading: shaking them off.
         */
        FLANK
    }

    /** A hiding spot and, for a Peek-a-boo, where to lean out from it. */
    record PeekSetup(Vector3f hide, Vector3f peek) {}

    /**
     * What a tracking victim is about to do: look toward where they last saw the Wisp — and on past
     * it — and carry on the way they are heading. Those two lines are the path a flank must keep
     * out of.
     *
     * @param player   the victim's feet
     * @param lastSeen where they last saw the Wisp, or null if they never have
     * @param heading  where they will be in a few seconds (their feet, standing still)
     */
    record Pursuit(Vector3f player, Vector3f lastSeen, Vector3f heading) {

        /** Horizontal distance from {@code spot} to the nearer of the two lines. */
        float distanceToPath(Vector3f spot) {
            float d = lineDistance(spot, player, heading, 1f);
            return lastSeen != null ? Math.min(d, lineDistance(spot, player, lastSeen, Float.MAX_VALUE)) : d;
        }

        /** Distance from {@code p} to the line from {@code a} through {@code b}, out to {@code maxT} of it. */
        private static float lineDistance(Vector3f p, Vector3f a, Vector3f b, float maxT) {
            float abx = b.x - a.x;
            float abz = b.z - a.z;
            float lenSq = abx * abx + abz * abz;
            float t = lenSq < 1.0e-6f ? 0f
                    : Math.clamp(((p.x - a.x) * abx + (p.z - a.z) * abz) / lenSq, 0f, maxT);
            return (float) Math.hypot(p.x - (a.x + abx * t), p.z - (a.z + abz * t));
        }
    }

    /** Points of the body that must be out of sight: chest and head, above the feet. */
    static final float CHEST = 0.9f;
    static final float HEAD = 1.7f;
    /** How far behind a trunk's centre the Wisp stands. */
    private static final float BEHIND_TRUNK = 1.1f;
    /** How far it leans sideways out of cover to be seen. */
    private static final float PEEK_SIDESTEP = 0.75f;
    /** Rows of logs above and below the reference height that count as a trunk. */
    private static final int TRUNK_SCAN_HEIGHT = 4;
    /** Getting away or flanking, it also looks for trees below it that it could glide down to. */
    private static final int ESCAPE_SCAN_DEPTH = TRUNK_SCAN_HEIGHT + (int) Wisp.MAX_GLIDE_DROP;
    private static final float RAY_STEP = 0.3f;
    /** Closer than this to a pursuit's path and the spot is in the way of the victim's eyes. */
    static final float PATH_HALF_WIDTH = 5f;
    /** Up to this far from the last sighting still counts in a flank's favour. */
    private static final float LAST_SEEN_REACH = 16f;
    /** Ways it tries to run in the open, degrees off straight away from the victim, best first. */
    private static final float[] RUN_ANGLES = {0f, 45f, -45f, 90f, -90f};

    private final World world;
    private final ShadeColumns shade;

    CoverFinder(World world, ShadeColumns shade) {
        this.world = world;
        this.shade = shade;
    }

    /**
     * The best hidden spot within {@code radius} of {@code around}, {@code bandMin}–{@code bandMax}
     * blocks from the victim, or {@code null} when the woods offer none.
     *
     * @param victimLook unit look direction, or null when unknown (no behind-them preference)
     */
    Vector3f find(Vector3f around, float radius, Vector3f victimEye, Vector3f victimLook,
                  float bandMin, float bandMax, Preference preference) {
        return find(around, radius, victimEye, victimLook, bandMin, bandMax, preference, null);
    }

    /**
     * As {@link #find(Vector3f, float, Vector3f, Vector3f, float, float, Preference)}, knowing what
     * a tracking victim is about to do, which {@link Preference#FLANK} steers clear of.
     *
     * @param pursuit what the victim is about to do, or null when it is not known
     */
    Vector3f find(Vector3f around, float radius, Vector3f victimEye, Vector3f victimLook,
                  float bandMin, float bandMax, Preference preference, Pursuit pursuit) {
        Vector3f best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        List<int[]> trunks = trunksNear(around, radius,
                preference == Preference.AWAY || preference == Preference.FLANK
                        ? ESCAPE_SCAN_DEPTH : TRUNK_SCAN_HEIGHT);
        Set<Long> trunkColumns = columnsOf(trunks);
        Vector3f spot = new Vector3f();
        for (int[] trunk : trunks) {
            if (!behindTrunk(trunk, victimEye, spot) || !hidden(victimEye, spot)) {
                continue;
            }
            float score = score(spot, around, trunk, trunkColumns, victimEye, victimLook,
                    bandMin, bandMax, preference, pursuit);
            if (score > bestScore) {
                bestScore = score;
                best = new Vector3f(spot);
            }
        }
        return best;
    }

    /**
     * A hiding spot near {@code around} the victim can be teased from: hidden itself, with a
     * sidestep to either side of its trunk that puts the Wisp's head in their sight.
     */
    PeekSetup findPeek(Vector3f around, float radius, Vector3f victimEye, float bandMin, float bandMax) {
        PeekSetup best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        List<int[]> trunks = trunksNear(around, radius, TRUNK_SCAN_HEIGHT);
        Set<Long> trunkColumns = columnsOf(trunks);
        Vector3f hide = new Vector3f();
        Vector3f peek = new Vector3f();
        for (int[] trunk : trunks) {
            if (!behindTrunk(trunk, victimEye, hide) || !hidden(victimEye, hide)) {
                continue;
            }
            float score = score(hide, around, trunk, trunkColumns, victimEye, null,
                    bandMin, bandMax, Preference.FOREST, null);
            if (score <= bestScore) {
                continue;
            }
            float sideX = -(victimEye.z - hide.z);
            float sideZ = victimEye.x - hide.x;
            float len = (float) Math.sqrt(sideX * sideX + sideZ * sideZ);
            if (len < 1.0e-4f) {
                continue;
            }
            for (int sign = -1; sign <= 1; sign += 2) {
                peek.set(hide.x + sign * sideX / len * PEEK_SIDESTEP, hide.y,
                        hide.z + sign * sideZ / len * PEEK_SIDESTEP);
                if (isOpen(peek) && !blocked(victimEye, peek.x, peek.y + HEAD, peek.z)) {
                    best = new PeekSetup(new Vector3f(hide), new Vector3f(peek));
                    bestScore = score;
                    break;
                }
            }
        }
        return best;
    }

    /**
     * Somewhere in the open to run to when there is no cover: {@code distance} blocks from
     * {@code from}, straight away from the victim or as near that as the Shade and the ground
     * allow — never more than square to them, which would be running past them. Null when every
     * way off is barred.
     */
    Vector3f runSpot(Vector3f from, Vector3f victim, float distance) {
        float ax = from.x - victim.x;
        float az = from.z - victim.z;
        float len = (float) Math.sqrt(ax * ax + az * az);
        if (len < 1.0e-3f) {
            ax = 1f;
            az = 0f;
            len = 1f;
        }
        ax /= len;
        az /= len;
        int nearY = (int) Math.floor(from.y);
        for (float angle : RUN_ANGLES) {
            double r = Math.toRadians(angle);
            float cos = (float) Math.cos(r);
            float sin = (float) Math.sin(r);
            float x = from.x + (ax * cos - az * sin) * distance;
            float z = from.z + (ax * sin + az * cos) * distance;
            if (!shade.isShade((int) Math.floor(x), (int) Math.floor(z))) {
                continue;
            }
            Float ground = groundAt(x, z, nearY);
            if (ground != null) {
                return new Vector3f(x, ground, z);
            }
        }
        return null;
    }

    /** Whether the victim at {@code eye} cannot see a body standing at {@code feet}. */
    boolean hidden(Vector3f eye, Vector3f feet) {
        return blocked(eye, feet.x, feet.y + HEAD, feet.z) && blocked(eye, feet.x, feet.y + CHEST, feet.z);
    }

    private float score(Vector3f spot, Vector3f around, int[] trunk, Set<Long> trunkColumns,
                        Vector3f victimEye, Vector3f victimLook, float bandMin, float bandMax,
                        Preference preference, Pursuit pursuit) {
        float toVictim = (float) Math.hypot(spot.x - victimEye.x, spot.z - victimEye.z);
        float score = 0f;
        if (toVictim < bandMin) {
            score -= (bandMin - toVictim) * 3f;
        } else if (toVictim > bandMax) {
            score -= (toVictim - bandMax) * 3f;
        }
        score -= 0.15f * spot.distance(around); // a long trip there is a long time in the open
        score += 0.6f * Math.min(8, density(trunk, trunkColumns));
        switch (preference) {
            case FOREST -> score += 0.6f * Math.min(8, density(trunk, trunkColumns));
            case BEHIND_VICTIM -> score += behindBonus(spot, victimEye, victimLook);
            case AWAY -> score += 0.5f * toVictim;
            case FLANK -> {
                // How far round it can get is left to the trip cost: from far off, a flank that
                // circles them is a long way in the open, so out there it mostly just breaks the line.
                score += behindBonus(spot, victimEye, victimLook);
                if (pursuit != null) {
                    score -= 1.2f * Math.max(0f, PATH_HALF_WIDTH - pursuit.distanceToPath(spot));
                    if (pursuit.lastSeen() != null) {
                        score += 0.3f * Math.min(LAST_SEEN_REACH, spot.distance(pursuit.lastSeen()));
                    }
                }
            }
        }
        return score;
    }

    /** Up to 6 for a spot directly behind where the victim looks, nothing dead ahead. */
    private static float behindBonus(Vector3f spot, Vector3f victimEye, Vector3f victimLook) {
        if (victimLook == null) {
            return 0f;
        }
        return 6f * com.stonebreak.mobs.entities.ai.perception.PlayerGaze
                .horizontalAngleTo(victimEye, victimLook, spot) / 180f;
    }

    /** Where to stand behind {@code trunk} as seen from {@code eye}; false if there is no footing. */
    private boolean behindTrunk(int[] trunk, Vector3f eye, Vector3f out) {
        float cx = trunk[0] + 0.5f;
        float cz = trunk[2] + 0.5f;
        float dx = cx - eye.x;
        float dz = cz - eye.z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-3f) {
            return false;
        }
        float x = cx + dx / len * BEHIND_TRUNK;
        float z = cz + dz / len * BEHIND_TRUNK;
        if (!shade.isShade((int) Math.floor(x), (int) Math.floor(z))) {
            return false;
        }
        Float ground = groundAt(x, z, trunk[1]);
        if (ground == null) {
            return false;
        }
        out.set(x, ground, z);
        return true;
    }

    /** Feet height of the first spot with solid footing and two blocks of air, scanning down. */
    private Float groundAt(float x, float z, int nearY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        for (int y = nearY + 3; y >= nearY - 6; y--) {
            if (solid(bx, y - 1, bz) && !solid(bx, y, bz) && !solid(bx, y + 1, bz)) {
                return (float) y;
            }
        }
        return null;
    }

    private boolean isOpen(Vector3f feet) {
        int bx = (int) Math.floor(feet.x);
        int by = (int) Math.floor(feet.y);
        int bz = (int) Math.floor(feet.z);
        return !solid(bx, by, bz) && !solid(bx, by + 1, bz) && shade.isShade(bx, bz);
    }

    /**
     * Logs near {@code around}, one per column: {x, y, z} of the lowest log seen, from
     * {@code depth} rows below it to {@link #TRUNK_SCAN_HEIGHT} above.
     */
    private List<int[]> trunksNear(Vector3f around, float radius, int depth) {
        List<int[]> trunks = new ArrayList<>();
        int cx = (int) Math.floor(around.x);
        int cy = (int) Math.floor(around.y);
        int cz = (int) Math.floor(around.z);
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                for (int y = cy - depth; y <= cy + TRUNK_SCAN_HEIGHT; y++) {
                    BlockType block = world.getBlockAt(cx + dx, y, cz + dz);
                    if (block != null && block.isLog()) {
                        trunks.add(new int[] {cx + dx, y, cz + dz});
                        break;
                    }
                }
            }
        }
        return trunks;
    }

    private static Set<Long> columnsOf(List<int[]> trunks) {
        Set<Long> columns = new HashSet<>();
        for (int[] trunk : trunks) {
            columns.add(column(trunk[0], trunk[2]));
        }
        return columns;
    }

    /** Trunks within three blocks of this one: how thick the woods are here. */
    private static int density(int[] trunk, Set<Long> trunkColumns) {
        int count = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if ((dx != 0 || dz != 0) && trunkColumns.contains(column(trunk[0] + dx, trunk[2] + dz))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static long column(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Whether a solid block stands anywhere on the segment from {@code eye} to the point. */
    boolean blocked(Vector3f eye, float x, float y, float z) {
        float dx = x - eye.x;
        float dy = y - eye.y;
        float dz = z - eye.z;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (int) Math.ceil(length / RAY_STEP);
        for (int i = 1; i < steps; i++) {
            float t = (float) i / steps;
            if (solid((int) Math.floor(eye.x + dx * t), (int) Math.floor(eye.y + dy * t),
                    (int) Math.floor(eye.z + dz * t))) {
                return true;
            }
        }
        return false;
    }

    private boolean solid(int x, int y, int z) {
        BlockType block = world.getBlockAt(x, y, z);
        return block != null && block.isSolid();
    }
}
