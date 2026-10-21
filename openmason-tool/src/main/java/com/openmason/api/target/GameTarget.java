package com.openmason.api.target;

import java.util.Locale;
import java.util.Objects;

/**
 * A game a project can target (#380): where its resources are, how it names and files assets,
 * how the editor may write into it and how its UI host is reached. Plugins contribute targets
 * ({@code PluginContext.gameTargets()}); a project's {@code .omp} names the one it targets.
 *
 * @param id               stable id the {@code .omp} records
 * @param displayName      name shown in menus
 * @param defaultNamespace namespace of the game's ids ({@code <ns>:oak_planks}); defaults to {@code id}
 * @param resources        the game's resource tree and class path
 * @param folders          where each asset kind lives in the tree
 * @param assetFilePrefix  prefix of shipped asset file names ({@code SB_}); "" for none
 * @param writes           whether and how the editor writes into the tree
 * @param uiDeploy         where shipped UI goes, or null when the game ships no UI documents
 * @param uiHost           the game's UI host, or null when it has none
 * @param templates        where template-sourced models are read from; defaults to {@code resources}
 * @param legacyDefault    projects saved before targets existed (OMP below 1.5) migrate to this target
 */
public record GameTarget(String id, String displayName, String defaultNamespace, ResourceRoot resources,
                         AssetFolders folders, String assetFilePrefix, WriteRules writes, UiDeployLayout uiDeploy,
                         UiHostSource uiHost, ResourceRoot templates, boolean legacyDefault) {

    public GameTarget {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || !id.equals(id.toLowerCase(Locale.ROOT)) || id.contains(":")) {
            throw new IllegalArgumentException("Game target id must be lowercase without ':' (" + id + ")");
        }
        Objects.requireNonNull(resources, "resources");
        if (displayName == null || displayName.isBlank()) {
            displayName = id;
        }
        if (defaultNamespace == null || defaultNamespace.isBlank()) {
            defaultNamespace = id;
        }
        if (folders == null) {
            folders = AssetFolders.NONE;
        }
        if (assetFilePrefix == null) {
            assetFilePrefix = "";
        }
        if (writes == null) {
            writes = WriteRules.SHIPPED_ASSETS;
        }
        if (templates == null) {
            templates = resources;
        }
    }

    public static Builder builder(String id, String displayName) {
        return new Builder(id, displayName);
    }

    /** True when {@code qualifiedId} ({@code ns:path}) is in this game's namespace. */
    public boolean owns(String qualifiedId) {
        return qualifiedId != null && qualifiedId.startsWith(defaultNamespace + ":");
    }

    public static final class Builder {

        private final String id;
        private final String displayName;
        private String namespace;
        private ResourceRoot resources;
        private AssetFolders folders;
        private String assetFilePrefix;
        private WriteRules writes;
        private UiDeployLayout uiDeploy;
        private UiHostSource uiHost;
        private ResourceRoot templates;
        private boolean legacyDefault;

        private Builder(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder resources(ResourceRoot resources) {
            this.resources = resources;
            return this;
        }

        public Builder folders(AssetFolders folders) {
            this.folders = folders;
            return this;
        }

        public Builder assetFilePrefix(String prefix) {
            this.assetFilePrefix = prefix;
            return this;
        }

        public Builder writes(WriteRules writes) {
            this.writes = writes;
            return this;
        }

        public Builder uiDeploy(UiDeployLayout uiDeploy) {
            this.uiDeploy = uiDeploy;
            return this;
        }

        public Builder uiHost(UiHostSource uiHost) {
            this.uiHost = uiHost;
            return this;
        }

        public Builder templates(ResourceRoot templates) {
            this.templates = templates;
            return this;
        }

        public Builder legacyDefault(boolean legacyDefault) {
            this.legacyDefault = legacyDefault;
            return this;
        }

        public GameTarget build() {
            return new GameTarget(id, displayName, namespace, resources, folders, assetFilePrefix, writes,
                    uiDeploy, uiHost, templates, legacyDefault);
        }
    }
}
