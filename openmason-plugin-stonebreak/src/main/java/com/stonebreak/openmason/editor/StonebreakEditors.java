package com.stonebreak.openmason.editor;

import com.openmason.api.PluginContext;
import com.openmason.api.ServiceRegistry;
import com.openmason.api.asset.AssetFormatContribution;
import com.openmason.api.asset.AssetWindow;
import com.openmason.api.asset.TextureAction;
import com.openmason.api.asset.TextureSource;
import com.openmason.api.lifecycle.LifecycleListener;
import com.openmason.api.menu.MenuItem;
import com.openmason.api.menu.TopMenu;
import com.openmason.main.systems.menus.dialogs.AnimationClipBridge;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.ProjectDirectory;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.themes.core.ThemeManager;
import com.stonebreak.format.sbe.SBECodec;
import com.stonebreak.format.sbo.SBOCodec;
import com.stonebreak.format.sbt.SBTCodec;
import imgui.type.ImBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Stonebreak's asset kinds in Open Mason (OpenMason#88; core until then, #431): SBO, SBE and SBT, each
 * registered as one {@link AssetFormatContribution} — file type + opener, codec, section surfaces, the
 * editor/export windows, Tools menu entries and the Texture Editor's export entries. Without this plugin
 * the editor does not know these files.
 *
 * <p>The windows are built on the UI thread the first time anything needs them (a render, a menu
 * click, an opener) or when the editor is ready, from the services the editor publishes; until then
 * nothing renders. Once built they are published as services ({@code SBOEditorWindow.class}...), where
 * the plugin's MCP asset tools find them.
 */
public final class StonebreakEditors {

    private static final Logger logger = LoggerFactory.getLogger(StonebreakEditors.class);

    private final ServiceRegistry services;
    private final AssetExportMenuActions actions = new AssetExportMenuActions();
    private SBOExportWindow sboExport;
    private SBEExportWindow sbeExport;
    private SBTExportWindow sbtExport;
    private SBOEditorWindow sboEditor;
    private SBEEditorWindow sbeEditor;
    private boolean built;
    private boolean bridged;

    public StonebreakEditors(ServiceRegistry services) {
        this.services = services;
    }

    /** Registers SBO, SBE and SBT with {@code context}. */
    public void register(PluginContext context) {
        context.assetFormats().register(AssetFormatContribution.builder(StonebreakFileTypes.SBO)
                .opener(path -> with(e -> e.sboEditor.openFile(path.toString())))
                .codec(new SBOCodec())
                .surface(StonebreakSurfaces.SBO_EDITOR)
                .surface(StonebreakSurfaces.SBO_EXPORT)
                .window(window("sbo.export", "SBO Export Window", e -> e.sboExport.render(), e -> e.sboExport.close()))
                .window(window("sbo.editor", "SBO Editor Window", e -> e.sboEditor.render(), e -> e.sboEditor.close()))
                .menuEntry(MenuItem.of("stonebreak.tools.export-sbo", TopMenu.TOOLS, 200, "Export SBO...",
                        () -> with(e -> actions.exportSBO())).withEnabled(actions::canExport).withSeparatorBefore())
                .menuEntry(MenuItem.of("stonebreak.tools.sbo-editor", TopMenu.TOOLS, 300, "SBO Editor...",
                        () -> with(e -> actions.openSboEditor())).withSeparatorBefore())
                .textureAction(new TextureAction("stonebreak.texture.export-sbo", "Export SBO...", 110, true, false,
                        () -> with(e -> e.sboExport.showForTexture())))
                .textureAction(new TextureAction("stonebreak.texture.sbo-editor", "SBO Editor...", 200, false, true,
                        () -> with(e -> e.sboEditor.openWithDialog())))
                .build());

        context.assetFormats().register(AssetFormatContribution.builder(StonebreakFileTypes.SBE)
                .opener(path -> with(e -> e.sbeEditor.openFile(path.toString())))
                .codec(new SBECodec())
                .surface(StonebreakSurfaces.SBE_EDITOR)
                .surface(StonebreakSurfaces.SBE_EXPORT)
                .window(window("sbe.export", "SBE Export Window", e -> e.sbeExport.render(), e -> e.sbeExport.close()))
                .window(window("sbe.editor", "SBE Editor Window", e -> e.sbeEditor.render(), e -> e.sbeEditor.close()))
                .menuEntry(MenuItem.of("stonebreak.tools.export-sbe", TopMenu.TOOLS, 210, "Export SBE...",
                        () -> with(e -> actions.exportSBE())).withEnabled(actions::canExport))
                .menuEntry(MenuItem.of("stonebreak.tools.sbe-editor", TopMenu.TOOLS, 310, "SBE Editor...",
                        () -> with(e -> actions.openSbeEditor())))
                .build());

        context.assetFormats().register(AssetFormatContribution.builder(StonebreakFileTypes.SBT)
                .codec(new SBTCodec())
                .window(window("sbt.export", "SBT Export Window", e -> e.sbtExport.render(), e -> e.sbtExport.close()))
                .textureAction(new TextureAction("stonebreak.texture.export-sbt", "Export SBT...", 100, true, false,
                        () -> with(e -> e.sbtExport.show())))
                .build());

        context.lifecycle().register(new LifecycleListener() {
            @Override
            public void ready() {
                ensure();
            }
        });
    }

