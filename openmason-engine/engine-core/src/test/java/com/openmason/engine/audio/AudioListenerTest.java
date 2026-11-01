package com.openmason.engine.audio;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.lwjgl.openal.AL10.AL_ORIENTATION;
import static org.lwjgl.openal.AL10.AL_POSITION;
import static org.lwjgl.openal.AL10.AL_VELOCITY;
import static org.lwjgl.openal.AL10.alGetListener3f;
import static org.lwjgl.openal.AL10.alGetListenerfv;

/** Listener state as OpenAL actually stores it (orientation = forward then up, 6 floats). */
@Tag("integration")
class AudioListenerTest {

    private static final float EPS = 1e-6f;

    private LoopbackAl al;
    private final AudioListener listener = new AudioListener();

    @BeforeEach
    void open() {
        al = LoopbackAl.open();
    }

    @AfterEach
    void close() {
        al.close();
    }

    private static float[] listener3f(int param) {
        float[] x = new float[1], y = new float[1], z = new float[1];
        alGetListener3f(param, x, y, z);
        return new float[]{x[0], y[0], z[0]};
    }

    private static float[] orientation() {
        float[] o = new float[6];
        alGetListenerfv(AL_ORIENTATION, o);
        return o;
    }

    @Test
    void defaultListenerSitsAtTheOriginLookingDownNegativeZ() {
        listener.setListenerPosition(5, 5, 5);
        listener.setListenerVelocity(1, 1, 1);
        listener.setListenerOrientation(1, 0, 0, 0, 0, 1);

        listener.initializeDefaultListener();

        assertArrayEquals(new float[]{0, 0, 0}, listener3f(AL_POSITION), EPS);
        assertArrayEquals(new float[]{0, 0, 0}, listener3f(AL_VELOCITY), EPS);
        assertArrayEquals(new float[]{0, 0, -1, 0, 1, 0}, orientation(), EPS);
    }

    @Test
    void orientationIsForwardThenUp() {
        listener.setListenerOrientation(0.6f, 0f, -0.8f, 0f, 1f, 0f);
        assertArrayEquals(new float[]{0.6f, 0f, -0.8f, 0f, 1f, 0f}, orientation(), EPS);
    }

    @Test
    void vectorOrientationOverloadMatchesTheScalarOne() {
        listener.setListenerOrientation(new Vector3f(1, 0, 0), new Vector3f(0, 0, 1));
        assertArrayEquals(new float[]{1, 0, 0, 0, 0, 1}, orientation(), EPS);
    }

    @Test
    void transformSetsPositionAndOrientationTogether() {
        listener.setListenerTransform(1, 2, 3, 0, 0, 1, 0, 1, 0);
        assertArrayEquals(new float[]{1, 2, 3}, listener3f(AL_POSITION), EPS);
        assertArrayEquals(new float[]{0, 0, 1, 0, 1, 0}, orientation(), EPS);
    }

    @Test
    void cameraFollowCopiesPositionFrontAndUp() {
        Vector3f pos = new Vector3f(100.5f, 70f, -20.25f);
        Vector3f front = new Vector3f(0, -1, 0);
        Vector3f up = new Vector3f(0, 0, -1);
        listener.setListenerFromCamera(pos, front, up);
        assertArrayEquals(new float[]{100.5f, 70f, -20.25f}, listener3f(AL_POSITION), EPS);
        assertArrayEquals(new float[]{0, -1, 0, 0, 0, -1}, orientation(), EPS);
        // The caller's vectors are read, never modified.
        assertArrayEquals(new float[]{100.5f, 70f, -20.25f}, new float[]{pos.x, pos.y, pos.z}, EPS);
    }

    @Test
    void cameraFollowTracksEveryFrameNotJustLargeMoves() {
        // The method throttles a (now silent) log on >1 block moves; the AL update must not be.
        listener.setListenerFromCamera(new Vector3f(0, 0, 0), new Vector3f(0, 0, -1), new Vector3f(0, 1, 0));
        listener.setListenerFromCamera(new Vector3f(0.1f, 0, 0), new Vector3f(0, 0, -1), new Vector3f(0, 1, 0));
        assertArrayEquals(new float[]{0.1f, 0, 0}, listener3f(AL_POSITION), EPS);
    }
}
