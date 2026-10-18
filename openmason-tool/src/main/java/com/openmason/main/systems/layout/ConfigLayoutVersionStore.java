package com.openmason.main.systems.layout;

import com.openmason.main.omConfig;
import com.openmason.main.systems.menus.preferences.PreferencesManager;

import java.util.Objects;

/**
 * Per-workspace layout versions in the app config ({@code ui.layout.workspace.<id>.version}),
 * seeded on first read from the keys the built-in builders used before #379, so nobody gets an
 * extra rebuild from the migration: Scene from {@code ui.layout.main.version}, Texture from the
 * preferences' {@code texture.editor.layout.version}. Any other workspace without a record counts
 * as version 1 (the UI workspace never had a version; a brand-new workspace has no saved
 * arrangement, so it is built regardless).
 */
public final class ConfigLayoutVersionStore implements WorkspaceLayouts.VersionStore {

    private final omConfig config;
    private final PreferencesManager preferences;

    public ConfigLayoutVersionStore(omConfig config, PreferencesManager preferences) {
        this.config = Objects.requireNonNull(config, "config");
        this.preferences = preferences;
    }

    @Override
    public int stored(String workspaceId) {
        int recorded = config.getWorkspaceLayoutVersion(workspaceId);
        if (recorded >= 0) {
            return recorded;
        }
        return switch (workspaceId) {
            case BuiltinWorkspaces.MODELING -> config.getMainLayoutVersion();
            case BuiltinWorkspaces.TEXTURE -> preferences != null ? preferences.getTextureEditorLayoutVersion() : 0;
            default -> 1;
        };
    }

    @Override
    public void store(String workspaceId, int version) {
        config.setWorkspaceLayoutVersion(workspaceId, version);
        config.saveConfiguration();
    }
}
