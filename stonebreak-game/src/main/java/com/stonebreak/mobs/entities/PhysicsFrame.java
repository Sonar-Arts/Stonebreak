package com.stonebreak.mobs.entities;

import com.openmason.engine.physics.PhysicsWorld;

/**
 * Absolute world positions to and from the engine's frame (#142, #445).
 *
 * <p>The native physics, navigation and entity worlds take positions relative to a per-world int64
 * frame origin ({@code setOrigin}, 0 unless the host rebases). Every body, agent and query this game
 * hands the engine goes through here, so the day a world rebases its frame, entity code keeps
 * working. The physics world's origin is the reference: {@code PathfindingService} keeps the nav
 * world's origin equal to it, and the agents follow ({@code PathAgent}/{@code AirPathAgent}).
 */
public final class PhysicsFrame {

    private PhysicsFrame() {
    }

    public static long originX(PhysicsWorld physics) {
        return physics == null || physics.isClosed() ? 0L : physics.originX();
    }

    public static long originY(PhysicsWorld physics) {
        return physics == null || physics.isClosed() ? 0L : physics.originY();
    }

    public static long originZ(PhysicsWorld physics) {
        return physics == null || physics.isClosed() ? 0L : physics.originZ();
    }
}
