package com.openmason.api;

import com.openmason.api.files.FileOpener;
import com.openmason.api.files.FileType;
import com.openmason.api.knowledge.GuideTopic;
import com.openmason.api.knowledge.KnowledgePack;
import com.openmason.api.lifecycle.LifecycleListener;
import com.openmason.api.mcp.McpToolContribution;
import com.openmason.api.menu.MenuEntry;
import com.openmason.api.menu.TopMenu;
import com.openmason.api.prefs.PreferencePage;
import com.openmason.api.viewport.ViewportContentProvider;
import com.openmason.api.workspace.WorkspaceDescriptor;
import com.openmason.main.systems.keybinds.KeybindAction;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.themes.core.ThemeDefinition;

/**
 * What a plugin registers with, handed to {@link OpenMasonPlugin#register}. Every registrar
 * records the plugin as the owner of what it adds, so a plugin that fails part-way is rolled back
 * cleanly. Readers see everything registered so far, including other plugins' contributions.
 */
public interface PluginContext {

    /** The plugin being registered. */
    PluginInfo plugin();

    /** Typed services: the editor's core services, and anything other plugins published. */
    ServiceRegistry services();

    /** Main-window workspace tabs. */
    Registrar<WorkspaceDescriptor> workspaces();

    /** Top-level entries of the main menu bar. */
    Registrar<TopMenu> menus();

    /** Items and sections inside the main menus. */
    Registrar<MenuEntry> menuEntries();

    /**
     * Commands: keybind-able actions (shown and rebindable on the Keybinds preference page). A
     * menu item refers to one by id to show its shortcut and run it.
     */
    Registrar<KeybindAction> commands();

    /** File types: extensions, default save targets, Project Browser listing. */
    Registrar<FileType> fileTypes();

    /** What opening a file of a given type does. */
    Registrar<FileOpener> fileOpeners();

    /** MCP tool groups. */
    Registrar<McpToolContribution> mcpTools();

    /** Topics of the {@code describe_api} agent guide. */
    Registrar<GuideTopic> guideTopics();

    /** Packs of the {@code knowledge} base. */
    Registrar<KnowledgePack> knowledgePacks();

    /** Preferences window pages. */
    Registrar<PreferencePage> preferencePages();

    /** Non-model content for the Model Editor viewport. */
    Registrar<ViewportContentProvider> viewportContent();

    /** Project Hub templates. */
    Registrar<ProjectTemplate> templates();

    /** Editor colour themes. */
    Registrar<ThemeDefinition> themes();

    /** Application lifecycle listeners. */
    Registrar<LifecycleListener> lifecycle();
}
