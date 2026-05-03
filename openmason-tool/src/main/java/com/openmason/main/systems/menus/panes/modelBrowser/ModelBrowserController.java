package com.openmason.main.systems.menus.panes.modelBrowser;

import com.openmason.engine.format.omo.OMOFileManager;
import com.openmason.engine.format.sbt.SBTFileManager;
import com.openmason.main.omConfig;
import com.openmason.main.systems.menus.panes.modelBrowser.events.ModelBrowserListener;
import com.openmason.main.systems.menus.panes.modelBrowser.events.OMOSelectedEvent;
import com.openmason.main.systems.menus.panes.modelBrowser.events.SBTSelectedEvent;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.StatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Controller for the Model Browser component.
 *
 * <p>The browser exclusively works with file-backed assets discovered on disk:
 * .OMO models and .SBT textures. Folder paths are sourced from {@link omConfig}
 * and can be retargeted at runtime via {@link #refreshAssetFolders()} after the
 * user updates them in Preferences.
 */
public class ModelBrowserController {

    private static final Logger logger = LoggerFactory.getLogger(ModelBrowserController.class);

    private final ModelBrowserState state;
    private final List<ModelBrowserListener> listeners;
    private final ModelOperationService modelOperationService;
    private final StatusService statusService;
    private final OMOFileManager omoFileManager;
    private final SBTFileManager sbtFileManager;

    public ModelBrowserController(ModelOperationService modelOperationService, StatusService statusService) {
        this.state = new ModelBrowserState();
        this.listeners = new ArrayList<>();
        this.modelOperationService = modelOperationService;
        this.statusService = statusService;

        omConfig config = new omConfig();
        this.omoFileManager = new OMOFileManager(config.getOMOFolder());
        this.sbtFileManager = new SBTFileManager(config.getSBTFolder());
    }

    public ModelBrowserState getState() {
        return state;
    }

    public void addListener(ModelBrowserListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Re-reads the OMO/SBT folder paths from {@link omConfig} and forces a
     * rescan on the next call to {@link #getOMOFiles()} / {@link #getSBTFiles()}.
     * Call this after the user changes folders in Preferences.
     */
    public void refreshAssetFolders() {
        omConfig config = new omConfig();
        omoFileManager.setSearchDirectory(config.getOMOFolder());
        sbtFileManager.setSearchDirectory(config.getSBTFolder());
    }

    /** Returns the currently configured OMO folder. */
    public Path getOMOFolder() {
        return omoFileManager.getSearchDirectory();
    }

    /** Returns the currently configured SBT folder. */
    public Path getSBTFolder() {
        return sbtFileManager.getSearchDirectory();
    }

    public List<OMOFileManager.OMOFileEntry> getOMOFiles() {
        return omoFileManager.scanForOMOFiles();
    }

    public List<SBTFileManager.SBTFileEntry> getSBTFiles() {
        return sbtFileManager.scanForSBTFiles();
    }

    /** Forces both folder scans to repeat on next access. */
    public void invalidateScanCaches() {
        omoFileManager.invalidateCache();
        sbtFileManager.invalidateCache();
    }

    public void selectOMOFile(OMOFileManager.OMOFileEntry entry) {
        if (entry == null) {
            return;
        }
        try {
            state.setSelectedModelInfo("Selected: " + entry.name() + " (.OMO Model)");
            state.addRecentFile(entry.name());

            statusService.updateStatus("Loading .OMO model: " + entry.name());
            modelOperationService.loadOMOModel(entry.getFilePathString());

            OMOSelectedEvent event = new OMOSelectedEvent(entry);
            for (ModelBrowserListener l : listeners) {
                l.onOMOSelected(event);
            }
        } catch (Exception e) {
            logger.error("Failed to load .OMO file: {}", entry.name(), e);
            statusService.updateStatus("Error loading .OMO model: " + e.getMessage());
        }
    }

    public void selectSBTFile(SBTFileManager.SBTFileEntry entry) {
        if (entry == null) {
            return;
        }
        try {
            state.setSelectedModelInfo("Selected: " + entry.name() + " (.SBT Texture)");
            state.addRecentFile(entry.name());

            statusService.updateStatus("Selected .SBT texture: " + entry.name());

            SBTSelectedEvent event = new SBTSelectedEvent(entry);
            for (ModelBrowserListener l : listeners) {
                l.onSBTSelected(event);
            }
        } catch (Exception e) {
            logger.error("Failed to handle .SBT file: {}", entry.name(), e);
            statusService.updateStatus("Error handling .SBT texture: " + e.getMessage());
        }
    }

    public void reset() {
        state.reset();
    }
}
