package com.openmason.main.systems.menus.panes.projectBrowser;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import com.openmason.main.systems.menus.panes.projectBrowser.events.ModelSelectedEvent;
import com.openmason.main.systems.menus.panes.projectBrowser.events.ProjectBrowserListener;
import com.openmason.main.systems.menus.panes.projectBrowser.sorting.SortBy;
import com.openmason.main.systems.menus.panes.projectBrowser.sorting.SortOrder;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.io.FileTypeService;
import com.openmason.main.systems.project.ProjectService;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.StatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Controller for the Project Browser.
 *
 * <p>The browser is rooted at the open project's folder — the directory the
 * current .omp file lives in — and lists the files of every registered file type with a
 * browse rule (#379). A click opens the file with the opener its plugin registered; .OMO models
 * load here (the browser also marks the project dirty). Call {@link #refreshRoot()} whenever the current project path
 * changes (open, save-as, new project) and {@link #refresh()} to rescan the
 * same folder for externally added files.
 */
public class ProjectBrowserController {

    private static final Logger logger = LoggerFactory.getLogger(ProjectBrowserController.class);

    private final ProjectBrowserState state = new ProjectBrowserState();
    private final List<ProjectBrowserListener> listeners = new ArrayList<>();
    private final ProjectAssetScanner scanner = new ProjectAssetScanner();
    private FileTypeService fileTypes = FileTypeService.builtin();

    private final ProjectService projectService;
    private final ModelOperationService modelOperationService;
    private final StatusService statusService;

    /** Invoked after a successful delete so the ImGui layer can free GL thumbnails. */
    private Consumer<AssetEntry> thumbnailInvalidator = entry -> { };

    /** Register the GL-side thumbnail invalidator (ImGui layer owns the GL cache). */
    public void setThumbnailInvalidator(Consumer<AssetEntry> invalidator) {
        this.thumbnailInvalidator = invalidator != null ? invalidator : entry -> { };
    }

    public ProjectBrowserController(ProjectService projectService,
                                    ModelOperationService modelOperationService,
                                    StatusService statusService) {
        this.projectService = projectService;
        this.modelOperationService = modelOperationService;
        this.statusService = statusService;
        refreshRoot();
    }

    /** The registered file types: what is listed, and what a click opens files with. */
    public void setFileTypes(FileTypeService fileTypes) {
        this.fileTypes = fileTypes != null ? fileTypes : FileTypeService.builtin();
        scanner.setTypes(this.fileTypes::browsable);
    }

    public ProjectBrowserState getState() {
        return state;
    }

    public void addListener(ProjectBrowserListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Re-derive the browser root from the current project and rescan.
     * Call after opening, creating, or saving-as a project.
     */
    public void refreshRoot() {
        Path root = null;
        if (projectService != null && projectService.hasCurrentProject()) {
            String ompPath = projectService.getCurrentProjectPath();
            if (ompPath != null && !ompPath.isBlank()) {
                root = Path.of(ompPath).getParent();
            }
        }
        scanner.setRoot(root);
        scanner.invalidate();
    }

    /** Rescan the current project folder (picks up externally added files). */
    public void refresh() {
        scanner.invalidate();
    }

    /** True when a project is open and the browser has a folder to show. */
    public boolean hasProjectOpen() {
        return scanner.getRoot() != null;
    }

    /** The project folder being browsed, or {@code null} when no project is open. */
    public Path getRootPath() {
        return scanner.getRoot();
    }

    /** The open project's display name, or an empty string when none is open. */
    public String getProjectName() {
        if (projectService != null && projectService.hasCurrentProject()) {
            String name = projectService.getCurrentProjectName();
            return name != null ? name : "";
        }
        return "";
    }

    /**
     * The assets to display this frame: the folder scan filtered by the active
     * search and ordered by the state's sort field/direction.
     */
    public List<AssetEntry> getVisibleAssets() {
        List<AssetEntry> visible = new ArrayList<>();
        for (AssetEntry entry : scanner.scan()) {
            if (state.matchesSearch(entry.name())) {
                visible.add(entry);
            }
        }
        Comparator<AssetEntry> comparator = state.getSortBy() == SortBy.TYPE
                ? Comparator.comparingInt((AssetEntry e) -> e.type().browse().sortOrder())
                        .thenComparing(e -> e.name().toLowerCase())
                : Comparator.comparing(e -> e.name().toLowerCase());
        if (state.getSortOrder() == SortOrder.DESCENDING) {
            comparator = comparator.reversed();
        }
        visible.sort(comparator);
        return visible;
    }

    /** Show the delete confirmation for an asset (the shell owns the dialog). */
    public void requestDelete(AssetEntry entry) {
        listeners.forEach(l -> l.onAssetDeleteRequested(entry));
    }

    /**
     * Delete an asset file from disk, report status, invalidate its thumbnails,
     * and rescan. The caller is responsible for detaching the editor from a
     * deleted backing file.
     *
     * @return true when the file was deleted
     */
    public boolean deleteAsset(AssetEntry entry) {
        try {
            Files.delete(entry.path());
            logger.info("Deleted asset: {}", entry.pathString());
            statusService.updateStatus("Deleted " + entry.name() + " (" + label(entry) + ")");
            thumbnailInvalidator.accept(entry);
            refresh();                        // scanner.invalidate(); browser rescans on next frame
            // Selection is only the status-line string; clear it when THIS entry was selected.
            String selected = "Selected: " + entry.name() + " (" + label(entry) + ")";
            if (selected.equals(state.getSelectedAssetInfo())) {
                state.setSelectedAssetInfo(null);
            }
            return true;
        } catch (NoSuchFileException e) {
            logger.warn("Failed to delete asset (file not found): {}", entry.pathString());
            statusService.updateStatus("Error deleting " + entry.name() + ": file not found");
            return false;
        } catch (AccessDeniedException e) {
            logger.warn("Failed to delete asset (access denied): {}", entry.pathString());
            statusService.updateStatus("Error deleting " + entry.name() + ": access denied");
            return false;
        } catch (IOException e) {
            logger.warn("Failed to delete asset: {}", entry.pathString(), e);
            statusService.updateStatus("Error deleting " + entry.name() + ": " + e.getMessage());
            return false;
        }
    }

    /** The browser's type label of an entry (".OMO Model"). */
    private static String label(AssetEntry entry) {
        return entry.type().browse() != null ? entry.type().browse().label() : entry.type().displayName();
    }

    /** A click: .OMO models load into the model editor; anything else opens with its type's opener. */
    public void selectAsset(AssetEntry entry) {
        if (entry == null) {
            return;
        }
        if (BuiltinFileTypes.OMO.id().equals(entry.type().id())) {
            selectModel(entry);
            return;
        }
        try {
            state.setSelectedAssetInfo("Selected: " + entry.name() + " (" + label(entry) + ")");
            if (fileTypes.canOpen(entry.type())) {
                statusService.updateStatus("Opening " + entry.name() + " (" + label(entry) + ")");
                fileTypes.open(entry.path());
            } else {
                statusService.updateStatus("Nothing opens " + entry.type().extension() + " files");
            }
        } catch (Exception e) {
            logger.error("Failed to open {}", entry.name(), e);
            statusService.updateStatus("Error opening " + entry.name() + ": " + e.getMessage());
        }
    }

    private void selectModel(AssetEntry entry) {
        try {
            state.setSelectedAssetInfo("Selected: " + entry.name() + " (" + label(entry) + ")");
            statusService.updateStatus("Loading .OMO model: " + entry.name());
            modelOperationService.loadOMOModel(entry.pathString());
            if (projectService != null && projectService.hasCurrentProject()) {
                projectService.markDirty();
            }

            ModelSelectedEvent event = new ModelSelectedEvent(entry);
            for (ProjectBrowserListener l : listeners) {
                l.onModelSelected(event);
            }
        } catch (Exception e) {
            logger.error("Failed to load .OMO file: {}", entry.name(), e);
            statusService.updateStatus("Error loading .OMO model: " + e.getMessage());
        }
    }

    public void reset() {
        state.reset();
    }
}
