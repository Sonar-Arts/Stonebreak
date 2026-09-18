package com.stonebreak.battle.stage;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battletest.BattleTestArena;

import java.util.ArrayList;
import java.util.List;

/** Builds the battle's {@link BattleStageLayout} from an authored arena. GL-free. */
public final class BattleStageLayouts {
    private BattleStageLayouts() {}

    /** SB_Player.sbe head-to-foot height in blocks. */
    public static final float MONK_HEIGHT = 1.8f;
    /** SB_Ice_Archon.sbe authored height in blocks (asset manifest). */
    public static final float ARCHON_HEIGHT = 2.72f;
    /**
     * Centre-to-centre distance at which the monk stops to punch. MEASURED from the shipped rigs
     * (SbePoseSolver socket matrices): the fist sockets reach 0.45-0.55 blocks forward at the contact
     * frames and the Archon's torso front sits 0.21-0.29 from its centre, so the jab just touches at
     * about 0.78. The monk combat clips have no root travel; never scale the player to bridge a gap.
     */
    public static final float STRIKE_DISTANCE = 0.78f;
    /**
     * Centre-to-centre distance at which the gliding Archon stops: sword reach, not fist reach.
     * Measured: the overhead's blade tip reaches 1.96 forward at impact.
     */
    public static final float ARCHON_STRIKE_DISTANCE = 1.8f;
    /**
     * The overhead comes down along the Archon's right side (authored x = -0.87), not its centre
     * line, so by the end of its glide it has side-stepped this far to its own left, which puts the
     * blade line through the monk. (The slash arcs above head height at the centre line in the
     * shipped clip and cannot touch a monk straight ahead at any distance: an asset question.)
     */
    public static final float ARCHON_STRIKE_LATERAL = 0.8f;
    /**
     * Full recoil distance. The model also uses a full recoil as the monk's kick stand-off, since a
     * front kick reaches farther than a punch.
     */
    public static final float RECOIL_DISTANCE = 0.45f;
    /** The arena session resets the player beyond ±55; keep the camera well inside that. */
    public static final float ARENA_RADIUS = 48f;
    /** Colliders lower than this above the court are floor/steps, not camera obstacles. */
    private static final float OBSTACLE_MIN_RISE = 0.6f;

    /**
     * Distance between the two combatants' battle marks. The arena's spawn rings are 18 blocks apart
     * (sized for free roam); a two-shot that wide leaves both actors tiny. The battle stages them on
     * the same axis, centred between the rings, at a classic JRPG stand-off distance.
     */
    public static final float BATTLE_SEPARATION = 7f;

    public static BattleStageLayout fromArena(BattleTestArena arena) {
        org.joml.Vector3f monkSpawn = arena.playerSpawn().position();
        org.joml.Vector3f archonSpawn = arena.archonSpawn().position();
        org.joml.Vector3f mid = new org.joml.Vector3f(monkSpawn).add(archonSpawn).mul(0.5f);
        org.joml.Vector3f axis = new org.joml.Vector3f(monkSpawn).sub(archonSpawn);
        axis.y = 0f;
        float spawnGap = axis.length();
        float half = Math.min(BATTLE_SEPARATION, spawnGap) * 0.5f;
        if (spawnGap > 1.0e-4f) {
            axis.div(spawnGap);
        } else {
            axis.set(0f, 0f, 1f);
        }
        org.joml.Vector3f monkMark = new org.joml.Vector3f(mid).add(new org.joml.Vector3f(axis).mul(half));
        org.joml.Vector3f archonMark = new org.joml.Vector3f(mid).sub(new org.joml.Vector3f(axis).mul(half));
        monkMark.y = monkSpawn.y;
        archonMark.y = archonSpawn.y;
        float floorY = Math.min(arena.playerSpawn().y(), arena.archonSpawn().y());
        List<float[]> obstacles = new ArrayList<>();
        for (BattleTestArena.Box box : arena.collisionBoxes()) {
            if (box.maxY() > floorY + OBSTACLE_MIN_RISE) {
                obstacles.add(box.bounds());
            }
        }
        return new BattleStageLayout(monkMark, archonMark,
                MONK_HEIGHT, ARCHON_HEIGHT, STRIKE_DISTANCE, RECOIL_DISTANCE, ARENA_RADIUS, floorY, obstacles,
                ARCHON_STRIKE_DISTANCE, ARCHON_STRIKE_LATERAL);
    }
}
