package com.openmason.main.systems.services;

import com.openmason.api.ServiceRegistry;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.mcp.approval.ApprovalGate;
import com.openmason.main.systems.mcp.approval.SaveSheetGate;
import com.openmason.main.systems.menus.animationEditor.AnimationEditorImGui;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.menus.dialogs.SBEEditorWindow;
import com.openmason.main.systems.menus.dialogs.SBOEditorWindow;
import com.openmason.main.systems.menus.panes.propertyPane.PropertyPanelImGui;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.scene.SceneViewerImGuiInterface;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.uiEditor.view.UiEditorWorkspace;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Typed, null-tolerant reads of the editor services published in a {@link ServiceRegistry}: what
 * the MCP services, the write sandbox and the scripting window need, without reaching through the
 * editor shell object (#379). Every getter returns null when that service is not published (the
 * editor is not running, as in the headless tool-surface tests, or it is not wired yet), and is
 * read at call time, so late-wired services are seen.
 */
public final class EditorServices {

    private final ServiceRegistry services;

    public EditorServices(ServiceRegistry services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    /** No editor: every getter returns null. */
    public static EditorServices empty() {
        return new EditorServices(new ServiceRegistry());
    }

    /** The registry behind these reads. */
    public ServiceRegistry registry() {
        return services;
    }

    private <T> T get(Class<T> type) {
        return services.find(type).orElse(null);
    }

    public ViewportController getViewport3D() {
        return get(ViewportController.class);
    }

    public ModelOperationService getModelOperations() {
        return get(ModelOperationService.class);
    }

    public ModelState getModelState() {
        return get(ModelState.class);
    }

    public FileDialogService getFileDialogService() {
        return get(FileDialogService.class);
    }

    public TextureCreatorImGui getTextureCreator() {
        return get(TextureCreatorImGui.class);
    }

    public TextureEditorPresenter getTextureEditorPresenter() {
        return get(TextureEditorPresenter.class);
    }

    public PropertyPanelImGui getPropertyPanel() {
        return get(PropertyPanelImGui.class);
    }

    public AnimationEditorImGui getAnimationEditor() {
        return get(AnimationEditorImGui.class);
    }

    public SceneViewerImGuiInterface getSceneViewer() {
        return get(SceneViewerImGuiInterface.class);
    }

    public UiEditorWorkspace getUiEditor() {
        return get(UiEditorWorkspace.class);
    }

    public ScriptingPresenter getScriptingPresenter() {
        return get(ScriptingPresenter.class);
    }

    public ApprovalGate getApprovalGate() {
        return get(ApprovalGate.class);
    }

    public SaveSheetGate getSaveSheetGate() {
        return get(SaveSheetGate.class);
    }

    public SBOEditorWindow getSBOEditorWindow() {
        return get(SBOEditorWindow.class);
    }

    public SBEEditorWindow getSBEEditorWindow() {
        return get(SBEEditorWindow.class);
    }

    /** The open project's root folder supplier; never null (supplies null without a project). */
    public Supplier<String> getProjectDirectorySupplier() {
        ProjectDirectory dir = get(ProjectDirectory.class);
        return dir != null ? dir : () -> null;
    }
}
