package com.stonebreak.fx;

import com.openmason.engine.audio.SoundSystem;
import com.openmason.engine.rendering.particles.FxHost;
import com.openmason.engine.rendering.particles.FxInstance;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.audio.SoundEvents;
import com.stonebreak.audio.GameSoundLoader;
import com.stonebreak.core.Game;
import com.stonebreak.fx.sbfx.SbfxDocument;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The game side of FX systems: their sound cues play the samples embedded in their SBFX ({@code format.sound}
 * definitions + {@code sounds/} files), registered through {@link SoundEvents} exactly like SBO/SBE
 * sounds — content-keyed, so a sample shared with a block loads once. Forwarded FX events have no game logic yet.
 *
 * <p>Runs on the thread that updates the {@link FxRuntime} (the main thread).
 */
public final class GameFxHost implements FxHost {

    private final Supplier<FxLibrary> library;
    private final Supplier<SoundSystem> sounds;
    private final Map<FxSystem, SoundEvents> resolved = new IdentityHashMap<>();

    public GameFxHost(Supplier<FxLibrary> library) {
        this(library, Game::getSoundSystem);
    }

    GameFxHost(Supplier<FxLibrary> library, Supplier<SoundSystem> sounds) {
        this.library = library;
        this.sounds = sounds;
    }

    @Override
    public void sound(FxInstance instance, String sound, double x, double y, double z, float volume) {
        if (com.stonebreak.network.MultiplayerSession.isShowcase()) {
            return; // the main menu's live backdrop is silent, as for every other world sound
        }
        SoundSystem soundSystem = sounds.get();
        if (soundSystem == null) {
            return;
        }
        eventsFor(instance.system(), soundSystem).playAt(soundSystem, sound, (float) x, (float) y, (float) z, volume);
    }

    private SoundEvents eventsFor(FxSystem system, SoundSystem soundSystem) {
        SoundEvents events = resolved.get(system);
        if (events == null) {
            SbfxDocument doc = library.get().documentOf(system);
            String owner = doc != null ? doc.objectId() : system.name();
            events = SoundEvents.resolve(soundSystem, owner, system.sounds(),
                    doc != null ? doc.fx()::soundFile : path -> null, GameSoundLoader.RESOURCES);
            resolved.put(system, events);
        }
        return events;
    }
}
