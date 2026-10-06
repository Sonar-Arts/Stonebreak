package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.ui.assets.export.ExportMode;
import com.openmason.main.systems.menus.dialogs.ModalDialogs;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.UiDocumentService;
import com.openmason.main.systems.uiEditor.service.UiDocumentTemplates;
import com.openmason.main.systems.uiEditor.service.UiRecoveryService;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import com.openmason.main.systems.uiEditor.view.widgets.Glyphs;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.nio.file.Path;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The UI editor's modal dialogs, all through {@link ModalDialogs}: new document (template
 * cards, name, id and where it will be saved), the unsaved-changes prompt for closing tabs,
 * export to SBUI with the plan's findings, and crash recovery of untitled documents.
 */
final class UiEditorDialogs {

    private static final String NEW_ID = "New UI Document##uiNew";
    private static final String CLOSE_ID = "Unsaved UI Document##uiClose";
    private static final String EXPORT_ID = "Export SBUI##uiExport";
    private static final String RECOVER_ID = "Recover UI Documents##uiRecover";

    private final UiEditorContext ctx;
    private final UiEditorWorkspace workspace;

    // new document
    private boolean openNew;
    private UiDocumentTemplates template = UiDocumentTemplates.BLANK_SCREEN;
    private final ImString name = new ImString(96);
    private final ImString docId = new ImString(160);
    private boolean idEdited;
    // close
    private final List<UiEditorDocument> closeQueue = new ArrayList<>();
    private Runnable afterClose;
    private boolean openClose;
    // export
    private boolean openExport;
    private UiEditorDocument exportDoc;
    private final ImString exportPath = new ImString(1024);
    private final ImInt exportMode = new ImInt(0);
    private UiDocumentService.ExportResult exportResult;
    // recovery
    private boolean openRecover;
    private List<UiRecoveryService.Slot> slots = List.of();

    UiEditorDialogs(UiEditorContext ctx, UiEditorWorkspace workspace) {
        this.ctx = ctx;
        this.workspace = workspace;
    }

    void openNewDocument() {
        openNew = true;
        name.set("");
        docId.set("");
        idEdited = false;
    }

    void openNewDocument(UiDocumentTemplates t) {
        openNewDocument();
        template = t;
    }

    /** Closes {@code docs}, asking about each dirty one; {@code then} runs when all are handled (not on Cancel). */
    void close(List<UiEditorDocument> docs, Runnable then) {
        closeQueue.clear();
        closeQueue.addAll(docs);
        afterClose = then;
        advanceClose();
    }

    private void advanceClose() {
        while (!closeQueue.isEmpty() && !closeQueue.getFirst().isDirty()) {
            ctx.service.close(closeQueue.removeFirst());
        }
        if (closeQueue.isEmpty()) {
            Runnable r = afterClose;
            afterClose = null;
            if (r != null) {
                r.run();
            }
        } else {
            openClose = true;
        }
    }

    void openExport(UiEditorDocument doc) {
        exportDoc = doc;
        exportPath.set(ctx.service.defaultExportTarget(doc).toString());
        exportResult = null;
        openExport = true;
    }

    void openRecovery(List<UiRecoveryService.Slot> s) {
        slots = new ArrayList<>(s);
        openRecover = !slots.isEmpty();
    }

    void render() {
        newDocument();
        closePrompt();
        export();
        recovery();
    }

    // ── new document ────────────────────────────────────────────────────────

