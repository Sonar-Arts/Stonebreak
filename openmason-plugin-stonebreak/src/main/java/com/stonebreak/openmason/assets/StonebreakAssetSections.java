package com.stonebreak.openmason.assets;

import com.stonebreak.gameplay.SboGameplay;
import com.openmason.api.PluginContext;
import com.openmason.api.asset.AssetEditorSection;
import com.openmason.api.asset.AssetSectionContribution;
import com.openmason.api.asset.AssetSectionHost;
import com.openmason.api.asset.AssetSurface;
import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.stonebreak.mobs.sbe.SbeEntityKind;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.type.ImInt;

import java.util.List;

/**
 * Stonebreak's game data in the SBO/SBE editor and export windows (#431). The tab order matches the
 * pre-#431 editor: Metadata, Game Properties, States, Recipes, Smelting, Sounds, Drops, Tool, Weapon.
 *
 * <p>The SBO sections read and write the {@code stonebreak:gameplay} extension ({@link SboGameplay},
 * #383); an older file's top-level fields are read through it and move into the extension on save.
 */
public final class StonebreakAssetSections {

    private StonebreakAssetSections() {
    }

    /** Every section this plugin contributes. */
    public static List<AssetSectionContribution<?>> all() {
        return List.of(
                new AssetSectionContribution<>("stonebreak.sbo.game-properties", AssetSurface.SBO_EDITOR, 100,
                        GamePropertiesSection::new),
                new AssetSectionContribution<>("stonebreak.sbo.recipes", AssetSurface.SBO_EDITOR, 300,
                        RecipesSection::new),
                new AssetSectionContribution<>("stonebreak.sbo.smelting", AssetSurface.SBO_EDITOR, 400,
                        SmeltingSection::new),
                new AssetSectionContribution<>("stonebreak.sbo.fuel-table", AssetSurface.SBO_EDITOR, 450,
                        FuelTableSection::new),
                new AssetSectionContribution<>("stonebreak.sbo.drops", AssetSurface.SBO_EDITOR, 600,
                        DropsSection::new),
                new AssetSectionContribution<>("stonebreak.sbo.tool", AssetSurface.SBO_EDITOR, 700,
                        ToolSection::new),
                new AssetSectionContribution<>("stonebreak.sbo.weapon", AssetSurface.SBO_EDITOR, 800,
                        WeaponSection::new),
                new AssetSectionContribution<>("stonebreak.sbo-export.game-properties", AssetSurface.SBO_EXPORT,
                        100, ExportGamePropertiesSection::new),
                new AssetSectionContribution<>("stonebreak.sbe.entity-type", AssetSurface.SBE_EDITOR, 100,
                        h -> new EntityTypeEditorSection(h)),
                new AssetSectionContribution<>("stonebreak.sbe-export.entity-type", AssetSurface.SBE_EXPORT, 100,
                        h -> new EntityTypeExportSection()));
    }

    /** Registers {@link #all()}. */
    public static void register(PluginContext context) {
        all().forEach(context.assetSections()::register);
    }

    // ── SBO editor ───────────────────────────────────────────────────────────

    /** Shaped crafting recipes (1.4+) that produce this SBO. */
    static final class RecipesSection implements AssetEditorSection<SBOFormat.Document> {
        final SBORecipeSection form;

        RecipesSection(AssetSectionHost host) {
            this.form = new SBORecipeSection(host::markDirty, host::objectId);
        }

        @Override
        public String title() {
            return "Recipes";
        }

        @Override
        public void load(SBOFormat.Document doc) {
            form.setFromRecipeData(SboGameplay.of(doc).recipes());
        }

        @Override
        public void render() {
            form.render();
        }

        @Override
        public SBOFormat.Document write(SBOFormat.Document doc) {
            return SboGameplay.of(doc).withRecipes(form.toRecipeData()).applyTo(doc);
        }

        @Override
        public void close() {
            form.close();
            SBOIngredientIcons.clear();
        }
    }

    /** Furnace recipes (1.5+) that produce this SBO. */
    static final class SmeltingSection implements AssetEditorSection<SBOFormat.Document> {
        final SBOSmeltingSection form;

        SmeltingSection(AssetSectionHost host) {
            this.form = new SBOSmeltingSection(host::markDirty, host::objectId);
        }

        @Override
        public String title() {
            return "Smelting";
        }

        @Override
        public void load(SBOFormat.Document doc) {
            form.setFromSmeltingData(SboGameplay.of(doc).smeltingRecipes());
        }

        @Override
        public void render() {
            form.render();
        }

        @Override
        public SBOFormat.Document write(SBOFormat.Document doc) {
            return SboGameplay.of(doc).withSmeltingRecipes(form.toSmeltingRecipeData()).applyTo(doc);
        }
    }

    /** Fuel table: burn times for other objects / whole dictionaries (the furnace carries the game's). */
    static final class FuelTableSection implements AssetEditorSection<SBOFormat.Document> {
        final SBOFuelTableSection form;

        FuelTableSection(AssetSectionHost host) {
            this.form = new SBOFuelTableSection(host::markDirty);
        }

        @Override
        public String title() {
            return "Fuel Table";
        }

        @Override
        public void load(SBOFormat.Document doc) {
            form.setFrom(SboGameplay.of(doc).fuelTable());
        }

        @Override
        public void render() {
            form.render();
        }

        @Override
        public SBOFormat.Document write(SBOFormat.Document doc) {
            return SboGameplay.of(doc).withFuelTable(form.toFuelTableData()).applyTo(doc);
        }
    }

