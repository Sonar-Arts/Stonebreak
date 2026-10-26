package com.stonebreak.audio;

import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.openmason.engine.entity.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityRegistry;
import com.stonebreak.world.ServerMutationSinks;
import com.stonebreak.world.World;

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
     * Makes {@code entity} sound {@code event} wherever it lives (issue #344): in the world this
     * client renders it plays here ({@link #playAt(Entity, String)}); on the authoritative server
     * world it goes to the world's sound sink, which replicates it to the players near it. The
     * server tick thread never plays into this client's mixer (#340). Any other world is silent.
     */
    public static void emit(Entity entity, String event) {
        if (entity == null) return;
        World world = entity.getWorld();
        if (world == null) return;
        if (world == Game.getWorld()) {
            playAt(entity, event);
            return;
        }
        ServerMutationSinks sinks = world.serverSinks();
        ServerMutationSinks.SoundSink sink = sinks != null ? sinks.sounds() : null;
        if (sink != null && entity.getType() != null) {
            org.joml.Vector3f pos = entity.getPosition();
            sink.onServerEntitySound(entity.getType(), event,
                    pos.x, pos.y + entity.getHeight() * 0.5f, pos.z);
        }
    }

    /**
     * Plays {@code event} from the entity's SBE at the entity's position
     * (positional 3D). Unbound events and non-SBE entities no-op. Runs on the main thread
     * whichever thread asks ({@link MainThreadAudio}), from where the entity stood when asked.
     */
    public static void playAt(Entity entity, String event) {
        if (entity == null) return;
        org.joml.Vector3f pos = entity.getPosition();
        playAt(entity.getType(), event, pos.x, pos.y + entity.getHeight() * 0.5f, pos.z);
    }

    /**
     * Plays {@code event} from {@code type}'s SBE at a world position — a replicated sound
     * ({@code SoundEventS2C}) needs no live copy of the entity, so a death voice still plays
     * after the despawn that follows it.
     */
    public static void playAt(EntityType type, String event, float x, float y, float z) {
        if (type == null) return;
        MainThreadAudio.run(() -> {
            ResolvedSoundEvents.Entry entry = eventsFor(type).pick(event);
            SoundSystem soundSystem = Game.getSoundSystem();
            if (entry == null || soundSystem == null) return;
            soundSystem.playSoundAt3D(entry.soundKey(), entry.volume(), entry.pitch(), x, y, z);
        });
    }

    /** Drop all cached resolutions (e.g. after the SBE registry reloads). */
    public static void invalidate() {
        CACHE.clear();
    }

    private static ResolvedSoundEvents eventsFor(EntityType type) {
        String objectId = type.getSbeObjectId();
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
