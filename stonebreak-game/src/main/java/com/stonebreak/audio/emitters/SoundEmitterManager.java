package com.stonebreak.audio.emitters;

import org.joml.Vector3f;
import com.stonebreak.audio.emitters.types.BlockPickupSoundEmitter;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

/**
 * Manages all sound emitters in the game world.
 * Handles updating, adding, removing, and rendering sound emitters.
 */
public class SoundEmitterManager {
    private final List<SoundEmitter> emitters;

    public SoundEmitterManager() {
        this.emitters = new ArrayList<>();
    }

    /**
     * Updates all active sound emitters.
     * @param deltaTime The time elapsed since last update in seconds
     */
    public void update(float deltaTime) {
        for (SoundEmitter emitter : emitters) {
            emitter.update(deltaTime);
        }
    }

    /**
     * Adds a sound emitter to the manager.
     * @param emitter The sound emitter to add
     */
    public void addEmitter(SoundEmitter emitter) {
        if (emitter != null) {
            emitters.add(emitter);
        }
    }

    /**
     * Removes a sound emitter from the manager.
     * @param emitter The sound emitter to remove
     * @return True if the emitter was found and removed
     */
    public boolean removeEmitter(SoundEmitter emitter) {
        return emitters.remove(emitter);
    }

    /**
     * Removes all sound emitters within a certain distance of a position.
     * @param position The center position
     * @param radius The radius around the position
     * @return The number of emitters removed
     */
    public int removeEmittersInRadius(Vector3f position, float radius) {
        Iterator<SoundEmitter> iterator = emitters.iterator();
        int removedCount = 0;
        float radiusSquared = radius * radius;

        while (iterator.hasNext()) {
            SoundEmitter emitter = iterator.next();
            Vector3f emitterPos = emitter.getPosition();
            float distanceSquared = position.distanceSquared(emitterPos);

            if (distanceSquared <= radiusSquared) {
                iterator.remove();
                removedCount++;
            }
        }

        return removedCount;
    }

    /**
     * Gets all sound emitters currently managed.
     * @return A copy of the emitters list
     */
    public List<SoundEmitter> getEmitters() {
        return new ArrayList<>(emitters);
    }

    /**
     * Gets all sound emitters that should be visible in debug mode.
     * @return A list of debug-visible emitters
     */
    public List<SoundEmitter> getDebugVisibleEmitters() {
        List<SoundEmitter> visibleEmitters = new ArrayList<>();
        for (SoundEmitter emitter : emitters) {
            if (emitter.isDebugVisible()) {
                visibleEmitters.add(emitter);
            }
        }
        return visibleEmitters;
    }

    /**
     * Spawns a new block pickup sound emitter at the specified position.
     * @param position The world position to spawn the emitter
     * @return The newly created emitter
     */
    public BlockPickupSoundEmitter spawnBlockPickupEmitter(Vector3f position) {
        BlockPickupSoundEmitter emitter = new BlockPickupSoundEmitter(new Vector3f(position));
        addEmitter(emitter);
        return emitter;
    }

    /**
     * Spawns a new block pickup sound emitter with custom settings.
     * @param position The world position to spawn the emitter
     * @param interval The time between sound emissions in seconds
     * @param volume The volume level (0.0 to 1.0)
     * @return The newly created emitter
     */
    public BlockPickupSoundEmitter spawnBlockPickupEmitter(Vector3f position, float interval, float volume) {
        BlockPickupSoundEmitter emitter = new BlockPickupSoundEmitter(new Vector3f(position), interval, volume);
        addEmitter(emitter);
        return emitter;
    }

    /**
     * Gets the total number of managed emitters.
     * @return The number of emitters
     */
    public int getEmitterCount() {
        return emitters.size();
    }

    /**
     * Gets the number of active emitters.
     * @return The number of emitters that are currently active
     */
    public int getActiveEmitterCount() {
        int count = 0;
        for (SoundEmitter emitter : emitters) {
            if (emitter.isActive()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Clears all sound emitters.
     */
    public void clearAllEmitters() {
        emitters.clear();
    }

    /**
     * Sets the active state of all emitters.
     * @param active True to activate all emitters, false to deactivate
     */
    public void setAllEmittersActive(boolean active) {
        for (SoundEmitter emitter : emitters) {
            emitter.setActive(active);
        }
    }

    /**
     * Gets debug information about all emitters.
     * @return A list of debug info strings
     */
    public List<String> getDebugInfo() {
        List<String> debugInfo = new ArrayList<>();
        debugInfo.add("Sound Emitters: " + emitters.size() + " total, " + getActiveEmitterCount() + " active");

        for (int i = 0; i < emitters.size(); i++) {
            SoundEmitter emitter = emitters.get(i);
            debugInfo.add(String.format("[%d] %s", i, emitter.getDebugInfo()));
        }

        return debugInfo;
    }
}