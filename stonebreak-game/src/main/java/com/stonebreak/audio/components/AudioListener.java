package com.stonebreak.audio.components;

import static org.lwjgl.openal.AL10.*;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;

public class AudioListener {

    public void setListenerPosition(float x, float y, float z) {
        alListener3f(AL_POSITION, x, y, z);
    }

    public void setListenerVelocity(float x, float y, float z) {
        alListener3f(AL_VELOCITY, x, y, z);
    }

    /**
     * Sets the listener orientation using forward and up vectors.
     * OpenAL requires 6 values: forward.x, forward.y, forward.z, up.x, up.y, up.z
     *
     * @param forwardX Forward vector X component
     * @param forwardY Forward vector Y component
     * @param forwardZ Forward vector Z component
     * @param upX Up vector X component
     * @param upY Up vector Y component
     * @param upZ Up vector Z component
     */
    public void setListenerOrientation(float forwardX, float forwardY, float forwardZ,
                                     float upX, float upY, float upZ) {
        FloatBuffer orientationBuffer = BufferUtils.createFloatBuffer(6);
        orientationBuffer.put(forwardX).put(forwardY).put(forwardZ);
        orientationBuffer.put(upX).put(upY).put(upZ);
        orientationBuffer.flip();

        alListenerfv(AL_ORIENTATION, orientationBuffer);
    }

    /**
     * Convenience method to set listener orientation using Vector3f objects.
     */
    public void setListenerOrientation(org.joml.Vector3f forward, org.joml.Vector3f up) {
        setListenerOrientation(forward.x, forward.y, forward.z, up.x, up.y, up.z);
    }

    /**
     * Initializes the default listener with proper 3D audio setup.
     * Sets up a default position, velocity, and orientation for 3D audio.
     */
    public void initializeDefaultListener() {
        // Set default position at origin
        alListener3f(AL_POSITION, 0.0f, 0.0f, 0.0f);

        // Set default velocity (no movement)
        alListener3f(AL_VELOCITY, 0.0f, 0.0f, 0.0f);

        // Set default orientation: looking down negative Z axis, up is positive Y
        // Forward: (0, 0, -1), Up: (0, 1, 0)
        setListenerOrientation(0.0f, 0.0f, -1.0f, 0.0f, 1.0f, 0.0f);
    }

    /**
     * Sets the listener position and orientation based on camera data.
     * This should be called every frame to maintain proper 3D audio.
     *
     * @param posX Listener position X
     * @param posY Listener position Y
     * @param posZ Listener position Z
     * @param frontX Camera front vector X
     * @param frontY Camera front vector Y
     * @param frontZ Camera front vector Z
     * @param upX Camera up vector X
     * @param upY Camera up vector Y
     * @param upZ Camera up vector Z
     */
    public void setListenerTransform(float posX, float posY, float posZ,
                                   float frontX, float frontY, float frontZ,
                                   float upX, float upY, float upZ) {
        setListenerPosition(posX, posY, posZ);
        setListenerOrientation(frontX, frontY, frontZ, upX, upY, upZ);
    }

    // Last logged position to reduce spam
    private static org.joml.Vector3f lastLoggedPosition = new org.joml.Vector3f();
    private static long lastLogTime = 0;

    /**
     * Sets the listener position and orientation using the player's camera.
     * Convenience method for common use case.
     */
    public void setListenerFromCamera(org.joml.Vector3f position, org.joml.Vector3f front, org.joml.Vector3f up) {
        // Only log significant position changes to reduce spam
        long currentTime = System.currentTimeMillis();
        float distance = position.distance(lastLoggedPosition);

        if (distance > 1.0f || (currentTime - lastLogTime) > 5000) { // Log every 1 block movement or 5 seconds
            System.out.println("🎧 LISTENER DEBUG: Position (" + String.format("%.1f", position.x) + ", " + String.format("%.1f", position.y) + ", " + String.format("%.1f", position.z) + ")");
            lastLoggedPosition.set(position);
            lastLogTime = currentTime;
        }

        setListenerPosition(position.x, position.y, position.z);
        setListenerOrientation(front, up);
    }
}