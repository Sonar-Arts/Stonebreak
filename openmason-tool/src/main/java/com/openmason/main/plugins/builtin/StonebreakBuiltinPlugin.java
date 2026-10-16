package com.openmason.main.plugins.builtin;

import com.openmason.api.OpenMasonPlugin;
import com.openmason.api.PluginContext;
import com.openmason.api.ServiceRegistry;
import com.openmason.api.files.FileOpener;
import com.openmason.api.menu.MenuItem;
import com.openmason.api.menu.TopMenu;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.mcp.BuiltinKnowledge;
import com.openmason.main.systems.mcp.BuiltinMcpTools;
import com.openmason.main.systems.menus.AssetExportMenuActions;
import com.openmason.main.systems.menus.dialogs.SBEEditorWindow;
import com.openmason.main.systems.menus.dialogs.SBOEditorWindow;

import java.util.Set;

/**
 * Everything in the tool that is about Stonebreak's own assets, gathered into one plugin (#379)
 * so #382 can move it into {@code openmason-plugin-stonebreak} unchanged: the .sbo/.sbe/.sbt file
 * types (opened in their editor windows), Tools → Export SBO/SBE and SBO/SBE Editor, the asset
 * export and asset-lens MCP tools, the {@code assets} topic, the block/item/.SBT viewport content,
 * the New Biome template and the Stonebreak game target (#380). It only wires tool classes; none of it names a game type directly
 * (the boundary ratchet allows no new referrers).
 */
public final class StonebreakBuiltinPlugin implements OpenMasonPlugin {

    public static final String ID = "stonebreak";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Stonebreak (built in)";
    }

    @Override
    public Set<String> requires() {
        return Set.of(com.openmason.main.plugins.PluginManager.CORE_ID);
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

        ViewportController viewport = services.find(ViewportController.class).orElse(null);
        if (viewport != null) {
            viewport.builtinContentProviders().forEach(context.viewportContent()::register);
        }
        context.gameTargets().register(StonebreakGameTarget.create());
        context.templates().register(StonebreakGameTarget.newBiomeTemplate());
    }
}
