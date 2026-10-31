package com.stonebreak.openmason.assets;

import com.openmason.api.asset.AssetEditorSection;
import com.openmason.api.asset.AssetSectionHost;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.main.systems.menus.dialogs.EditorWidgets;
import com.openmason.main.systems.menus.dialogs.validation.NumericIdValidator;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import com.stonebreak.openmason.assets.mcp.AssetExportBuilder;
import imgui.ImGui;
import imgui.type.ImInt;

/**
 * The Export SBO window's Game Properties tab (#431): the numeric id (suggested per object type,
 * required for blocks and sprite items, checked for conflicts) and Stonebreak's default
 * {@code gameProperties} block for the object type, shared with the {@code sbo_export} MCP tool
 * through {@link AssetExportBuilder}. Everything else is authored later in the SBO Editor.
 */
final class ExportGamePropertiesSection implements AssetEditorSection<SBOFormat.ExportParameters> {

    private final AssetSectionHost host;
    private final NumericIdConflictPopup conflictPopup = new NumericIdConflictPopup();
    private final TakenIdsPopup takenIdsPopup = new TakenIdsPopup();

    /** Required for blocks/items; -1 = no GameProperties block emitted. */
    private final ImInt numericId = new ImInt(-1);
    private NumericIdValidator.Domain lastSuggestedDomain;

    ExportGamePropertiesSection(AssetSectionHost host) {
        this.host = host;
    }

    @Override
    public String title() {
        return "Game Properties";
    }

    @Override
    public void load(SBOFormat.ExportParameters params) {
        numericId.set(-1);
        lastSuggestedDomain = null;
        suggestNumericIdForType();
    }

    @Override
    public void render() {
        suggestNumericIdForType();
        ThemedWidgets.sectionLabel("Identity");
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        ImGui.inputInt("Numeric ID", numericId);
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.smallButton(EditorWidgets.TAKEN_IDS_LABEL + "##exp_taken")) {
            takenIdsPopup.open(domain());
        }
        ImGui.textDisabled(numericIdRequired()
                ? blockSelected()
                        ? "Required for blocks - unique across all blocks (chunk saves reference it)."
                        : "Required for sprite items - unique across all items (ItemRegistry keys on it)."
                : domain() == NumericIdValidator.Domain.ITEM
                        ? "Unique across all items; -1 skips the gameProperties block."
                        : "Optional for this object type; -1 skips the gameProperties block.");
        renderConflictHint();

        ThemedWidgets.sectionLabel("Defaults");
        ImGui.textDisabled(host.texturePayload()
                ? "Hardness 0, not solid, CUTOUT layer, max stack 64, TOOLS category, no atlas tile."
                : blockSelected()
                        ? "Hardness 1.0, solid, breakable, OPAQUE layer, first free atlas tile."
                        : "Hardness 1.0, stackable x64, MATERIALS category.");
        ImGui.textDisabled("Everything else (recipes, smelting, sounds, drops) is authored in the");
        ImGui.textDisabled("SBO Editor, which opens automatically after the export.");
    }

    @Override
    public void renderOverlays() {
        conflictPopup.render();
        takenIdsPopup.render();
    }

    @Override
    public String validate() {
        suggestNumericIdForType();
        if (numericIdRequired() && numericId.get() <= 0) {
            return blockSelected()
                    ? "Numeric ID must be > 0 for blocks (required for chunk save references)"
                    : "Numeric ID must be > 0 for items (required for ItemRegistry to load this SBO)";
        }
        return null;
    }

    @Override
    public String conflict() {
        NumericIdValidator.Result.Conflict c = currentConflict();
        return c == null ? null : "numeric_id_conflict: " + c.numericId() + " is taken by " + c.existingObjectId();
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
        suggestNumericIdForType();
        return NumericIdValidator.validate(domain(), numericId.get(), host.objectId())
                instanceof NumericIdValidator.Result.Conflict c ? c : null;
    }

    /**
     * Minimal {@code GameProperties} from the Numeric ID and the defaults for the object type and
     * payload; the full set (atlas coords, hardness, render layer...) is authored in the SBO Editor.
     */
    @Override
    public SBOFormat.ExportParameters write(SBOFormat.ExportParameters params) {
        suggestNumericIdForType();
        if (numericId.get() >= 0) {
            params.setGameProperties(host.texturePayload()
                    ? AssetExportBuilder.spriteGameProperties(null, numericId.get())
                    : AssetExportBuilder.gameProperties(null, blockSelected(), numericId.get()));
        }
        return params;
    }

    private NumericIdValidator.Domain domain() {
        return NumericIdValidator.domainFor(host.objectType());
    }

    private boolean blockSelected() {
        return domain() == NumericIdValidator.Domain.BLOCK;
    }

    /** Blocks (chunk saves) and texture items (ItemRegistry) cannot load without an ID. */
    private boolean numericIdRequired() {
        return blockSelected() || (host.texturePayload() && domain() == NumericIdValidator.Domain.ITEM);
    }

    /**
     * Re-suggest the numeric ID whenever the type's ID domain changes, so a block gets the next free
     * block ID and an item the next free item ID. Engine sentinels without an SBO (water = 8) are
     * excluded by the validator.
     */
    private void suggestNumericIdForType() {
        NumericIdValidator.Domain domain = domain();
        if (domain == lastSuggestedDomain) return;
        lastSuggestedDomain = domain;
        numericId.set(NumericIdValidator.suggestNextFreeId(domain));
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
