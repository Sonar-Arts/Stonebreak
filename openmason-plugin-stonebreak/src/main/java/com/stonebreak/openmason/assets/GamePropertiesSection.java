package com.stonebreak.openmason.assets;

import com.stonebreak.gameplay.SboGameplay;
import com.stonebreak.items.ToolMiningRules;
import com.openmason.api.asset.AssetEditorSection;
import com.openmason.api.asset.AssetSectionHost;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.main.systems.menus.dialogs.EditorWidgets;
import com.openmason.main.systems.menus.dialogs.validation.NumericIdValidator;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The SBO editor's Game Properties tab (#431, was {@code SBOEditorWindow.renderGamePropertiesTab}):
 * Stonebreak's {@code gameProperties} (numeric id, hardness, solid/breakable/placeable, atlas tile,
 * flower, stacking, category), the block mining controls with a live break-time preview, and the
 * item's fuel value, all in the {@code stonebreak:gameplay} extension (#383). The render layer and
 * transparency are the SBO's own {@code rendering}, on the core Metadata tab.
 */
final class GamePropertiesSection implements AssetEditorSection<SBOFormat.Document> {

    private final AssetSectionHost host;
    private final NumericIdConflictPopup conflictPopup = new NumericIdConflictPopup();
    private final TakenIdsPopup takenIdsPopup = new TakenIdsPopup();

    private boolean hasGameProperties;
    private final ImInt numericId = new ImInt(-1);
    private final ImFloat hardness = new ImFloat(1.0f);
    private final ImBoolean solid = new ImBoolean(true);
    private final ImBoolean breakable = new ImBoolean(true);
    private final ImInt atlasX = new ImInt(-1);
    private final ImInt atlasY = new ImInt(-1);
    private final ImBoolean flower = new ImBoolean(false);
    private final ImBoolean stackable = new ImBoolean(false);
    private final ImInt maxStackSize = new ImInt(64);
    private final ImString category = new ImString(64);
    private final ImBoolean placeable = new ImBoolean(true);
    // Mining (1.9+): block material + minimum tool tier.
    private final ImString material = new ImString(64);
    private final ImInt requiredTier = new ImInt(0);
    // Fuel (1.5+): lives in the top-level Document.fuel field, shown here since it's an item property.
    private boolean isFuel;
    private final ImInt fuelBurnTicks = new ImInt(1600);
    // Item dictionaries this object belongs to (recipes accept any member as "#id").
    private final List<String> dictionaries = new ArrayList<>();
    private final ImString newDictionary = new ImString(96);
    // Tags neighbours' multipart conditions test this block for ("tag:<id>"); shape only.
    private final List<String> connectTags = new ArrayList<>();
    private final ImString newConnectTag = new ImString(96);

    GamePropertiesSection(AssetSectionHost host) {
        this.host = host;
    }

    @Override
    public String title() {
        return "Game Properties";
    }

    @Override
    public void load(SBOFormat.Document doc) {
        SboGameplay data = SboGameplay.of(doc);
        SboGameplay.GameProperties gp = data.gameProperties();
        hasGameProperties = gp != null;
        if (gp != null) {
            numericId.set(gp.numericId());
            hardness.set(gp.hardness());
            solid.set(gp.solid());
            breakable.set(gp.breakable());
            atlasX.set(gp.atlasX());
            atlasY.set(gp.atlasY());
            flower.set(gp.flower());
            stackable.set(gp.stackable());
            maxStackSize.set(gp.maxStackSize());
            category.set(gp.categoryOrDefault());
            placeable.set(gp.placeable());
            material.set(gp.material() != null ? gp.material() : "");
            requiredTier.set(gp.requiredTier());
        }
        isFuel = data.fuel() != null;
        fuelBurnTicks.set(data.fuel() != null ? data.fuel().burnTicks() : 1600);
        dictionaries.clear();
        dictionaries.addAll(data.dictionaries());
        newDictionary.set("");
        connectTags.clear();
        connectTags.addAll(data.connectTags());
        newConnectTag.set("");
    }

