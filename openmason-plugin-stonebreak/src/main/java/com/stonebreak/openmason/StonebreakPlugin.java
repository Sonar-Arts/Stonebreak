package com.stonebreak.openmason;

import com.openmason.api.OpenMasonPlugin;
import com.openmason.api.PluginContext;
import com.openmason.api.ServiceRegistry;
import com.openmason.api.knowledge.KnowledgePack;
import com.openmason.api.knowledge.ResourceText;
import com.openmason.api.lifecycle.LifecycleListener;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.plugins.PluginManager;
import com.stonebreak.openmason.assets.StonebreakAssetSections;
import com.stonebreak.openmason.assets.mcp.StonebreakMcpTools;
import com.stonebreak.openmason.item.StonebreakItemContent;

import java.util.Set;

/**
 * Stonebreak's tooling for Open Mason (#382, epic #390): the Stonebreak game target (#380: its
 * folders, UI host and numeric-id registries), the game-data sections of the SBO/SBE editor and
 * export windows (#431: game properties, mining, fuel, recipes, smelting, drops, tool, weapon,
 * entity type), the asset export and asset-lens MCP tools, the {@code assets} guide topic, the
 * {@code stonebreak_conventions} knowledge pack, the game-item viewport content and the New Biome
 * template. The windows themselves, the .sbo/.sbe/.sbt file types and the .SBT viewport content
 * are core (#431).
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
        // Game data in the (core) SBO/SBE editor and export windows (#431).
        StonebreakAssetSections.register(context);

        StonebreakMcpTools.all().forEach(context.mcpTools()::register);
        context.guideTopics().register(StonebreakMcpTools.assetsTopic());
        context.knowledgePacks().register(conventionsPack());

        ViewportController viewport = services.find(ViewportController.class).orElse(null);
        StonebreakItemContent items = new StonebreakItemContent(() -> viewport);
        context.viewportContent().register(items);
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
