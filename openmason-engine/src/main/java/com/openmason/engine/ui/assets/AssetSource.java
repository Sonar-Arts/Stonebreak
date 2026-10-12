package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.UiDependency.Kind;

import java.io.IOException;

/**
 * One place shared dependencies can be found by id. {@link AssetResolver} consults sources in
 * the order the host lists them; the first source holding an id owns it.
 */
public interface AssetSource {

    /** Stable name used in reports ({@code project}, {@code pack:core}, {@code packaged}). */
    String name();

    AssetOrigin origin();

    /** Pack id this source serves, or {@code null} for a default resource root. */
    default String packId() {
        return null;
    }

    /**
     * @param sourceHint project-relative hint from the dependency row; only editor project
     *                   sources may use it, runtime sources must ignore it
     * @return the asset, or {@code null} when this source does not hold {@code id}
     * @throws IOException when the asset exists but cannot be read
     */
    ResolvedAsset find(String id, Kind kind, String sourceHint) throws IOException;
}
