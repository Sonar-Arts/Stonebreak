package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.target.GameTargets;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.nfd.NFDFilterItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.util.nfd.NativeFileDialog.*;

/**
 * File dialog service for open/save/export operations.
 * Uses LWJGL NFD for native file dialogs.
 * <p>
 * Optimized to minimize NFD initialization overhead and reduce code duplication.
 */
public class FileDialogService {

    private static final Logger logger = LoggerFactory.getLogger(FileDialogService.class);

    private final StatusService statusService;
    private volatile boolean nfdInitialized = false;

    /**
     * Per-file-type last-used directory. Keyed by the filter spec (e.g. "omo",
     * "omp", "sbo") so each format keeps an independent navigation context —
     * saving a .sbo right after a .omo no longer forces the user back to the
     * OS default directory.
     */
    private final Map<String, String> lastDirectoryByType = new ConcurrentHashMap<>();

    /** Supplies the open project's folder (null when no project is loaded). */
    private java.util.function.Supplier<String> projectDirectorySupplier;

    public FileDialogService(StatusService statusService) {
        this.statusService = statusService;
        initializeNFD();
    }

    /**
     * Initialize NFD once for all dialog operations.
     * This avoids repeated initialization overhead.
     */
    private void initializeNFD() {
        if (!nfdInitialized) {
            try {
                int initResult = NFD_Init();
                if (initResult == NFD_OKAY) {
                    nfdInitialized = true;
                    logger.debug("NFD initialized successfully");
                } else {
                    logger.error("Failed to initialize NFD: {}", NFD_GetError());
                }
            } catch (Exception e) {
                logger.error("Exception during NFD initialization", e);
            }
        }
    }

    /**
     * Cleanup NFD resources. Should be called when service is no longer needed.
     */
    public void cleanup() {
        if (nfdInitialized) {
            try {
                NFD_Quit();
                nfdInitialized = false;
                logger.debug("NFD cleaned up successfully");
            } catch (Exception e) {
                logger.error("Error during NFD cleanup", e);
            }
        }
    }

    /**
     * Create NFD filter for a single file type.
     * Note: filters.get() returns a view into the buffer - it doesn't need separate resource management.
     * The parent buffer is managed by try-with-resources in the calling methods.
     */
    @SuppressWarnings("resource")
    private void createFilter(NFDFilterItem.Buffer filters, int index, MemoryStack stack, String name, String spec) {
        filters.get(index)
                .name(stack.UTF8(name))
                .spec(stack.UTF8(spec));
    }

    /**
     * Generic open dialog implementation to reduce code duplication.
     */
    private void showNFDOpenDialog(String statusMessage, String filterName, String filterSpec,
                                   String logPrefix, OpenCallback callback) {
        showNFDOpenDialogMultiFilter(statusMessage, new String[]{filterName}, new String[]{filterSpec}, null, logPrefix, callback);
    }

    /**
     * Generic open dialog with an optional preferred starting directory that
     * takes precedence over the per-type last-used directory.
     */
    private void showNFDOpenDialog(String statusMessage, String filterName, String filterSpec,
                                   String preferredDirectory, String logPrefix, OpenCallback callback) {
        showNFDOpenDialogMultiFilter(statusMessage, new String[]{filterName}, new String[]{filterSpec},
                preferredDirectory, logPrefix, callback);
    }

    /**
     * Generic open dialog with multiple filter support.
     */
    private void showNFDOpenDialogMultiFilter(String statusMessage, String[] filterNames, String[] filterSpecs,
                                              String logPrefix, OpenCallback callback) {
        showNFDOpenDialogMultiFilter(statusMessage, filterNames, filterSpecs, null, logPrefix, callback);
    }

