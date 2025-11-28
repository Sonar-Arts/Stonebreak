package com.openmason.main.systems.viewport.util;

import org.joml.Vector3f;

/**
      * Utilities for smoothing camera values and angles.
      */
     public final class CameraInterpolator {

         public static final float INTERPOLATION_SPEED = 15.0f;
         private static final float EPSILON = 0.01f;
         private static final float ANGLE_EPSILON = 0.1f;

         /**
          * Linear interpolation (t clamped to 1).
          */
         public static float lerp(float from, float to, float t) {
             return from + (to - from) * Math.min(t, 1.0f);
         }

         /**
          * Interpolate angles in degrees, taking the shortest path and normalizing result.
          */
         public static float lerpAngle(float from, float to, float t) {
             float difference = to - from;

             // Handle angle wraparound for smooth rotation
             if (difference > 180) {
                 difference -= 360;
             } else if (difference < -180) {
                 difference += 360;
             }

             return CameraMath.normalizeAngle(from + difference * Math.min(t, 1.0f));
         }

         /**
          * True if two floats are within EPSILON.
          */
         public static boolean isApproximately(float a, float b) {
             return Math.abs(a - b) < EPSILON;
         }

         /**
          * True if two angles are within ANGLE_EPSILON.
          */
         public static boolean isAngleApproximately(float a, float b) {
             return Math.abs(a - b) < ANGLE_EPSILON;
         }
    public static boolean isAtTarget(Vector3f current, Vector3f target) {
        Vector3f delta = new Vector3f(target).sub(current);
        return delta.length() < EPSILON;
    }
}
