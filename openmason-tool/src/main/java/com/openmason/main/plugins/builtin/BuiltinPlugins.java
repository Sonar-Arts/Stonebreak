package com.openmason.main.plugins.builtin;

import com.openmason.api.OpenMasonPlugin;

import java.util.List;

/**
 * The plugins shipped inside the tool. The editor's own features are not plugins (they register
 * through {@code bootstrap.CoreFeatures}); only Stonebreak's tooling is, because it leaves the tool
 * for {@code openmason-plugin-stonebreak} in #382.
 */
public final class BuiltinPlugins {

    private BuiltinPlugins() {
    }

    public static List<OpenMasonPlugin> all() {
        return List.of(new StonebreakBuiltinPlugin());
    }
}