    /**
     * Generic open dialog with multiple filter support and an optional preferred
     * starting directory.
     */
    private void showNFDOpenDialogMultiFilter(String statusMessage, String[] filterNames, String[] filterSpecs,
                                              String preferredDirectory, String logPrefix, OpenCallback callback) {
        if (!nfdInitialized) {
            statusService.updateStatus("Error: File dialog not initialized");
            return;
        }

        statusService.updateStatus(statusMessage);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer outPath = stack.mallocPointer(1);

            // Create filters with proper resource management
            try (NFDFilterItem.Buffer filters = NFDFilterItem.malloc(filterNames.length)) {
                for (int i = 0; i < filterNames.length; i++) {
                    createFilter(filters, i, stack, filterNames[i], filterSpecs[i]);
                }

                // Show open dialog — a caller-preferred directory (e.g. the open
                // project's root) wins; otherwise seed with the last directory
                // used for any of these filter types, so each format keeps its
                // own navigation context across sessions.
                String defaultPath = (preferredDirectory != null && new File(preferredDirectory).isDirectory())
                        ? preferredDirectory
                        : resolveDefaultDirectory(filterSpecs);
                int result = NFD_OpenDialog(outPath, filters, defaultPath);

                if (result == NFD_OKAY) {
                    String selectedPath = outPath.getStringUTF8(0);
                    NFD_FreePath(outPath.get(0));
                    rememberDirectoryFor(filterSpecs, selectedPath);
                    logger.info("{}: {}", logPrefix, selectedPath);
                    callback.onOpen(selectedPath);
                    statusService.updateStatus("Opened: " + new File(selectedPath).getName());
                } else if (result == NFD_CANCEL) {
                    logger.info("User cancelled {} dialog", logPrefix);
                    statusService.updateStatus("Open cancelled");
                } else {
                    logger.error("NFD Error: {}", NFD_GetError());
                    statusService.updateStatus("Error opening file dialog");
                }
            }
        } catch (Exception e) {
            logger.error("Error showing {} dialog", logPrefix, e);
            statusService.updateStatus("Error: " + e.getMessage());
        }
    }

    /**
     * Generic save dialog implementation to reduce code duplication.
     */
    private void showNFDSaveDialog(String statusMessage, String filterName, String filterSpec,
                                   String defaultFileName, String logPrefix, SaveCallback callback) {
        showNFDSaveDialog(statusMessage, filterName, filterSpec, defaultFileName, null, logPrefix, callback);
    }

    /**
     * Generic save dialog with an optional preferred starting directory that
     * takes precedence over the per-type last-used directory.
     */
    private void showNFDSaveDialog(String statusMessage, String filterName, String filterSpec,
                                   String defaultFileName, String preferredDirectory,
                                   String logPrefix, SaveCallback callback) {
        if (!nfdInitialized) {
            statusService.updateStatus("Error: File dialog not initialized");
            return;
        }

        statusService.updateStatus(statusMessage);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer outPath = stack.mallocPointer(1);

            // Create filter with proper resource management
            try (NFDFilterItem.Buffer filters = NFDFilterItem.malloc(1)) {
                createFilter(filters, 0, stack, filterName, filterSpec);

                // Show save dialog — a caller-preferred directory (e.g. the open
                // project's folder) wins; otherwise seed with the last directory
                // used for this file type so each format has its own context.
                String defaultPath = preferredDirectory != null
                        ? preferredDirectory
                        : resolveDefaultDirectory(new String[]{filterSpec});
                int result = NFD_SaveDialog(outPath, filters, defaultPath, defaultFileName);

                if (result == NFD_OKAY) {
                    String selectedPath = outPath.getStringUTF8(0);
                    NFD_FreePath(outPath.get(0));

                    // Ensure correct extension
                    if (!selectedPath.toLowerCase().endsWith("." + filterSpec)) {
                        selectedPath += "." + filterSpec;
                    }

                    rememberDirectoryFor(new String[]{filterSpec}, selectedPath);
                    logger.info("{}: {}", logPrefix, selectedPath);
                    callback.onSave(selectedPath);
                    statusService.updateStatus("Saved: " + new File(selectedPath).getName());
                } else if (result == NFD_CANCEL) {
                    logger.info("User cancelled {} dialog", logPrefix);
                    statusService.updateStatus("Save cancelled");
                } else {
                    logger.error("NFD Error: {}", NFD_GetError());
                    statusService.updateStatus("Error opening save dialog");
                }
            }
        } catch (Exception e) {
            logger.error("Error showing {} dialog", logPrefix, e);
            statusService.updateStatus("Error: " + e.getMessage());
        }
    }

    /**
     * Wire the source of the current project's directory, used to point
     * model-save dialogs at the folder the open .OMP lives in.
     */
    public void setProjectDirectorySupplier(java.util.function.Supplier<String> supplier) {
        this.projectDirectorySupplier = supplier;
    }

    /** The open project's folder, or null when none is loaded or it's gone. */
    private String projectDirectoryOrNull() {
        if (projectDirectorySupplier == null) {
            return null;
        }
        try {
            String dir = projectDirectorySupplier.get();
            return dir != null && !dir.isBlank() && new File(dir).isDirectory() ? dir : null;
        } catch (Exception e) {
            logger.debug("Could not resolve project directory: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Look up the last-used directory for any of the given filter specs.
     * Returns the first match so multi-filter dialogs (e.g. OMT + PNG) reuse
     * whichever context the user most recently navigated within.
     */
    private String resolveDefaultDirectory(String[] filterSpecs) {
        for (String spec : filterSpecs) {
            String dir = lastDirectoryByType.get(spec.toLowerCase());
            if (dir != null && new File(dir).isDirectory()) {
                return dir;
            }
        }
        return null; // NFD falls back to its default when null
    }

    /**
     * Record the parent directory of a just-selected file under each of the
     * provided filter specs. Multi-filter dialogs record the same directory
     * for every accepted extension so the context follows the user's intent.
     */
    private void rememberDirectoryFor(String[] filterSpecs, String selectedPath) {
        if (selectedPath == null || selectedPath.isBlank()) {
            return;
        }
        try {
            Path parent = Path.of(selectedPath).getParent();
            if (parent == null) {
                return;
            }
            String dir = parent.toString();
            for (String spec : filterSpecs) {
                lastDirectoryByType.put(spec.toLowerCase(), dir);
            }
        } catch (Exception e) {
            logger.debug("Could not record directory for {}: {}", selectedPath, e.getMessage());
        }
    }

    /**
     * Generic save callback interface.
     */
    private interface SaveCallback {
        void onSave(String filePath);
    }

    // ========================================
    // Type-driven dialogs (#379): any registered file type
    // ========================================

    /** Where a type-driven dialog starts when the caller names no folder. */
    public enum StartIn {
        /** The folder last used for this type (per-type navigation context). */
        LAST_USED,
        /** The open project's folder (the .omp's), else the last-used folder. */
        PROJECT,
        /**
         * The type's own folder inside the project ({@code FileType.SaveRule.subdir}, e.g. Scenes
         * or UI) when it exists, else the project folder, else the last-used folder.
         */
        TYPE_FOLDER
    }

    /**
     * Open a file of one of {@code types} (one filter per type, in order).
     *
     * @param start where the dialog starts (the first type's folder for {@link StartIn#TYPE_FOLDER})
     */
    public void showOpenDialog(StartIn start, OpenCallback callback, com.openmason.api.files.FileType... types) {
        showOpenDialog(directoryFor(start, types[0]), callback, types);
    }

    /** Open a file of one of {@code types}, starting in {@code preferredDirectory} (null = last used). */
    public void showOpenDialog(String preferredDirectory, OpenCallback callback,
                               com.openmason.api.files.FileType... types) {
        String[] names = new String[types.length];
        String[] specs = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            names[i] = types[i].displayName();
            specs[i] = String.join(",", types[i].extensions().stream().map(e -> e.substring(1)).toList());
        }
        showNFDOpenDialogMultiFilter("Opening " + types[0].displayName() + "...", names, specs,
                preferredDirectory, "Selected " + types[0].displayName(), callback);
    }

    /**
     * Save a file of {@code type} (its primary extension is enforced). A null
     * {@code preferredDirectory} starts in the type's folder for project-rooted types
     * ({@link StartIn#TYPE_FOLDER}), else in the last folder used for the type.
     */
    public void showSaveDialog(com.openmason.api.files.FileType type, String defaultFileName,
                               String preferredDirectory, OpenCallback callback) {
        String dir = preferredDirectory != null ? preferredDirectory
                : type.save() != null && "project".equals(type.save().root())
                ? directoryFor(StartIn.TYPE_FOLDER, type) : null;
        String name = defaultFileName != null && !defaultFileName.isBlank() ? defaultFileName
                : (type.save() != null ? type.save().fallbackName() : "untitled") + type.extension();
        showNFDSaveDialog("Saving " + type.displayName() + "...", type.displayName(), type.filterSpec(), name, dir,
                "Save " + type.displayName() + " to file", callback::onOpen);
    }

    /** The starting folder for {@code start} and {@code type} (null = the type's last-used folder). */
    private String directoryFor(StartIn start, com.openmason.api.files.FileType type) {
        return switch (start) {
            case LAST_USED -> null;
            case PROJECT -> projectDirectoryOrNull();
            case TYPE_FOLDER -> {
                String projectDir = projectDirectoryOrNull();
                if (projectDir == null || type.save() == null || type.save().subdir().isEmpty()) {
                    yield projectDir;
                }
                java.nio.file.Path sub = java.nio.file.Path.of(projectDir).resolve(type.save().subdir());
                yield java.nio.file.Files.isDirectory(sub) ? sub.toString() : projectDir;
            }
        };
    }

    /**
     * Show open PNG dialog using native file dialog.
     */
    public void showOpenPNGDialog(OpenCallback callback) {
        showOpenDialog(StartIn.LAST_USED, callback, com.openmason.main.systems.io.BuiltinFileTypes.PNG);
    }

    /**
     * Show save PNG dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showSavePNGDialog(SavePNGCallback callback) {
        showSaveDialog(com.openmason.main.systems.io.BuiltinFileTypes.PNG, "texture.png", null, callback::onSave);
    }

    /**
     * Callback interface for open operations.
     */
    public interface OpenCallback {
        void onOpen(String filePath);
    }

    /**
     * Callback interface for PNG save operations.
     */
    public interface SavePNGCallback {
        void onSave(String filePath);
    }

    /**
     * Show open audio-sample dialog (.wav). Used by the SBO/SBE editors'
     * Sounds sections to pick samples to embed; the engine decodes via
     * {@code javax.sound.sampled}, so WAV is the supported interchange format.
     */
    public void showOpenAudioDialog(OpenCallback callback) {
        showNFDOpenDialog("Opening audio sample...", "WAV Audio", "wav",
                "Selected audio file", callback);
    }

    /**
     * Show open OMT (Open Mason Texture) dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showOpenOMTDialog(OpenCallback callback) {
        showOpenDialog(StartIn.LAST_USED, callback, com.openmason.main.systems.io.BuiltinFileTypes.OMT);
    }

    /**
     * Like {@link #showOpenOMTDialog(OpenCallback)}, but starts in the open
     * project's root (where the .OMP lives) when a project is loaded. Used by
     * the SBO editor/export screens so texture replacements default to the
     * assets that belong to the object's project.
     */
    public void showOpenOMTInProjectDialog(OpenCallback callback) {
        showOpenDialog(StartIn.PROJECT, callback, com.openmason.main.systems.io.BuiltinFileTypes.OMT);
    }

    /**
     * Show open texture dialog supporting both .OMT and .PNG files.
     * Used for selecting textures for editable models.
     *
     * @param callback callback to receive selected file path
     */
    public void showOpenTextureDialog(OpenCallback callback) {
        showOpenDialog(StartIn.LAST_USED, callback, com.openmason.main.systems.io.BuiltinFileTypes.OMT, com.openmason.main.systems.io.BuiltinFileTypes.PNG);
    }

    /**
     * Show save OMT (Open Mason Texture) dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showSaveOMTDialog(SaveOMTCallback callback) {
        showSaveDialog(com.openmason.main.systems.io.BuiltinFileTypes.OMT, "texture.omt", null, callback::onSave);
    }

    /**
     * Callback interface for OMT save operations.
     */
    public interface SaveOMTCallback {
        void onSave(String filePath);
    }

    /**
     * Show open OMO (Open Mason Object) dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showOpenOMODialog(OpenCallback callback) {
        showOpenDialog(StartIn.LAST_USED, callback, com.openmason.main.systems.io.BuiltinFileTypes.OMO);
    }

    /**
     * Like {@link #showOpenOMODialog(OpenCallback)}, but starts in the open
     * project's root (where the .OMP lives) when a project is loaded. Used by
     * the SBE editor so model replacements default to the assets that belong to
     * the entity's project.
     */
    public void showOpenOMOInProjectDialog(OpenCallback callback) {
        showOpenDialog(StartIn.PROJECT, callback, com.openmason.main.systems.io.BuiltinFileTypes.OMO);
    }

    /**
     * Show save OMO (Open Mason Object) dialog using native file dialog.
     * Starts in the open project's folder (where the .OMP lives) when a
     * project is loaded, so models land next to their project by default.
     * @param callback callback to receive selected file path
     */
    public void showSaveOMODialog(SaveOMOCallback callback) {
        showSaveDialog(com.openmason.main.systems.io.BuiltinFileTypes.OMO, "model.omo", null, callback::onSave);
    }

    /** The open project's UI folder when it exists, else the project folder, else null (#293). */
    private String uiDirectoryOrNull() {
        String projectDir = projectDirectoryOrNull();
        if (projectDir == null) {
            return null;
        }
        java.nio.file.Path ui = java.nio.file.Path.of(projectDir).resolve(
                com.openmason.main.systems.project.ProjectLayout.UI_DIR);
        return java.nio.file.Files.isDirectory(ui) ? ui.toString() : projectDir;
    }

    /** Open a UI document: an .omui, or an .sbui export (opened as an editable copy). */
    public void showOpenUiDocumentDialog(OpenCallback callback) {
        showOpenDialog(StartIn.TYPE_FOLDER, callback, com.openmason.main.systems.io.BuiltinFileTypes.OMUI, com.openmason.main.systems.io.BuiltinFileTypes.SBUI);
    }

    /** Open an .sbui export (import into the project). */
    public void showOpenSBUIDialog(OpenCallback callback) {
        showOpenDialog(uiDirectoryOrNull(), callback, com.openmason.main.systems.io.BuiltinFileTypes.SBUI);
    }

    /** Save a UI document (.omui), defaulting into the project's UI folder. */
    public void showSaveOMUIDialog(String defaultName, OpenCallback callback) {
        showSaveDialog(com.openmason.main.systems.io.BuiltinFileTypes.OMUI, defaultName, null, callback);
    }

    /** Choose an .sbui export target. */
    public void showSaveSBUIDialog(String defaultName, OpenCallback callback) {
        showSaveDialog(com.openmason.main.systems.io.BuiltinFileTypes.SBUI, defaultName, projectDirectoryOrNull(), callback);
    }

    /** Open a .OMSC scene, starting in the project's Scenes folder. */
    public void showOpenOMSCDialog(OpenCallback callback) {
        showOpenDialog(StartIn.TYPE_FOLDER, callback, com.openmason.main.systems.io.BuiltinFileTypes.OMSC);
    }

    /** Save a .OMSC scene, defaulting into the project's Scenes folder. */
    public void showSaveOMSCDialog(SaveOMSCCallback callback) {
        showSaveDialog(com.openmason.main.systems.io.BuiltinFileTypes.OMSC, "scene.omsc", null, callback::onSave);
    }

    /** Callback interface for scene save operations. */
    public interface SaveOMSCCallback {
        void onSave(String filePath);
    }

    /**
     * Callback interface for OMO save operations.
     */
    public interface SaveOMOCallback {
        void onSave(String filePath);
    }

    /**
     * Show open OMP (Open Mason Project) dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showOpenOMPDialog(OpenCallback callback) {
        showOpenDialog(StartIn.LAST_USED, callback, com.openmason.main.systems.io.BuiltinFileTypes.OMP);
    }

    /**
     * Show save OMP (Open Mason Project) dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showSaveOMPDialog(SaveOMPCallback callback) {
        showSaveDialog(com.openmason.main.systems.io.BuiltinFileTypes.OMP, "project.omp", null, callback::onSave);
    }

    /**
     * Callback interface for OMP save operations.
     */
    public interface SaveOMPCallback {
        void onSave(String filePath);
    }

    /**
     * Show a native folder-picker dialog. The chosen absolute directory path is
     * delivered to the callback; cancellation invokes nothing.
     */
    public void showPickFolderDialog(OpenCallback callback) {
        if (!nfdInitialized) {
            statusService.updateStatus("Error: File dialog not initialized");
            return;
        }
        statusService.updateStatus("Choosing folder...");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer outPath = stack.mallocPointer(1);
            int result = NFD_PickFolder(outPath, (ByteBuffer) null);
            if (result == NFD_OKAY) {
                String selectedPath = outPath.getStringUTF8(0);
                NFD_FreePath(outPath.get(0));
                logger.info("Selected folder: {}", selectedPath);
                callback.onOpen(selectedPath);
                statusService.updateStatus("Folder: " + selectedPath);
            } else if (result == NFD_CANCEL) {
                statusService.updateStatus("Folder selection cancelled");
            } else {
                logger.error("NFD Error: {}", NFD_GetError());
                statusService.updateStatus("Error opening folder dialog");
            }
        } catch (Exception e) {
            logger.error("Error showing folder dialog", e);
            statusService.updateStatus("Error: " + e.getMessage());
        }
    }

    /**
     * Show open OMA (Open Mason Animation) dialog using native file dialog.
     * Starts in the open project's root when a project is loaded (matching
     * the OMO/OMT dialogs), so animation files default to project assets.
     * @param callback callback to receive selected file path
     */
    public void showOpenOMADialog(OpenCallback callback) {
        showOpenDialog(StartIn.PROJECT, callback, com.openmason.main.systems.io.BuiltinFileTypes.OMANIM);
    }

    /**
     * Alias of {@link #showOpenOMADialog(OpenCallback)}, kept for the SBE
     * editor call site from before the plain OMA dialog was project-rooted.
     */
    public void showOpenOMAInProjectDialog(OpenCallback callback) {
        showOpenOMADialog(callback);
    }

    /**
     * Show save OMA (Open Mason Animation) dialog using native file dialog.
     * Starts in the open project's root when a project is loaded.
     * @param callback callback to receive selected file path
     */
    public void showSaveOMADialog(SaveOMACallback callback) {
        showSaveDialog(com.openmason.main.systems.io.BuiltinFileTypes.OMANIM, "animation.omanim", null, callback::onSave);
    }

    /**
     * Callback interface for OMA save operations.
     */
    public interface SaveOMACallback {
        void onSave(String filePath);
    }

    /**
     * Show save SBO (Stonebreak Object) dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showSaveSBODialog(SaveSBOCallback callback) {
        showSaveSBODialog("object.sbo", targetFolder(BuiltinFileTypes.SBO, "block"), callback);
    }

    /**
     * Save-SBO dialog that opens directly in the folder the object belongs in
     * (e.g. {@code sbo/blocks} or {@code sbo/items}) with a suggested file
     * name, so an export needs no navigation. A null {@code preferredDirectory}
     * falls back to the last directory used for {@code .sbo} files.
     */
    public void showSaveSBODialog(String defaultFileName, String preferredDirectory, SaveSBOCallback callback) {
        showSaveDialog(com.openmason.main.systems.io.BuiltinFileTypes.SBO, defaultFileName, preferredDirectory, callback::onSave);
    }

    /**
     * Show open SBO (Stonebreak Object) dialog using native file dialog.
     * Starts in the last {@code .sbo} folder the user visited as long as it
     * lies inside the game target's resources, otherwise in the target's block folder.
     */
    public void showOpenSBODialog(OpenCallback callback) {
        showOpenDialog(repoDirectoryFor("sbo", targetFolder(BuiltinFileTypes.SBO, "block")), callback, BuiltinFileTypes.SBO);
    }

    /** The active game target's folder for {@code type} assets of manifest type {@code subtype}, or null. */
    private static String targetFolder(com.openmason.api.files.FileType type, String subtype) {
        return GameTargets.shared().folderPathFor(type.id(), subtype);
    }

    /**
     * Repo-routed starting directory for a file type: the last directory used
     * for that type if it is inside the game target's resource tree, else the given
     * canonical folder (may be null → NFD default).
     */
    private String repoDirectoryFor(String filterSpec, String canonicalDirectory) {
        String last = resolveDefaultDirectory(new String[]{filterSpec});
        if (last != null && GameTargets.shared().isInsideResources(last)) {
            return last;
        }
        return canonicalDirectory;
    }

    /**
     * Callback interface for SBO save operations.
     */
    public interface SaveSBOCallback {
        void onSave(String filePath);
    }

    /**
     * Show save SBE (Stonebreak Entity) dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showSaveSBEDialog(SaveSBECallback callback) {
        showSaveSBEDialog("entity.sbe", targetFolder(BuiltinFileTypes.SBE, null), callback);
    }

    /**
     * Save-SBE dialog that opens directly in the folder the entity belongs in
     * (e.g. {@code sbe/Mobs}) with a suggested file name. A null
     * {@code preferredDirectory} falls back to the last {@code .sbe} folder.
     */
    public void showSaveSBEDialog(String defaultFileName, String preferredDirectory, SaveSBECallback callback) {
        showSaveDialog(com.openmason.main.systems.io.BuiltinFileTypes.SBE, defaultFileName, preferredDirectory, callback::onSave);
    }

    /**
     * Show open SBE (Stonebreak Entity) dialog using native file dialog.
     * Starts in the last {@code .sbe} folder the user visited as long as it
     * lies inside the game target's resources, otherwise in the target's entity root.
     */
    public void showOpenSBEDialog(OpenCallback callback) {
        showOpenDialog(repoDirectoryFor("sbe", targetFolder(BuiltinFileTypes.SBE, null)), callback, BuiltinFileTypes.SBE);
    }

    /**
     * Show an open dialog for any attachable model asset — the formats the
     * game's socket system can mount ({@code .sbe}, {@code .sbo}, {@code .omo}).
     * Used by the rigging pane's "Test Model..." socket action.
     */
    public void showOpenAttachableModelDialog(OpenCallback callback) {
        showNFDOpenDialogMultiFilter("Opening model file...",
                new String[]{"Attachable Model", "Stonebreak Entity", "Stonebreak Object", "Open Mason Object"},
                new String[]{"sbe,sbo,omo", "sbe", "sbo", "omo"},
                "Selected attachable model", callback);
    }

    /**
     * Callback interface for SBE save operations.
     */
    public interface SaveSBECallback {
        void onSave(String filePath);
    }

    /**
     * Show save SBT (Stonebreak Texture) dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showSaveSBTDialog(SaveSBTCallback callback) {
        showSaveDialog(com.openmason.main.systems.io.BuiltinFileTypes.SBT, "texture.sbt", null, callback::onSave);
    }

    /**
     * Callback interface for SBT save operations.
     */
    public interface SaveSBTCallback {
        void onSave(String filePath);
    }
}
