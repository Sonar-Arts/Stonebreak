package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.themes.utils.ThemedWidgets;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.format.sbo.SBOSerializer;
import com.openmason.main.systems.menus.dialogs.validation.NumericIdConflictPopup;
import com.openmason.main.systems.menus.dialogs.validation.NumericIdValidator;
import com.openmason.main.systems.menus.dialogs.validation.TakenIdsPopup;
import com.openmason.main.systems.services.StatusService;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Standalone editor window for {@code .sbo} files.
 *
 * <p>Loads an existing SBO from disk via {@link SBOParser#parseRaw(Path)},
 * exposes its manifest fields (identity, gameProperties, states, recipes) as
 * editable widgets across a tab bar, and writes the result back via
 * {@link SBOSerializer#exportFromDocument}. The embedded OMO/OMT bytes are
 * passed through unchanged — this editor is metadata-only.
 */
public class SBOEditorWindow {

    private static final Logger logger = LoggerFactory.getLogger(SBOEditorWindow.class);
    private static final String WINDOW_TITLE = "SBO Editor";

    private final ImBoolean visible;
    private final FileDialogService fileDialogService;
    private final StatusService statusService;
    private final SBOParser parser = new SBOParser();
    private final SBOSerializer serializer = new SBOSerializer();
    private final SBORecipeSection recipeSection;
    private final SBOSmeltingSection smeltingSection;
    private final SBOStatesEditor statesEditor;
    private final SoundsEditor soundsEditor;
    private final SBODropsSection dropsSection;
    private final SBOToolSection toolSection;
    private final NumericIdConflictPopup conflictPopup = new NumericIdConflictPopup();
    private final TakenIdsPopup takenIdsPopup = new TakenIdsPopup();

    // Loaded document state
    private Path currentPath;
    private SBOFormat.Document loadedManifest;
    private byte[] loadedDefaultBytes;
    private java.util.Map<String, byte[]> loadedStateBytes;
    private java.util.Map<String, byte[]> loadedStateClipBytes;
    private java.util.Map<String, byte[]> loadedSoundBytes;
    private boolean dirty;

    // Form buffers (mirror manifest)
    private final ImString objectId = new ImString(256);
    private final ImString objectName = new ImString(256);
    private final ImString objectPack = new ImString(256);
    private final ImString author = new ImString(256);
    private final ImString description = new ImString(1024);
    private final ImInt objectTypeIndex = new ImInt(0);

    // GameProperties buffers
    private boolean hasGameProperties;
    private final ImInt numericId = new ImInt(-1);
    private final ImFloat hardness = new ImFloat(1.0f);
    private final ImBoolean solid = new ImBoolean(true);
    private final ImBoolean breakable = new ImBoolean(true);
    private final ImInt atlasX = new ImInt(-1);
    private final ImInt atlasY = new ImInt(-1);
    private final ImInt renderLayerIndex = new ImInt(0);
    private final ImBoolean transparent = new ImBoolean(false);
    private final ImBoolean flower = new ImBoolean(false);
    private final ImBoolean stackable = new ImBoolean(false);
    private final ImInt maxStackSize = new ImInt(64);
    private final ImString category = new ImString(64);
    private final ImBoolean placeable = new ImBoolean(true);
    // Mining (1.9+): block material + minimum tool tier.
    private final ImString material = new ImString(64);
    private final ImInt requiredTier = new ImInt(0);

    // Fuel buffers (1.5+ — sits on the Properties tab even though it lives in
    // the top-level Document.fuel field, since it's a property of the item).
    private boolean isFuel;
    private final ImInt fuelBurnTicks = new ImInt(1600);

    private static final String[] OBJECT_TYPE_LABELS = {
            "block", "item", "entity", "decoration", "particle", "other"
    };
    private static final String[] RENDER_LAYER_LABELS = { "OPAQUE", "CUTOUT", "TRANSLUCENT" };
    private static final String[] TAB_LABELS = {
            "Metadata", "Game Properties", "States", "Recipes", "Smelting", "Sounds", "Drops", "Tool"
    };

    private static final int TAB_STATES = 2;
    private static final int TAB_SOUNDS = 5;
    private static final int TAB_DROPS = 6;
    private static final int TAB_TOOL = 7;

    /** Mortar window chrome (action bar + tab strip); ImGui fallback inside. */
    private final EditorChrome chrome = new EditorChrome("sbo");

    /** Opens embedded model bytes in the model editor (wired by MainImGuiInterface). */
    private java.util.function.BiConsumer<byte[], String> openModelHandler;

    public void setOpenModelHandler(java.util.function.BiConsumer<byte[], String> handler) {
        this.openModelHandler = handler;
    }
    private int selectedTab;

    /** Last save-blocking problem, shown inline under the tab content (cleared on load / successful save). */
    private String validationMessage = "";

    public SBOEditorWindow(FileDialogService fileDialogService, StatusService statusService) {
        this.visible = new ImBoolean(false);
        this.fileDialogService = fileDialogService;
        this.statusService = statusService;
        this.recipeSection = new SBORecipeSection(() -> dirty = true, () -> objectId.get().trim());
        this.smeltingSection = new SBOSmeltingSection(() -> dirty = true, () -> objectId.get().trim());
        this.statesEditor = new SBOStatesEditor(
                () -> dirty = true,
                cb -> { if (fileDialogService != null) fileDialogService.showOpenOMOInProjectDialog(cb::accept); },
                cb -> { if (fileDialogService != null) fileDialogService.showOpenOMTInProjectDialog(cb::accept); },
                cb -> { if (fileDialogService != null) fileDialogService.showOpenOMADialog(cb::accept); });
        this.soundsEditor = new SoundsEditor(
                () -> dirty = true,
                cb -> { if (fileDialogService != null) fileDialogService.showOpenAudioDialog(cb::accept); });
        this.dropsSection = new SBODropsSection(() -> dirty = true);
        this.toolSection = new SBOToolSection(() -> dirty = true);
    }

    /** Link the states editor to the Animation Editor (in-memory clip round trips). */
    public void setAnimationBridge(AnimationClipBridge bridge) {
        statesEditor.setAnimationBridge(bridge);
    }


    /**
     * Open the editor: prompts for an SBO file, loads it on success.
     */
    public void openWithDialog() {
        if (fileDialogService == null) {
            logger.warn("Cannot open SBO editor: FileDialogService unavailable");
            return;
        }
        fileDialogService.showOpenSBODialog(this::loadFile);
    }

    /** Show editor without prompting (for "still has unsaved file" reopen). */
    public void show() {
        visible.set(true);
    }

    public boolean isVisible() {
        return visible.get();
    }

    /**
     * Open a freshly written SBO without prompting — the exporter hands the
     * just-exported file straight to this editor so the export form acts as
     * the "start screen" and the full editor carries on from there.
     */
    public boolean openFile(String pathStr) {
        return loadFile(pathStr);
    }

    // ---- Agent seams (MCP sbo_editor_*) ------------------------------------

    /** The file the editor is showing, or null. */
    public Path currentPath() { return currentPath; }

    public boolean isDirty() { return dirty; }

    public boolean hasDocument() { return loadedManifest != null; }

    /** The manifest as currently edited in the form (null when nothing is loaded). */
    public SBOFormat.Document snapshotDocument() {
        return loadedManifest == null ? null : buildEditedDocument();
    }

    /**
     * Replace the edited manifest (embedded bytes are kept) and refresh the
     * form; marks the draft dirty and shows the window so the human sees it.
     */
    public void applyDocument(SBOFormat.Document doc) {
        this.loadedManifest = doc;
        populateBuffers(doc);
        this.dirty = true;
        this.visible.set(true);
    }

    /** Null when the draft can be written; otherwise the first blocking problem. */
    public String validateForWrite() {
        if (loadedManifest == null) return "nothing is loaded in the SBO editor";
        String err = statesEditor.validate();
        if (err == null) err = soundsEditor.validate();
        if (err == null) err = dropsSection.validate();
        if (err == null) err = toolSection.validate();
        if (err != null) return err;
        if (hasGameProperties) {
            NumericIdValidator.Result result = NumericIdValidator.validate(
                    currentDomain(), numericId.get(), objectId.get().trim());
            if (result instanceof NumericIdValidator.Result.Conflict c) {
                return "numeric_id_conflict: " + c.numericId() + " is taken by "
                        + c.existingObjectId() + " — change gameProperties.numericId";
            }
        }
        return null;
    }

    /** Write the draft to {@code pathStr} (no validation, no dialog); true on success. */
    public boolean writeDocumentTo(String pathStr) {
        return performWrite(pathStr);
    }

    private boolean loadFile(String pathStr) {
        try {
            Path path = Path.of(pathStr);
            SBOParser.RawParse raw = parser.parseRaw(path);
            this.currentPath = path;
            this.loadedManifest = raw.manifest();
            this.loadedDefaultBytes = raw.defaultBytes();
            this.loadedStateBytes = raw.stateBytes();
            this.loadedStateClipBytes = raw.stateClipBytes();
            this.loadedSoundBytes = raw.soundBytes();
            populateBuffers(raw.manifest());
            this.validationMessage = "";
            this.dirty = false;
            this.visible.set(true);
            if (statusService != null) {
                statusService.updateStatus("Opened SBO: " + path.getFileName());
            }
            return true;
        } catch (IOException e) {
            logger.error("Failed to load SBO {}", pathStr, e);
            if (statusService != null) statusService.updateStatus("Failed to open SBO");
            return false;
        }
    }

    private void populateBuffers(SBOFormat.Document doc) {
        objectId.set(doc.objectId());
        objectName.set(doc.objectName());
        objectPack.set(doc.objectPack());
        author.set(doc.author());
        description.set(doc.description() != null ? doc.description() : "");
        objectTypeIndex.set(indexOf(OBJECT_TYPE_LABELS, doc.objectType()));

        SBOFormat.GameProperties gp = doc.gameProperties();
        hasGameProperties = gp != null;
        if (gp != null) {
            numericId.set(gp.numericId());
            hardness.set(gp.hardness());
            solid.set(gp.solid());
            breakable.set(gp.breakable());
            atlasX.set(gp.atlasX());
            atlasY.set(gp.atlasY());
            renderLayerIndex.set(indexOf(RENDER_LAYER_LABELS, gp.renderLayerOrDefault()));
            transparent.set(gp.transparent());
            flower.set(gp.flower());
            stackable.set(gp.stackable());
            maxStackSize.set(gp.maxStackSize());
            category.set(gp.categoryOrDefault());
            placeable.set(gp.placeable());
            material.set(gp.material() != null ? gp.material() : "");
            requiredTier.set(gp.requiredTier());
        }

        recipeSection.setFromRecipeData(doc.recipes());
        smeltingSection.setFromSmeltingData(doc.smeltingRecipes());
        isFuel = doc.fuel() != null;
        fuelBurnTicks.set(doc.fuel() != null ? doc.fuel().burnTicks() : 1600);
        statesEditor.load(doc, loadedStateBytes, loadedStateClipBytes, loadedDefaultBytes);
        soundsEditor.load(doc.sounds(),
                loadedSoundBytes != null ? loadedSoundBytes::get : f -> null);
        dropsSection.setFromDropData(doc.drops());
        toolSection.setFromToolData(doc.tool());
    }

    public void render() {
        if (!visible.get()) return;

        ImGui.setNextWindowSize(720, 640, imgui.flag.ImGuiCond.FirstUseEver);
        int flags = ImGuiWindowFlags.NoCollapse;
        String title = WINDOW_TITLE
                + (currentPath != null ? " - " + currentPath.getFileName() : "")
                + (dirty ? " *" : "")
                + "###sbo_editor";
        if (ImGui.begin(title, visible, flags)) {
            boolean loaded = loadedManifest != null;
            selectedTab = chrome.render(loaded, loaded && dirty, dirty,
                    currentPath != null ? currentPath.getFileName().toString() : "",
                    "Open Different SBO...", TAB_LABELS, selectedTab,
                    this::saveInPlace, this::saveAs, this::openWithDialog);
            ImGui.dummy(0, 6);
            if (!loaded) {
                ImGui.textDisabled(EditorWidgets.emptyEditorText("SBO"));
            } else {
                switch (selectedTab) {
                    case 0 -> renderMetadataTab();
                    case 1 -> renderGamePropertiesTab();
                    case 2 -> renderStatesTab();
                    case 3 -> recipeSection.render();
                    case 4 -> smeltingSection.render();
                    case 5 -> soundsEditor.render();
                    case 6 -> dropsSection.render();
                    case 7 -> renderToolTab();
                    default -> { }
                }
                if (!validationMessage.isEmpty()) {
                    ImGui.dummy(0, 8);
                    ThemedWidgets.inlineError(validationMessage);
                }
            }
            conflictPopup.render();
            takenIdsPopup.render();
        }
        ImGui.end();
    }

    private void renderMetadataTab() {
        if (openModelHandler != null && loadedManifest != null
                && loadedManifest.isModelBearing() && loadedDefaultBytes != null) {
            if (ImGui.button("Open Model in Editor")) {
                openModelHandler.accept(loadedDefaultBytes,
                        objectName.get().isBlank() ? objectId.get() : objectName.get());
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Loads a COPY of the embedded model into the viewport "
                        + "(the .sbo file itself is not touched; Save becomes Save As)");
            }
            ImGui.separator();
        }
        ThemedWidgets.sectionLabel("Identity");
        if (ImGui.inputTextWithHint("Object ID", "e.g. stonebreak:oak_planks", objectId)) dirty = true;
        if (ImGui.inputTextWithHint("Object Name", "e.g. Oak Planks", objectName))         dirty = true;

        ThemedWidgets.sectionLabel("Classification");
        if (ImGui.combo("Object Type", objectTypeIndex, OBJECT_TYPE_LABELS)) dirty = true;
        if (ImGui.inputTextWithHint("Pack", "e.g. default, expansion_1", objectPack))      dirty = true;

        ThemedWidgets.sectionLabel("Attribution");
        if (ImGui.inputTextWithHint("Author", "Creator name or studio", author))           dirty = true;
        ImGui.text("Description");
        if (ImGui.inputTextMultiline("##desc", description, -1, 80)) dirty = true;
    }

    private void renderGamePropertiesTab() {
        if (ImGui.checkbox("Has gameProperties block", new ImBoolean(hasGameProperties))) {
            hasGameProperties = !hasGameProperties;
            dirty = true;
        }
        if (!hasGameProperties) {
            ImGui.textDisabled("This SBO has no gameProperties block (legacy 1.0).");
            ImGui.dummy(0, 4);
            renderFuelControls();
            return;
        }

        ThemedWidgets.sectionLabel("Identity");
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        if (ImGui.inputInt("Numeric ID", numericId))         dirty = true;
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.smallButton(EditorWidgets.TAKEN_IDS_LABEL + "##editor_taken")) {
            takenIdsPopup.open(currentDomain());
        }
        renderConflictHint();

        ThemedWidgets.sectionLabel("World");
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        if (ImGui.inputFloat("Hardness", hardness))          dirty = true;
        ImGui.popItemWidth();
        if (ImGui.checkbox("Solid", solid))                  dirty = true;
        ImGui.sameLine(160);
        if (ImGui.checkbox("Breakable", breakable))          dirty = true;
        ImGui.sameLine(320);
        if (ImGui.checkbox("Placeable", placeable))          dirty = true;

        if (isBlockType()) {
            renderMiningControls();
        }

        ThemedWidgets.sectionLabel("Rendering");
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        if (ImGui.inputInt("Atlas X", atlasX))               dirty = true;
        ImGui.sameLine(240);
        if (ImGui.inputInt("Atlas Y", atlasY))               dirty = true;
        if (ImGui.combo("Render Layer", renderLayerIndex, RENDER_LAYER_LABELS)) dirty = true;
        ImGui.popItemWidth();
        if (ImGui.checkbox("Transparent", transparent))      dirty = true;
        ImGui.sameLine(160);
        if (ImGui.checkbox("Flower", flower))                dirty = true;

        ThemedWidgets.sectionLabel("Item");
        if (ImGui.checkbox("Stackable", stackable))          dirty = true;
        ImGui.sameLine(160);
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        if (ImGui.inputInt("Max Stack Size", maxStackSize))  dirty = true;
        if (ImGui.inputText("Category", category))           dirty = true;
        ImGui.popItemWidth();
        ImGui.dummy(0, 4);
        renderFuelControls();
    }

    /** Material + required tier (1.9+) and a live break-time preview per known tool. */
    private void renderMiningControls() {
        ThemedWidgets.sectionLabel("Mining");
        java.util.List<String> known = SBOMiningIndex.shared().materials();
        ImGui.pushItemWidth(EditorWidgets.NAME_FIELD_WIDTH);
        if (ImGui.inputTextWithHint("##material", "e.g. stone", material)) dirty = true;
        ImGui.popItemWidth();
        ImGui.sameLine();
        ImGui.pushItemWidth(EditorWidgets.LOOP_COMBO_WIDTH);
        if (ImGui.beginCombo("Material", "Known")) {
            if (ImGui.selectable("(none)", material.get().isBlank())) {
                material.set("");
                dirty = true;
            }
            for (String m : known) {
                if (ImGui.selectable(m, m.equals(material.get().trim()))) {
                    material.set(m);
                    dirty = true;
                }
            }
            ImGui.endCombo();
        }
        ImGui.popItemWidth();
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        if (ImGui.inputInt("Required Tier", requiredTier)) {
            if (requiredTier.get() < 0) requiredTier.set(0);
            dirty = true;
        }
        ImGui.popItemWidth();
        ImGui.sameLine();
        ImGui.textDisabled(requiredTier.get() == 0 ? "(any tool)"
                : "(" + SBOFormat.ToolData.tierName(requiredTier.get()) + " or better)");
        renderBreakTimePreview();
    }

    private void renderBreakTimePreview() {
        String mat = SBOFormat.normalizeMaterial(material.get());
        float hard = hardness.get();
        java.util.List<SBOMiningIndex.KnownTool> tools = SBOMiningIndex.shared().tools();
        ImGui.dummy(0, 2);
        if (!breakable.get()) {
            ImGui.textDisabled("Not breakable: no tool can break this block.");
            return;
        }
        int flags = imgui.flag.ImGuiTableFlags.BordersInnerH | imgui.flag.ImGuiTableFlags.SizingStretchProp;
        if (!ImGui.beginTable("##break_preview", 3, flags)) return;
        ImGui.tableSetupColumn("Breaking with");
        ImGui.tableSetupColumn("Tier");
        ImGui.tableSetupColumn("Break time");
        ImGui.tableHeadersRow();
        previewRow("Bare hands", "-", hard, false);
        for (SBOMiningIndex.KnownTool t : tools) {
            float eff = SBOFormat.ToolData.effectiveHardness(t.tool(), mat, requiredTier.get(), hard);
            previewRow(t.displayName(), SBOFormat.ToolData.tierName(t.tool().tier()), eff,
                    !t.tool().isEffectiveOn(mat, requiredTier.get()));
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
                : String.format(java.util.Locale.ROOT, "%.2f s", seconds);
        if (noBonus) ImGui.textDisabled(time + "  (no bonus)"); else ImGui.text(time);
    }

    private void renderToolTab() {
        if (!"item".equals(OBJECT_TYPE_LABELS[objectTypeIndex.get()])) {
            ThemedWidgets.statusText(com.openmason.main.systems.themes.utils.ThemeColors.Tone.WARNING,
                    "Tool data is read from item SBOs only. Set Object Type to \"item\" on the Metadata tab.");
            ImGui.dummy(0, 4);
        }
        toolSection.render();
    }

    private boolean isBlockType() {
        return "block".equals(OBJECT_TYPE_LABELS[objectTypeIndex.get()]);
    }

    private void renderFuelControls() {
        ImGui.text("Fuel");
        ImGui.sameLine();
        ImGui.textDisabled("(declares this SBO's own item as furnace fuel)");
        ImBoolean fuelBuf = new ImBoolean(isFuel);
        if (ImGui.checkbox("Is a fuel", fuelBuf)) {
            isFuel = fuelBuf.get();
            dirty = true;
        }
        if (isFuel) {
            ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
            if (ImGui.inputInt("Burn ticks per unit", fuelBurnTicks)) {
                if (fuelBurnTicks.get() < 1) fuelBurnTicks.set(1);
                dirty = true;
            }
            ImGui.popItemWidth();
            ImGui.textDisabled("200 ticks = 1 smelt. Coal-tier is typically 1600 (8 smelts).");
        }
    }

    private void renderStatesTab() {
        statesEditor.render();
    }

    private void saveInPlace() {
        if (currentPath == null) {
            saveAs();
            return;
        }
        writeTo(currentPath.toString());
    }

    private void saveAs() {
        if (fileDialogService == null) return;
        fileDialogService.showSaveSBODialog(this::writeTo);
    }

    private void writeTo(String pathStr) {
        if (loadedManifest == null) return;
        String stateError = statesEditor.validate();
        if (stateError != null) { rejectSave(stateError, TAB_STATES); return; }
        String soundError = soundsEditor.validate();
        if (soundError != null) { rejectSave(soundError, TAB_SOUNDS); return; }
        String dropError = dropsSection.validate();
        if (dropError != null) { rejectSave(dropError, TAB_DROPS); return; }
        String toolError = toolSection.validate();
        if (toolError != null) { rejectSave(toolError, TAB_TOOL); return; }
        validationMessage = "";
        if (hasGameProperties) {
            NumericIdValidator.Result result = NumericIdValidator.validate(
                    currentDomain(), numericId.get(), objectId.get().trim());
            if (result instanceof NumericIdValidator.Result.Conflict c) {
                conflictPopup.open(c, () -> performWrite(pathStr));
                return;
            }
        }
        performWrite(pathStr);
    }

    /** Show a save-blocking problem inline, jump to the offending tab and echo it to the status bar. */
    private void rejectSave(String error, int tab) {
        validationMessage = error;
        selectedTab = tab;
        if (statusService != null) statusService.updateStatus("Cannot save: " + error);
    }

    private boolean performWrite(String pathStr) {
        SBOFormat.Document edited = buildEditedDocument();
        byte[] effectiveDefaultBytes = statesEditor.defaultBytes(loadedDefaultBytes);
        java.util.Map<String, byte[]> effectiveStateBytes = statesEditor.hasStates()
                ? statesEditor.stateBytesByName()
                : loadedStateBytes;
        java.util.Map<String, byte[]> effectiveClipBytes = statesEditor.hasStates()
                ? statesEditor.stateClipBytesByName()
                : loadedStateClipBytes;
        java.util.Map<String, byte[]> effectiveSoundBytes = soundsEditor.soundBytesByFilename();
        boolean ok = serializer.exportFromDocument(edited, effectiveDefaultBytes, effectiveStateBytes,
                effectiveClipBytes, effectiveSoundBytes, pathStr);
        if (ok) {
            currentPath = Path.of(pathStr);
            loadedManifest = edited;
            loadedDefaultBytes = effectiveDefaultBytes;
            loadedStateBytes = effectiveStateBytes;
            loadedStateClipBytes = effectiveClipBytes;
            loadedSoundBytes = effectiveSoundBytes;
            dirty = false;
            SBOMiningIndex.invalidate(); // materials / tools may have changed
            if (statusService != null) {
                statusService.updateStatus("Saved SBO: " + currentPath.getFileName());
            }
        } else if (statusService != null) {
            statusService.updateStatus("Failed to save SBO");
        }
        return ok;
    }

    private SBOFormat.Document buildEditedDocument() {
        SBOFormat.GameProperties gp = hasGameProperties
                ? new SBOFormat.GameProperties(
                        numericId.get(), hardness.get(), solid.get(), breakable.get(),
                        atlasX.get(), atlasY.get(), RENDER_LAYER_LABELS[renderLayerIndex.get()],
                        transparent.get(), flower.get(), stackable.get(),
                        maxStackSize.get(), category.get().trim(), placeable.get(),
                        material.get(), requiredTier.get())
                : null;
        // An invalid tool form only reaches here from a snapshot (writes validate
        // first); keep the last good tool data rather than throwing.
        SBOFormat.ToolData tool = toolSection.validate() == null
                ? toolSection.toToolData() : loadedManifest.tool();

        return new SBOFormat.Document(
                loadedManifest.version(),
                objectId.get().trim(),
                objectName.get().trim(),
                OBJECT_TYPE_LABELS[objectTypeIndex.get()],
                objectPack.get().trim(),
                loadedManifest.checksum(), // recomputed by serializer
                author.get().trim(),
                description.get().isBlank() ? null : description.get(),
                loadedManifest.createdAt(),
                loadedManifest.omoFilename(),
                loadedManifest.textureFilename(),
                gp,
                statesEditor.toStateEntries(),
                statesEditor.defaultStateName(),
                recipeSection.toRecipeData(),
                smeltingSection.toSmeltingRecipeData(),
                isFuel ? new SBOFormat.FuelData(Math.max(1, fuelBurnTicks.get())) : null,
                soundsEditor.toSoundData(),
                dropsSection.toDropData(),
                tool
        );
    }

    private NumericIdValidator.Domain currentDomain() {
        return NumericIdValidator.domainFor(OBJECT_TYPE_LABELS[objectTypeIndex.get()]);
    }

    private void renderConflictHint() {
        NumericIdValidator.Domain domain = currentDomain();
        if (domain == NumericIdValidator.Domain.NONE) return;
        NumericIdValidator.Result result = NumericIdValidator.validate(
                domain, numericId.get(), objectId.get().trim());
        if (result instanceof NumericIdValidator.Result.Conflict c) {
            ThemedWidgets.inlineError("ID " + c.numericId() + " taken by " + c.existingObjectId());
        }
    }

    private static int indexOf(String[] arr, String value) {
        if (value == null) return 0;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equalsIgnoreCase(value)) return i;
        }
        return 0;
    }

    /**
     * Release GPU-backed resources (chrome/recipe/sounds Mortar regions,
     * ingredient icon textures). Must run with a current GL context, before
     * the SkijaContext closes.
     */
    public void close() {
        chrome.close();
        recipeSection.close();
        statesEditor.close();
        soundsEditor.close();
        dropsSection.close();
        SBOIngredientIcons.clear();
    }
}