    @Override
    public SBOFormat.Document write(SBOFormat.Document doc) {
        SboGameplay.GameProperties gp = hasGameProperties
                ? new SboGameplay.GameProperties(
                        numericId.get(), hardness.get(), solid.get(), breakable.get(),
                        atlasX.get(), atlasY.get(),
                        flower.get(), stackable.get(),
                        maxStackSize.get(), category.get().trim(), placeable.get(),
                        material.get(), requiredTier.get())
                : null;
        return SboGameplay.of(doc).withGameProperties(gp)
                .withFuel(isFuel ? new SboGameplay.FuelData(Math.max(1, fuelBurnTicks.get())) : null)
                .withDictionaries(dictionaries)
                .withConnectTags(connectTags)
                .applyTo(doc);
    }

    @Override
    public String conflict() {
        NumericIdValidator.Result.Conflict c = currentConflict();
        return c == null ? null : "numeric_id_conflict: " + c.numericId() + " is taken by "
                + c.existingObjectId() + " — change gameProperties.numericId";
    }

    @Override
    public boolean confirmWrite(Runnable proceed) {
        NumericIdValidator.Result.Conflict c = currentConflict();
        if (c == null) {
            return false;
        }
        conflictPopup.open(c, proceed);
        return true;
    }

    private NumericIdValidator.Result.Conflict currentConflict() {
        if (!hasGameProperties) {
            return null;
        }
        return NumericIdValidator.validate(domain(), numericId.get(), host.objectId())
                instanceof NumericIdValidator.Result.Conflict c ? c : null;
    }

    @Override
    public void written() {
        SBOMiningIndex.invalidate(); // materials may have changed
        SBODictionaryIndex.invalidate(); // dictionary membership may have changed
    }

    @Override
    public void renderOverlays() {
        conflictPopup.render();
        takenIdsPopup.render();
    }

    @Override
    public void render() {
        if (ImGui.checkbox("Has gameProperties block", new ImBoolean(hasGameProperties))) {
            hasGameProperties = !hasGameProperties;
            host.markDirty();
        }
        if (!hasGameProperties) {
            ImGui.textDisabled("This SBO has no gameProperties block (legacy 1.0).");
            ImGui.dummy(0, 4);
            renderDictionaryControls();
            renderConnectTagControls();
            renderFuelControls();
            return;
        }

        ThemedWidgets.sectionLabel("Identity");
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        if (ImGui.inputInt("Numeric ID", numericId))         host.markDirty();
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.smallButton(EditorWidgets.TAKEN_IDS_LABEL + "##editor_taken")) {
            takenIdsPopup.open(domain());
        }
        renderConflictHint();

        ThemedWidgets.sectionLabel("World");
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        if (ImGui.inputFloat("Hardness", hardness))          host.markDirty();
        ImGui.popItemWidth();
        if (ImGui.checkbox("Solid", solid))                  host.markDirty();
        ImGui.sameLine(160);
        if (ImGui.checkbox("Breakable", breakable))          host.markDirty();
        ImGui.sameLine(320);
        if (ImGui.checkbox("Placeable", placeable))          host.markDirty();

        if ("block".equals(host.objectType())) {
            renderMiningControls();
        }

        ThemedWidgets.sectionLabel("Rendering");
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        if (ImGui.inputInt("Atlas X", atlasX))               host.markDirty();
        ImGui.sameLine(240);
        if (ImGui.inputInt("Atlas Y", atlasY))               host.markDirty();
        ImGui.popItemWidth();
        if (ImGui.checkbox("Flower", flower))                host.markDirty();
        ImGui.textDisabled("Render layer and transparency are on the Metadata tab.");

