package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.document.UiTree;
import com.openmason.main.systems.uiEditor.service.UiDocumentService;
import com.openmason.main.systems.uiEditor.service.UiProjectContext;
import com.stonebreak.ui.runtime.GameUiResources;
import io.github.humbleui.skija.Typeface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * What every UI editor panel shares: the document service, the active document's runtime and
 * view state, editor toggles (overlays, snapping) and cross-panel requests (hover, rename,
 * frame, jump to rule or line). Panels read from it and change the source only through
 * {@link UiEditorActions}.
 */
public final class UiEditorContext implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(UiEditorContext.class);

    public final UiDocumentService service;
    public final UiProjectContext project;
    public final UiEditorActions actions;
    private final Map<UiEditorDocument, DesignerRuntime> runtimes = new HashMap<>();
    private final Map<UiEditorDocument, DocumentViewState> views = new HashMap<>();
    private Typeface typeface;
    private boolean typefaceFailed;

    // ── overlays and snapping ──
    public boolean showBounds = true;
    public boolean showFlex = true;
    public boolean showSpacing = true;
    public boolean showRulers = true;
    public boolean snapEdges = true;
    public boolean snapGrid = false;
    public int gridStep = 8;
    public boolean gpuPath = true;

    // ── cross-panel requests (consumed by the panel that owns them) ──
    /** Element key under the pointer in the designer or hierarchy this frame. */
    public String hoverKey;
    private String nextHoverKey;
    /** Hierarchy should start renaming this key. */
    public String renameRequest;
    /** Designer should frame the selection. */
    public boolean frameSelectionRequest;
    /** Style panel should show this rule. */
    public String jumpSheet;
    public int jumpRule = -1;
    /** Script panel should reveal this line (1-based). */
    public int jumpLine = -1;
    /** Bring this window to the front next frame. */
    public String focusWindow;
    /** Save the active document at the end of this frame (a text editor swallowed Ctrl+S). */
    public boolean saveRequest;
    /** A UI editor panel (other than the designer) has keyboard focus this frame. */
    public boolean panelFocused;
    /** Sprites panel should open this sheet ({@code id}) next frame. */
    public String editSheetRequest;

    // ── textures and sprites (#294) ──
    /** Routes "Edit texture" to the Texture Editor (SBTs through their OMT source). */
    public final com.openmason.main.systems.uiEditor.service.TextureEditBridge textures =
        new com.openmason.main.systems.uiEditor.service.TextureEditBridge();
    private com.openmason.main.systems.uiEditor.service.TextureEditBridge.Opener textureEditor;
    private long assetEpoch;
    /** The inspector's texture/sprite field. */
    final ImagePicker images = new ImagePicker(this);

    /** Panels call this right after their {@code begin}: shortcuts dispatch while one is focused. */
    public void noteFocus() {
        if (imgui.ImGui.isWindowFocused(imgui.flag.ImGuiFocusedFlags.RootAndChildWindows)) {
            panelFocused = true;
        }
    }

    public UiEditorContext(UiDocumentService service) {
        this.service = service;
        this.project = service.project();
        this.actions = new UiEditorActions(this);
    }

    public UiEditorDocument doc() {
        return service.active();
    }

    /** The active document's runtime (created on demand), or null without a document or font. */
    public DesignerRuntime runtime() {
        UiEditorDocument d = doc();
        return d == null ? null : runtime(d);
    }

    public DesignerRuntime runtime(UiEditorDocument d) {
        Typeface tf = typeface();
        if (tf == null) {
            return null;
        }
        return runtimes.computeIfAbsent(d, k -> {
            DesignerRuntime r = new DesignerRuntime(k, project, tf);
            r.setOnFilesChanged(this::assetFilesChanged);
            return r;
        });
    }

    public DocumentViewState view() {
        UiEditorDocument d = doc();
        return d == null ? null : view(d);
    }

    public DocumentViewState view(UiEditorDocument d) {
        return views.computeIfAbsent(d, k -> WorkspaceStamp.restore(k));
    }

    /** The active runtime instance, or null. */
    public UiDocumentInstance instance() {
        DesignerRuntime r = runtime();
        return r == null ? null : r.instance();
    }

    public UiElement element(String key) {
        UiDocumentInstance ui = instance();
        return ui == null || key == null ? null : ui.find(key);
    }

    /** The document node of a document-level key, or null (internal keys, missing). */
    public UiNode node(String key) {
        UiEditorDocument d = doc();
        if (d == null || key == null || key.indexOf('/') >= 0) {
            return null;
        }
        return UiTree.find(d.archive().document().root(), key);
    }

    /** Hover reported this frame; becomes {@link #hoverKey} at the start of the next. */
    public void hover(String key) {
        nextHoverKey = key;
    }

    /** Called once per frame before panels draw. */
    public void beginFrame() {
        hoverKey = nextHoverKey;
        panelFocused = false;
        nextHoverKey = null;
        for (UiEditorDocument d : runtimes.keySet().toArray(UiEditorDocument[]::new)) {
            if (!service.documents().contains(d)) {
                runtimes.remove(d).close();
                views.remove(d);
            }
        }
    }

    /** Installed by the host application: opens an OMT in the Texture Editor. */
    public void setTextureEditor(com.openmason.main.systems.uiEditor.service.TextureEditBridge.Opener opener) {
        this.textureEditor = opener;
    }

    /**
     * Opens the texture behind an image reference (a texture id or {@code sheet#sprite}) in the
     * Texture Editor; a refusal (embedded snapshot, packaged asset, flat PNG) lands in the status line.
     */
    public void editTexture(String ref) {
        UiEditorDocument d = doc();
        if (d == null) {
            return;
        }
        String problem = textures.edit(com.openmason.main.systems.uiEditor.service.UiImageAssets.editableTexture(
            d.archive(), ref, project), textureEditor);
        d.setLastMessage(problem);
    }

    /**
     * Project files changed under the editor (a Texture Editor save, a sprite sheet apply): every
     * open document re-reads its resolved assets and repaints, so every shared reference updates.
     */
    public void assetFilesChanged(java.util.List<java.nio.file.Path> files) {
        assetEpoch++;
        java.util.Set<String> stale = new java.util.HashSet<>();
        for (DesignerRuntime r : runtimes.values()) {
            stale.addAll(r.assetsChanged());
        }
        // evict superseded texture revisions no open document still draws (never closed here:
        // an image still referenced somewhere lives until the GC collects it)
        com.openmason.engine.ui.runtime.paint.ResolvedUiAssets any = null;
        for (DesignerRuntime r : runtimes.values()) {
            stale.removeAll(r.textureKeysInUse());
            if (any == null) {
                any = r.resolvedAssets();
            }
        }
        if (any != null && !stale.isEmpty()) {
            any.forget(stale);
        }
        logger.debug("UI assets changed: {} (evicted {} texture revisions)", files, stale.size());
    }

    /** Bumps whenever project asset files change; panels key their resolution caches on it. */
    public long assetEpoch() {
        return assetEpoch;
    }

    public Typeface typeface() {
        if (typeface == null && !typefaceFailed) {
            try {
                typeface = GameUiResources.loadTypeface();
            } catch (Exception e) {
                typefaceFailed = true;
                logger.error("UI editor: cannot load the game font", e);
            }
        }
        return typeface;
    }

    @Override
    public void close() {
        runtimes.values().forEach(DesignerRuntime::close);
        runtimes.clear();
        if (typeface != null) {
            typeface.close();
            typeface = null;
        }
    }
}
