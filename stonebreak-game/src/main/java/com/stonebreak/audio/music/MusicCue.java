package com.stonebreak.audio.music;

import com.openmason.engine.audio.MusicDirector.Track;
import com.stonebreak.world.generation.biomes.BiomeType;

import java.util.Objects;
import java.util.Set;

/**
 * A one-shot theme and the moment it belongs to, from {@link MusicCatalog}: it plays once when every
 * condition holds, never loops, and plays again only after its {@link Time} window has closed and
 * reopened (a night cue: once a night). A cue with {@link Time#ANY} plays once per world visit.
 *
 * @param biomes  the biomes the player must stand in; empty = any biome
 * @param surface true = on the sky surface, false = below it (caves), null = either
 */
public record MusicCue(String id, Track track, Time time, Set<BiomeType> biomes, Boolean surface) {

    /** When on the world clock a cue may play ({@code TimeOfDay}). */
    public enum Time {
        ANY, DAY, NIGHT;

        boolean open(boolean night) {
            return switch (this) {
                case ANY -> true;
                case DAY -> !night;
                case NIGHT -> night;
            };
        }
    }

    public MusicCue {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(time, "time");
        biomes = Set.copyOf(biomes);
    }

    /** Whether any condition needs the player's place (biome / surface), not just the clock. */
    boolean needsPlace() {
        return !biomes.isEmpty() || surface != null;
    }

    /** The place conditions; biome first, so the surface is only asked inside a matching biome. */
    boolean matches(MusicPlace place) {
        if (!biomes.isEmpty() && !biomes.contains(place.biome())) return false;
        return surface == null || surface == place.onSurface();
    }
}
