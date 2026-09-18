package com.stonebreak.battle.api;

import org.joml.Vector3f;

import java.util.List;

/**
 * Where the battle is staged, in world space. Pure math shared by the stage (actor placement), the
 * camera (framing, occlusion) and the HUD (world-anchored elements).
 *
 * @param monkHome       feet position of the monk's ring
 * @param archonHome     feet position of the Archon's ring
 * @param monkHeight     model height in blocks
 * @param archonHeight   model height in blocks
 * @param strikeDistance how far from the Archon's centre the dashing MONK stops (fist reach)
 * @param archonStrikeDistance how far from the monk's centre the gliding ARCHON stops (sword reach)
 * @param archonStrikeLateral   how far the Archon side-steps (toward its own LEFT) by the end of its
 *                              glide, so a blade swung down its right side comes down on the monk
 * @param recoilDistance how far a full recoil pushes an actor away from its opponent
 * @param arenaRadius    horizontal radius (from the midpoint) the camera must stay inside
 * @param floorY         the camera eye must stay above this height
 * @param obstacles      axis-aligned boxes {minX,minY,minZ,maxX,maxY,maxZ} that block the camera
 */
public record BattleStageLayout(Vector3f monkHome, Vector3f archonHome, float monkHeight, float archonHeight,
                                float strikeDistance, float recoilDistance, float arenaRadius, float floorY,
                                List<float[]> obstacles, float archonStrikeDistance, float archonStrikeLateral) {

    /** Without a side-step. */
    public BattleStageLayout(Vector3f monkHome, Vector3f archonHome, float monkHeight, float archonHeight,
                             float strikeDistance, float recoilDistance, float arenaRadius, float floorY,
                             List<float[]> obstacles, float archonStrikeDistance) {
        this(monkHome, archonHome, monkHeight, archonHeight, strikeDistance, recoilDistance, arenaRadius, floorY,
                obstacles, archonStrikeDistance, 0f);
    }

    /** Legacy shape: the Archon's reach defaults to the monk's strike distance plus one block of sword. */
    public BattleStageLayout(Vector3f monkHome, Vector3f archonHome, float monkHeight, float archonHeight,
                             float strikeDistance, float recoilDistance, float arenaRadius, float floorY,
                             List<float[]> obstacles) {
        this(monkHome, archonHome, monkHeight, archonHeight, strikeDistance, recoilDistance, arenaRadius, floorY,
                obstacles, strikeDistance + 1.0f, 0f);
    }

    public BattleStageLayout {
        monkHome = new Vector3f(monkHome);
        archonHome = new Vector3f(archonHome);
        obstacles = List.copyOf(obstacles);
    }

    public Vector3f home(CombatantId id) {
        return new Vector3f(id == CombatantId.MONK ? monkHome : archonHome);
    }

    public float height(CombatantId id) {
        return id == CombatantId.MONK ? monkHeight : archonHeight;
    }

    /** Centre-to-centre distance at which {@code attacker} stops in front of its opponent. */
    public float strikeDistance(CombatantId attacker) {
        return attacker == CombatantId.MONK ? strikeDistance : archonStrikeDistance;
    }

    /** Point halfway between the two home rings, at floor level. */
    public Vector3f midpoint() {
        return new Vector3f(monkHome).add(archonHome).mul(0.5f);
    }

    /** Unit horizontal direction from {@code id}'s home toward its opponent's home. */
    public Vector3f facing(CombatantId id) {
        Vector3f d = home(id.opponent()).sub(home(id));
        d.y = 0f;
        if (d.lengthSquared() < 1.0e-8f) return new Vector3f(0f, 0f, -1f);
        return d.normalize();
    }

    /** Feet position of a combatant for the given pose (dash toward the opponent, recoil away). */
    public Vector3f feet(CombatantId id, ActorPose pose) {
        Vector3f home = home(id);
        Vector3f facing = facing(id);
        float gap = home(id.opponent()).sub(home).length();
        float dashLength = Math.max(0f, gap - strikeDistance(id));
        float dash = Math.max(0f, Math.min(1f, pose.dashProgress())) * dashLength;
        float recoil = Math.max(0f, Math.min(1f, pose.recoil())) * recoilDistance;
        home.add(new Vector3f(facing).mul(dash - recoil));
        if (id == CombatantId.ARCHON && archonStrikeLateral != 0f) {
            // facing x up points to the actor's own left when its model is authored facing -Z.
            Vector3f left = new Vector3f(facing).cross(0f, 1f, 0f);
            home.add(left.mul(archonStrikeLateral * Math.max(0f, Math.min(1f, pose.dashProgress()))));
        }
        return home;
    }

    /** A point on the combatant's body: {@code heightFraction} 0 = feet, 1 = top of head. */
    public Vector3f bodyPoint(CombatantId id, ActorPose pose, float heightFraction) {
        Vector3f p = feet(id, pose);
        p.y += height(id) * heightFraction;
        return p;
    }
}
