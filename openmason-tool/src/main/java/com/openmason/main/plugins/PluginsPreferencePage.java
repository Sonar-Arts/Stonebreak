package com.openmason.main.plugins;

import com.openmason.api.Contributions;
import com.openmason.api.prefs.PreferencePage;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;

import java.util.ArrayList;
import java.util.List;

/**
 * Preferences -> Plugins (#379): the editor's own features as one summary line (they are not
 * plugins), then every discovered plugin with its version, module, outcome
 * (active, failed and rolled back, blocked by a dependency...) and what it contributes. Read-only:
 * plugins load at start-up, so enabling/disabling one is a restart-time setting for a later phase.
 */
public final class PluginsPreferencePage implements PreferencePage {

    public static final String ID = "plugins";

    private final PluginManager plugins;
    private final EditorRegistries registries;

    public PluginsPreferencePage(PluginManager plugins, EditorRegistries registries) {
        this.plugins = plugins;
        this.registries = registries;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Plugins";
    }

    @Override
    public int order() {
        return 700;
    }

    @Override
    public void render() {
        List<PluginManager.PluginStatus> statuses = plugins.statuses();
        ImGui.textWrapped("Plugins add workspaces, menus, file types, MCP tools and more. They are found on "
                + "the module path at start-up; changes take effect after a restart.");
        ImGui.textDisabled("Built into Open Mason (always present): " + contributions(PluginManager.CORE_ID));
        ImGui.spacing();
        if (statuses.isEmpty()) {
            ImGui.textDisabled("No plugins installed.");
            return;
        }
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("##plugins", 4, flags)) {
            ImGui.tableSetupColumn("Plugin");
            ImGui.tableSetupColumn("Version");
            ImGui.tableSetupColumn("Status");
            ImGui.tableSetupColumn("Contributes");
            ImGui.tableHeadersRow();
            for (PluginManager.PluginStatus s : statuses) {
                ImGui.tableNextRow();
                ImGui.tableNextColumn();
                ImGui.text(s.info().displayName());
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(s.info().id() + "\nmodule: " + s.info().module()
                            + (s.info().requires().isEmpty() ? "" : "\nrequires: " + String.join(", ", s.info().requires()))
                            + (s.info().builtIn() ? "\nbuilt in" : ""));
                }
                ImGui.tableNextColumn();
                ImGui.text(s.info().version());
                ImGui.tableNextColumn();
                status(s);
                ImGui.tableNextColumn();
                ImGui.textWrapped(contributions(s.info().id()));
            }
            ImGui.endTable();
        }
    }

    private static void status(PluginManager.PluginStatus s) {
        switch (s.state()) {
            case ACTIVE -> ThemedWidgets.statusText(ThemeColors.Tone.SUCCESS, "Active");
            case FAILED -> ThemedWidgets.statusText(ThemeColors.Tone.ERROR, "Failed");
            default -> ThemedWidgets.statusText(ThemeColors.Tone.WARNING,
                    s.state().name().charAt(0) + s.state().name().substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        if (s.detail() != null && ImGui.isItemHovered()) {
            ImGui.setTooltip(s.detail());
        }
    }

    /** "2 workspaces, 3 file types, ..." for one plugin. */
    String contributions(String pluginId) {
        List<String> parts = new ArrayList<>();
        for (Contributions<?> c : registries.all()) {
            int n = c.ownedBy(pluginId).size();
            if (n > 0) {
                parts.add(n + " " + c.kind() + (n == 1 ? "" : "s"));
            }
        }
        return parts.isEmpty() ? "-" : String.join(", ", parts);
    }
}
