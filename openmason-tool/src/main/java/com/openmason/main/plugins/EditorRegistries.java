package com.openmason.main.plugins;

import com.openmason.api.Contributions;
import com.openmason.api.PluginContext;
import com.openmason.api.PluginInfo;
import com.openmason.api.Registrar;
import com.openmason.api.Registration;
import com.openmason.api.ServiceRegistry;
import com.openmason.api.files.FileOpener;
import com.openmason.api.files.FileType;
import com.openmason.api.knowledge.GuideTopic;
import com.openmason.api.knowledge.KnowledgePack;
import com.openmason.api.lifecycle.LifecycleListener;
import com.openmason.api.mcp.McpToolContribution;
import com.openmason.api.menu.MenuEntry;
import com.openmason.api.menu.TopMenu;
import com.openmason.api.prefs.PreferencePage;
import com.openmason.api.target.GameTarget;
import com.openmason.api.viewport.ViewportContentProvider;
import com.openmason.api.workspace.WorkspaceDescriptor;
import com.openmason.main.systems.keybinds.KeybindAction;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.themes.core.ThemeDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every contribution list of the editor plus the shared {@link ServiceRegistry}: the host side of
 * {@link PluginContext} (#379). The editor shell reads these lists (the tab strip reads the
 * workspaces, the menu bar the menus...); plugins fill them through owner-bound contexts.
 */
public final class EditorRegistries {

