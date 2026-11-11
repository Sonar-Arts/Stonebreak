package com.stonebreak.audio;

import com.openmason.engine.audio.SoundEventCache;
import com.openmason.engine.audio.SoundEvents;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.format.sound.SoundData;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.sbo.SBOBlockBridge;
import org.joml.Vector3f;

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
 * block loads its samples into the engine sound system (deduped across blocks
 * that share a sample — see {@link SoundEvents}). Safe from any thread: the
 * native mixer queues plays, so nothing is handed to the main thread.
 */
public final class BlockSounds {

    private static final SoundEventCache<BlockType> CACHE = new SoundEventCache<>(BlockSounds::resolve);

    private BlockSounds() {
    }

    /** Footstep for the local player: 2D, listener-relative. */
    public static void playStepLocal(BlockType block, float volumeScale) {
        eventsFor(block).play2D(Game.getSoundSystem(), SoundData.EVENT_STEP, volumeScale);
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
        eventsFor(block).playAt(Game.getSoundSystem(), event, x, y, z, volumeScale);
    }

    /** Drop all cached resolutions (e.g. after the SBO bridge re-initializes). */
    public static void invalidate() {
        CACHE.invalidate();
    }

    private static SoundEvents eventsFor(BlockType block) {
        return block == null || block == BlockType.AIR ? SoundEvents.EMPTY : CACHE.get(block);
    }

    /** Null while the SBO bridge or audio is not up yet, so the miss is not cached. */
    private static SoundEvents resolve(BlockType block) {
        Renderer renderer = Game.getRenderer();
        SBOBlockBridge bridge = renderer != null ? renderer.getSBOBlockBridge() : null;
        SBOParseResult sbo = bridge != null ? bridge.getSBODefinition(block) : null;
        if (sbo == null || Game.getSoundSystem() == null) return null;
        return SoundEvents.resolve(Game.getSoundSystem(), sbo.getObjectId(), sbo.sounds(), sbo::soundBytesFor,
                GameSoundLoader.RESOURCES);
    }
}
