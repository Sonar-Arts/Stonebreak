package com.openmason.main.systems.menus.dialogs;

import com.openmason.api.asset.AssetEditorSection;
import com.openmason.api.asset.AssetSectionHost;
import com.openmason.api.asset.AssetSurface;
import com.openmason.main.systems.target.GameTargets;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.format.sbo.SBOSerializer;
import com.openmason.main.systems.services.StatusService;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Standalone editor window for {@code .sbo} files.
 *
 * <p>Loads an existing SBO from disk via {@link SBOParser#parseRaw(Path)}, exposes the generic
 * container data (identity, classification, render layer and transparency, states and clips,
 * sounds) as editable tabs, and writes the result back via {@link SBOSerializer#exportFromDocument}.
 * Game data (properties, recipes, drops, tools...) is edited by the {@link AssetEditorSection}s a
 * game plugin contributes for {@link AssetSurface#SBO_EDITOR} (#431); without one, that data is
 * carried through a save unchanged. The embedded OMO/OMT bytes are passed through unchanged.
 */
public class SBOEditorWindow {

    private static final Logger logger = LoggerFactory.getLogger(SBOEditorWindow.class);
    private static final String WINDOW_TITLE = "SBO Editor";

    private final ImBoolean visible;
    private final FileDialogService fileDialogService;
    private final StatusService statusService;
    private final SBOParser parser = new SBOParser();
    private final SBOSerializer serializer = new SBOSerializer();
    private final SBOStatesEditor statesEditor;
    private final SoundsEditor soundsEditor;
    /** Game-data sections contributed by plugins (#431). */
    private final AssetSectionSet<SBOFormat.Document> sections;
    private List<AssetSectionSet.Tab> tabs = CORE_TABS;

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

    // Container-level rendering fields, stored in the gameProperties block when it exists.
    private final ImInt renderLayerIndex = new ImInt(0);
    private final ImBoolean transparent = new ImBoolean(false);

    private static final String[] OBJECT_TYPE_LABELS = {
            "block", "item", "entity", "decoration", "particle", "other"
    };
    private static final String[] RENDER_LAYER_LABELS = { "OPAQUE", "CUTOUT", "TRANSLUCENT" };
    private static final String TAB_METADATA = "core.metadata";
    private static final String TAB_STATES = "core.states";
    private static final String TAB_SOUNDS = "core.sounds";
    private static final List<AssetSectionSet.Tab> CORE_TABS = List.of(
            new AssetSectionSet.Tab(TAB_METADATA, "Metadata", 0, null),
            new AssetSectionSet.Tab(TAB_STATES, "States", 200, null),
            new AssetSectionSet.Tab(TAB_SOUNDS, "Sounds", 500, null));

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
        this.statesEditor = new SBOStatesEditor(
                () -> dirty = true,
                cb -> { if (fileDialogService != null) fileDialogService.showOpenOMOInProjectDialog(cb::accept); },
                cb -> { if (fileDialogService != null) fileDialogService.showOpenOMTInProjectDialog(cb::accept); },
                cb -> { if (fileDialogService != null) fileDialogService.showOpenOMADialog(cb::accept); });
        this.soundsEditor = new SoundsEditor(
                () -> dirty = true,
                cb -> { if (fileDialogService != null) fileDialogService.showOpenAudioDialog(cb::accept); });
        this.sections = new AssetSectionSet<>(AssetSurface.SBO_EDITOR, new AssetSectionHost() {
            @Override
            public AssetSurface<?> surface() {
                return AssetSurface.SBO_EDITOR;
            }

            @Override
            public String objectId() {
                return objectId.get().trim();
            }

            @Override
            public String objectType() {
                return OBJECT_TYPE_LABELS[objectTypeIndex.get()];
            }

            @Override
            public boolean texturePayload() {
                return loadedManifest != null && loadedManifest.isTextureOnly();
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
        if (err != null) return err;
        AssetSectionSet.Problem problem = sections.firstError();
        if (problem != null) return problem.message();
        List<AssetSectionSet.Problem> conflicts = sections.conflicts();
        return conflicts.isEmpty() ? null : conflicts.get(0).message();
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
        renderLayerIndex.set(gp != null ? indexOf(RENDER_LAYER_LABELS, gp.renderLayerOrDefault()) : 0);
        transparent.set(gp != null && gp.transparent());

        statesEditor.load(doc, loadedStateBytes, loadedStateClipBytes, loadedDefaultBytes);
        soundsEditor.load(doc.sounds(),
                loadedSoundBytes != null ? loadedSoundBytes::get : f -> null);
        sections.load(doc);
        tabs = sections.tabs(CORE_TABS);
        if (selectedTab >= tabs.size()) selectedTab = 0;
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
                    "Open Different SBO...", tabLabels(), selectedTab,
                    this::saveInPlace, this::saveAs, this::openWithDialog);
            ImGui.dummy(0, 6);
            if (!loaded) {
                ImGui.textDisabled(EditorWidgets.emptyEditorText("SBO"));
            } else {
                AssetSectionSet.Tab tab = tabs.get(Math.min(selectedTab, tabs.size() - 1));
                if (tab.section() != null) {
                    sections.renderSection(tab.section());
                } else {
                    switch (tab.key()) {
                        case TAB_METADATA -> renderMetadataTab();
                        case TAB_STATES -> statesEditor.render();
                        case TAB_SOUNDS -> soundsEditor.render();
                        default -> { }
                    }
                }
                if (!validationMessage.isEmpty()) {
                    ImGui.dummy(0, 8);
                    ThemedWidgets.inlineError(validationMessage);
                }
            }
            sections.renderOverlays();
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
        if (ImGui.inputTextWithHint("Object ID", "e.g. " + GameTargets.shared().qualify("oak_planks"), objectId)) dirty = true;
        if (ImGui.inputTextWithHint("Object Name", "e.g. Oak Planks", objectName))         dirty = true;

        ThemedWidgets.sectionLabel("Classification");
        if (ImGui.combo("Object Type", objectTypeIndex, OBJECT_TYPE_LABELS)) dirty = true;
        if (ImGui.inputTextWithHint("Pack", "e.g. default, expansion_1", objectPack))      dirty = true;
        sections.renderMetadataSections();

        renderRenderingControls();

        ThemedWidgets.sectionLabel("Attribution");
        if (ImGui.inputTextWithHint("Author", "Creator name or studio", author))           dirty = true;
        ImGui.text("Description");
        if (ImGui.inputTextMultiline("##desc", description, -1, 80)) dirty = true;
    }

    /**
     * Render layer + transparency: container-level fields every game's renderer reads, stored in
     * the gameProperties block (they are only written when the SBO has one).
     */
    private void renderRenderingControls() {
        ThemedWidgets.sectionLabel("Rendering");
        boolean stored = buildEditedDocument().gameProperties() != null;
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        if (ImGui.combo("Render Layer", renderLayerIndex, RENDER_LAYER_LABELS)) dirty = true;
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.checkbox("Transparent", transparent)) dirty = true;
        if (!stored) {
            ImGui.textDisabled("Not saved: this SBO has no gameProperties block to store them in.");
        }
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
        AssetSectionSet.Problem problem = sections.firstError();
        if (problem != null) { rejectSave(problem.message(), problem.section()); return; }
        validationMessage = "";
        sections.confirmThenWrite(() -> performWrite(pathStr));
    }

    /** {@link #rejectSave(String, String)} for a section's problem: jump to the section's tab. */
    private void rejectSave(String error, AssetEditorSection<?> section) {
        String key = TAB_METADATA;
        if (!AssetSectionSet.onMetadataTab(section)) {
            for (AssetSectionSet.Tab t : tabs) {
                if (t.section() == section) key = t.key();
            }
        }
        rejectSave(error, key);
    }

    private String[] tabLabels() {
        return tabs.stream().map(AssetSectionSet.Tab::label).toArray(String[]::new);
    }

    private int tabIndex(String key) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).key().equals(key)) return i;
        }
        return 0;
    }

    /** Show a save-blocking problem inline, jump to the offending tab and echo it to the status bar. */
    private void rejectSave(String error, String tabKey) {
        validationMessage = error;
        selectedTab = tabIndex(tabKey);
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
            sections.written();
            if (statusService != null) {
                statusService.updateStatus("Saved SBO: " + currentPath.getFileName());
            }
        } else if (statusService != null) {
            statusService.updateStatus("Failed to save SBO");
        }
        return ok;
    }

    /**
     * The edited manifest: the core form over the loaded manifest (whose game data is kept as it
     * was), then each section's form, then the core rendering fields into the gameProperties block.
     */
    private SBOFormat.Document buildEditedDocument() {
        SBOFormat.Document core = new SBOFormat.Document(
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
                loadedManifest.gameProperties(),
                statesEditor.toStateEntries(),
                statesEditor.defaultStateName(),
                loadedManifest.recipes(),
                loadedManifest.smeltingRecipes(),
                loadedManifest.fuel(),
                soundsEditor.toSoundData(),
                loadedManifest.drops(),
                loadedManifest.tool(),
                loadedManifest.weapon()
        );
        SBOFormat.Document edited = sections.write(core);
        SBOFormat.GameProperties gp = edited.gameProperties();
        return gp == null ? edited : edited.withGameProperties(
                gp.withRendering(RENDER_LAYER_LABELS[renderLayerIndex.get()], transparent.get()));
    }

    private static int indexOf(String[] arr, String value) {
        if (value == null) return 0;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equalsIgnoreCase(value)) return i;
        }
        return 0;
    }

    /**
     * Release GPU-backed resources (chrome/states/sounds Mortar regions and
     * whatever the contributed sections hold). Must run with a current GL context, before
     * the SkijaContext closes.
     */
    public void close() {
        chrome.close();
        statesEditor.close();
        soundsEditor.close();
        sections.close();
    }
}
