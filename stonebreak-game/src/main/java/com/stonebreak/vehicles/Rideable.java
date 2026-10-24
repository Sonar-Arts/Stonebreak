package com.stonebreak.vehicles;

import org.joml.Vector3f;

/**
 * An entity others can sit in. Seats are fixed points on the vehicle; whoever sits in one is
 * carried to it every tick ({@link RiderSystem}) and skips its own movement.
 */
public interface Rideable {

    int seatCount();

    /** World position of the top of {@code seat}'s bench (what a rider sits on), into {@code dest}. */
    Vector3f seatPosition(int seat, Vector3f dest);

    /** World yaw (degrees, model-forward convention of the vehicle) a rider in {@code seat} faces. */
    float seatYaw(int seat);

    boolean isAlive();
}
