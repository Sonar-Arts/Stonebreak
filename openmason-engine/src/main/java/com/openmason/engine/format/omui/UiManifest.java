package com.openmason.engine.format.omui;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code manifest.json}: identity and every version/feature contract the document depends on.
 *
 * @param schemaVersion   container/schema version the document is written in
 * @param documentId      namespaced logical id, stable across saves and renames
 * @param kind            screen or reusable component
 * @param displayName     editor-facing name (not an identity)
 * @param uiApi           Lua {@code ui} API version the code-behind targets
 * @param layoutSemantics layout semantics id ({@code flex-1})
 * @param requires        format features a reader must understand to open this document
 * @param hostApis        host contracts the document calls (actions, data sources)
 * @param providers       host draw/widget providers the document instantiates
 * @param unknown         preserved fields this reader does not know
 */
public record UiManifest(SchemaVersion schemaVersion, String documentId, DocumentKind kind, String displayName,
                         int uiApi, String layoutSemantics, List<String> requires,
                         List<HostRequirement> hostApis, List<HostRequirement> providers,
                         Map<String, UiValue> unknown) implements UiRequirements {

    public enum DocumentKind implements WireEnum {
        SCREEN("screen"), COMPONENT("component");

        private final String wire;

        DocumentKind(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /**
     * A versioned host contract ({@code stonebreak:screen.pause}) or provider
     * ({@code stonebreak:item-icon}). An optional requirement degrades with a warning when the
     * host lacks it; a required one makes the host refuse the document.
     */
    public record HostRequirement(String id, int version, boolean optional, Map<String, UiValue> unknown) {
        public HostRequirement {
            Objects.requireNonNull(id, "id");
            unknown = Canon.unknown(unknown);
        }

        public HostRequirement(String id, int version) {
            this(id, version, false, Map.of());
        }
    }

    public UiManifest {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(kind, "kind");
        displayName = displayName == null ? "" : displayName;
        Objects.requireNonNull(layoutSemantics, "layoutSemantics");
        requires = Canon.sortedUnique(requires);
        hostApis = Canon.sortedBy(hostApis, HostRequirement::id);
        providers = Canon.sortedBy(providers, HostRequirement::id);
        unknown = Canon.unknown(unknown);
    }

    /** A fresh current-version manifest with no host requirements. */
    public static UiManifest create(String documentId, DocumentKind kind, String displayName) {
        return new UiManifest(OmuiFormat.SCHEMA_VERSION, documentId, kind, displayName,
                OmuiFormat.UI_API_VERSION, OmuiFormat.LAYOUT_SEMANTICS, List.of(), List.of(), List.of(), Map.of());
    }

    public UiManifest withSchemaVersion(SchemaVersion version) {
        return new UiManifest(version, documentId, kind, displayName, uiApi, layoutSemantics, requires,
                hostApis, providers, unknown);
    }
}