    /** Runs {@code action} with the windows built; a no-op (logged) when the editor UI is not up. */
    private void with(Consumer<StonebreakEditors> action) {
        if (ensure()) {
            action.accept(this);
        } else {
            logger.warn("Stonebreak asset editors are unavailable: the editor UI services are missing");
        }
    }

    private AssetWindow window(String id, String title, Consumer<StonebreakEditors> render,
                               Consumer<StonebreakEditors> close) {
        return AssetWindow.of(id, title,
                () -> {
                    if (ensure()) {
                        render.accept(this);
                    }
                },
                () -> {
                    if (built) {
                        close.accept(this);
                    }
                });
    }

    /** Builds the windows once the editor's services exist; true when they are built. */
    synchronized boolean ensure() {
        if (!built) {
            ThemeManager theme = find(ThemeManager.class);
            ModelState modelState = find(ModelState.class);
            StatusService status = find(StatusService.class);
            FileDialogService dialogs = find(FileDialogService.class);
            if (theme == null || modelState == null || status == null || dialogs == null) {
                return false;
            }
            ModelOperationService modelOperations = find(ModelOperationService.class);
            ProjectDirectory projectDirectory = services.find(ProjectDirectory.class).orElse(() -> null);
            java.util.function.Supplier<String> omtPath =
                    () -> services.find(TextureSource.class).map(TextureSource::currentOmtPath).orElse(null);

            sboExport = new SBOExportWindow(new ImBoolean(false), theme, modelState, status, dialogs);
            sbeExport = new SBEExportWindow(new ImBoolean(false), theme, modelState, status, dialogs);
            sbtExport = new SBTExportWindow(new ImBoolean(false), theme, status, dialogs);
            sboExport.setOMTPathSupplier(omtPath);
            sbtExport.setOMTPathSupplier(omtPath);

            sboEditor = new SBOEditorWindow(dialogs, status);
            sboEditor.setProjectDirectorySupplier(projectDirectory);
            sbeEditor = new SBEEditorWindow(dialogs, status);
            sbeEditor.setProjectDirectorySupplier(projectDirectory);
            if (modelOperations != null) {
                sboEditor.setOpenModelHandler((omo, name) -> modelOperations.openExtractedAssetModel(omo, name, name));
                sbeEditor.setOpenModelHandler((omo, name) -> modelOperations.openExtractedAssetModel(omo, name, name));
            }
            // Export windows act as "start screens": a successful export opens the new file in its editor.
            sboExport.setOnExported(sboEditor::openFile);
            sbeExport.setOnExported(sbeEditor::openFile);

            actions.setSBOExportWindow(sboExport);
            actions.setSBEExportWindow(sbeExport);
            actions.setSBOEditorWindow(sboEditor);
            actions.setSBEEditorWindow(sbeEditor);
            actions.setModelState(modelState);
            actions.setStatusService(status);
            actions.setModelOperations(modelOperations);

            services.publish(SBOExportWindow.class, sboExport);
            services.publish(SBEExportWindow.class, sbeExport);
            services.publish(SBTExportWindow.class, sbtExport);
            services.publish(SBOEditorWindow.class, sboEditor);
            services.publish(SBEEditorWindow.class, sbeEditor);
            built = true;
            logger.debug("Stonebreak SBO/SBE/SBT editor and export windows built");
        }
        if (!bridged) {
            // The Animation Editor's clip bridge (state clips), published by the editor once it exists.
            AnimationClipBridge bridge = find(AnimationClipBridge.class);
            if (bridge != null) {
                sboEditor.setAnimationBridge(bridge);
                sbeEditor.setAnimationBridge(bridge);
                bridged = true;
            }
        }
        return true;
    }

    private <T> T find(Class<T> type) {
        return services.find(type).orElse(null);
    }

    /** For tests: the action wiring, as menus see it. */
    AssetExportMenuActions actions() {
        return actions;
    }
}
