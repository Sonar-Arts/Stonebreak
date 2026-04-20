package com.stonebreak.player;

/**
 * Shared constants for the Player subsystem. Centralized to avoid duplication across
 * the master controller and its delegated subsystems.
 */
public final class PlayerConstants {
    private PlayerConstants() {}

    public static final float PLAYER_HEIGHT = 1.8f;
    public static final float PLAYER_WIDTH = 0.6f;

    public static final float MOVE_SPEED = 31.0f;
    public static final float SWIM_SPEED = 11.5f;
    public static final float JUMP_FORCE = 8.5f;
    public static final float GRAVITY = 40.0f;
    public static final float WATER_GRAVITY = 12.0f;
    public static final float WATER_BUOYANCY = 25.0f;
    public static final float WATER_JUMP_BOOST = 5.0f;

    public static final float RAY_CAST_DISTANCE = 5.0f;
    public static final float ATTACK_ANIMATION_DURATION = 0.25f;

    public static final float HEALTH_PER_HEART = 2.0f;
    public static final int MAX_HEARTS = 10;
    public static final float MAX_HEALTH = MAX_HEARTS * HEALTH_PER_HEART;

    public static final float SPAWN_PROTECTION_DURATION = 2.0f;

    public static final float WATER_EXIT_ANTI_FLOAT_DURATION = 0.5f;
    public static final float NORMAL_JUMP_GRACE_PERIOD = 0.2f;
    public static final float DOUBLE_TAP_WINDOW = 0.3f;

    public static final float FLY_SPEED = MOVE_SPEED * 2.5f;
    public static final float FLY_VERTICAL_SPEED = 15.0f;

    public static final float SPAWN_X = 0.0f;
    public static final float SPAWN_Y = 100.0f;
    public static final float SPAWN_Z = 0.0f;

    public static final float CAMERA_EYE_OFFSET = PLAYER_HEIGHT * 0.8f;
}
