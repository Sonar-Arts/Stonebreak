package com.openmason.main.plugins.builtin;

import com.openmason.api.target.AssetFolders;
import com.openmason.api.target.GameTarget;
import com.openmason.api.target.ResourceRoot;
import com.openmason.api.target.UiDeployLayout;
import com.openmason.api.target.WriteRules;
import com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory.Shape;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.uiEditor.service.UiGameDeploy;

import java.util.List;

/**
 * The Stonebreak {@link GameTarget} (#380): the one place the editor learns where Stonebreak keeps
 * its assets, how it names them and how its UI host is reached. Contributed by
 * {@link StonebreakBuiltinPlugin}; moves with it into {@code openmason-plugin-stonebreak} (#382).
 */
public final class StonebreakGameTarget {

    public static final String ID = "stonebreak";

    /** The game module's source resource tree, relative to the repository root. */
    static final String SOURCE_TREE = "stonebreak-game/src/main/resources";

    /** Names the resource tree directly, overriding the search from the working directory. */
    public static final String RESOURCES_PROPERTY = "openmason.target.stonebreak.resources";

    private StonebreakGameTarget() {
    }

    public static GameTarget create() {
        String sbo = BuiltinFileTypes.SBO.id();
        String sbe = BuiltinFileTypes.SBE.id();
        return GameTarget.builder(ID, "Stonebreak")
                .namespace("stonebreak")
                // The game's packaged resources are on this module's loader (the tool requires the
                // game module until #382; the plugin module will carry the same loader then).
                .resources(new ResourceRoot(List.of(SOURCE_TREE), RESOURCES_PROPERTY,
                        StonebreakGameTarget.class.getClassLoader()))
                .folders(AssetFolders.builder()
                        .base(sbo, "sbo")
                        .subtype(sbo, "block", "sbo/blocks")
                        .subtype(sbo, "item", "sbo/items")
                        .subtype(sbo, "model", "sbo/models")
                        .base(sbe, "sbe")
                        .subtype(sbe, "mob", "sbe/Mobs")
                        .subtype(sbe, "clothing", "sbe/Clothing")
                        .subtype(sbe, "playercustomize", "sbe/PlayerCustomize")
                        .subtype(sbe, "particle", "sbe/Particles")
                        .build())
                .assetFilePrefix("SB_")
                .writes(WriteRules.SHIPPED_ASSETS)
                .uiDeploy(new UiDeployLayout("ui/documents", "ui/shared"))
                .uiHost(UiGameDeploy.stonebreakHost())
                .legacyDefault(true)
                .build();
    }

    /** A biome's block set, targeting Stonebreak. */
    public static ProjectTemplate newBiomeTemplate() {
        return new ProjectTemplate.Builder()
                .id("new-biome")
                .name("New Biome")
                .description("Start a biome's block set: blank Log, Planks, Stairs, Leaves, Sapling and Grass models, "
                        + "ready to shape and texture.")
                .category("Biome")
                .type(ProjectTemplate.TemplateType.BLOCK_SET)
                .target(ID)
                .metadata("Models", "Log, Planks, Stairs, Leaves, Sapling, Grass")
                .model("Log", Shape.CUBE)
                .model("Planks", Shape.CUBE)
                // The game's own stair shape (all wood stairs share it), without the wood texture.
                .modelFromTargetSbo("Stairs", "sbo/blocks/SB_Oak_Stairs.sbo")
                .model("Leaves", Shape.CUBE)
                .model("Sapling", Shape.CROSS)
                .model("Grass", Shape.CUBE)
                .openOnCreate("Log")
                .build();
    }
}
