package com.openmason.main.systems.menus.panes.projectBrowser;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import com.openmason.main.systems.menus.panes.projectBrowser.events.ModelSelectedEvent;
import com.openmason.main.systems.menus.panes.projectBrowser.events.ProjectBrowserListener;
import com.openmason.main.systems.menus.panes.projectBrowser.events.TextureSelectedEvent;
import com.openmason.main.systems.menus.panes.projectBrowser.sorting.SortBy;
import com.openmason.main.systems.menus.panes.projectBrowser.sorting.SortOrder;
import com.openmason.main.systems.project.ProjectService;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.StatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Controller for the Project Browser.
 *
 * <p>The browser is rooted at the open project's folder — the directory the
 * current .omp file lives in — and lists the .OMO models and .OMT textures
 * found there. Call {@link #refreshRoot()} whenever the current project path
 * changes (open, save-as, new project) and {@link #refresh()} to rescan the
 * same folder for externally added files.
 */
public class ProjectBrowserController {

    private static final Logger logger = LoggerFactory.getLogger(ProjectBrowserController.class);

    private final ProjectBrowserState state = new ProjectBrowserState();
    private final List<ProjectBrowserListener> listeners = new ArrayList<>();
    private final ProjectAssetScanner scanner = new ProjectAssetScanner();

    private final ProjectService projectService;
    private final ModelOperationService modelOperationService;
    private final StatusService statusService;

    public ProjectBrowserController(ProjectService projectService,
                                    ModelOperationService modelOperationService,
                                    StatusService statusService) {
        this.projectService = projectService;
        this.modelOperationService = modelOperationService;
        this.statusService = statusService;
        refreshRoot();
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
                ? Comparator.comparing((AssetEntry e) -> e.type().ordinal())
                        .thenComparing(e -> e.name().toLowerCase())
                : Comparator.comparing(e -> e.name().toLowerCase());
        if (state.getSortOrder() == SortOrder.DESCENDING) {
            comparator = comparator.reversed();
        }
        visible.sort(comparator);
        return visible;
    }

    /** Route a click to the model or texture selection flow by asset type. */
    public void selectAsset(AssetEntry entry) {
        if (entry == null) {
            return;
        }
        switch (entry.type()) {
            case OMO -> selectModel(entry);
            case OMT -> selectTexture(entry);
        }
    }

    private void selectModel(AssetEntry entry) {
        try {
            state.setSelectedAssetInfo("Selected: " + entry.name() + " (" + entry.type().label() + ")");
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

    private void selectTexture(AssetEntry entry) {
        try {
            state.setSelectedAssetInfo("Selected: " + entry.name() + " (" + entry.type().label() + ")");
            statusService.updateStatus("Opening .OMT in texture editor: " + entry.name());

            TextureSelectedEvent event = new TextureSelectedEvent(entry);
            for (ProjectBrowserListener l : listeners) {
                l.onTextureSelected(event);
            }
        } catch (Exception e) {
            logger.error("Failed to handle .OMT file: {}", entry.name(), e);
            statusService.updateStatus("Error handling .OMT texture: " + e.getMessage());
        }
    }

    public void reset() {
        state.reset();
    }
}
