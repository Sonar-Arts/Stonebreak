package com.stonebreak.fx.sbfx;

import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;

import java.util.Locale;
import java.util.Objects;

/**
 * One shipped Stonebreak FX asset ({@code .sbfx}, Stonebreak#435): the game-ready form of an Open Mason
 * {@code .omfx} v2 system, in the SBO/SBE/SBT family. Self-contained — the FX system, its embedded sprites and
 * its sounds all travel inside; nothing references an external atlas.
 *
 * <p>The game resolves an asset's FX bindings ({@code fx[]} in SBO/SBE manifests) by {@link #objectId()}
 * ({@code stonebreak:block_debris}) or by the bare system id ({@link #fxId()}, {@code block_debris}).
 *
 * @param objectId namespaced id, {@code <namespace>:<name>}
 * @param fx       the engine-neutral FX document: system, sprites, sound files, extensions (game data)
 */
public record SbfxDocument(String objectId, OmfxDocument fx) {

    /** The namespace a bare FX id is shipped under. */
    public static final String NAMESPACE = "stonebreak";

    public SbfxDocument {
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(fx, "fx");
        int colon = objectId.indexOf(':');
        if (colon <= 0 || colon == objectId.length() - 1 || objectId.indexOf(':', colon + 1) >= 0
                || !objectId.equals(objectId.strip())) {
            throw new IllegalArgumentException("SBFX objectId must be '<namespace>:<name>': '" + objectId + "'");
        }
    }

    /** A document shipped under the id {@link #objectIdFor} derives from the system's name. */
    public static SbfxDocument of(OmfxDocument fx) {
        return new SbfxDocument(objectIdFor(fx.id()), fx);
    }

    /**
     * The object id for an FX system id: unchanged when already namespaced, else {@code stonebreak:<id>} with the
     * id lower-cased and anything but {@code [a-z0-9_]} turned into {@code _}.
     */
    public static String objectIdFor(String fxId) {
        Objects.requireNonNull(fxId, "fxId");
        if (fxId.indexOf(':') > 0) {
            return fxId;
        }
        StringBuilder name = new StringBuilder(fxId.length());
        for (char c : fxId.strip().toLowerCase(Locale.ROOT).toCharArray()) {
            name.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' ? c : '_');
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("FX id is blank");
        }
        return NAMESPACE + ":" + name;
    }

    /** The FX system's own id (the {@code .omfx} document id, the system's name). */
    public String fxId() {
        return fx.id();
    }

    public FxSystem system() {
        return fx.system();
    }
}
