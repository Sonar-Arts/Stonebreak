package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiValue;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A decoded {@code .sbui} export.
 *
 * @param manifest     export manifest
 * @param source       the embedded canonical OMUI, decoded (the only editable tree)
 * @param sourceBytes  the embedded OMUI archive bytes, carried verbatim
 * @param assets       dependencies collected at export ({@code assets/...})
 * @param derived      derived caches ({@code derived/...})
 * @param extraEntries unrecognized entries, carried verbatim
 */
public record SbuiArchive(SbuiManifest manifest, OmuiArchive source, UiBytes sourceBytes, Map<String, UiBytes> assets,
                          Map<String, UiBytes> derived, Map<String, UiBytes> extraEntries) {

    public SbuiArchive {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceBytes, "sourceBytes");
        assets = sorted(assets);
        derived = sorted(derived);
        extraEntries = sorted(extraEntries);
    }

    private static Map<String, UiBytes> sorted(Map<String, UiBytes> map) {
        TreeMap<String, UiBytes> out = new TreeMap<>(UiValue.KEY_ORDER);
        if (map != null) {
            out.putAll(map);
        }
        return Collections.unmodifiableMap(out);
    }
}
