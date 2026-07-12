package com.stonebreak.audio;

import com.openmason.engine.audio.SoundSystem;
import com.openmason.engine.format.sound.SoundData;
import com.openmason.engine.format.sound.SoundDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * An asset's manifest {@code sounds[]} section resolved for playback:
 * every sample registered with the engine {@link SoundSystem} under a
 * dedupe-friendly key, grouped by event name.
 *
 * <p>Keys are content-derived so shared samples load once no matter how many
 * assets reference them: resource-referenced defs key by classpath path
 * ({@code res:/sounds/GrassWalk.wav}), embedded defs by their SHA-256
 * ({@code snd:<checksum>} — two blocks embedding identical audio share one
 * OpenAL buffer).
 *
 * <p>Playback sites call {@link #pick(String)} to draw a random sample for an
 * event (variation across multiple defs) and {@link Entry#pitch()} for the
 * playback pitch — random within the def's authored range when the def's
 * per-state {@code variation} switch is on, the natural 1.0 otherwise.
 */
public final class ResolvedSoundEvents {

    private static final Logger logger = LoggerFactory.getLogger(ResolvedSoundEvents.class);

    /** Shared empty instance for assets that declare no sounds. */
    public static final ResolvedSoundEvents EMPTY = new ResolvedSoundEvents(Map.of());

    /**
     * One playable sample: engine sound key + authored gain, pitch range, and
     * the per-def pitch-variation switch.
     */
    public record Entry(String soundKey, float volume, float pitchMin, float pitchMax,
                        boolean variation) {
        /**
         * The pitch for one playback: random within the authored range when
         * the def's variation algorithm is enabled, the natural 1.0 otherwise.
         */
        public float pitch() {
            if (!variation) return 1.0f;
            return pitchMin >= pitchMax
                    ? pitchMin
                    : pitchMin + ThreadLocalRandom.current().nextFloat() * (pitchMax - pitchMin);
        }
    }

    private final Map<String, List<Entry>> byEvent;

    public ResolvedSoundEvents(Map<String, List<Entry>> byEvent) {
        Map<String, List<Entry>> copy = new LinkedHashMap<>();
        byEvent.forEach((event, entries) -> copy.put(event, List.copyOf(entries)));
        this.byEvent = Map.copyOf(copy);
    }

    /** True when at least one sample is bound to {@code event}. */
    public boolean has(String event) {
        return byEvent.containsKey(event);
    }

    /** A random sample for {@code event}, or null when the event is unbound. */
    public Entry pick(String event) {
        List<Entry> entries = byEvent.get(event);
        if (entries == null || entries.isEmpty()) return null;
        return entries.size() == 1
                ? entries.get(0)
                : entries.get(ThreadLocalRandom.current().nextInt(entries.size()));
    }

    public boolean isEmpty() {
        return byEvent.isEmpty();
    }

    /**
     * Registers every sample of {@code data} with the sound system (skipping
     * keys already loaded) and returns the playable event table. Defs whose
     * audio fails to load are dropped so playback never spams per-frame
     * "sound not found" warnings.
     *
     * @param ownerId          label for logging and stub-checksum fallback keys
     *                         (e.g. the asset's objectId)
     * @param data             the asset's manifest sound section (null-safe)
     * @param embeddedBytes    lookup from embedded entry filename to raw sample
     *                         bytes (the parse result's sound-bytes map)
     */
    public static ResolvedSoundEvents resolve(SoundSystem soundSystem,
                                              String ownerId,
                                              SoundData data,
                                              Function<String, byte[]> embeddedBytes) {
        if (soundSystem == null || data == null || data.isEmpty()) {
            return EMPTY;
        }
        Map<String, List<Entry>> byEvent = new LinkedHashMap<>();
        for (SoundDef def : data.sounds()) {
            String key = keyFor(def, ownerId);
            if (!soundSystem.isSoundLoaded(key)) {
                if (def.isEmbedded()) {
                    byte[] bytes = embeddedBytes != null ? embeddedBytes.apply(def.filename()) : null;
                    if (bytes == null) {
                        logger.warn("Sound '{}' of {} has no embedded bytes for {} — skipped",
                                def.event(), ownerId, def.filename());
                        continue;
                    }
                    soundSystem.loadSound(key, new ByteArrayInputStream(bytes));
                } else {
                    GameSoundLoader.load(soundSystem, key, def.resourcePath());
                }
            }
            if (!soundSystem.isSoundLoaded(key)) {
                logger.warn("Sound '{}' of {} failed to load ({}) — skipped",
                        def.event(), ownerId,
                        def.isEmbedded() ? def.filename() : def.resourcePath());
                continue;
            }
            byEvent.computeIfAbsent(def.event(), e -> new ArrayList<>())
                    .add(new Entry(key, def.volume(), def.pitchMin(), def.pitchMax(),
                            def.variation()));
        }
        return byEvent.isEmpty() ? EMPTY : new ResolvedSoundEvents(byEvent);
    }

    /**
     * Content-derived engine sound key for a def. Package-visible for tests.
     */
    static String keyFor(SoundDef def, String ownerId) {
        if (!def.isEmbedded()) {
            return "res:" + def.resourcePath();
        }
        String checksum = def.checksum();
        if (checksum != null && !checksum.isBlank()) {
            return "snd:" + checksum;
        }
        return "snd:" + ownerId + "/" + def.filename();
    }
}