    private void newDocument() {
        if (openNew) {
            ModalDialogs.openIfNeeded(NEW_ID);
            openNew = false;
        }
        if (!ModalDialogs.begin(NEW_ID, 620)) {
            return;
        }
        ImGui.textDisabled("Template");
        UiDocumentTemplates[] all = UiDocumentTemplates.values();
        float cardW = (ImGui.getContentRegionAvailX() - 3 * 8) / 4f;
        for (int i = 0; i < all.length; i++) {
            if (i > 0) {
                ImGui.sameLine(0, 8);
            }
            card(all[i], cardW);
        }
        ImGui.spacing();
        ImGui.textDisabled("Name");
        ImGui.setNextItemWidth(-1);
        if (ImGui.isWindowAppearing()) {
            ImGui.setKeyboardFocusHere();
        }
        ImGui.inputTextWithHint("##newName", template.kind() == UiManifest.DocumentKind.COMPONENT ? "Inventory Slot"
            : "Main Menu", name);
        if (!idEdited) {
            docId.set(suggestId(name.get(), template.kind()));
        }
        ImGui.textDisabled("Document id (stable; screens find components by it)");
        ImGui.setNextItemWidth(-1);
        if (ImGui.inputText("##newId", docId)) {
            idEdited = true;
        }
        String id = docId.get().trim();
        boolean validId = OmuiFormat.LOGICAL_ID.matcher(id).matches();
        Path target = validId ? ctx.project.conventionPath(id) : null;
        if (!validId) {
            ThemeColors.push(imgui.flag.ImGuiCol.Text, ThemeColors.Tone.ERROR);
            ImGui.textWrapped("Ids look like namespace:path, lowercase (stonebreak:ui/screens/main_menu)");
            ImGui.popStyleColor();
        } else if (target != null) {
            boolean exists = java.nio.file.Files.exists(target);
            if (exists) {
                ThemeColors.push(imgui.flag.ImGuiCol.Text, ThemeColors.Tone.WARNING);
            }
            ImGui.textWrapped((exists ? "Already exists: " : "Saves to ") + ctx.project.relative(target));
            if (exists) {
                ImGui.popStyleColor();
            }
        } else {
            ImGui.textDisabled("No project is open: you will choose where to save.");
        }
        ModalDialogs.buttonsBegin();
        boolean ok = validId && !name.get().isBlank();
        if (ModalDialogs.primary("Create", ok, true)) {
            UiEditorDocument doc = ctx.service.create(template, id, name.get().trim());
            ctx.view(doc).fitPending = true;
            workspace.revealDesigner();
            ModalDialogs.close();
        }
        if (ModalDialogs.cancel()) {
            ModalDialogs.close();
        }
        ModalDialogs.end();
    }

    private void card(UiDocumentTemplates t, float w) {
        float h = w * 0.78f + ImGui.getTextLineHeight() * 2 + 12;
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton("##card" + t.name(), w, h);
        boolean hovered = ImGui.isItemHovered();
        boolean sel = t == template;
        if (clicked) {
            template = t;
            if (!idEdited) {
                docId.set(suggestId(name.get(), t.kind()));
            }
        }
        if (hovered && ImGui.isMouseDoubleClicked(0)) {
            template = t;
        }
        dl.addRectFilled(x, y, x + w, y + h, sel ? EditorWidgets.accent(0.18f) : EditorWidgets.frame(hovered ? 1f : 0.7f), 6f);
        dl.addRect(x, y, x + w, y + h, sel ? EditorWidgets.accent(1f) : EditorWidgets.border(0.8f), 6f, 0, sel ? 2f : 1f);
        float px = x + 8;
        float py = y + 8;
        float pw = w - 16;
        float ph = w * 0.78f - 16;
        dl.addRectFilled(px, py, px + pw, py + ph, Glyphs.rgba(0.11f, 0.12f, 0.14f, 1f), 4f);
        sketch(dl, t, px, py, pw, ph);
        dl.addText(x + 8, y + w * 0.78f, EditorWidgets.text(1f), t.title());
        dl.addText(x + 8, y + w * 0.78f + ImGui.getTextLineHeight() + 2, EditorWidgets.dim(1f),
            t.kind() == UiManifest.DocumentKind.COMPONENT ? "component" : "screen");
        if (hovered) {
            ImGui.setTooltip(t.description());
        }
    }

