package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.services.StatusService;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.nfd.NFDFilterItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.util.nfd.NativeFileDialog.*;

/**
 * File dialog service for open/save/export operations.
 * Uses LWJGL NFD for native file dialogs (PNG files) and Swing for legacy model dialogs.
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
     * Show export model dialog.
     */
    public void showExportDialog(ExportCallback callback) {
        statusService.updateStatus("Exporting model...");

        try {
            SwingUtilities.invokeLater(() -> {
                JFileChooser fileChooser = createFileChooser("Export Model");

                FileNameExtensionFilter jsonFilter = new FileNameExtensionFilter("JSON Model (*.json)", "json");
                fileChooser.addChoosableFileFilter(jsonFilter);
                fileChooser.setFileFilter(jsonFilter);

                int result = fileChooser.showSaveDialog(null);

                if (result == JFileChooser.APPROVE_OPTION) {
                    File selectedFile = fileChooser.getSelectedFile();
                    FileNameExtensionFilter selectedFilter = (FileNameExtensionFilter) fileChooser.getFileFilter();
                    String extension = selectedFilter.getExtensions()[0];

                    selectedFile = ensureExtension(selectedFile, extension);
                    callback.onExport(selectedFile, extension);
                } else {
                    statusService.updateStatus("Export cancelled");
                }
            });
        } catch (Exception e) {
            logger.error("Error showing export dialog", e);
            statusService.updateStatus("Error opening export dialog: " + e.getMessage());
        }
    }

    /**
     * Create configured file chooser.
     */
    private JFileChooser createFileChooser(String title) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle(title);
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
        return fileChooser;
    }

    /**
     * Ensure file has correct extension.
     */
    private File ensureExtension(File file, String extension) {
        String filePath = file.getAbsolutePath();
        if (!filePath.toLowerCase().endsWith("." + extension)) {
            filePath += "." + extension;
            file = new File(filePath);
        }
        return file;
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
        showNFDOpenDialogMultiFilter(statusMessage, new String[]{filterName}, new String[]{filterSpec}, logPrefix, callback);
    }

    /**
     * Generic open dialog with multiple filter support.
     */
    private void showNFDOpenDialogMultiFilter(String statusMessage, String[] filterNames, String[] filterSpecs,
                                              String logPrefix, OpenCallback callback) {
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

                // Show open dialog — seed the default path with the last directory
                // used for any of these filter types, so each format keeps its
                // own navigation context across sessions.
                String defaultPath = resolveDefaultDirectory(filterSpecs);
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

                // Show save dialog — seed default path with the last directory
                // used for this file type so each format has its own context.
                String defaultPath = resolveDefaultDirectory(new String[]{filterSpec});
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

    /**
     * Callback interface for export operations.
     */
    public interface ExportCallback {
        void onExport(File file, String format);
    }

    /**
     * Show open PNG dialog using native file dialog.
     */
    public void showOpenPNGDialog(OpenCallback callback) {
        showNFDOpenDialog("Opening PNG file...", "PNG Image", "png",
                "Selected PNG file", callback);
    }

    /**
     * Show save PNG dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showSavePNGDialog(SavePNGCallback callback) {
        showNFDSaveDialog("Saving PNG file...", "PNG Image", "png",
                "texture.png", "Save PNG to file", callback::onSave);
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
     * Show open OMT (Open Mason Texture) dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showOpenOMTDialog(OpenCallback callback) {
        showNFDOpenDialog("Opening OMT project...", "Open Mason Texture", "omt",
                "Selected OMT file", callback);
    }

    /**
     * Show open texture dialog supporting both .OMT and .PNG files.
     * Used for selecting textures for editable models.
     *
     * @param callback callback to receive selected file path
     */
    public void showOpenTextureDialog(OpenCallback callback) {
        showNFDOpenDialogMultiFilter("Opening texture file...",
                new String[]{"Open Mason Texture", "PNG Image"},
                new String[]{"omt", "png"},
                "Selected texture file", callback);
    }

    /**
     * Show save OMT (Open Mason Texture) dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showSaveOMTDialog(SaveOMTCallback callback) {
        showNFDSaveDialog("Saving OMT project...", "Open Mason Texture", "omt",
                "texture.omt", "Save OMT to file", callback::onSave);
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
        showNFDOpenDialog("Opening OMO model...", "Open Mason Object", "omo",
                "Selected OMO file", callback);
    }

    /**
     * Show save OMO (Open Mason Object) dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showSaveOMODialog(SaveOMOCallback callback) {
        showNFDSaveDialog("Saving OMO model...", "Open Mason Object", "omo",
                "model.omo", "Save OMO to file", callback::onSave);
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
        showNFDOpenDialog("Opening OMP project...", "Open Mason Project", "omp",
                "Selected OMP file", callback);
    }

    /**
     * Show save OMP (Open Mason Project) dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showSaveOMPDialog(SaveOMPCallback callback) {
        showNFDSaveDialog("Saving OMP project...", "Open Mason Project", "omp",
                "project.omp", "Save OMP to file", callback::onSave);
    }

    /**
     * Callback interface for OMP save operations.
     */
    public interface SaveOMPCallback {
        void onSave(String filePath);
    }

    /**
     * Show open OMA (Open Mason Animation) dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showOpenOMADialog(OpenCallback callback) {
        showNFDOpenDialog("Opening OMA animation...", "Open Mason Animation", "omanim",
                "Selected OMA file", callback);
    }

    /**
     * Show save OMA (Open Mason Animation) dialog using native file dialog.
     * @param callback callback to receive selected file path
     */
    public void showSaveOMADialog(SaveOMACallback callback) {
        showNFDSaveDialog("Saving OMA animation...", "Open Mason Animation", "omanim",
                "animation.omanim", "Save OMA to file", callback::onSave);
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
        showNFDSaveDialog("Exporting SBO file...", "Stonebreak Object", "sbo",
                "object.sbo", "Export SBO to file", callback::onSave);
    }

    /**
     * Show open SBO (Stonebreak Object) dialog using native file dialog.
     */
    public void showOpenSBODialog(OpenCallback callback) {
        showNFDOpenDialog("Opening SBO file...", "Stonebreak Object", "sbo",
                "Selected SBO file", callback);
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
        showNFDSaveDialog("Exporting SBE file...", "Stonebreak Entity", "sbe",
                "entity.sbe", "Export SBE to file", callback::onSave);
    }

    /**
     * Show open SBE (Stonebreak Entity) dialog using native file dialog.
     */
    public void showOpenSBEDialog(OpenCallback callback) {
        showNFDOpenDialog("Opening SBE file...", "Stonebreak Entity", "sbe",
                "Selected SBE file", callback);
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
        showNFDSaveDialog("Exporting SBT file...", "Stonebreak Texture", "sbt",
                "texture.sbt", "Export SBT to file", callback::onSave);
    }

    /**
     * Callback interface for SBT save operations.
     */
    public interface SaveSBTCallback {
        void onSave(String filePath);
    }
}