    private final ServiceRegistry services = new ServiceRegistry();
    private final Contributions<WorkspaceDescriptor> workspaces = new Contributions<>("workspace", WorkspaceDescriptor::id);
    private final Contributions<TopMenu> menus = new Contributions<>("menu", TopMenu::id);
    private final Contributions<MenuEntry> menuEntries = new Contributions<>("menu entry", MenuEntry::id);
    private final Contributions<KeybindAction> commands = new Contributions<>("command", KeybindAction::getId);
    private final Contributions<FileType> fileTypes = new Contributions<>("file type", FileType::id);
    private final Contributions<FileOpener> fileOpeners = new Contributions<>("file opener", FileOpener::fileTypeId);
    private final Contributions<McpToolContribution> mcpTools = new Contributions<>("MCP tool group", McpToolContribution::id);
    private final Contributions<GuideTopic> guideTopics = new Contributions<>("guide topic", GuideTopic::id);
    private final Contributions<KnowledgePack> knowledgePacks = new Contributions<>("knowledge pack", KnowledgePack::id);
    private final Contributions<PreferencePage> preferencePages = new Contributions<>("preference page", PreferencePage::id);
    private final Contributions<ViewportContentProvider> viewportContent =
            new Contributions<>("viewport content", ViewportContentProvider::id);
    private final Contributions<ProjectTemplate> templates = new Contributions<>("project template", ProjectTemplate::getId);
    private final Contributions<GameTarget> gameTargets = new Contributions<>("game target", GameTarget::id);
    private final Contributions<ThemeDefinition> themes = new Contributions<>("theme", ThemeDefinition::getId);
    private final Contributions<LifecycleListener> lifecycle = new Contributions<>("lifecycle listener",
            l -> l.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(l)));

    /** Services a plugin published, withdrawn with the rest of its contributions on failure. */
    private final Map<String, List<Registration>> publishedServices = new ConcurrentHashMap<>();

    public ServiceRegistry services() {
        return services;
    }

    public Contributions<WorkspaceDescriptor> workspaces() {
        return workspaces;
    }

    public Contributions<TopMenu> menus() {
        return menus;
    }

    public Contributions<MenuEntry> menuEntries() {
        return menuEntries;
    }

    public Contributions<KeybindAction> commands() {
        return commands;
    }

    public Contributions<FileType> fileTypes() {
        return fileTypes;
    }

    public Contributions<FileOpener> fileOpeners() {
        return fileOpeners;
    }

    public Contributions<McpToolContribution> mcpTools() {
        return mcpTools;
    }

    public Contributions<GuideTopic> guideTopics() {
        return guideTopics;
    }

    public Contributions<KnowledgePack> knowledgePacks() {
        return knowledgePacks;
    }

    public Contributions<PreferencePage> preferencePages() {
        return preferencePages;
    }

    public Contributions<ViewportContentProvider> viewportContent() {
        return viewportContent;
    }

    public Contributions<ProjectTemplate> templates() {
        return templates;
    }

    public Contributions<GameTarget> gameTargets() {
        return gameTargets;
    }

    public Contributions<ThemeDefinition> themes() {
        return themes;
    }

    public Contributions<LifecycleListener> lifecycle() {
        return lifecycle;
    }

    /** Every contribution list, for owner-wide operations. */
    public List<Contributions<?>> all() {
        return List.of(workspaces, menus, menuEntries, commands, fileTypes, fileOpeners, mcpTools, guideTopics,
                knowledgePacks, preferencePages, viewportContent, templates, gameTargets, themes, lifecycle);
    }

    /**
     * Mirrors the command contributions into {@code keybinds} (where the Keybinds page, the shortcut
     * dispatch and the menus find them). Call once, before plugins register.
     */
    public void forwardCommandsTo(com.openmason.main.systems.keybinds.KeybindRegistry keybinds) {
        commands.addListener(new Contributions.Listener<>() {
            @Override
            public void added(KeybindAction action) {
                keybinds.registerAction(action);
            }

            @Override
            public void removed(KeybindAction action) {
                // Only our own instance: a refused duplicate must not take the original down with it.
                if (keybinds.getAction(action.getId()) == action) {
                    keybinds.unregisterAction(action.getId());
                }
            }
        });
    }

    /** A context whose registrations are owned by {@code plugin}. */
    public PluginContext contextFor(PluginInfo plugin) {
        String owner = plugin.id();
        ServiceRegistry tracked = services.tracking(r ->
                publishedServices.computeIfAbsent(owner, k -> new ArrayList<>()).add(r));
        return new PluginContext() {
            @Override
            public PluginInfo plugin() {
                return plugin;
            }

            @Override
            public ServiceRegistry services() {
                return tracked;
            }

            @Override
            public Registrar<WorkspaceDescriptor> workspaces() {
                return workspaces.registrar(owner);
            }

            @Override
            public Registrar<TopMenu> menus() {
                return menus.registrar(owner);
            }

            @Override
            public Registrar<MenuEntry> menuEntries() {
                return menuEntries.registrar(owner);
            }

            @Override
            public Registrar<KeybindAction> commands() {
                return commands.registrar(owner);
            }

            @Override
            public Registrar<FileType> fileTypes() {
                return fileTypes.registrar(owner);
            }

            @Override
            public Registrar<FileOpener> fileOpeners() {
                return fileOpeners.registrar(owner);
            }

            @Override
            public Registrar<McpToolContribution> mcpTools() {
                return mcpTools.registrar(owner);
            }

            @Override
            public Registrar<GuideTopic> guideTopics() {
                return guideTopics.registrar(owner);
            }

            @Override
            public Registrar<KnowledgePack> knowledgePacks() {
                return knowledgePacks.registrar(owner);
            }

            @Override
            public Registrar<PreferencePage> preferencePages() {
                return preferencePages.registrar(owner);
            }

            @Override
            public Registrar<ViewportContentProvider> viewportContent() {
                return viewportContent.registrar(owner);
            }

            @Override
            public Registrar<ProjectTemplate> templates() {
                return templates.registrar(owner);
            }

            @Override
            public Registrar<GameTarget> gameTargets() {
                return gameTargets.registrar(owner);
            }

            @Override
            public Registrar<ThemeDefinition> themes() {
                return themes.registrar(owner);
            }

            @Override
            public Registrar<LifecycleListener> lifecycle() {
                return lifecycle.registrar(owner);
            }
        };
    }

    /** Removes everything {@code owner} contributed or published (a failed plugin's rollback). */
    public void removeOwner(String owner) {
        // Reverse order: things registered later may depend on earlier ones (an opener on its type).
        List<Contributions<?>> lists = new ArrayList<>(all());
        java.util.Collections.reverse(lists);
        for (Contributions<?> c : lists) {
            c.removeOwner(owner);
        }
        List<Registration> published = publishedServices.remove(owner);
        if (published != null) {
            published.forEach(Registration::close);
        }
    }
}
