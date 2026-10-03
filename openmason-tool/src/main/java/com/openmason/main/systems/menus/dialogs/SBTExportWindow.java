package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbt.SBTFormat;
import com.openmason.engine.format.sbt.SBTSerializer;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.themes.core.ThemeManager;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Export window for creating Stonebreak Texture (.SBT) files.
 *
 * <p>A single-page form for the SBT metadata (Texture ID, Name, Type, Pack,
 * Author, Description). Uses the same {@link EditorChrome} export bar
 * ([Export...][Cancel] + source label), section labels and hinted-input form
 * idiom as the SBO/SBE exporters, and resets every field on {@link #show()}.
 */
public class SBTExportWindow {

    private static final Logger logger = LoggerFactory.getLogger(SBTExportWindow.class);

    private static final String WINDOW_TITLE = "Export SBT";
    private static final float WINDOW_W = 620.0f;
    private static final float WINDOW_H = 520.0f;
    private static final String[] NO_TABS = {};

    private final ImBoolean visible;
    private final StatusService statusService;
    private final FileDialogService fileDialogService;
    private final SBTSerializer serializer;

    /** Same Mortar export chrome as the SBO/SBE exporters (no tab strip). */
    private final EditorChrome chrome = new EditorChrome("sbt_export");

    /**
     * Supplier for the current OMT file path. Pulled lazily so the window can
     * be constructed before the texture editor is available.
     */
    private Supplier<String> omtPathSupplier = () -> null;

    private final ImString textureId = new ImString(256);
    private final ImString textureName = new ImString(256);
    private final ImInt textureTypeIndex = new ImInt(0);
    private final ImString texturePack = new ImString(256);
    private final ImString author = new ImString(256);
    private final ImString description = new ImString(1024);

    /**
     * SBT-valid texture types. ITEM was removed when sprite items migrated
     * to texture-only SBOs (format 1.2+). Combo indices map to
     * {@link #TEXTURE_TYPE_VALUES} so the SBTFormat.TextureType round-trips
     * correctly even though the indices no longer match the enum's ordinals.
     */
    private static final String[] TEXTURE_TYPE_LABELS = {
            "Block", "Entity", "UI", "Other"
    };
    private static final SBTFormat.TextureType[] TEXTURE_TYPE_VALUES = {
            SBTFormat.TextureType.BLOCK,
            SBTFormat.TextureType.ENTITY,
            SBTFormat.TextureType.UI,
            SBTFormat.TextureType.OTHER
    };

    private String validationMessage = "";
    private boolean centerOnNextFrame = false;

    public SBTExportWindow(ImBoolean visible,
                           ThemeManager themeManager,
                           StatusService statusService,
                           FileDialogService fileDialogService) {
        this.visible = visible;
        this.statusService = statusService;
        this.fileDialogService = fileDialogService;
        this.serializer = new SBTSerializer();
    }

    /**
     * Wire a supplier that returns the current OMT file path on disk, or null
     * if no OMT is loaded/saved. Pulled at export time.
     */
    public void setOMTPathSupplier(Supplier<String> supplier) {
        this.omtPathSupplier = supplier != null ? supplier : (() -> null);
    }

    /** Shows the window with a clean form, pre-populated from the current texture. */
    public void show() {
        resetForm();
        prepopulateFromTexture();
        visible.set(true);
        centerOnNextFrame = true;
        logger.debug("SBT export window shown");
    }

    public void hide() {
        visible.set(false);
    }

    public boolean isVisible() {
        return visible.get();
    }

    /** Return every buffer to its initial state — no context survives between exports. */
    private void resetForm() {
        textureId.set("");
        textureName.set("");
        textureTypeIndex.set(0);
        texturePack.set("default");
        author.set("");
        description.set("");
        validationMessage = "";
    }

    public void render() {
        if (!visible.get()) {
            return;
        }

        ImGui.setNextWindowSize(WINDOW_W, WINDOW_H, ImGuiCond.FirstUseEver);
        if (centerOnNextFrame) {
            // Center on the app's main viewport (absolute screen coords under
            // multi-viewport) — size-only math would land on the primary monitor.
            float screenW = ImGui.getMainViewport().getSizeX();
            float screenH = ImGui.getMainViewport().getSizeY();
            float originX = ImGui.getMainViewport().getPosX();
            float originY = ImGui.getMainViewport().getPosY();
            ImGui.setNextWindowPos(originX + (screenW - WINDOW_W) * 0.5f,
                    originY + (screenH - WINDOW_H) * 0.5f, ImGuiCond.Always);
            centerOnNextFrame = false;
        }

        String title = WINDOW_TITLE + sourceSuffix() + "###sbt_export";
        if (ImGui.begin(title, visible, ImGuiWindowFlags.NoCollapse)) {
            try {
                chrome.renderExport(canExport(), sourceLabel(), "Export...", NO_TABS, 0,
                        this::performExport, this::hide, "Save the texture as .OMT first");
                ImGui.dummy(0, 6);
                renderFormFields();
                if (!validationMessage.isEmpty()) {
                    ImGui.dummy(0, 8);
                    ThemedWidgets.inlineError(validationMessage);
                }
            } catch (Exception e) {
                logger.error("Error rendering SBT export window", e);
                ImGui.textDisabled("Error rendering export window");
            }
        }
        ImGui.end();
    }

    private String currentOmtPath() {
        String path = omtPathSupplier.get();
        return path != null && !path.isBlank() ? path : null;
    }

    private String sourceSuffix() {
        String path = currentOmtPath();
        return path != null ? " - " + Path.of(path).getFileName() : "";
    }

    private String sourceLabel() {
        String path = currentOmtPath();
        return path != null ? "Texture: " + Path.of(path).getFileName() : "Texture not saved as .OMT";
    }

    private boolean canExport() {
        return currentOmtPath() != null;
    }

    private void renderFormFields() {
        ThemedWidgets.sectionLabel("Identity");
        ImGui.inputTextWithHint("Texture ID", "e.g. stonebreak:cow_default", textureId);
        ImGui.inputTextWithHint("Texture Name", "e.g. Cow Default", textureName);

        ThemedWidgets.sectionLabel("Classification");
        ImGui.combo("Texture Type", textureTypeIndex, TEXTURE_TYPE_LABELS);
        ImGui.inputTextWithHint("Pack", "e.g. default, expansion_1", texturePack);

        ThemedWidgets.sectionLabel("Attribution");
        ImGui.inputTextWithHint("Author", "Creator name or studio", author);
        ImGui.text("Description");
        ImGui.inputTextMultiline("##desc", description, -1, 80);
    }

    /** Release the Mortar chrome region. Must run with a current GL context. */
    public void close() {
        chrome.close();
    }

    private void prepopulateFromTexture() {
        String omtPath = omtPathSupplier.get();
        if (omtPath != null && !omtPath.isBlank()) {
            String fileName = Path.of(omtPath).getFileName().toString();
            String nameWithoutExt = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
            textureName.set(nameWithoutExt);

            String idCandidate = nameWithoutExt.toLowerCase().replace(' ', '_');
            textureId.set("stonebreak:" + idCandidate);
        }
    }

    private void performExport() {
        SBTFormat.ExportParameters params = buildParameters();

        if (!params.isValid()) {
            validationMessage = params.getValidationError();
            return;
        }
        validationMessage = "";

        String omtPathStr = omtPathSupplier.get();
        if (omtPathStr == null || omtPathStr.isBlank()) {
            validationMessage = "Texture must be saved as .OMT before exporting to .SBT";
            statusService.updateStatus("Export failed: texture not saved as .OMT");
            return;
        }

        Path omtPath = Path.of(omtPathStr);

        fileDialogService.showSaveSBTDialog(filePath -> {
            boolean success = serializer.export(params, omtPath, filePath);
            if (success) {
                statusService.updateStatus("Exported SBT: " + Path.of(filePath).getFileName());
                visible.set(false);
                logger.info("SBT export successful: {}", filePath);
            } else {
                validationMessage = "Export failed. Check logs for details.";
                statusService.updateStatus("SBT export failed");
            }
        });
    }

    private SBTFormat.ExportParameters buildParameters() {
        SBTFormat.ExportParameters params = new SBTFormat.ExportParameters();
        params.setTextureId(textureId.get().trim());
        params.setTextureName(textureName.get().trim());
        params.setTextureType(TEXTURE_TYPE_VALUES[textureTypeIndex.get()]);
        params.setTexturePack(texturePack.get().trim());
        params.setAuthor(author.get().trim());
        params.setDescription(description.get().trim());
        return params;
    }
}
