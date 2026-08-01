package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.audio.SoundSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * In-tool audio audition for the SBO/SBE Sounds tabs, backed by the engine
 * {@link SoundSystem} (OpenAL). The OpenAL device is opened lazily on the
 * first preview so the tool pays nothing for audio until an author actually
 * clicks Play, and torn down once at shutdown via {@link #shutdown()}.
 *
 * <p>Samples are cached in the engine's sound buffer under content-derived
 * keys ({@code res:<path>} for classpath resources, {@code embed:<len>:<hash>}
 * for embedded bytes) so repeated previews of the same sample decode once.
 * Resource paths resolve against the tool classpath, which includes the
 * stonebreak-game module — exactly the resources a shipped asset would
 * reference at runtime.
 *
 * <p>Failures never throw out of {@link #playEmbedded}/{@link #playResource};
 * they return {@code false} and expose a human-readable {@link #lastError()}
 * for inline display next to the row that requested the preview.
 */
public final class SoundPreviewService {

    private static final Logger logger = LoggerFactory.getLogger(SoundPreviewService.class);

    private static SoundPreviewService instance;

    private boolean engineStarted;
    private final Set<String> loadedKeys = new HashSet<>();
    private String lastError;

    private SoundPreviewService() {
    }

    public static synchronized SoundPreviewService instance() {
        if (instance == null) {
            instance = new SoundPreviewService();
        }
        return instance;
    }

    /** Preview an embedded sample from its raw (WAV) bytes. */
    public boolean playEmbedded(byte[] bytes, float volume, float pitch) {
        if (bytes == null || bytes.length == 0) {
            lastError = "No audio picked yet — nothing to preview.";
            return false;
        }
        String key = "embed:" + bytes.length + ":" + Arrays.hashCode(bytes);
        return play(key, () -> new ByteArrayInputStream(bytes), volume, pitch);
    }

    /** Preview a game classpath resource (e.g. {@code /sounds/GrassWalk.wav}). */
    public boolean playResource(String resourcePath, float volume, float pitch) {
        if (resourcePath == null || resourcePath.isBlank()) {
            lastError = "No resource path set — nothing to preview.";
            return false;
        }
        String path = resourcePath.trim();
        return play("res:" + path,
                () -> SoundPreviewService.class.getResourceAsStream(path), volume, pitch);
    }

    /** Human-readable reason the most recent preview failed, or null. */
    public String lastError() {
        return lastError;
    }

    private boolean play(String key, Supplier<InputStream> streamSupplier,
                         float volume, float pitch) {
        try {
            SoundSystem sound = ensureEngine();
            if (!loadedKeys.contains(key)) {
                if (!sound.isSoundLoaded(key)) {
                    InputStream stream = streamSupplier.get();
                    if (stream == null) {
                        lastError = "Resource not found on the game classpath.";
                        return false;
                    }
                    sound.loadSound(key, stream);
                }
                if (!sound.isSoundLoaded(key)) {
                    lastError = "Could not decode the sample (uncompressed WAV expected).";
                    return false;
                }
                loadedKeys.add(key);
            }
            sound.playSound(key, Math.max(0.01f, volume), Math.max(0.05f, pitch));
            lastError = null;
            return true;
        } catch (Throwable t) {
            logger.warn("Audio preview failed for {}", key, t);
            lastError = "Audio preview unavailable: " + t.getMessage();
            return false;
        }
    }

    private SoundSystem ensureEngine() {
        SoundSystem sound = SoundSystem.getInstance();
        if (!engineStarted) {
            sound.initialize();
            engineStarted = true;
            logger.info("Sound preview engine initialized (lazy OpenAL start)");
        }
        return sound;
    }

    /** Release the OpenAL device if a preview ever started it. Idempotent. */
    public static synchronized void shutdown() {
        if (instance != null && instance.engineStarted) {
            try {
                SoundSystem.getInstance().cleanup();
            } catch (Throwable t) {
                logger.warn("Error shutting down sound preview engine", t);
            }
        }
        instance = null;
    }
}