        ThemedWidgets.sectionLabel("Item");
        if (ImGui.checkbox("Stackable", stackable))          host.markDirty();
        ImGui.sameLine(160);
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        if (ImGui.inputInt("Max Stack Size", maxStackSize))  host.markDirty();
        if (ImGui.inputText("Category", category))           host.markDirty();
        ImGui.popItemWidth();
        renderDictionaryControls();
        renderConnectTagControls();
        ImGui.dummy(0, 4);
        renderFuelControls();
    }

    /**
     * Tags other blocks' Neighbour Shapes conditions can test this block for: a fence's arm
     * condition {@code tag:stonebreak:fence_connectable} reaches every block listing that tag.
     * Shape only; crafting reads Dictionaries.
     */
    private void renderConnectTagControls() {
        ThemedWidgets.sectionLabel("Connect Tags");
        if (connectTags.isEmpty()) {
            ImGui.textDisabled("No connect tags.");
        }
        for (int i = 0; i < connectTags.size(); i++) {
            if (ImGui.smallButton("x##ctag_rm_" + i)) {
                connectTags.remove(i);
                host.markDirty();
                break;
            }
            ImGui.sameLine();
            ImGui.text(connectTags.get(i));
        }
        ImGui.pushItemWidth(EditorWidgets.NAME_FIELD_WIDTH);
        boolean enter = ImGui.inputTextWithHint("##ctag_new", "e.g. stonebreak:fence_connectable", newConnectTag,
                imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue);
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.smallButton("Add##ctag_add") || enter) {
            String tag = newConnectTag.get().strip();
            if (!tag.isEmpty() && !connectTags.contains(tag)) {
                connectTags.add(tag);
                host.markDirty();
            }
            newConnectTag.set("");
        }
        ImGui.textDisabled("Neighbour Shapes conditions test a neighbour for these as \"tag:<id>\".");
    }

    /**
     * The item dictionaries this object is a member of. A recipe slot, smelting input or fuel-table
     * entry written {@code #id} accepts every member, so e.g. a new plank joins every plank recipe
     * by declaring {@code stonebreak:wooden_planks} here.
     */
    private void renderDictionaryControls() {
        ThemedWidgets.sectionLabel("Dictionaries");
        if (dictionaries.isEmpty()) {
            ImGui.textDisabled("Not in any dictionary.");
        }
        for (int i = 0; i < dictionaries.size(); i++) {
            String id = dictionaries.get(i);
            if (ImGui.smallButton("x##dict_rm_" + i)) {
                dictionaries.remove(i);
                host.markDirty();
                break;
            }
            ImGui.sameLine();
            ImGui.text(SBODictionaryIndex.ref(id));
            int others = (int) SBODictionaryIndex.shared().members(id).stream()
                    .filter(m -> !m.equals(host.objectId())).count();
            ImGui.sameLine();
            ImGui.textDisabled(others == 0 ? "(no other members)" : "(+" + others + " other member" + (others == 1 ? ")" : "s)"));
        }
        ImGui.pushItemWidth(EditorWidgets.NAME_FIELD_WIDTH);
        boolean enter = ImGui.inputTextWithHint("##dict_new", "e.g. stonebreak:wooden_planks", newDictionary,
                imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue);
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.smallButton("Add##dict_add") || enter) {
            addDictionary(newDictionary.get());
            newDictionary.set("");
        }
        ImGui.sameLine();
        ImGui.pushItemWidth(EditorWidgets.LOOP_COMBO_WIDTH);
        if (ImGui.beginCombo("##dict_known", "Known")) {
            for (String id : SBODictionaryIndex.shared().ids()) {
                if (ImGui.selectable(SBODictionaryIndex.ref(id), dictionaries.contains(id))) {
                    addDictionary(id);
                }
            }
            ImGui.endCombo();
        }
        ImGui.popItemWidth();
        ImGui.textDisabled("Recipes use a dictionary as \"#id\" in a slot: any member fits.");
    }

    private void addDictionary(String raw) {
        String id = SBODictionaryIndex.strip(raw);
        if (!id.isEmpty() && !dictionaries.contains(id)) {
            dictionaries.add(id);
            host.markDirty();
        }
    }

    /** Material + required tier (1.9+) and a live break-time preview per known tool. */
    private void renderMiningControls() {
        ThemedWidgets.sectionLabel("Mining");
        List<String> known = SBOMiningIndex.shared().materials();
        ImGui.pushItemWidth(EditorWidgets.NAME_FIELD_WIDTH);
        if (ImGui.inputTextWithHint("##material", "e.g. stone", material)) host.markDirty();
        ImGui.popItemWidth();
        ImGui.sameLine();
        ImGui.pushItemWidth(EditorWidgets.LOOP_COMBO_WIDTH);
        if (ImGui.beginCombo("Material", "Known")) {
            if (ImGui.selectable("(none)", material.get().isBlank())) {
                material.set("");
                host.markDirty();
            }
            for (String m : known) {
                if (ImGui.selectable(m, m.equals(material.get().trim()))) {
                    material.set(m);
                    host.markDirty();
                }
            }
            ImGui.endCombo();
        }
        ImGui.popItemWidth();
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        if (ImGui.inputInt("Required Tier", requiredTier)) {
            if (requiredTier.get() < 0) requiredTier.set(0);
            host.markDirty();
        }
        ImGui.popItemWidth();
        ImGui.sameLine();
        ImGui.textDisabled(requiredTier.get() == 0 ? "(any tool)"
                : "(" + ToolMiningRules.tierName(requiredTier.get()) + " or better)");
        renderBreakTimePreview();
    }

    private void renderBreakTimePreview() {
        String mat = SboGameplay.normalizeMaterial(material.get());
        float hard = hardness.get();
        List<SBOMiningIndex.KnownTool> tools = SBOMiningIndex.shared().tools();
        ImGui.dummy(0, 2);
        if (!breakable.get()) {
            ImGui.textDisabled("Not breakable: no tool can break this block.");
            return;
        }
        int flags = ImGuiTableFlags.BordersInnerH | ImGuiTableFlags.SizingStretchProp;
        if (!ImGui.beginTable("##break_preview", 3, flags)) return;
        ImGui.tableSetupColumn("Breaking with");
        ImGui.tableSetupColumn("Tier");
        ImGui.tableSetupColumn("Break time");
        ImGui.tableHeadersRow();
        previewRow("Bare hands", "-", hard, false);
        for (SBOMiningIndex.KnownTool t : tools) {
            float eff = ToolMiningRules.effectiveHardness(t.tool(), mat, requiredTier.get(), hard);
            previewRow(t.displayName(), ToolMiningRules.tierName(t.tool().tier()), eff,
                    !ToolMiningRules.isEffectiveOn(t.tool(), mat, requiredTier.get()));
        }
        ImGui.endTable();
        if (tools.isEmpty()) {
            ImGui.textDisabled("No mining tools found on disk.");
        } else if (mat == null) {
            ImGui.textDisabled("No material: every tool breaks this block at bare-hand speed.");
        }
    }

    private static void previewRow(String name, String tier, float seconds, boolean noBonus) {
        ImGui.tableNextRow();
        ImGui.tableSetColumnIndex(0);
        if (noBonus) ImGui.textDisabled(name); else ImGui.text(name);
        ImGui.tableSetColumnIndex(1);
        ImGui.textDisabled(tier);
        ImGui.tableSetColumnIndex(2);
        String time = Float.isInfinite(seconds) ? "unbreakable"
                : String.format(Locale.ROOT, "%.2f s", seconds);
        if (noBonus) ImGui.textDisabled(time + "  (no bonus)"); else ImGui.text(time);
    }

    private void renderFuelControls() {
        ImGui.text("Fuel");
        ImGui.sameLine();
        ImGui.textDisabled("(declares this SBO's own item as furnace fuel)");
        ImBoolean fuelBuf = new ImBoolean(isFuel);
        if (ImGui.checkbox("Is a fuel", fuelBuf)) {
            isFuel = fuelBuf.get();
            host.markDirty();
        }
        if (isFuel) {
            ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
            if (ImGui.inputInt("Burn ticks per unit", fuelBurnTicks)) {
                if (fuelBurnTicks.get() < 1) fuelBurnTicks.set(1);
                host.markDirty();
            }
            ImGui.popItemWidth();
            ImGui.textDisabled("200 ticks = 1 smelt. Coal-tier is typically 1600 (8 smelts).");
        }
    }

    private NumericIdValidator.Domain domain() {
        return NumericIdValidator.domainFor(host.objectType());
    }

    private void renderConflictHint() {
        NumericIdValidator.Domain domain = domain();
        if (domain == NumericIdValidator.Domain.NONE) return;
        NumericIdValidator.Result result = NumericIdValidator.validate(domain, numericId.get(), host.objectId());
        if (result instanceof NumericIdValidator.Result.Conflict c) {
            ThemedWidgets.inlineError("ID " + c.numericId() + " taken by " + c.existingObjectId());
        }
    }
}
