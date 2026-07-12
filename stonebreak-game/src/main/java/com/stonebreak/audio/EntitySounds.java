package com.stonebreak.audio;

import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data-driven entity sound playback from an SBE's {@code sounds[]} section
 * (SBE 1.4+). Like {@link BlockSounds}, there is no hardcoded table: an
 * entity whose SBE declares a {@code hurt}/{@code death}/{@code ambient}/…
 * event plays it, everything else is silent.
 *
 * <p>Note that footsteps stay block-driven ({@code step} on the block walked
 * on, via {@link MobSounds}); the events here are the entity's own voice.
 */
public final class EntitySounds {

    /** Standard entity event: took damage. */
    public static final String EVENT_HURT = "hurt";
    /** Standard entity event: died. */
    public static final String EVENT_DEATH = "death";
    /** Standard entity event: idle/ambient vocalization. */
    public static final String EVENT_AMBIENT = "ambient";

    private static final Map<String, ResolvedSoundEvents> CACHE = new ConcurrentHashMap<>();

    private EntitySounds() {
    }

    /**
     * Plays {@code event} from the entity's SBE at the entity's position
     * (positional 3D). Unbound events and non-SBE entities no-op.
     */
    public static void playAt(Entity entity, String event) {
        if (entity == null) return;
        ResolvedSoundEvents.Entry entry = eventsFor(entity).pick(event);
        SoundSystem soundSystem = Game.getSoundSystem();
        if (entry == null || soundSystem == null) return;
        org.joml.Vector3f pos = entity.getPosition();
        soundSystem.playSoundAt3D(entry.soundKey(), entry.volume(), entry.pitch(),
                pos.x, pos.y + entity.getHeight() * 0.5f, pos.z);
    }

    /** Drop all cached resolutions (e.g. after the SBE registry reloads). */
    public static void invalidate() {
        CACHE.clear();
    }

    private static ResolvedSoundEvents eventsFor(Entity entity) {
        String objectId = entity.getType() != null ? entity.getType().getSbeObjectId() : null;
        if (objectId == null) return ResolvedSoundEvents.EMPTY;

        ResolvedSoundEvents cached = CACHE.get(objectId);
        if (cached != null) return cached;

        SbeEntityAsset asset = SbeEntityRegistry.get(objectId);
        SoundSystem soundSystem = Game.getSoundSystem();
        if (asset == null || soundSystem == null) {
            return ResolvedSoundEvents.EMPTY; // registry/audio not ready — don't cache
        }
        ResolvedSoundEvents resolved = ResolvedSoundEvents.resolve(
                soundSystem, objectId, asset.sounds(), asset::soundBytesFor);
        CACHE.put(objectId, resolved);
        return resolved;
    }
}
