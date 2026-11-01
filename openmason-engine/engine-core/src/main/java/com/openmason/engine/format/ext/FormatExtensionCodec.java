package com.openmason.engine.format.ext;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Reads and writes one namespaced manifest extension (#383), e.g. a game's
 * {@code "stonebreak:gameplay"} data inside an SBO. The engine stores every extension as JSON and
 * never interprets it; the game that owns the namespace supplies the codec and the typed value.
 *
 * <p>A codec may also claim {@linkplain #legacyFields() top-level manifest fields} written by older
 * files before the extension existed. Reading an asset without the extension then decodes an object
 * made of those fields (the extension's own shape must match it), and writing the extension removes
 * them, so the asset moves to the namespaced layout on its next save.
 *
 * @param <T> the extension's typed value
 */
public interface FormatExtensionCodec<T> {

    /** The extension key, {@code <namespace>:<name>} (lower case). */
    String key();

    /**
     * Decodes the extension's JSON. Throw {@link IllegalArgumentException} for a value that cannot
     * be read: the reader logs it and treats the extension as absent.
     */
    T decode(JsonNode value);

    /** Encodes {@code value} (never null) as the extension's JSON object. */
    JsonNode encode(T value);

    /** Top-level manifest fields older files used for this data; empty when there were none. */
    default Set<String> legacyFields() {
        return Set.of();
    }
}
