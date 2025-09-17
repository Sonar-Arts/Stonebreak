package com.stonebreak.audio.components;

import static org.lwjgl.openal.AL10.*;

public class AudioListener {

    public void setListenerPosition(float x, float y, float z) {
        alListener3f(AL_POSITION, x, y, z);
    }

    public void setListenerVelocity(float x, float y, float z) {
        alListener3f(AL_VELOCITY, x, y, z);
    }

    public void setListenerOrientation(float x, float y, float z) {
        alListener3f(AL_ORIENTATION, x, y, z);
    }

    public void initializeDefaultListener() {
        alListener3f(AL_POSITION, 0, 0, 1.0f);
        alListener3f(AL_VELOCITY, 0, 0, 0);
        alListener3f(AL_ORIENTATION, 0, 0, 1);
    }
}