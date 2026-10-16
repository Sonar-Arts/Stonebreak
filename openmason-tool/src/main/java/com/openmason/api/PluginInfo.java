package com.openmason.api;

import java.util.Objects;
import java.util.Set;

/**
 * A discovered plugin as the host sees it.
 *
 * @param id          {@link OpenMasonPlugin#id()}
 * @param displayName {@link OpenMasonPlugin#displayName()}
 * @param version     {@link OpenMasonPlugin#version()}
 * @param requires    {@link OpenMasonPlugin#requires()}
 * @param builtIn     shipped with the editor (registered before discovered plugins)
 * @param module      the plugin's module name, or {@code unnamed} on the class path
 */
public record PluginInfo(String id, String displayName, String version, Set<String> requires, boolean builtIn,
                         String module) {

    public PluginInfo {
        Objects.requireNonNull(id, "id");
        requires = Set.copyOf(requires);
    }

    public static PluginInfo of(OpenMasonPlugin plugin, boolean builtIn) {
        Module m = plugin.getClass().getModule();
        return new PluginInfo(plugin.id(), plugin.displayName(), plugin.version(), plugin.requires(), builtIn,
                m.isNamed() ? m.getName() : "unnamed");
    }
}
