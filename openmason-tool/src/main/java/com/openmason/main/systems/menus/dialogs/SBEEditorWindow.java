package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbe.SBEParser;
import com.openmason.engine.format.sbe.SBESerializer;
import com.openmason.main.systems.services.StatusService;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiTabBarFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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

    private static final String[] ENTITY_TYPE_LABELS = {
            "Mob", "NPC", "Projectile", "Vehicle", "Other"
    };

    private final ImBoolean visible = new ImBoolean(false);
    private final FileDialogService fileDialogService;
    private final StatusService statusService;
    private final SBEParser parser = new SBEParser();
    private final SBESerializer serializer = new SBESerializer();
    private final SBEStatesEditor statesEditor;
    private final SBEVariantsEditor variantsEditor;

    // Loaded document state
    private Path currentPath;
    private SBEFormat.Document loadedManifest;
    private byte[] loadedOmoBytes;
    private boolean dirty;

    // Form buffers
    private final ImString objectId = new ImString(256);
    private final ImString objectName = new ImString(256);
    private final ImString objectPack = new ImString(256);
    private final ImString author = new ImString(256);
    private final ImString description = new ImString(1024);
    private final ImInt entityTypeIndex = new ImInt(0);

    public SBEEditorWindow(FileDialogService fileDialogService, StatusService statusService) {
        this.fileDialogService = fileDialogService;
        this.statusService = statusService;
        this.statesEditor = new SBEStatesEditor(
                () -> dirty = true,
                cb -> { if (fileDialogService != null) fileDialogService.showOpenOMODialog(cb::accept); },
                cb -> { if (fileDialogService != null) fileDialogService.showOpenOMADialog(cb::accept); });
        this.variantsEditor = new SBEVariantsEditor(
                () -> dirty = true,
                cb -> { if (fileDialogService != null) fileDialogService.showOpenOMODialog(cb::accept); });
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

    private void loadFile(String pathStr) {
        try {
            Path path = Path.of(pathStr);
            SBEParser.RawParse raw = parser.parseRaw(path);
            this.currentPath = path;
            this.loadedManifest = raw.manifest();
            this.loadedOmoBytes = raw.omoBytes();
            populateBuffers(raw.manifest(), raw.stateAssetBytes());
            this.dirty = false;
            this.visible.set(true);
            if (statusService != null) {
                statusService.updateStatus("Opened SBE: " + path.getFileName());
            }
        } catch (IOException e) {
            logger.error("Failed to load SBE {}", pathStr, e);
            if (statusService != null) statusService.updateStatus("Failed to open SBE");
        }
    }

    private void populateBuffers(SBEFormat.Document doc, java.util.Map<String, byte[]> stateAssetBytes) {
        objectId.set(doc.objectId());
        objectName.set(doc.objectName());
        objectPack.set(doc.objectPack());
        author.set(doc.author());
        description.set(doc.description() != null ? doc.description() : "");
        entityTypeIndex.set(indexOf(ENTITY_TYPE_LABELS, doc.entityType()));
        statesEditor.load(doc, stateAssetBytes);
        variantsEditor.load(doc, stateAssetBytes);
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
            renderToolbar();
            ImGui.separator();
            if (loadedManifest == null) {
                ImGui.textDisabled("No SBE loaded. Use Tools > SBE Editor...");
            } else {
                renderTabs();
            }
        }
        ImGui.end();
    }

    private void renderToolbar() {
        boolean canSave = loadedManifest != null && dirty;
        if (!canSave) ImGui.beginDisabled();
        if (ImGui.button("Save")) {
            saveInPlace();
        }
        if (!canSave) ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.button("Save As...")) {
            saveAs();
        }
        ImGui.sameLine();
        if (ImGui.button("Open Different SBE...")) {
            openWithDialog();
        }
    }

    private void renderTabs() {
        if (ImGui.beginTabBar("##sbe_editor_tabs", ImGuiTabBarFlags.Reorderable)) {
            if (ImGui.beginTabItem("Metadata")) {
                renderMetadataTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("States")) {
                statesEditor.render();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Variants")) {
                variantsEditor.render();
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
    }

    private void renderMetadataTab() {
        if (ImGui.inputText("Object ID", objectId))    dirty = true;
        if (ImGui.inputText("Object Name", objectName)) dirty = true;
        if (ImGui.combo("Entity Type", entityTypeIndex, ENTITY_TYPE_LABELS)) dirty = true;
        if (ImGui.inputText("Pack", objectPack))        dirty = true;
        if (ImGui.inputText("Author", author))          dirty = true;
        ImGui.text("Description");
        if (ImGui.inputTextMultiline("##desc", description, -1, 80)) dirty = true;
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
        if (stateError != null) {
            if (statusService != null) statusService.updateStatus("Cannot save: " + stateError);
            return;
        }
        String variantError = variantsEditor.validate();
        if (variantError != null) {
            if (statusService != null) statusService.updateStatus("Cannot save: " + variantError);
            return;
        }

        SBEFormat.Document edited = buildEditedDocument();
        java.util.Map<String, byte[]> assetBytes = new java.util.LinkedHashMap<>();
        assetBytes.putAll(statesEditor.stateAssetBytesByFilename());
        assetBytes.putAll(variantsEditor.variantAssetBytesByFilename());

        boolean ok = serializer.exportFromDocument(edited, loadedOmoBytes, assetBytes, pathStr);
        if (ok) {
            currentPath = Path.of(pathStr);
            loadedManifest = edited;
            dirty = false;
            if (statusService != null) {
                statusService.updateStatus("Saved SBE: " + currentPath.getFileName());
            }
        } else if (statusService != null) {
            statusService.updateStatus("Failed to save SBE");
        }
    }

    private SBEFormat.Document buildEditedDocument() {
        return new SBEFormat.Document(
                loadedManifest.version(),
                objectId.get().trim(),
                objectName.get().trim(),
                SBEFormat.EntityType.values()[entityTypeIndex.get()].getId(),
                objectPack.get().trim(),
                loadedManifest.checksum(), // recomputed by serializer
                author.get().trim(),
                description.get().isBlank() ? null : description.get(),
                loadedManifest.createdAt(),
                loadedManifest.omoFilename(),
                statesEditor.toStateEntries(),
                variantsEditor.toVariantEntries()
        );
    }

    private static int indexOf(String[] arr, String value) {
        if (value == null) return 0;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equalsIgnoreCase(value)) return i;
        }
        return 0;
    }
}
