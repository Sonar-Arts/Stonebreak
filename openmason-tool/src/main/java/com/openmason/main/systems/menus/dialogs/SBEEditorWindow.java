package com.openmason.main.systems.menus.dialogs;

import com.openmason.api.asset.AssetSectionHost;
import com.openmason.api.asset.AssetSurface;

import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbe.SBEParser;
import com.openmason.engine.format.sbe.SBESerializer;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.target.GameTargets;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone editor window for {@code .sbe} files.
 *
 * <p>Round-trips an SBE through {@link SBEParser#parseRaw(Path)} and
 * {@link SBESerializer#exportFromDocument}. The base OMO bytes pass through
 * unchanged; metadata and per-state assets (model overrides, clips) are
 * editable via the tab bar.
 *
 * <p>Structurally mirrors {@code SBOEditorWindow} so the two editors share
 * the same authoring idioms.
 */
public class SBEEditorWindow {

    private static final Logger logger = LoggerFactory.getLogger(SBEEditorWindow.class);
    private static final String WINDOW_TITLE = "SBE Editor";

    private static final String TAB_METADATA = "core.metadata";
    private static final String TAB_STATES = "core.states";
    private static final String TAB_VARIANTS = "core.variants";
    private static final String TAB_SOUNDS = "core.sounds";
    private static final java.util.List<AssetSectionSet.Tab> CORE_TABS = java.util.List.of(
            new AssetSectionSet.Tab(TAB_METADATA, "Metadata", 0, null),
            new AssetSectionSet.Tab(TAB_STATES, "States", 100, null),
            new AssetSectionSet.Tab(TAB_VARIANTS, "Variants", 200, null),
            new AssetSectionSet.Tab(TAB_SOUNDS, "Sounds", 300, null));

    private final ImBoolean visible = new ImBoolean(false);
    private final FileDialogService fileDialogService;
    private final StatusService statusService;
    private final SBEParser parser = new SBEParser();
    private final SBESerializer serializer = new SBESerializer();
    private final SBEStatesEditor statesEditor;
    private final SBEVariantsEditor variantsEditor;
    private final SoundsEditor soundsEditor;
    /** Game-data sections contributed by plugins (#431; Stonebreak: the entity type). */
    private final AssetSectionSet<SBEFormat.Document> sections;
    private java.util.List<AssetSectionSet.Tab> tabs = CORE_TABS;

    /** Popup listing already-registered SBE object ids. */
    private final SBEObjectIndexPopup objectIndexPopup = new SBEObjectIndexPopup();

    /** Mortar window chrome (action bar + tab strip); ImGui fallback inside. */
    private final EditorChrome chrome = new EditorChrome("sbe");

    /** Opens embedded model bytes in the model editor (wired by MainImGuiInterface). */
    private java.util.function.BiConsumer<byte[], String> openModelHandler;

    public void setOpenModelHandler(java.util.function.BiConsumer<byte[], String> handler) {
        this.openModelHandler = handler;
    }
    private int selectedTab;


    /** Last save-blocking problem, shown inline under the tab content (cleared on load / successful save). */
    private String validationMessage = "";

    // Loaded document state
    private Path currentPath;
    private SBEFormat.Document loadedManifest;
    private byte[] loadedOmoBytes;
    private java.util.Map<String, byte[]> loadedStateAssetBytes = java.util.Map.of();
    /** Human-readable source of the base OMO ("(original)" or a picked filename). */
    private String baseModelLabel;
    private boolean dirty;

    // Form buffers
    private final ImString objectId = new ImString(256);
    private final ImString objectName = new ImString(256);
    private final ImString objectPack = new ImString(256);
    private final ImString author = new ImString(256);
    private final ImString description = new ImString(1024);

    public SBEEditorWindow(FileDialogService fileDialogService, StatusService statusService) {
        this.fileDialogService = fileDialogService;
        this.statusService = statusService;
        this.statesEditor = new SBEStatesEditor(
                () -> dirty = true,
                cb -> { if (fileDialogService != null) fileDialogService.showOpenOMOInProjectDialog(cb::accept); },
                cb -> { if (fileDialogService != null) fileDialogService.showOpenOMAInProjectDialog(cb::accept); });
        this.variantsEditor = new SBEVariantsEditor(
                () -> dirty = true,
                cb -> { if (fileDialogService != null) fileDialogService.showOpenOMOInProjectDialog(cb::accept); });
        this.soundsEditor = new SoundsEditor(
                () -> dirty = true,
                cb -> { if (fileDialogService != null) fileDialogService.showOpenAudioDialog(cb::accept); });
        this.sections = new AssetSectionSet<>(AssetSurface.SBE_EDITOR, new AssetSectionHost() {
            @Override
            public AssetSurface<?> surface() {
                return AssetSurface.SBE_EDITOR;
            }

            @Override
            public String objectId() {
                return objectId.get().trim();
            }

            @Override
            public String objectType() {
                return "";
            }

            @Override
            public boolean texturePayload() {
                return false;
            }

            @Override
            public void markDirty() {
                dirty = true;
            }
        });
    }

    /** Link the states editor to the Animation Editor (in-memory clip round trips). */
    public void setAnimationBridge(AnimationClipBridge bridge) {
        statesEditor.setAnimationBridge(bridge);
    }

    // ========================================================================
    // Open / show
    // ========================================================================

    /** Prompt for an SBE file, load it on success. */
    public void openWithDialog() {
        if (fileDialogService == null) {
            logger.warn("Cannot open SBE editor: FileDialogService unavailable");
            return;
        }
        fileDialogService.showOpenSBEDialog(this::loadFile);
    }

    /** Show editor without prompting. */
    public void show() {
        visible.set(true);
    }

    public boolean isVisible() {
        return visible.get();
    }

    /**
     * Open a freshly written SBE without prompting — the exporter hands the
     * just-exported file straight to this editor so the export form acts as
     * the "start screen" and the full editor carries on from there.
     */
    public boolean openFile(String pathStr) {
        return loadFile(pathStr);
    }

    // ---- Agent seams (MCP sbe_editor_*) ------------------------------------

    public Path currentPath() { return currentPath; }

    public boolean isDirty() { return dirty; }

    public boolean hasDocument() { return loadedManifest != null; }

    /** The manifest as currently edited in the form (null when nothing is loaded). */
    public SBEFormat.Document snapshotDocument() {
        return loadedManifest == null ? null : buildEditedDocument();
    }

    /** Replace the edited manifest (embedded bytes kept), refresh the form, mark dirty, show. */
    public void applyDocument(SBEFormat.Document doc) {
        this.loadedManifest = doc;
        populateBuffers(doc, loadedStateAssetBytes);
        this.dirty = true;
        this.visible.set(true);
    }

    /** Null when the draft can be written; otherwise the first blocking problem. */
    public String validateForWrite() {
        if (loadedManifest == null) return "nothing is loaded in the SBE editor";
        String err = statesEditor.validate();
        if (err == null) err = variantsEditor.validate();
        if (err == null) err = soundsEditor.validate();
        if (err != null) return err;
        AssetSectionSet.Problem problem = sections.firstError();
        if (problem != null) return problem.message();
        java.util.List<AssetSectionSet.Problem> conflicts = sections.conflicts();
        return conflicts.isEmpty() ? null : conflicts.get(0).message();
    }

    /** Write the draft to {@code pathStr} (no validation, no dialog); true on success. */
    public boolean writeDocumentTo(String pathStr) {
        return performWrite(pathStr);
    }

    private boolean loadFile(String pathStr) {
        try {
            Path path = Path.of(pathStr);
            SBEParser.RawParse raw = parser.parseRaw(path);
            this.currentPath = path;
            this.loadedManifest = raw.manifest();
            this.loadedOmoBytes = raw.omoBytes();
            this.loadedStateAssetBytes = raw.stateAssetBytes();
            this.baseModelLabel = "(original)";
            populateBuffers(raw.manifest(), raw.stateAssetBytes());
            this.validationMessage = "";
            this.dirty = false;
            this.visible.set(true);
            if (statusService != null) {
                statusService.updateStatus("Opened SBE: " + path.getFileName());
            }
            return true;
        } catch (IOException e) {
            logger.error("Failed to load SBE {}", pathStr, e);
            if (statusService != null) statusService.updateStatus("Failed to open SBE");
            return false;
        }
    }

    private void populateBuffers(SBEFormat.Document doc, java.util.Map<String, byte[]> stateAssetBytes) {
        objectId.set(doc.objectId());
        objectName.set(doc.objectName());
        objectPack.set(doc.objectPack());
        author.set(doc.author());
        description.set(doc.description() != null ? doc.description() : "");
        statesEditor.load(doc, stateAssetBytes);
        variantsEditor.load(doc, stateAssetBytes);
        soundsEditor.load(doc.sounds(),
                stateAssetBytes != null ? stateAssetBytes::get : f -> null);
        sections.load(doc);
        tabs = sections.tabs(CORE_TABS);
        if (selectedTab >= tabs.size()) selectedTab = 0;
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    public void render() {
        if (!visible.get()) return;

        ImGui.setNextWindowSize(720, 640, ImGuiCond.FirstUseEver);
        int flags = ImGuiWindowFlags.NoCollapse;
        String title = WINDOW_TITLE
                + (currentPath != null ? " - " + currentPath.getFileName() : "")
                + (dirty ? " *" : "")
                + "###sbe_editor";
        if (ImGui.begin(title, visible, flags)) {
            boolean loaded = loadedManifest != null;
            selectedTab = chrome.render(loaded, loaded && dirty, dirty,
                    currentPath != null ? currentPath.getFileName().toString() : "",
                    "Open Different SBE...", tabLabels(), selectedTab,
                    this::saveInPlace, this::saveAs, this::openWithDialog);
            ImGui.dummy(0, 6);
            if (!loaded) {
                ImGui.textDisabled(EditorWidgets.emptyEditorText("SBE"));
            } else {
                AssetSectionSet.Tab tab = tabs.get(Math.min(selectedTab, tabs.size() - 1));
                if (tab.section() != null) {
                    sections.renderSection(tab.section());
                } else {
                    switch (tab.key()) {
                        case TAB_METADATA -> renderMetadataTab();
                        case TAB_STATES -> statesEditor.render();
                        case TAB_VARIANTS -> variantsEditor.render();
                        case TAB_SOUNDS -> soundsEditor.render();
                        default -> { }
                    }
                }
                if (!validationMessage.isEmpty()) {
                    ImGui.dummy(0, 8);
                    ThemedWidgets.inlineError(validationMessage);
                }
            }
            objectIndexPopup.render();
            sections.renderOverlays();
        }
        ImGui.end();
    }

    private void renderMetadataTab() {
        if (openModelHandler != null && loadedOmoBytes != null) {
            if (ImGui.button("Open Model in Editor")) {
                openModelHandler.accept(loadedOmoBytes,
                        loadedManifest != null ? loadedManifest.objectName() : "SBE model");
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Loads a COPY of the embedded model into the viewport "
                        + "(the .sbe file itself is not touched; Save becomes Save As)");
            }
            ImGui.separator();
        }
        ThemedWidgets.sectionLabel("Identity");
        if (ImGui.inputTextWithHint("Object ID", "e.g. " + GameTargets.shared().qualify("cow"), objectId)) dirty = true;
        ImGui.sameLine();
        if (ImGui.smallButton(EditorWidgets.TAKEN_IDS_LABEL + "##sbe_editor_taken")) objectIndexPopup.open();
        if (ImGui.inputTextWithHint("Object Name", "e.g. Cow", objectName))        dirty = true;

        ThemedWidgets.sectionLabel("Classification");
        if (ImGui.inputTextWithHint("Pack", "e.g. default, expansion_1", objectPack)) dirty = true;
        sections.renderMetadataSections();

        ThemedWidgets.sectionLabel("Attribution");
        if (ImGui.inputTextWithHint("Author", "Creator name or studio", author))   dirty = true;
        ImGui.text("Description");
        if (ImGui.inputTextMultiline("##desc", description, -1, 80)) dirty = true;

        renderBaseModelRow();
    }

    /**
     * Base OMO slot. The base model applies to the whole entity (variants/states
     * layer their own overrides on top). Replacing it swaps {@link #loadedOmoBytes},
     * which the serializer re-embeds as {@code model.omo} on save.
     */
    private void renderBaseModelRow() {
        ThemedWidgets.sectionLabel("Base Model");
        ImGui.textDisabled("Embedded OMO applied to every variant/state unless overridden.");

        ImGui.textDisabled("Model:");
        ImGui.sameLine(80.0f);
        if (loadedOmoBytes != null) {
            ImGui.text(baseModelLabel != null ? baseModelLabel : "(loaded)");
            ImGui.sameLine();
            ImGui.textDisabled("(" + EditorWidgets.humanBytes(loadedOmoBytes.length) + ")");
        } else {
            ImGui.textDisabled("(none)");
        }
        ImGui.sameLine();
        if (ImGui.smallButton("Replace...")) pickBaseModel();
    }

    private void pickBaseModel() {
        if (fileDialogService == null) return;
        fileDialogService.showOpenOMOInProjectDialog(this::readBaseModelFrom);
    }

    private void readBaseModelFrom(String picked) {
        if (picked == null || picked.isBlank()) return;
        try {
            Path path = Path.of(picked);
            loadedOmoBytes = Files.readAllBytes(path);
            baseModelLabel = path.getFileName().toString();
            dirty = true;
            if (statusService != null) {
                statusService.updateStatus("Base model set to " + baseModelLabel);
            }
        } catch (IOException e) {
            logger.error("Failed to read OMO {}", picked, e);
            if (statusService != null) statusService.updateStatus("Failed to read OMO file");
        }
    }

    // ========================================================================
    // Save flow
    // ========================================================================

    private void saveInPlace() {
        if (currentPath == null) {
            saveAs();
            return;
        }
        writeTo(currentPath.toString());
    }

    private void saveAs() {
        if (fileDialogService == null) return;
        fileDialogService.showSaveSBEDialog(this::writeTo);
    }

    private void writeTo(String pathStr) {
        if (loadedManifest == null) return;

        String stateError = statesEditor.validate();
        if (stateError != null) { rejectSave(stateError, TAB_STATES); return; }
        String variantError = variantsEditor.validate();
        if (variantError != null) { rejectSave(variantError, TAB_VARIANTS); return; }
        String soundError = soundsEditor.validate();
        if (soundError != null) { rejectSave(soundError, TAB_SOUNDS); return; }
        AssetSectionSet.Problem problem = sections.firstError();
        if (problem != null) { rejectSave(problem.message(), sectionTab(problem.section())); return; }
        validationMessage = "";

        sections.confirmThenWrite(() -> performWrite(pathStr));
    }

    private String sectionTab(com.openmason.api.asset.AssetEditorSection<?> section) {
        for (AssetSectionSet.Tab t : tabs) {
            if (t.section() == section) return t.key();
        }
        return TAB_METADATA;
    }

    private String[] tabLabels() {
        return tabs.stream().map(AssetSectionSet.Tab::label).toArray(String[]::new);
    }

    /** Show a save-blocking problem inline, jump to the offending tab and echo it to the status bar. */
    private void rejectSave(String error, String tabKey) {
        validationMessage = error;
        selectedTab = 0;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).key().equals(tabKey)) selectedTab = i;
        }
        if (statusService != null) statusService.updateStatus("Cannot save: " + error);
    }

    private boolean performWrite(String pathStr) {
        SBEFormat.Document edited = buildEditedDocument();
        java.util.Map<String, byte[]> assetBytes = new java.util.LinkedHashMap<>();
        assetBytes.putAll(statesEditor.stateAssetBytesByFilename());
        assetBytes.putAll(variantsEditor.variantAssetBytesByFilename());
        // Embedded sound samples (1.4+) ride in the same filename-keyed map.
        assetBytes.putAll(soundsEditor.soundBytesByFilename());

        boolean ok = serializer.exportFromDocument(edited, loadedOmoBytes, assetBytes, pathStr);
        if (ok) {
            currentPath = Path.of(pathStr);
            loadedManifest = edited;
            loadedStateAssetBytes = assetBytes;
            dirty = false;
            sections.written();
            if (statusService != null) {
                statusService.updateStatus("Saved SBE: " + currentPath.getFileName());
            }
        } else if (statusService != null) {
            statusService.updateStatus("Failed to save SBE");
        }
        return ok;
    }

    /** The core form over the loaded manifest (entity type kept as loaded), then each section's form. */
    private SBEFormat.Document buildEditedDocument() {
        return sections.write(new SBEFormat.Document(
                loadedManifest.version(),
                objectId.get().trim(),
                objectName.get().trim(),
                loadedManifest.entityType(),
                objectPack.get().trim(),
                loadedManifest.checksum(), // recomputed by serializer
                author.get().trim(),
                description.get().isBlank() ? null : description.get(),
                loadedManifest.createdAt(),
                loadedManifest.omoFilename(),
                statesEditor.toStateEntries(),
                variantsEditor.toVariantEntries(),
                soundsEditor.toSoundData(),
                loadedManifest.extensions() // game data: kept as loaded unless a section changes it
        ));
    }

    /**
     * Release GPU-backed resources (chrome + sounds Mortar regions). Must run
     * with a current GL context, before the SkijaContext closes.
     */
    public void close() {
        chrome.close();
        statesEditor.close();
        variantsEditor.close();
        soundsEditor.close();
        sections.close();
    }
}
