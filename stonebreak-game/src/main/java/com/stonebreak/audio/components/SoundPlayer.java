package com.stonebreak.audio.components;

import static org.lwjgl.openal.AL10.*;

public class SoundPlayer {
    private final SoundBuffer soundBuffer;
    private final VolumeController volumeController;

    public SoundPlayer(SoundBuffer soundBuffer, VolumeController volumeController) {
        this.soundBuffer = soundBuffer;
        this.volumeController = volumeController;
    }

    public void playSound(String name) {
        playSoundWithVolume(name, 0.5f);
    }

    public void playSoundWithVolume(String name, float volume) {
        Integer[] soundSources = soundBuffer.getSources(name);
        if (soundSources != null) {
            int currentIndex = soundBuffer.getNextSourceIndex(name);
            if (currentIndex == -1) {
                System.err.println("Failed to get source index for sound: " + name);
                return;
            }

            int source = soundSources[currentIndex];

            float finalVolume = volume * volumeController.getMasterVolume();
            alSourcef(source, AL_GAIN, finalVolume);
            alSourcePlay(source);

            int error = alGetError();
            if (error != AL_NO_ERROR) {
                System.err.println("OpenAL error playing sound " + name + " with volume " + volume + ": " + error);
            }
        } else {
            System.err.println("Sound not found: " + name);
        }
    }

    public void playSoundWithVariation(String name, float volume) {
        Integer[] soundSources = soundBuffer.getSources(name);
        if (soundSources != null) {
            int currentIndex = soundBuffer.getNextSourceIndex(name);
            if (currentIndex == -1) {
                System.err.println("Failed to get source index for sound: " + name);
                return;
            }

            int source = soundSources[currentIndex];

            // Create slight pitch variation (0.9 to 1.1, so ±10% variation)
            float pitchVariation = 0.9f + (float)(Math.random() * 0.2f);

            float finalVolume = volume * volumeController.getMasterVolume();
            alSourcef(source, AL_GAIN, finalVolume);
            alSourcef(source, AL_PITCH, pitchVariation);
            alSourcePlay(source);

            int error = alGetError();
            if (error != AL_NO_ERROR) {
                System.err.println("OpenAL error playing sound " + name + " with variation: " + error);
            }
        } else {
            System.err.println("Sound not found: " + name);
        }
    }
}