package com.openmason.api;

import java.util.Set;

/**
 * An Open Mason plugin: the unit that contributes workspaces, menus, commands, file types, MCP
 * tools, knowledge packs, preference pages, viewport content, project templates, themes and
 * lifecycle listeners to the editor (#379, epic #390).
 *
 * <p>Plugins are found with {@link java.util.ServiceLoader}: a plugin module declares
 * {@code provides com.openmason.api.OpenMasonPlugin with ...} and sits on the module path. The
 * tool's own features are plugins too (the built-in plugins register through the same
 * {@link PluginContext}), so anything a built-in feature can contribute, a third-party one can.
 *
 * <p>Order: a plugin registers after every plugin it {@link #requires() requires}. A plugin whose
 * {@link #register} throws is rolled back (all of its contributions are removed) and disabled;
 * the tool and the other plugins keep starting. Plugins that require a disabled or missing plugin
 * are disabled too.
 */
public interface OpenMasonPlugin {

    /** Stable, unique id: lowercase, dotted or dashed (e.g. {@code openmason.texture}). */
    String id();

    /** Human-readable name for the Plugins preference page. */
    default String displayName() {
        return id();
    }

    /** The plugin's own version, shown on the Plugins page. */
    default String version() {
        return "1.0";
    }

    /** Ids of the plugins this one needs registered first. */
    default Set<String> requires() {
        return Set.of();
    }

    /**
     * Contributes everything the plugin adds. Runs once, on the UI thread, after the core services
     * are published in {@link PluginContext#services()} and the plugins this one requires have
     * registered. Throwing rolls back what this call registered.
     */
    void register(PluginContext context);

    /** The editor finished starting (every plugin registered, main loop about to run). */
    default void started() {
    }

    /** The editor is shutting down; release what {@link #register} acquired outside the registries. */
    default void stopping() {
    }
}
