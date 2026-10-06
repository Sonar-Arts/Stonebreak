package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiSpriteSheet;
import com.openmason.engine.format.omui.UiSpriteSheet.Fill;
import com.openmason.engine.format.omui.UiSpriteSheet.Frame;
import com.openmason.engine.format.omui.UiSpriteSheet.Sampling;
import com.openmason.engine.format.omui.UiSpriteSheet.ScaleMode;
import com.openmason.engine.format.omui.UiSpriteSheet.Skin;
import com.openmason.engine.format.omui.UiSpriteSheet.Slice;
import com.openmason.engine.format.omui.UiSpriteSheet.Sprite;
import com.openmason.engine.format.omui.UiSpriteSheets;
import com.openmason.engine.format.omui.io.SpriteSheetCodec;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.AssetRow;
import com.openmason.engine.ui.assets.ResolvedAsset;
import com.openmason.engine.ui.assets.SpriteBinding;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.SpriteFrames;
import com.openmason.engine.ui.runtime.paint.SpriteSlices;
import com.openmason.main.systems.uiEditor.command.UiCommandException;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.SpriteSheetDraft;
import com.openmason.main.systems.uiEditor.service.UiImageAssets;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiButtonFlags;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Sprite-sheet authoring (#294): named regions, nine-slice insets, pivots, logical sizes, fill
 * and sampling, tint/opacity, animation frames and per-state skins over a texture the Texture
 * Editor owns. Only metadata is edited here; the texture opens in the Texture Editor.
 *
 * <p>Canvas: drag on empty texels to draw a region, drag a region to move it, its edges to resize
 * it and the dashed slice guides to set insets; wheel zooms, middle or right drag pans. Edits
 * collect in a {@link SpriteSheetDraft} (local undo) and Apply writes the sheet as one document
 * step, which repaints every document that shares it.
 */
final class SpritesPanel implements AutoCloseable {

    static final String TITLE = "Sprites###uiSprites";

    private static final int REGION = argb(0xC8, 0x6A, 0xB4, 0xFF);
    private static final int SELECTED = argb(0xFF, 0xFF, 0xC8, 0x32);
    private static final int SLICE = argb(0xFF, 0xFF, 0x50, 0xC8);
    private static final int FRAME = argb(0xA0, 0x90, 0xFF, 0x90);
    private static final int INVALID = argb(0xFF, 0xFF, 0x40, 0x40);
    private static final int GRID = argb(0x30, 0xFF, 0xFF, 0xFF);
    private static final int CHECKER_A = argb(0xFF, 0x3A, 0x3A, 0x3A);
    private static final int CHECKER_B = argb(0xFF, 0x30, 0x30, 0x30);
    private static final String[] SAMPLING = {"(element)", "nearest", "linear"};
    private static final String[] SCALE = {"(auto)", "stretch", "nine-slice", "tile", "integer"};

    private final UiEditorContext ctx;
    private final SheetTexture texture = new SheetTexture();
    private MTexture decoded;
    private String sheetId;
    private SpriteSheetDraft draft;
    private String loadError;
    private Object loadedKey;
    private int texW;
    private int texH;
    private String textureId;

    // view
    private float zoom = -1;
    private boolean autoFit = true;
    private float fitW;
    private float fitH;
    private float panX;
    private float panY;
    // drag
    private enum Drag { NONE, CREATE, MOVE, RESIZE, SLICE, PAN }
    private Drag drag = Drag.NONE;
    private int edges; // 1 left, 2 top, 4 right, 8 bottom
    private int startTx;
    private int startTy;
    private Sprite startSprite;
    private final ImString rename = new ImString(64);
    private String renaming;
    private boolean renameFocus;
    private int focusFrames;
    private String pendingSelect;
    private UiEditorDocument boundDoc;
    private final java.util.Map<UiEditorDocument, Session> sessions = new java.util.HashMap<>();
    private float previewW = 64;
    private float previewH = 40;
    private float previewScale = 2;
    private boolean playFrames = true;

    SpritesPanel(UiEditorContext ctx) {
        this.ctx = ctx;
    }

    void render() {
        if (ctx.editSheetRequest != null) {
            focusFrames = 3; // a docked tab behind another renders only once in front; a fresh dock layout
        }                    // built in the same frame would otherwise re-select its first tab
        if (focusFrames > 0) {
            focusFrames--;
            ImGui.setNextWindowFocus();
        }
        if (!ImGui.begin(TITLE)) {
            ImGui.end();
            return;
        }
        ctx.noteFocus();
        UiEditorDocument doc = ctx.doc();
        bind(doc);
        if (doc == null) {
            EditorWidgets.emptyState("No document", "Open a UI document to edit its sprite sheets.");
            ImGui.end();
            return;
        }
        if (ctx.editSheetRequest != null) {
            open(ctx.editSheetRequest);
            ctx.editSheetRequest = null;
        }
        header(doc);
        if (sheetId != null) {
            load(doc);
        }
        if (draft == null) {
            EditorWidgets.emptyState(loadError != null ? "Cannot edit this sheet" : "No sprite sheet",
                loadError != null ? loadError : "Pick a sheet above, or create one for a texture of this document.");
            ImGui.end();
            return;
        }
        if (pendingSelect != null) {
            draft.select(pendingSelect);
            pendingSelect = null;
        }
        findings();
        float avail = ImGui.getContentRegionAvailX();
        float listW = 150;
        float propsW = Math.min(300, Math.max(220, avail * 0.32f));
        float h = ImGui.getContentRegionAvailY();
        ImGui.beginChild("##spriteList", listW, h, true);
        list();
        ImGui.endChild();
        ImGui.sameLine();
        ImGui.beginChild("##spriteCanvas", Math.max(80, avail - listW - propsW - 16), h, true,
            imgui.flag.ImGuiWindowFlags.NoScrollbar | imgui.flag.ImGuiWindowFlags.NoScrollWithMouse);
        canvas();
        ImGui.endChild();
        ImGui.sameLine();
        ImGui.beginChild("##spriteProps", 0, h, true);
        properties();
        ImGui.endChild();
        if (drag == Drag.NONE && !ImGui.isAnyItemActive()) {
            draft.endInteraction();
        }
        ImGui.end();
    }

    /** What the panel was doing for one document: its sheet and unapplied draft survive switching documents. */
    private record Session(String sheetId, SpriteSheetDraft draft) {
    }

    /** Parks the previous document's sheet and draft, and resumes the active one's. */
    private void bind(UiEditorDocument doc) {
        if (doc == boundDoc) {
            return;
        }
        if (boundDoc != null && sheetId != null) {
            sessions.put(boundDoc, new Session(sheetId, draft));
        }
        sessions.keySet().removeIf(d -> !ctx.service.documents().contains(d));
        Session next = doc == null ? null : sessions.remove(doc);
        boundDoc = doc;
        sheetId = next == null ? null : next.sheetId();
        draft = next == null ? null : next.draft();
        loadedKey = null;
        loadError = null;
        zoom = -1;
        autoFit = true;
    }

    /** Opens {@code id} (a sprites row of the active document), or {@code sheet#name} selecting a sprite or skin. */
    void open(String id) {
        com.openmason.engine.format.omui.UiSpriteRef ref = com.openmason.engine.format.omui.UiSpriteRef.parse(id);
        if (ref != null) {
            id = ref.sheet();
        }
        if (draft != null && draft.dirty() && !Objects.equals(id, sheetId)) {
            ctx.doc().setLastMessage("Sprite sheet " + sheetId + " has unapplied edits; Apply or Revert them before"
                + " opening " + id);
            return;
        }
        if (ref != null) {
            pendingSelect = ref.name();
        }
        if (!Objects.equals(id, sheetId)) {
            sheetId = id;
            draft = null;
            loadedKey = null;
            zoom = -1;
            autoFit = true;
        }
    }

    // ── header ──────────────────────────────────────────────────────────────

    private void header(UiEditorDocument doc) {
        List<String> sheets = new ArrayList<>();
        for (UiDependency d : doc.archive().dependencies().entries()) {
            if (d.kind() == UiDependency.Kind.SPRITES) {
                sheets.add(d.id());
            }
        }
        if (sheetId != null && !sheets.contains(sheetId)) {
            if (draft != null && draft.dirty()) {
                doc.setLastMessage("Sprite sheet " + sheetId + " is no longer listed by this document;"
                    + " its unapplied edits were discarded");
            }
            sheetId = null;
            draft = null;
        }
        ImGui.setNextItemWidth(Math.min(320, ImGui.getContentRegionAvailX() * 0.45f));
        if (ImGui.beginCombo("##sheet", sheetId == null ? "(choose a sprite sheet)" : sheetId)) {
            for (String s : sheets) {
                if (ImGui.selectable(s, s.equals(sheetId))) {
                    confirmSwitch(s);
                }
            }
            if (sheets.isEmpty()) {
                ImGui.textDisabled("This document lists no sprite sheets");
            }
            ImGui.endCombo();
        }
        ImGui.sameLine();
        if (ImGui.button("New Sheet...")) {
            ImGui.openPopup("##newSheet");
        }
        if (ImGui.beginPopup("##newSheet")) {
            ImGui.textDisabled("Regions for which texture?");
            boolean any = false;
            for (UiDependency d : doc.archive().dependencies().entries()) {
                if (d.kind() == UiDependency.Kind.TEXTURE || d.kind() == UiDependency.Kind.IMAGE) {
                    any = true;
                    if (ImGui.selectable(d.id())) {
                        createSheet(doc, d.id());
                    }
                }
            }
            if (!any) {
                ImGui.textDisabled("Add a texture in Details > Background Image first");
            }
            ImGui.endPopup();
        }
        if (draft == null) {
            return;
        }
        ImGui.sameLine();
        ImGui.beginDisabled(!draft.dirty());
        if (ImGui.button("Apply")) {
            apply(doc);
        }
        ImGui.sameLine();
        if (ImGui.button("Revert")) {
            loadedKey = null;
            draft = null;
        }
        ImGui.endDisabled();
        if (draft == null) {
            return;
        }
        ImGui.sameLine();
        ImGui.beginDisabled(!draft.canUndo());
        if (ImGui.button("Undo##sp")) {
            draft.undo();
        }
        ImGui.endDisabled();
        ImGui.sameLine();
        ImGui.beginDisabled(!draft.canRedo());
        if (ImGui.button("Redo##sp")) {
            draft.redo();
        }
        ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.button("Edit Texture")) {
            ctx.editTexture(textureId);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Open " + textureId + " in the Texture Editor; saving it updates every document");
        }
        if (draft.dirty()) {
            ImGui.sameLine();
            EditorWidgets.badge("unapplied", EditorWidgets.accent(1f));
        }
    }

    private void confirmSwitch(String next) {
        if (draft != null && draft.dirty() && !next.equals(sheetId)) {
            ctx.doc().setLastMessage("Sprite sheet " + sheetId + " has unapplied edits; Apply or Revert them first");
            return;
        }
        open(next);
    }

    private void createSheet(UiEditorDocument doc, String texId) {
        try {
            if (ctx.actions.run(UiImageAssets.newSheet(doc.archive(), ctx.project, texId))) {
                open(texId + "_sprites");
            }
        } catch (java.io.IOException | UiCommandException e) {
            doc.setLastMessage(e.getMessage());
        }
    }

    private void apply(UiEditorDocument doc) {
        if (ctx.actions.run(UiImageAssets.saveSheet(sheetId, draft.sheet(), ctx.project))) {
            draft.markApplied();
            ctx.assetFilesChanged(List.of());
            loadedKey = keyOf(doc); // after the epoch bump: no reload, so the draft keeps its undo stack
        }
    }

    // ── loading ─────────────────────────────────────────────────────────────

    private Object keyOf(UiEditorDocument doc) {
        return List.of(doc.archive().dependencies(), ctx.assetEpoch(), sheetId);
    }

    /** Re-reads the sheet and its texture when the document's table or project files changed. */
    private void load(UiEditorDocument doc) {
        Object key = keyOf(doc);
        if (key.equals(loadedKey)) {
            return;
        }
        loadedKey = key;
        loadError = null;
        OmuiArchive a = doc.archive();
        AssetResolver resolver = AssetResolver.forDocument(a, ctx.project.sources());
        UiDiagnostics d = new UiDiagnostics();
        ResolvedAsset sheetAsset = resolver.resolveOne(sheetId, d);
        if (sheetAsset == null) {
            fail("Sprite sheet " + sheetId + " does not resolve: " + d.list());
            return;
        }
        UiSpriteSheet sheet = SpriteSheetCodec.read(sheetAsset.bytes().toArray(), sheetId, d);
        if (sheet == null || d.hasErrors()) {
            fail("Sprite sheet " + sheetId + " cannot be read: " + d.list());
            return;
        }
        SpriteBinding binding = SpriteBinding.of(sheetId, sheet, id -> {
            UiDependency r = a.dependencies().find(id);
            return r == null ? null : AssetRow.of(r);
        });
        if (binding.texture() == null) {
            fail(binding.problem());
            return;
        }
        textureId = binding.texture();
        ResolvedAsset tex = resolver.resolveOne(textureId, new UiDiagnostics());
        MTexture next = tex == null ? null : MTexture.decode("ui:" + tex.sha256(), tex.bytes().toArray());
        if (next == null) {
            fail("Texture " + textureId + " of " + sheetId + " does not resolve to a readable texture");
            return;
        }
        if (decoded != null) {
            decoded.close();
        }
        decoded = next;
        texture.show(decoded);
        texW = decoded.width();
        texH = decoded.height();
        if (draft == null || !draft.dirty()) {
            if (draft == null) {
                draft = new SpriteSheetDraft(sheetId, sheet);
            } else {
                draft.reload(sheet);
            }
        }
    }

    private void fail(String message) {
        loadError = message;
        draft = null;
    }

    // ── findings ────────────────────────────────────────────────────────────

    private void findings() {
        UiSpriteSheets.Check check = draft.check(texW, texH);
        for (UiDiagnostic f : check.diagnostics()) {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, f.isError() ? INVALID : argb(0xFF, 0xE0, 0xA0, 0x30));
            ImGui.textWrapped((f.isError() ? "[error] " : "[warning] ") + f.message());
            ImGui.popStyleColor();
            if (f.code() == UiDiagnostic.Code.TEXTURE_SIZE_CHANGED) {
                ImGui.sameLine();
                if (ImGui.smallButton("Use " + texW + "x" + texH)) {
                    draft.acceptTextureSize(texW, texH);
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip("Record the new size once the regions are fixed");
                }
            }
        }
    }

    // ── list ────────────────────────────────────────────────────────────────

    private void list() {
        UiSpriteSheet sheet = draft.sheet();
        EditorWidgets.caption("Sprites");
        if (ImGui.smallButton("+ Sprite")) {
            draft.addSprite(0, 0, Math.min(16, texW), Math.min(16, texH));
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Or drag on empty texels in the canvas");
        }
        UiSpriteSheets.Check check = draft.check(texW, texH);
        for (Sprite s : sheet.sprites()) {
            item(s.name(), !check.drawable(s.name()) || !check.sliceUsable(s.name()));
        }
        ImGui.spacing();
        EditorWidgets.caption("Skins");
        ImGui.beginDisabled(sheet.sprites().isEmpty());
        if (ImGui.smallButton("+ Skin")) {
            Sprite base = draft.selectedSprite();
            draft.addSkin(base != null ? base.name() : sheet.sprites().getFirst().name());
        }
        ImGui.endDisabled();
        for (Skin k : sheet.skins()) {
            item(k.name(), false);
        }
    }

    private void item(String name, boolean invalid) {
        if (name.equals(renaming)) {
            ImGui.setNextItemWidth(-1);
            if (renameFocus) {
                ImGui.setKeyboardFocusHere();
                renameFocus = false;
            }
            if (ImGui.inputText("##rename", rename, ImGuiInputTextFlags.EnterReturnsTrue
                | ImGuiInputTextFlags.AutoSelectAll)) {
                if (!draft.rename(name, rename.get().trim())) {
                    ctx.doc().setLastMessage("'" + rename.get().trim() + "' is not a free sprite name");
                }
                renaming = null;
            } else if (ImGui.isItemDeactivated()) {
                renaming = null;
            }
            return;
        }
        if (invalid) {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, INVALID);
        }
        if (ImGui.selectable(name + "##it" + name, name.equals(draft.selected()))) {
            draft.select(name);
        }
        if (invalid) {
            ImGui.popStyleColor();
        }
        if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
            startRename(name);
        }
        if (ImGui.beginPopupContextItem("##ctx" + name)) {
            if (ImGui.menuItem("Rename")) {
                startRename(name);
            }
            if (ImGui.menuItem("Duplicate")) {
                draft.duplicate(name);
            }
            if (ImGui.menuItem("Copy Reference")) {
                ImGui.setClipboardText(sheetId + "#" + name);
            }
            if (ImGui.menuItem("Delete")) {
                draft.remove(name);
            }
            ImGui.endPopup();
        }
    }

    private void startRename(String name) {
        renaming = name;
        renameFocus = true;
        rename.set(name);
    }

    // ── canvas ──────────────────────────────────────────────────────────────

    private void canvas() {
        float cw = ImGui.getContentRegionAvailX();
        float ch = ImGui.getContentRegionAvailY();
        float x0 = ImGui.getCursorScreenPosX();
        float y0 = ImGui.getCursorScreenPosY();
        if (autoFit && (cw != fitW || ch != fitH)) {
            zoom = -1; // refit until the user pans or zooms (the first frames see the pre-dock window size)
            fitW = cw;
            fitH = ch;
        }
        if (zoom <= 0 && texW > 0) {
            zoom = Math.max(1, (float) Math.floor(Math.min((cw - 16) / texW, (ch - 16) / texH)));
            panX = (cw - texW * zoom) / 2f;
            panY = (ch - texH * zoom) / 2f;
        }
        ImGui.invisibleButton("##canvasHit", Math.max(1, cw), Math.max(1, ch),
            ImGuiButtonFlags.MouseButtonLeft | ImGuiButtonFlags.MouseButtonRight | ImGuiButtonFlags.MouseButtonMiddle);
        boolean hovered = ImGui.isItemHovered();
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.pushClipRect(x0, y0, x0 + cw, y0 + ch, true);
        float ox = x0 + panX;
        float oy = y0 + panY;
        // transparency checker, texture, texel grid
        float cell = Math.max(4, zoom * 4);
        for (float yy = 0; yy < texH * zoom; yy += cell) {
            for (float xx = 0; xx < texW * zoom; xx += cell) {
                boolean odd = (((int) (xx / cell) + (int) (yy / cell)) & 1) == 1;
                dl.addRectFilled(ox + xx, oy + yy, Math.min(ox + xx + cell, ox + texW * zoom),
                    Math.min(oy + yy + cell, oy + texH * zoom), odd ? CHECKER_A : CHECKER_B);
            }
        }
        dl.addImage(texture.id(), ox, oy, ox + texW * zoom, oy + texH * zoom, 0, 0, 1, 1);
        if (zoom >= 8) {
            for (int i = 0; i <= texW; i++) {
                dl.addLine(ox + i * zoom, oy, ox + i * zoom, oy + texH * zoom, GRID);
            }
            for (int j = 0; j <= texH; j++) {
                dl.addLine(ox, oy + j * zoom, ox + texW * zoom, oy + j * zoom, GRID);
            }
        }
        UiSpriteSheets.Check check = draft.check(texW, texH);
        for (Sprite s : draft.sheet().sprites()) {
            if (!s.name().equals(draft.selected())) {
                int c = check.drawable(s.name()) ? REGION : INVALID;
                dl.addRect(ox + s.x() * zoom, oy + s.y() * zoom, ox + (s.x() + s.w()) * zoom,
                    oy + (s.y() + s.h()) * zoom, c, 0, 0, 1f);
            }
        }
        Sprite sel = draft.selectedSprite();
        if (sel != null) {
            selection(dl, sel, ox, oy);
        }
        dl.popClipRect();
        interact(hovered, x0, y0, ox, oy);
    }

    private void selection(ImDrawList dl, Sprite s, float ox, float oy) {
        float l = ox + s.x() * zoom;
        float t = oy + s.y() * zoom;
        float r = ox + (s.x() + s.w()) * zoom;
        float b = oy + (s.y() + s.h()) * zoom;
        for (int i = 0; i < s.frames().size(); i++) {
            Frame f = s.frames().get(i);
            float fx = ox + f.x() * zoom;
            float fy = oy + f.y() * zoom;
            dl.addRect(fx, fy, fx + s.w() * zoom, fy + s.h() * zoom, FRAME, 0, 0, 1f);
            dl.addText(fx + 2, fy + 1, FRAME, Integer.toString(i + 1));
        }
        dl.addRect(l, t, r, b, SELECTED, 0, 0, 2f);
        Slice sl = s.slice();
        if (!sl.isNone()) {
            dashedV(dl, l + sl.left() * zoom, t, b);
            dashedV(dl, r - sl.right() * zoom, t, b);
            dashedH(dl, t + sl.top() * zoom, l, r);
            dashedH(dl, b - sl.bottom() * zoom, l, r);
        }
        float px = l + (float) s.pivotX() * (r - l);
        float py = t + (float) s.pivotY() * (b - t);
        dl.addCircle(px, py, 4, SELECTED, 12, 1.5f);
    }

    private static void dashedV(ImDrawList dl, float x, float y0, float y1) {
        for (float y = y0; y < y1; y += 6) {
            dl.addLine(x, y, x, Math.min(y + 3, y1), SLICE, 1.5f);
        }
    }

    private static void dashedH(ImDrawList dl, float y, float x0, float x1) {
        for (float x = x0; x < x1; x += 6) {
            dl.addLine(x, y, Math.min(x + 3, x1), y, SLICE, 1.5f);
        }
    }

    private void interact(boolean hovered, float x0, float y0, float ox, float oy) {
        float mx = ImGui.getMousePosX();
        float my = ImGui.getMousePosY();
        int tx = (int) Math.floor((mx - ox) / zoom);
        int ty = (int) Math.floor((my - oy) / zoom);
        if (hovered && ImGui.getIO().getMouseWheel() != 0 && drag == Drag.NONE) {
            float next = Math.clamp(zoom * (ImGui.getIO().getMouseWheel() > 0 ? 1.25f : 0.8f), 0.5f, 64f);
            autoFit = false;
            panX = mx - x0 - (mx - ox) * next / zoom;
            panY = my - y0 - (my - oy) * next / zoom;
            zoom = next;
        }
        if (hovered && drag == Drag.NONE && inside(tx, ty)) {
            ImGui.setTooltip(tx + ", " + ty);
        }
        if (ImGui.isItemActivated()) {
            if (ImGui.isMouseDown(1) || ImGui.isMouseDown(2)) {
                drag = Drag.PAN;
            } else {
                begin(mx, my, ox, oy, tx, ty);
            }
        }
        if (drag == Drag.PAN && ImGui.isItemActive() && (ImGui.getIO().getMouseDeltaX() != 0
            || ImGui.getIO().getMouseDeltaY() != 0)) {
            autoFit = false;
        }
        if (drag == Drag.PAN && ImGui.isItemActive()) {
            panX += ImGui.getIO().getMouseDeltaX();
            panY += ImGui.getIO().getMouseDeltaY();
        } else if (drag != Drag.NONE && ImGui.isItemActive()) {
            update(tx, ty);
        }
        if (ImGui.isItemDeactivated()) {
            if (drag == Drag.CREATE && tx == startTx && ty == startTy) {
                draft.undo(); // a click, not a drag: no 1x1 sprite, just deselect
                draft.select(null);
            }
            drag = Drag.NONE;
            draft.endInteraction();
        }
    }

    private boolean inside(int tx, int ty) {
        return tx >= 0 && ty >= 0 && tx < texW && ty < texH;
    }

    /** Pointer down: decide what the drag does from what is under it. */
    private void begin(float mx, float my, float ox, float oy, int tx, int ty) {
        startTx = tx;
        startTy = ty;
        Sprite sel = draft.selectedSprite();
        if (sel != null) {
            float l = ox + sel.x() * zoom;
            float t = oy + sel.y() * zoom;
            float r = ox + (sel.x() + sel.w()) * zoom;
            float b = oy + (sel.y() + sel.h()) * zoom;
            float grip = 5;
            boolean inY = my >= t - grip && my <= b + grip;
            boolean inX = mx >= l - grip && mx <= r + grip;
            Slice s = sel.slice();
            if (!s.isNone() && inX && inY) {
                int side = near(mx, l + s.left() * zoom, 1) | near(my, t + s.top() * zoom, 2)
                    | near(mx, r - s.right() * zoom, 4) | near(my, b - s.bottom() * zoom, 8);
                if (side != 0 && Math.abs(mx - l) > grip && Math.abs(mx - r) > grip && Math.abs(my - t) > grip
                    && Math.abs(my - b) > grip) {
                    drag = Drag.SLICE;
                    edges = Integer.lowestOneBit(side);
                    startSprite = sel;
                    return;
                }
            }
            int e = (inY ? near(mx, l, 1) | near(mx, r, 4) : 0) | (inX ? near(my, t, 2) | near(my, b, 8) : 0);
            if (e != 0) {
                drag = Drag.RESIZE;
                edges = e;
                startSprite = sel;
                return;
            }
            if (mx > l && mx < r && my > t && my < b) {
                drag = Drag.MOVE;
                startSprite = sel;
                return;
            }
        }
        List<Sprite> sprites = draft.sheet().sprites();
        for (int i = sprites.size() - 1; i >= 0; i--) {
            Sprite s = sprites.get(i);
            if (tx >= s.x() && ty >= s.y() && tx < s.x() + s.w() && ty < s.y() + s.h()) {
                draft.select(s.name());
                drag = Drag.MOVE;
                startSprite = s;
                return;
            }
        }
        if (inside(tx, ty)) {
            draft.addSprite(tx, ty, 1, 1, "canvas:" + Drag.CREATE);
            startSprite = draft.selectedSprite();
            drag = Drag.CREATE;
        } else {
            draft.select(null);
            drag = Drag.PAN;
        }
    }

    /**
     * {@link Math#clamp} that tolerates {@code hi < lo} (resolves to {@code lo}): after a texture
     * shrank or a slice stopped fitting, a drag must repair the region, not throw mid-frame.
     */
    static int clamp(int v, int lo, int hi) {
        return hi < lo ? lo : Math.max(lo, Math.min(hi, v));
    }

    private static int near(float v, float edge, int bit) {
        return Math.abs(v - edge) <= 5 ? bit : 0;
    }

    private void update(int tx, int ty) {
        Sprite s = startSprite;
        if (s == null || draft.selectedSprite() == null) {
            return;
        }
        String name = draft.selectedSprite().name();
        int dx = tx - startTx;
        int dy = ty - startTy;
        Sprite next = switch (drag) {
            case CREATE -> {
                int x1 = clamp(Math.min(startTx, tx), 0, texW - 1);
                int y1 = clamp(Math.min(startTy, ty), 0, texH - 1);
                int x2 = clamp(Math.max(startTx, tx), 0, texW - 1);
                int y2 = clamp(Math.max(startTy, ty), 0, texH - 1);
                yield s.withRect(x1, y1, x2 - x1 + 1, y2 - y1 + 1);
            }
            case MOVE -> {
                int nx = clamp(s.x() + dx, 0, Math.max(0, texW - s.w()));
                int ny = clamp(s.y() + dy, 0, Math.max(0, texH - s.h()));
                List<Frame> frames = new ArrayList<>();
                for (Frame f : s.frames()) {
                    frames.add(new Frame(f.x() + nx - s.x(), f.y() + ny - s.y(), f.duration(), f.unknown()));
                }
                yield s.withRect(nx, ny, s.w(), s.h()).withFrames(frames, s.loop());
            }
            case RESIZE -> {
                int l = s.x();
                int t = s.y();
                int r = s.x() + s.w();
                int b = s.y() + s.h();
                if ((edges & 1) != 0) l = clamp(l + dx, 0, r - 1);
                if ((edges & 4) != 0) r = clamp(r + dx, l + 1, texW);
                if ((edges & 2) != 0) t = clamp(t + dy, 0, b - 1);
                if ((edges & 8) != 0) b = clamp(b + dy, t + 1, texH);
                yield s.withRect(l, t, r - l, b - t);
            }
            case SLICE -> {
                Slice sl = s.slice();
                yield s.withSlice(switch (edges) {
                    case 1 -> new Slice(clamp(sl.left() + dx, 0, s.w() - sl.right()), sl.top(), sl.right(), sl.bottom());
                    case 4 -> new Slice(sl.left(), sl.top(), clamp(sl.right() - dx, 0, s.w() - sl.left()), sl.bottom());
                    case 2 -> new Slice(sl.left(), clamp(sl.top() + dy, 0, s.h() - sl.bottom()), sl.right(), sl.bottom());
                    default -> new Slice(sl.left(), sl.top(), sl.right(), clamp(sl.bottom() - dy, 0, s.h() - sl.top()));
                }, s.edges(), s.center());
            }
            default -> null;
        };
        if (next != null) {
            draft.setSprite(name, next, "canvas:" + drag);
        }
    }

    // ── properties ──────────────────────────────────────────────────────────

    private void properties() {
        Sprite s = draft.selectedSprite();
        Skin k = draft.selectedSkin();
        if (s != null) {
            sprite(s);
        } else if (k != null) {
            skin(k);
        } else {
            EditorWidgets.emptyState("Nothing selected", "Select a sprite or skin, or drag a region on the canvas.");
        }
    }

    private void sprite(Sprite s) {
        String n = s.name();
        ImGui.textDisabled("Reference");
        ImGui.textWrapped(sheetId + "#" + n);
        EditorWidgets.caption("Region (texels)");
        int[] rect = {s.x(), s.y(), s.w(), s.h()};
        if (ImGui.dragInt4("##rect", rect, 0.2f, 0, Math.max(texW, texH))) {
            set(n, s.withRect(rect[0], rect[1], Math.max(1, rect[2]), Math.max(1, rect[3])), "rect");
        }
        tip("x, y, width, height");
        EditorWidgets.caption("Layout");
        float[] logical = {(float) s.logicalWidth(), (float) s.logicalHeight()};
        if (ImGui.dragFloat2("Logical size", logical, 0.25f, 0, 4096, "%.1f")) {
            set(n, s.withLogicalSize(Math.max(0, Math.round(logical[0] * 4) / 4.0),
                Math.max(0, Math.round(logical[1] * 4) / 4.0)), "logical");
        }
        tip("Intrinsic size in logical px; 0 = the region's texel size");
        float[] pivot = {(float) s.pivotX(), (float) s.pivotY()};
        if (ImGui.sliderFloat2("Pivot", pivot, 0, 1, "%.2f")) {
            set(n, s.withPivot(Math.round(pivot[0] * 100) / 100.0, Math.round(pivot[1] * 100) / 100.0), "pivot");
        }
        tip("Anchor for natural-size (integer) placement and transforms; 0.5 = centre");
        EditorWidgets.caption("Nine-slice");
        int[] slice = {s.slice().left(), s.slice().top(), s.slice().right(), s.slice().bottom()};
        if (ImGui.dragInt4("##slice", slice, 0.1f, 0, Math.max(s.w(), s.h()))) {
            set(n, s.withSlice(new Slice(Math.max(0, slice[0]), Math.max(0, slice[1]), Math.max(0, slice[2]),
                Math.max(0, slice[3])), s.edges(), s.center()), "slice");
        }
        tip("left, top, right, bottom insets in texels; corners never stretch");
        String problem = UiSpriteSheets.sliceProblem(s);
        if (problem != null) {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, INVALID);
            ImGui.textWrapped(problem);
            ImGui.popStyleColor();
        }
        Fill edges = combo("Edges", s.edges(), new Fill[]{Fill.STRETCH, Fill.TILE});
        Fill center = combo("Centre", s.center(), Fill.values());
        if (edges != s.edges() || center != s.center()) {
            set(n, s.withSlice(s.slice(), edges, center), null);
        }
        EditorWidgets.caption("Look");
        int scaleIdx = s.scale() == null ? 0 : s.scale().ordinal() + 1;
        int newScale = choose("Fill", SCALE, scaleIdx);
        int samplingIdx = s.sampling() == null ? 0 : s.sampling().ordinal() + 1;
        int newSampling = choose("Sampling", SAMPLING, samplingIdx);
        boolean tinted = s.tint() != null;
        float[] tint = rgba(tinted ? s.tint() : "#FFFFFFFF");
        boolean tintChanged = false;
        if (ImGui.checkbox("##tintOn", tinted)) {
            tinted = !tinted;
            tintChanged = true;
        }
        ImGui.sameLine();
        ImGui.beginDisabled(!tinted);
        if (ImGui.colorEdit4("Tint", tint, imgui.flag.ImGuiColorEditFlags.AlphaBar)) {
            tintChanged = true;
        }
        ImGui.endDisabled();
        float[] opacity = {(float) s.opacity()};
        boolean opacityChanged = ImGui.sliderFloat("Opacity", opacity, 0, 1, "%.2f");
        if (newScale != scaleIdx || newSampling != samplingIdx || tintChanged || opacityChanged) {
            set(n, s.withLook(newScale == 0 ? null : ScaleMode.values()[newScale - 1],
                newSampling == 0 ? null : Sampling.values()[newSampling - 1], tinted ? hex(tint) : null,
                Math.round(opacity[0] * 100) / 100.0), "look");
        }
        frames(s);
        preview(s);
    }

    private void frames(Sprite s) {
        String n = s.name();
        EditorWidgets.caption("Animation");
        List<Frame> frames = new ArrayList<>(s.frames());
        LoopMode loop = combo("Loop", s.loop(), LoopMode.values());
        int remove = -1;
        int up = -1;
        boolean changed = loop != s.loop();
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            ImGui.pushID(i);
            int[] xy = {f.x(), f.y()};
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.45f);
            if (ImGui.dragInt2("##xy", xy, 0.2f, 0, Math.max(texW, texH))) {
                frames.set(i, new Frame(xy[0], xy[1], f.duration(), f.unknown()));
                changed = true;
            }
            ImGui.sameLine();
            float[] dur = {(float) f.duration()};
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.5f);
            if (ImGui.dragFloat("##d", dur, 0.005f, 0.01f, 60f, "%.3f s")) {
                frames.set(i, new Frame(f.x(), f.y(), Math.max(0.01, Math.round(dur[0] * 1000) / 1000.0), f.unknown()));
                changed = true;
            }
            ImGui.sameLine();
            if (ImGui.smallButton("^") && i > 0) {
                up = i;
            }
            ImGui.sameLine();
            if (ImGui.smallButton("x")) {
                remove = i;
            }
            ImGui.popID();
        }
        if (up > 0) {
            Frame f = frames.remove(up);
            frames.add(up - 1, f);
            changed = true;
        }
        if (remove >= 0) {
            frames.remove(remove);
            changed = true;
        }
        if (ImGui.smallButton("+ Frame")) {
            Frame last = frames.isEmpty() ? new Frame(s.x(), s.y(), 0.1) : frames.getLast();
            int nx = last.x() + s.w() + s.w() <= texW ? last.x() + s.w() : last.x();
            frames.add(frames.isEmpty() ? last : new Frame(nx, last.y(), last.duration()));
            changed = true;
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("The next frame is the region to the right; drag frame positions or the region");
        }
        if (changed) {
            set(n, s.withFrames(frames, loop), "frames");
        }
    }

    /** The sprite at a chosen size through the runtime's own nine-slice layout, frames playing. */
    private void preview(Sprite s) {
        EditorWidgets.caption("Preview");
        float[] size = {previewW, previewH};
        if (ImGui.dragFloat2("Size", size, 0.5f, 1, 512, "%.0f")) {
            previewW = size[0];
            previewH = size[1];
        }
        float[] sc = {previewScale};
        if (ImGui.sliderFloat("Scale", sc, 1, 4, "%.2fx")) {
            previewScale = Math.round(sc[0] * 4) / 4f;
        }
        if (s.animated()) {
            if (ImGui.checkbox("Play", playFrames)) {
                playFrames = !playFrames;
            }
        }
        double time = playFrames ? ImGui.getTime() : 0;
        int fx = s.x();
        int fy = s.y();
        if (s.animated()) {
            Frame f = s.frames().get(SpriteFrames.frameAt(s.frames(), s.loop(), time));
            fx = f.x();
            fy = f.y();
        }
        float w = previewW * previewScale;
        float h = previewH * previewScale;
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGui.dummy(w, h);
        ScaleMode mode = s.effectiveScale();
        if (mode == ScaleMode.NINE_SLICE && UiSpriteSheets.sliceProblem(s) != null) {
            mode = ScaleMode.STRETCH;
        }
        boolean nearest = s.sampling() != Sampling.LINEAR;
        float kx = (float) (s.layoutWidth() * previewScale / s.w());
        float ky = (float) (s.layoutHeight() * previewScale / s.h());
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRect(x - 1, y - 1, x + w + 1, y + h + 1, GRID);
        dl.pushClipRect(x, y, x + w, y + h, true);
        for (SpriteSlices.Patch p : SpriteSlices.layout(fx, fy, s.w(), s.h(), s.slice(), s.edges(), s.center(), mode,
            s.pivotX(), s.pivotY(), new UiRect(x, y, w, h), kx, ky, nearest)) {
            patch(dl, p);
        }
        dl.popClipRect();
    }

    private void patch(ImDrawList dl, SpriteSlices.Patch p) {
        float tw = p.tileX() ? p.sw() * p.tileKx() : p.dw();
        float th = p.tileY() ? p.sh() * p.tileKy() : p.dh();
        if (tw <= 0 || th <= 0) {
            return;
        }
        int count = 0;
        for (float yy = 0; yy < p.dh() && count < 1024; yy += th) {
            for (float xx = 0; xx < p.dw() && count < 1024; xx += tw, count++) {
                float w = Math.min(tw, p.dw() - xx);
                float h = Math.min(th, p.dh() - yy);
                float u0 = (float) p.sx() / texW;
                float v0 = (float) p.sy() / texH;
                float u1 = (p.sx() + p.sw() * (w / tw)) / texW;
                float v1 = (p.sy() + p.sh() * (h / th)) / texH;
                dl.addImage(texture.id(), p.dx() + xx, p.dy() + yy, p.dx() + xx + w, p.dy() + yy + h, u0, v0, u1, v1);
            }
        }
    }

    private void skin(Skin k) {
        ImGui.textDisabled("Reference");
        ImGui.textWrapped(sheetId + "#" + k.name());
        EditorWidgets.caption("Regions per state");
        List<String> names = draft.sheet().sprites().stream().map(Sprite::name).toList();
        Skin next = k;
        for (String state : Skin.STATES) {
            String current = k.region(state);
            if (ImGui.beginCombo(state, current == null ? "(normal)" : current)) {
                if (!"normal".equals(state) && ImGui.selectable("(normal)", current == null)) {
                    next = next.withState(state, null);
                }
                for (String name : names) {
                    if (ImGui.selectable(name, name.equals(current))) {
                        next = next.withState(state, name);
                    }
                }
                ImGui.endCombo();
            }
        }
        if (!next.equals(k)) {
            draft.setSkin(k.name(), next, null);
        }
        ImGui.textWrapped("Disabled beats pressed beats hover beats keyboard focus; missing states use normal.");
    }

    // ── small helpers ───────────────────────────────────────────────────────

    private void set(String name, Sprite next, String field) {
        draft.setSprite(name, next, field == null ? null : "field:" + name + ":" + field);
    }

    private static void tip(String text) {
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(text);
        }
    }

    private static <E extends Enum<E> & com.openmason.engine.format.omui.WireEnum> E combo(String label, E current,
                                                                                           E[] options) {
        E out = current;
        if (ImGui.beginCombo(label, current.wire())) {
            for (E o : options) {
                if (ImGui.selectable(o.wire(), o == current)) {
                    out = o;
                }
            }
            ImGui.endCombo();
        }
        return out;
    }

    private static int choose(String label, String[] options, int current) {
        int out = current;
        if (ImGui.beginCombo(label, options[current])) {
            for (int i = 0; i < options.length; i++) {
                if (ImGui.selectable(options[i], i == current)) {
                    out = i;
                }
            }
            ImGui.endCombo();
        }
        return out;
    }

    private static float[] rgba(String hex) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        int a = hex.length() >= 9 ? Integer.parseInt(hex.substring(7, 9), 16) : 255;
        return new float[]{r / 255f, g / 255f, b / 255f, a / 255f};
    }

    private static String hex(float[] c) {
        int a = Math.round(c[3] * 255);
        String rgb = String.format("#%02X%02X%02X", Math.round(c[0] * 255), Math.round(c[1] * 255),
            Math.round(c[2] * 255));
        return a == 255 ? rgb : rgb + String.format("%02X", a);
    }

    /** ImGui draw-list colour (ABGR) from ARGB components. */
    private static int argb(int a, int r, int g, int b) {
        return a << 24 | b << 16 | g << 8 | r;
    }

    @Override
    public void close() {
        texture.close();
        if (decoded != null) {
            decoded.close();
            decoded = null;
        }
    }
}
