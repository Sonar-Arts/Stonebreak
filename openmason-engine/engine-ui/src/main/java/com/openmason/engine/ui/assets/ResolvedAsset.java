package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency.Kind;

import java.util.Objects;

/**
 * Bytes found for one dependency id.
 *
 * @param id       the dependency id that was found (a fallback's own id when a fallback served)
 * @param kind     dependency kind
 * @param bytes    asset bytes, verbatim
 * @param origin   which kind of source served it
 * @param source   name of the serving source ({@code project}, {@code pack:core}, {@code packaged},
 *                 {@code document}, {@code export})
 * @param location portable location inside that source: a project-relative path, a resource
 *                 path or an archive entry. Never an absolute machine path.
 */
public record ResolvedAsset(String id, Kind kind, UiBytes bytes, AssetOrigin origin, String source,
                            String location) {

    public ResolvedAsset {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(location, "location");
    }

    public String sha256() {
        return bytes.sha256();
    }

    /** {@code project:textures/ui/panel.sbt}, for reports and logs. */
    public String describe() {
        return source + ":" + location;
    }
}