    /** Miniature of what a template lays out. */
    private static void sketch(ImDrawList dl, UiDocumentTemplates t, float x, float y, float w, float h) {
        int line = Glyphs.rgba(0.55f, 0.58f, 0.64f, 1f);
        int fill = Glyphs.rgba(0.30f, 0.32f, 0.36f, 1f);
        switch (t) {
            case BLANK_SCREEN -> Glyphs.dashedRect(dl, x + 6, y + 6, x + w - 6, y + h - 6, line, 1f);
            case MENU_SCREEN -> {
                float pw = w * 0.46f;
                float px = x + (w - pw) / 2f;
                dl.addRectFilled(px, y + h * 0.12f, px + pw, y + h * 0.88f, fill, 3f);
                dl.addLine(px + pw * 0.25f, y + h * 0.24f, px + pw * 0.75f, y + h * 0.24f, line, 2f);
                for (int i = 0; i < 3; i++) {
                    float by = y + h * (0.36f + i * 0.17f);
                    dl.addRectFilled(px + pw * 0.12f, by, px + pw * 0.88f, by + h * 0.11f,
                        Glyphs.rgba(0.62f, 0.56f, 0.42f, 1f), 2f);
                }
            }
            case BLANK_COMPONENT -> Glyphs.component(dl, x + w / 2f - h * 0.25f, y + h * 0.25f, h * 0.5f, line);
            case BUTTON_COMPONENT -> {
                dl.addRectFilled(x + w * 0.18f, y + h * 0.38f, x + w * 0.82f, y + h * 0.62f,
                    Glyphs.rgba(0.62f, 0.56f, 0.42f, 1f), 3f);
                dl.addLine(x + w * 0.38f, y + h * 0.5f, x + w * 0.62f, y + h * 0.5f, Glyphs.rgba(0.95f, 0.95f, 0.95f, 1f), 2f);
            }
        }
    }

    static String suggestId(String name, UiManifest.DocumentKind kind) {
        String stem = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (stem.isEmpty()) {
            stem = kind == UiManifest.DocumentKind.COMPONENT ? "component" : "screen";
        }
        return "stonebreak:ui/" + (kind == UiManifest.DocumentKind.COMPONENT ? "components/" : "screens/") + stem;
    }

    // ── close prompt ────────────────────────────────────────────────────────

    private void closePrompt() {
        if (openClose) {
            ModalDialogs.openIfNeeded(CLOSE_ID);
            openClose = false;
        }
        if (!ModalDialogs.begin(CLOSE_ID, 420)) {
            return;
        }
        UiEditorDocument doc = closeQueue.isEmpty() ? null : closeQueue.getFirst();
        if (doc == null) {
            ModalDialogs.close();
            ModalDialogs.end();
            return;
        }
        ImGui.textUnformatted("Save changes to \"" + doc.title() + "\" before closing?");
        ImGui.textDisabled("Your changes are lost if you don't save them.");
        ModalDialogs.buttonsBegin();
        if (ModalDialogs.primary("Save", true)) {
            if (workspace.save(doc)) {
                closeQueue.removeFirst();
                ctx.service.close(doc);
                ModalDialogs.close();
                advanceClose();
            }
        }
        if (ModalDialogs.secondary("Don't Save")) {
            closeQueue.removeFirst();
            ctx.service.close(doc);
            ModalDialogs.close();
            advanceClose();
        }
        if (ModalDialogs.cancel()) {
            closeQueue.clear();
            afterClose = null;
            ModalDialogs.close();
        }
        ModalDialogs.end();
    }

    // ── export ──────────────────────────────────────────────────────────────

