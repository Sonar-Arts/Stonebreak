package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.SchemaVersion;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiManifest.HostRequirement;
import com.openmason.engine.format.omui.UiRequirements;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.WireEnum;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * {@code manifest.json} of an SBUI export.
 *
 * @param assetId      game-facing id the game loads the export by
 * @param entry        documentId of the embedded entry document
 * @param source       where the canonical OMUI lives and its content digest
 * @param uiApi        highest ui API any embedded document targets
 * @param requires     union of required format features over embedded documents
 * @param hostApis     union of host contracts (highest version; optional only if optional
 *                     everywhere)
 * @param providers    union of providers, same rule
 * @param dependencies how every dependency id resolves for this export, sorted by id
 * @param derived      derived caches, sorted by entry
 */
public record SbuiManifest(SchemaVersion schemaVersion, String assetId, String entry, SourceRef source, int uiApi,
                           String layoutSemantics, List<String> requires, List<HostRequirement> hostApis,
                           List<HostRequirement> providers, List<SbuiDependency> dependencies,
                           List<DerivedEntry> derived, Map<String, UiValue> unknown) implements UiRequirements {

    public SbuiManifest {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(layoutSemantics, "layoutSemantics");
        requires = sortedUnique(requires);
        hostApis = sorted(hostApis, Comparator.comparing(HostRequirement::id, UiValue.KEY_ORDER));
        providers = sorted(providers, Comparator.comparing(HostRequirement::id, UiValue.KEY_ORDER));
        dependencies = sorted(dependencies, Comparator.comparing(SbuiDependency::id, UiValue.KEY_ORDER));
        derived = sorted(derived, Comparator.comparing(DerivedEntry::entry, UiValue.KEY_ORDER));
        unknown = sortedMap(unknown);
    }

    /**
     * @param entry         archive entry of the embedded OMUI ({@code source/<name>.omui})
     * @param digest        {@code ArchiveDigest} of the embedded OMUI's entries
     * @param schemaVersion OMUI schema of the embedded document
     */
    public record SourceRef(String entry, String digest, SchemaVersion schemaVersion, Map<String, UiValue> unknown) {
        public SourceRef {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(schemaVersion, "schemaVersion");
            unknown = sortedMap(unknown);
        }
    }

    /** Where an embedded dependency's bytes live. */
    public enum Location implements WireEnum {
        /** Inside the embedded OMUI ({@code entry} is its path there). */
        SOURCE("source"),
        /** Collected into the SBUI at export ({@code entry} is an SBUI path). */
        SBUI("sbui");

        private final String wire;

        Location(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /**
     * Resolution row for one dependency id. Shared rows have no location and resolve through
     * the host's declared resource root or {@code pack}; embedded rows name their entry.
     *
     * @param pack resource pack the shared asset ships in, or {@code null} for the host's
     *             default resource root
     */
    public record SbuiDependency(String id, UiDependency.Kind kind, String version, String sha256, long size,
                                 UiDependency.Mode mode, Location location, String entry, String pack,
                                 List<String> requires, boolean optional, String fallback, String license,
                                 Map<String, UiValue> unknown) {
        public SbuiDependency {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(mode, "mode");
            requires = sortedUnique(requires);
            unknown = sortedMap(unknown);
        }
    }

    public enum DerivedKind implements WireEnum {
        /** Lua compiled from a behavior graph (#291). */
        GRAPH_LUA("graph-lua"),
        /** Flattened/composited image from a texture dependency (#294). */
        IMAGE("image");

        private final String wire;

        DerivedKind(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /**
     * A derived cache. Never edited; valid only while {@code sourceSha256} matches the current
     * source and {@code compilerVersion} matches the host's compiler.
     *
     * @param source       {@code graph:<id>} or {@code dependency:<id>}
     * @param sourceSha256 SHA-256 of the canonical source entry (graph) or the dependency hash
     * @param sha256       SHA-256 of this entry's bytes
     */
    public record DerivedEntry(String entry, DerivedKind kind, String source, String sourceSha256, String sha256,
                               String compiler, String compilerVersion, Map<String, UiValue> unknown) {
        public DerivedEntry {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(sourceSha256, "sourceSha256");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(compiler, "compiler");
            Objects.requireNonNull(compilerVersion, "compilerVersion");
            unknown = sortedMap(unknown);
        }
    }

    public SbuiDependency dependency(String id) {
        for (SbuiDependency d : dependencies) {
            if (d.id().equals(id)) {
                return d;
            }
        }
        return null;
    }

    private static List<String> sortedUnique(List<String> values) {
        TreeSet<String> set = new TreeSet<>(UiValue.KEY_ORDER);
        if (values != null) {
            set.addAll(values);
        }
        return List.copyOf(set);
    }

    private static <T> List<T> sorted(List<T> values, Comparator<T> order) {
        List<T> copy = values == null ? new ArrayList<>() : new ArrayList<>(values);
        copy.sort(order);
        return List.copyOf(copy);
    }

    private static Map<String, UiValue> sortedMap(Map<String, UiValue> map) {
        return map == null || map.isEmpty() ? Map.of() : UiValue.Obj.sorted(map).fields();
    }
}
