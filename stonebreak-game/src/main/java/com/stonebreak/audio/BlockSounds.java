package com.stonebreak.audio;

import com.openmason.engine.audio.SoundSystem;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.format.sound.SoundData;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.sbo.SBOBlockBridge;
import org.joml.Vector3f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data-driven block sound playback. Every block↔sound association lives in
 * the block's {@code .sbo} manifest ({@code sounds[]}, SBO 1.7+) — there is
 * no hardcoded block→sample table. Blocks whose SBO declares no sounds (or
 * that have no SBO at all) are simply silent, exactly like unlisted blocks
 * were under the old hardcoded switches.
 *
 * <p>Standard events (see {@link SoundData}): {@code step} for footsteps,
 * {@code break} when destroyed, {@code hit} while being demolished, and
 * {@code place} on placement. Assets may declare further events; use
 * {@link #playEventAt(BlockType, String, float, float, float, float)} for those.
 *
 * <p>Resolution is lazy and cached per block type: the first trigger for a
 * block registers its samples with the {@link SoundSystem} (deduped across
 * blocks that share a sample — see {@link ResolvedSoundEvents}).
 */
public final class BlockSounds {

    private static final Map<BlockType, ResolvedSoundEvents> CACHE = new ConcurrentHashMap<>();

    private BlockSounds() {
    }

    /** Footstep for the local player: 2D, listener-relative. */
    public static void playStepLocal(BlockType block, float volumeScale) {
        ResolvedSoundEvents.Entry entry = pick(block, SoundData.EVENT_STEP);
        SoundSystem soundSystem = Game.getSoundSystem();
        if (entry == null || soundSystem == null) return;
        soundSystem.playSound(entry.soundKey(), entry.volume() * volumeScale, entry.pitch());
    }

    /** Footstep for a mob: positional 3D at the mob's feet. */
    public static void playStepAt(BlockType block, Vector3f position, float volumeScale) {
        playEventAt(block, SoundData.EVENT_STEP, position.x, position.y, position.z, volumeScale);
    }

    /** Block destroyed at block coordinates (played at the cell center). */
    public static void playBreak(BlockType block, int x, int y, int z) {
        playEventAt(block, SoundData.EVENT_BREAK, x + 0.5f, y + 0.5f, z + 0.5f, 1f);
    }

    /** Block being demolished/attacked (crack progress) at block coordinates. */
    public static void playHit(BlockType block, int x, int y, int z) {
        playEventAt(block, SoundData.EVENT_HIT, x + 0.5f, y + 0.5f, z + 0.5f, 1f);
    }

    /** Block placed at block coordinates. */
    public static void playPlace(BlockType block, int x, int y, int z) {
        playEventAt(block, SoundData.EVENT_PLACE, x + 0.5f, y + 0.5f, z + 0.5f, 1f);
    }

    /** Any (possibly custom) event as positional 3D audio; unbound events no-op. */
    public static void playEventAt(BlockType block, String event,
                                   float x, float y, float z, float volumeScale) {
        ResolvedSoundEvents.Entry entry = pick(block, event);
        SoundSystem soundSystem = Game.getSoundSystem();
        if (entry == null || soundSystem == null) return;
        soundSystem.playSoundAt3D(entry.soundKey(), entry.volume() * volumeScale,
                entry.pitch(), x, y, z);
    }

    /** Drop all cached resolutions (e.g. after the SBO bridge re-initializes). */
    public static void invalidate() {
        CACHE.clear();
    }

    private static ResolvedSoundEvents.Entry pick(BlockType block, String event) {
        if (block == null || block == BlockType.AIR) return null;
        return eventsFor(block).pick(event);
    }

    private static ResolvedSoundEvents eventsFor(BlockType block) {
        ResolvedSoundEvents cached = CACHE.get(block);
        if (cached != null) return cached;

        Renderer renderer = Game.getRenderer();
        SBOBlockBridge bridge = renderer != null ? renderer.getSBOBlockBridge() : null;
        SBOParseResult sbo = bridge != null ? bridge.getSBODefinition(block) : null;
        SoundSystem soundSystem = Game.getSoundSystem();
        if (sbo == null || soundSystem == null) {
            // The bridge/audio may simply not be ready yet — don't cache the miss.
            return ResolvedSoundEvents.EMPTY;
        }
        ResolvedSoundEvents resolved = ResolvedSoundEvents.resolve(
                soundSystem, sbo.getObjectId(), sbo.sounds(), sbo::soundBytesFor);
        CACHE.put(block, resolved);
        return resolved;
    }
}
