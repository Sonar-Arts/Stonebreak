package com.stonebreak.openmason;

import com.openmason.api.OpenMasonPlugin;
import com.openmason.api.PluginContext;
import com.openmason.api.ServiceRegistry;
import com.openmason.api.files.FileOpener;
import com.openmason.api.knowledge.KnowledgePack;
import com.openmason.api.knowledge.ResourceText;
import com.openmason.api.lifecycle.LifecycleListener;
import com.openmason.api.menu.MenuItem;
import com.openmason.api.menu.TopMenu;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.mcp.BuiltinKnowledge;
import com.openmason.main.systems.mcp.BuiltinMcpTools;
import com.openmason.main.systems.menus.AssetExportMenuActions;
import com.openmason.main.systems.menus.dialogs.SBEEditorWindow;
import com.openmason.main.systems.menus.dialogs.SBOEditorWindow;
import com.openmason.main.plugins.PluginManager;
import com.stonebreak.openmason.item.StonebreakItemContent;

import java.util.Set;

/**
 * Stonebreak's tooling for Open Mason (#382, epic #390): the Stonebreak game target (#380: its
 * folders, UI host and numeric-id registries), the .sbo/.sbe/.sbt file types (opened in their
 * editor windows), Tools → Export SBO/SBE and SBO/SBE Editor, the asset export and asset-lens MCP
 * tools, the {@code assets} guide topic, the {@code stonebreak_conventions} knowledge pack, the
 * game-item and .SBT viewport content and the New Biome template.
 *
 * <p>This module depends on the tool and on {@code stonebreak-game}; the tool never depends on it.
 * The tool finds it on the module path ({@code provides com.openmason.api.OpenMasonPlugin}) or the
 * class path ({@code META-INF/services}); without it Open Mason runs as a generic editor.
 */
public final class StonebreakPlugin implements OpenMasonPlugin {

    public static final String ID = "stonebreak";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Stonebreak";
    }

    @Override
    public Set<String> requires() {
        return Set.of(PluginManager.CORE_ID);
    }

    @Override
    public void register(PluginContext context) {
        ServiceRegistry services = context.services();
        AssetExportMenuActions actions = services.require(AssetExportMenuActions.class);
        context.menuEntries().register(MenuItem.of("stonebreak.tools.export-sbo", TopMenu.TOOLS, 200,
                "Export SBO...", actions::exportSBO).withEnabled(actions::canExport).withSeparatorBefore());
        context.menuEntries().register(MenuItem.of("stonebreak.tools.export-sbe", TopMenu.TOOLS, 210,
                "Export SBE...", actions::exportSBE).withEnabled(actions::canExport));
        context.menuEntries().register(MenuItem.of("stonebreak.tools.sbo-editor", TopMenu.TOOLS, 300,
                "SBO Editor...", actions::openSboEditor).withSeparatorBefore());
        context.menuEntries().register(MenuItem.of("stonebreak.tools.sbe-editor", TopMenu.TOOLS, 310,
                "SBE Editor...", actions::openSbeEditor));

        context.fileTypes().register(BuiltinFileTypes.SBO);
        context.fileTypes().register(BuiltinFileTypes.SBE);
        context.fileTypes().register(BuiltinFileTypes.SBT);
        context.fileOpeners().register(new FileOpener(BuiltinFileTypes.SBO.id(),
                path -> services.require(SBOEditorWindow.class).openFile(path.toString())));
        context.fileOpeners().register(new FileOpener(BuiltinFileTypes.SBE.id(),
                path -> services.require(SBEEditorWindow.class).openFile(path.toString())));

        context.mcpTools().register(BuiltinMcpTools.ASSET_EXPORT);
        context.mcpTools().register(BuiltinMcpTools.ASSET_LENS);
        context.guideTopics().register(BuiltinKnowledge.topic("assets"));
        context.knowledgePacks().register(conventionsPack());

        ViewportController viewport = services.find(ViewportController.class).orElse(null);
        StonebreakItemContent items = new StonebreakItemContent(() -> viewport);
        context.viewportContent().register(items);
        if (viewport != null) {
            viewport.builtinContentProviders().forEach(context.viewportContent()::register);
        }
        context.lifecycle().register(new LifecycleListener() {
            @Override
            public void shuttingDown() {
                items.close();
            }
        });
        context.gameTargets().register(StonebreakGameTarget.create());
        context.templates().register(StonebreakGameTarget.newBiomeTemplate());
    }

    /** Stonebreak's scale, facing, clip-name and asset-data conventions for agents. */
    static KnowledgePack conventionsPack() {
        // The opener is written here, so the lookup runs in this module (resources are encapsulated).
        return new KnowledgePack("stonebreak_conventions",
                ResourceText.of(StonebreakPlugin.class::getResourceAsStream, "/openmason/kb/stonebreak_conventions.md"));
    }
}
