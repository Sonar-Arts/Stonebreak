package com.openmason.engine.format.omui;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One row of {@code dependencies.json}. Documents reference dependencies only by
 * {@link #id()}; how the id resolves (project registry, game resource pack, archive entry) is
 * decided by this row and the resolvers of #285, never by a machine path.
 *
 * @param id         namespaced logical id ({@code stonebreak:ui/textures/stone_button})
 * @param kind       what the bytes are
 * @param version    the asset's own format version ({@code "1.0"} for an SBT), or {@code null}
 * @param sha256     content hash of the asset bytes, lowercase hex
 * @param size       byte length of the asset
 * @param mode       shared (resolved by id at load) or embedded (snapshot inside the archive)
 * @param entry      archive entry holding an embedded snapshot ({@code assets/...}); {@code null}
 *                   when shared
 * @param sourceHint project-relative path the editor last resolved it from; a hint for
 *                   relinking, never used by the game
 * @param requires   ids of dependencies this one needs (its closure for embedding)
 * @param optional   when true a missing asset degrades to {@code fallback} instead of failing
 * @param fallback   id of the dependency to use when an optional one is missing, or {@code null}
 * @param license    provenance/licence note required for fonts and sounds, or {@code null}
 */
public record UiDependency(String id, Kind kind, String version, String sha256, long size, Mode mode,
                           String entry, String sourceHint, List<String> requires, boolean optional,
                           String fallback, String license, Map<String, UiValue> unknown) {

    public enum Kind implements WireEnum {
        TEXTURE("texture"), SPRITES("sprites"), IMAGE("image"), COMPONENT("component"),
        STYLESHEET("stylesheet"), SCRIPT("script"), FONT("font"), SOUND("sound");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    public enum Mode implements WireEnum {
        SHARED("shared"), EMBEDDED("embedded");

        private final String wire;

        Mode(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    public UiDependency {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(mode, "mode");
        requires = Canon.sortedUnique(requires);
        unknown = Canon.unknown(unknown);
    }

    public static UiDependency shared(String id, Kind kind, String sha256, long size, String sourceHint) {
        return new UiDependency(id, kind, null, sha256, size, Mode.SHARED, null, sourceHint, List.of(), false,
                null, null, Map.of());
    }

    public static UiDependency embedded(String id, Kind kind, UiBytes bytes, String entry, String sourceHint) {
        return new UiDependency(id, kind, null, bytes.sha256(), bytes.size(), Mode.EMBEDDED, entry, sourceHint,
                List.of(), false, null, null, Map.of());
    }
}