    private void export() {
        if (openExport) {
            ModalDialogs.openIfNeeded(EXPORT_ID);
            openExport = false;
        }
        if (!ModalDialogs.begin(EXPORT_ID, 560)) {
            return;
        }
        if (exportDoc == null) {
            ModalDialogs.close();
            ModalDialogs.end();
            return;
        }
        ImGui.textUnformatted("Export \"" + exportDoc.title() + "\" for the game");
        ImGui.textDisabled("Target (.sbui; a .report.json is written beside it)");
        ImGui.setNextItemWidth(-90);
        ImGui.inputText("##exportPath", exportPath);
        ImGui.sameLine();
        if (ImGui.button("Browse...")) {
            workspace.chooseExportTarget(exportDoc, p -> exportPath.set(p));
        }
        ImGui.radioButton("Shared: dependencies ship with the game or packs", exportMode, 0);
        ImGui.radioButton("Collect all: one self-contained package", exportMode, 1);
        if (exportResult != null) {
            ImGui.spacing();
            if (exportResult.error() != null) {
                ThemeColors.push(imgui.flag.ImGuiCol.Text, ThemeColors.Tone.ERROR);
                ImGui.textWrapped(exportResult.error());
                ImGui.popStyleColor();
            } else {
                ThemeColors.push(imgui.flag.ImGuiCol.Text, ThemeColors.Tone.SUCCESS);
                ImGui.textWrapped("Exported " + exportResult.target());
                ImGui.popStyleColor();
            }
            if (ImGui.beginChild("##exportDiag", 0, Math.min(160, 20 + exportResult.diagnostics().size() * 18f), true)) {
                for (UiDiagnostic d : exportResult.diagnostics()) {
                    ThemeColors.Tone tone = d.severity() == UiDiagnostic.Severity.ERROR ? ThemeColors.Tone.ERROR
                        : d.severity() == UiDiagnostic.Severity.WARNING ? ThemeColors.Tone.WARNING : null;
                    if (tone != null) {
                        ThemeColors.push(imgui.flag.ImGuiCol.Text, tone);
                    }
                    ImGui.textWrapped(d.message());
                    if (tone != null) {
                        ImGui.popStyleColor();
                    }
                }
            }
            ImGui.endChild();
        }
        ModalDialogs.buttonsBegin();
        if (ModalDialogs.primary("Export", !exportPath.get().isBlank())) {
            Path target = Path.of(exportPath.get().trim());
            if (!target.toString().endsWith(".sbui")) {
                target = target.resolveSibling(target.getFileName() + ".sbui");
            }
            exportResult = ctx.service.export(exportDoc, target, exportMode.get() == 0 ? ExportMode.SHARED
                : ExportMode.COLLECT_ALL);
            ctx.project.entries(true);
        }
        if (ModalDialogs.cancel(exportResult != null && exportResult.error() == null ? "Close" : "Cancel")) {
            ModalDialogs.close();
        }
        ModalDialogs.end();
    }

    // ── recovery ────────────────────────────────────────────────────────────

    private void recovery() {
        if (openRecover) {
            ModalDialogs.openIfNeeded(RECOVER_ID);
            openRecover = false;
        }
        if (!ModalDialogs.begin(RECOVER_ID, 520)) {
            return;
        }
        ImGui.textWrapped("Open Mason closed while these UI documents had unsaved changes. Restore them to keep "
            + "working, or discard them.");
        ImGui.spacing();
        DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (UiRecoveryService.Slot s : new ArrayList<>(slots)) {
            ImGui.pushID(s.archive().toString());
            ImGui.alignTextToFramePadding();
            ImGui.textUnformatted(s.title().isBlank() ? s.documentId() : s.title());
            ImGui.sameLine();
            ImGui.textDisabled(fmt.format(new Date(s.savedAt())) + (s.file() != null ? "  " + s.file().getFileName() : "  untitled"));
            ImGui.sameLine(ImGui.getContentRegionMaxX() - 160);
            if (ImGui.smallButton("Restore")) {
                workspace.handleOpen(ctx.service.openRecovered(s));
                slots.remove(s);
            }
            ImGui.sameLine();
            if (ImGui.smallButton("Discard")) {
                ctx.service.recovery().clear(s);
                slots.remove(s);
            }
            ImGui.popID();
        }
        ModalDialogs.buttonsBegin();
        if (ModalDialogs.closeButton() || slots.isEmpty()) {
            ModalDialogs.close();
        }
        ModalDialogs.end();
    }
}