    /** The block's drop table (1.8+). */
    static final class DropsSection implements AssetEditorSection<SBOFormat.Document> {
        final SBODropsSection form;

        DropsSection(AssetSectionHost host) {
            this.form = new SBODropsSection(host::markDirty);
        }

        @Override
        public String title() {
            return "Drops";
        }

        @Override
        public void load(SBOFormat.Document doc) {
            form.setFromDropData(SboGameplay.of(doc).drops());
        }

        @Override
        public void render() {
            form.render();
        }

        @Override
        public String validate() {
            return form.validate();
        }

        @Override
        public SBOFormat.Document write(SBOFormat.Document doc) {
            return SboGameplay.of(doc).withDrops(form.toDropData()).applyTo(doc);
        }

        @Override
        public void close() {
            form.close();
        }
    }

    /** The item's mining-tool data (1.9+). */
    static final class ToolSection implements AssetEditorSection<SBOFormat.Document> {
        private final AssetSectionHost host;
        final SBOToolSection form;

        ToolSection(AssetSectionHost host) {
            this.host = host;
            this.form = new SBOToolSection(host::markDirty);
        }

        @Override
        public String title() {
            return "Tool";
        }

        @Override
        public void load(SBOFormat.Document doc) {
            form.setFromToolData(SboGameplay.of(doc).tool());
        }

        @Override
        public void render() {
            itemOnlyWarning(host, "Tool");
            form.render();
        }

        @Override
        public String validate() {
            return form.validate();
        }

        /** An invalid form only reaches here from a snapshot (writes validate first): keep the last good data. */
        @Override
        public SBOFormat.Document write(SBOFormat.Document doc) {
            return form.validate() == null ? SboGameplay.of(doc).withTool(form.toToolData()).applyTo(doc) : doc;
        }

        @Override
        public void written() {
            SBOMiningIndex.invalidate(); // tools may have changed
        }
    }

    /** The item's melee weapon data (1.10+). */
    static final class WeaponSection implements AssetEditorSection<SBOFormat.Document> {
        private final AssetSectionHost host;
        final SBOWeaponSection form;

        WeaponSection(AssetSectionHost host) {
            this.host = host;
            this.form = new SBOWeaponSection(host::markDirty);
        }

        @Override
        public String title() {
            return "Weapon";
        }

        @Override
        public void load(SBOFormat.Document doc) {
            form.setFromWeaponData(SboGameplay.of(doc).weapon());
        }

        @Override
        public void render() {
            itemOnlyWarning(host, "Weapon");
            form.render();
        }

        @Override
        public String validate() {
            return form.validate();
        }

        @Override
        public SBOFormat.Document write(SBOFormat.Document doc) {
            return form.validate() == null ? SboGameplay.of(doc).withWeapon(form.toWeaponData()).applyTo(doc) : doc;
        }
    }

    private static void itemOnlyWarning(AssetSectionHost host, String what) {
        if (!"item".equals(host.objectType())) {
            ThemedWidgets.statusText(ThemeColors.Tone.WARNING, what
                    + " data is read from item SBOs only. Set Object Type to \"item\" on the Metadata tab.");
            ImGui.dummy(0, 4);
        }
    }

    // ── SBE ──────────────────────────────────────────────────────────────────

    /** Stonebreak's entity types, in picker order (the {@code sbe} target folders follow them). */
    static final String[] ENTITY_TYPE_LABELS = java.util.Arrays.stream(SbeEntityKind.values())
            .map(SbeEntityKind::label).toArray(String[]::new);

    /** The picker row of {@code id}; unknown names show as the first entry, as before #431. */
    static int entityTypeIndex(String id) {
        if (id == null) return 0;
        for (SbeEntityKind k : SbeEntityKind.values()) {
            if (k.id().equalsIgnoreCase(id.trim())) return k.ordinal();
        }
        return 0;
    }

    /** The SBE editor's Entity Type picker (Metadata tab). */
    static final class EntityTypeEditorSection implements AssetEditorSection<SBEFormat.Document> {
        private final AssetSectionHost host;
        final ImInt index = new ImInt(0);

        EntityTypeEditorSection(AssetSectionHost host) {
            this.host = host;
        }

        @Override
        public String title() {
            return "Entity";
        }

        @Override
        public Placement placement() {
            return Placement.METADATA;
        }

        @Override
        public void load(SBEFormat.Document doc) {
            index.set(entityTypeIndex(doc.entityType()));
        }

        @Override
        public void render() {
            if (ImGui.combo("Entity Type", index, ENTITY_TYPE_LABELS)) host.markDirty();
        }

        @Override
        public SBEFormat.Document write(SBEFormat.Document doc) {
            return doc.withEntityType(SbeEntityKind.values()[index.get()].id());
        }
    }

    /** The Export SBE window's Entity Type picker (Metadata tab; chooses the target folder too). */
    static final class EntityTypeExportSection implements AssetEditorSection<SBEFormat.ExportParameters> {
        final ImInt index = new ImInt(0);

        @Override
        public String title() {
            return "Entity";
        }

        @Override
        public Placement placement() {
            return Placement.METADATA;
        }

        @Override
        public void load(SBEFormat.ExportParameters params) {
            index.set(0);
        }

        @Override
        public void render() {
            ImGui.combo("Entity Type", index, ENTITY_TYPE_LABELS);
        }

        @Override
        public SBEFormat.ExportParameters write(SBEFormat.ExportParameters params) {
            params.setEntityType(SbeEntityKind.values()[index.get()].id());
            return params;
        }
    }
}
