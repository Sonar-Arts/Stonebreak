package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.ResolvedAsset;
import com.openmason.engine.ui.assets.Resolution;
import com.openmason.engine.ui.assets.edit.AssetEdit;
import com.openmason.engine.ui.assets.edit.CollisionPolicy;
import com.openmason.engine.ui.assets.edit.EmbedOperations;
import com.openmason.engine.ui.assets.edit.ExtractOperations;
import com.openmason.engine.ui.assets.edit.RelinkOperations;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.uiEditor.command.DocumentCommands;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.UiProjectContext;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import com.openmason.main.systems.uiEditor.view.widgets.Glyphs;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImString;

import java.util.List;
import java.util.Locale;

/**
 * The project's UI files (screens, components, sheets, scripts, exports) and the active
 * document's dependency table: each row's mode (embedded snapshot or shared), where it resolves
 * from, and the #285 operations (embed, extract to project, relink, refresh) as undoable steps
 * whose project writes undo with them.
 */
final class AssetsPanel {

    static final String TITLE = "UI Assets###uiAssets";

    private final UiEditorContext ctx;
    private final UiEditorWorkspace workspace;
    private final ImString search = new ImString(64);
    private final ImString relinkPath = new ImString(256);
    private String relinkId;
    private Object resolvedFor;
    private long resolvedEpoch = -1;
    private Resolution resolution;

    AssetsPanel(UiEditorContext ctx, UiEditorWorkspace workspace) {
        this.ctx = ctx;
        this.workspace = workspace;
    }

    void render() {
        if (!ImGui.begin(TITLE)) {
            ImGui.end();
            return;
        }
        ctx.noteFocus();
        if (ImGui.beginTabBar("##assetTabs")) {
            if (ImGui.beginTabItem("Project")) {
                project();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Dependencies")) {
                dependencies();
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
        ImGui.end();
    }

    // ── project files ───────────────────────────────────────────────────────

    private void project() {
        if (ctx.project.root() == null) {
            EditorWidgets.emptyState("No project open", "UI documents live in a project's UI folder.");
            return;
        }
        ThemeColors.pushAccentButton();
        if (ImGui.button("+ New")) {
            workspace.dialogs().openNewDocument();
        }
        ImGui.popStyleColor(4);
        ImGui.sameLine();
        if (ImGui.button("Import SBUI...")) {
            workspace.importSbui();
        }
        ImGui.sameLine();
        if (ImGui.button("Refresh")) {
            ctx.project.entries(true);
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(-1);
        ImGui.inputTextWithHint("##assetSearch", "filter", search);
        String q = search.get().trim().toLowerCase(Locale.ROOT);
        List<UiProjectContext.Entry> entries = ctx.project.entries(false);
        if (entries.isEmpty()) {
            EditorWidgets.emptyState("No UI files yet", "New documents are saved under UI/<namespace>/... so screens "
                + "find the components they use automatically.");
            return;
        }
        if (ImGui.beginChild("##assetList")) {
            UiProjectContext.Entry.Kind last = null;
            for (UiProjectContext.Entry e : entries) {
                if (!q.isEmpty() && !e.label().toLowerCase(Locale.ROOT).contains(q)
                        && (e.relative() == null || !e.relative().toLowerCase(Locale.ROOT).contains(q))) {
                    continue;
                }
                if (e.kind() != last) {
                    EditorWidgets.caption(switch (e.kind()) {
                        case SCREEN -> "Screens";
                        case COMPONENT -> "Components";
                        case EXPORT -> "Exports (open as editable copies)";
                        case STYLESHEET -> "Shared Style Sheets";
                        case SCRIPT -> "Shared Scripts";
                        case OTHER -> "Unreadable";
                    });
                    last = e.kind();
                }
                entryRow(e);
            }
        }
        ImGui.endChild();
    }

    private void entryRow(UiProjectContext.Entry e) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float h = ImGui.getTextLineHeight() + 8;
        float w = ImGui.getContentRegionAvailX();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        UiEditorDocument active = ctx.doc();
        boolean open = active != null && e.path().equals(active.file());
        ImGui.invisibleButton("##ent" + e.path(), w, h);
        boolean hovered = ImGui.isItemHovered();
        if (open || hovered) {
            dl.addRectFilled(x, y, x + w, y + h, EditorWidgets.accent(open ? 0.22f : 0.12f), 3f);
        }
        String glyph = switch (e.kind()) {
            case SCREEN -> "Box";
            case COMPONENT -> "Instance";
            case EXPORT -> "ItemSlot";
            case STYLESHEET -> "Label";
            case SCRIPT -> "Canvas";
            case OTHER -> "?";
        };
        float s = h - 8;
        Glyphs.widget(dl, glyph, x + 4, y + 4, s, Glyphs.typeColor(glyph, 1f));
        dl.addText(x + s + 12, y + 4, e.kind() == UiProjectContext.Entry.Kind.OTHER
            ? ThemeColors.u32(ThemeColors.Tone.ERROR, 1f) : EditorWidgets.text(1f), e.label());
        String rel = e.relative() == null ? "" : e.relative();
        float lw = ImGui.calcTextSize(e.label()).x;
        dl.addText(x + s + 20 + lw, y + 4, EditorWidgets.dim(0.8f), rel);
        if (hovered && ImGui.isMouseDoubleClicked(0)) {
            activate(e);
        }
        if (hovered) {
            ImGui.setTooltip((e.documentId() != null ? e.documentId() + "\n" : "") + rel
                + (e.kind() == UiProjectContext.Entry.Kind.COMPONENT ? "\nDrag from the Palette to place it" : "")
                + "\nDouble-click to open");
        }
        if (ImGui.beginPopupContextItem("##entctx" + e.path())) {
            if ((e.kind() == UiProjectContext.Entry.Kind.SCREEN || e.kind() == UiProjectContext.Entry.Kind.COMPONENT
                    || e.kind() == UiProjectContext.Entry.Kind.EXPORT) && ImGui.menuItem("Open")) {
                activate(e);
            }
            if (e.kind() == UiProjectContext.Entry.Kind.COMPONENT && ctx.doc() != null
                    && ImGui.menuItem("Place in Current Document")) {
                ctx.actions.run(PalettePanel.createCommand(ctx, "component:" + e.documentId(), ctx.actions.insertionPoint()));
            }
            if (e.kind() == UiProjectContext.Entry.Kind.EXPORT && ImGui.menuItem("Import into Project")) {
                workspace.handleOpen(ctx.service.importIntoProject(e.path()));
            }
            if (ImGui.menuItem("Copy Path")) {
                ImGui.setClipboardText(rel);
            }
            ImGui.endPopup();
        }
    }

    private void activate(UiProjectContext.Entry e) {
        if (e.kind() == UiProjectContext.Entry.Kind.SCREEN || e.kind() == UiProjectContext.Entry.Kind.COMPONENT
                || e.kind() == UiProjectContext.Entry.Kind.EXPORT) {
            workspace.handleOpen(ctx.service.open(e.path()));
        }
    }

    // ── dependency table ────────────────────────────────────────────────────

    private void dependencies() {
        UiEditorDocument doc = ctx.doc();
        if (doc == null) {
            EditorWidgets.emptyState("No document", null);
            return;
        }
        List<UiDependency> rows = doc.archive().dependencies().entries();
        if (rows.isEmpty()) {
            EditorWidgets.emptyState("No dependencies", "Components, textures, shared sheets and scripts the document "
                + "uses are listed here with where they resolve from.");
            return;
        }
        if (resolvedFor != doc.archive() || resolvedEpoch != ctx.assetEpoch()) {
            resolvedEpoch = ctx.assetEpoch();
            try {
                resolution = AssetResolver.forDocument(doc.archive(), ctx.project.sources()).resolveAll();
            } catch (RuntimeException e) {
                resolution = null;
            }
            resolvedFor = doc.archive();
        }
        ImGui.textDisabled("Embedded rows are snapshots inside the document; shared rows resolve by id (project, then the game).");
        if (ImGui.beginTable("##deps", 5, ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp
                | ImGuiTableFlags.BordersInnerV | ImGuiTableFlags.Resizable)) {
            ImGui.tableSetupColumn("Id", ImGuiTableColumnFlags.WidthStretch, 1.6f);
            ImGui.tableSetupColumn("Kind", ImGuiTableColumnFlags.WidthFixed, 80);
            ImGui.tableSetupColumn("Mode", ImGuiTableColumnFlags.WidthFixed, 82);
            ImGui.tableSetupColumn("Resolves From", ImGuiTableColumnFlags.WidthStretch, 1.3f);
            ImGui.tableSetupColumn("", ImGuiTableColumnFlags.WidthFixed, 26);
            ImGui.tableHeadersRow();
            for (UiDependency d : rows) {
                ImGui.tableNextRow();
                ImGui.tableNextColumn();
                ImGui.textUnformatted(d.id());
                ImGui.tableNextColumn();
                ImGui.textDisabled(d.kind().wire());
                ImGui.tableNextColumn();
                boolean embedded = d.mode() == UiDependency.Mode.EMBEDDED;
                EditorWidgets.badge(embedded ? "embedded" : "shared", embedded ? Glyphs.rgba(0.86f, 0.62f, 0.30f, 1f)
                    : Glyphs.rgba(0.40f, 0.72f, 0.95f, 1f));
                ImGui.tableNextColumn();
                ResolvedAsset r = resolution == null ? null : resolution.get(d.id());
                if (r != null) {
                    ImGui.textDisabled(r.describe());
                    if (!embedded && d.sha256() != null && !d.sha256().equals(r.sha256())) {
                        ImGui.sameLine();
                        EditorWidgets.badge("changed", Glyphs.rgba(0.95f, 0.75f, 0.30f, 1f));
                        if (ImGui.isItemHovered()) {
                            ImGui.setTooltip("The shared file changed since this document recorded it (it already"
                                + " draws the new version). Actions > Accept Current Version records it.");
                        }
                    }
                } else if (resolution != null && resolution.fallbacks().containsKey(d.id())) {
                    ThemeColors.push(imgui.flag.ImGuiCol.Text, ThemeColors.Tone.WARNING);
                    ImGui.textUnformatted("fallback: " + resolution.fallbacks().get(d.id()));
                    ImGui.popStyleColor();
                } else {
                    ThemeColors.push(imgui.flag.ImGuiCol.Text, ThemeColors.Tone.ERROR);
                    ImGui.textUnformatted("MISSING" + (d.sourceHint() != null ? "  (hint " + d.sourceHint() + ")" : ""));
                    ImGui.popStyleColor();
                }
                ImGui.tableNextColumn();
                if (EditorWidgets.iconButton("##depmenu" + d.id(), ImGui.getFrameHeight(), (dl, x, y, s, c) -> {
                    for (int i = 0; i < 3; i++) {
                        dl.addCircleFilled(x + s / 2f, y + s * (0.2f + i * 0.3f), s * 0.08f, c);
                    }
                }, "Actions", false, true)) {
                    ImGui.openPopup("##depactions" + d.id());
                }
                if (ImGui.beginPopup("##depactions" + d.id())) {
                    depActions(doc, d, embedded);
                    ImGui.endPopup();
                }
            }
            ImGui.endTable();
        }
        if (relinkId != null) {
            relinkRow(doc);
        }
    }

    private void depActions(UiEditorDocument doc, UiDependency d, boolean embedded) {
        ProjectAssetSource project = ctx.project.projectSource();
        if (!embedded && ImGui.menuItem("Embed Snapshot")) {
            edit(doc, () -> EmbedOperations.embed(doc.archive(), d.id(), ctx.project.sources()));
        }
        if (embedded && ImGui.menuItem("Refresh Snapshot from Project")) {
            edit(doc, () -> EmbedOperations.refresh(doc.archive(), d.id(), ctx.project.sources()));
        }
        if (embedded && project != null && ImGui.beginMenu("Extract to Project")) {
            if (ImGui.menuItem("Keep the project's copy if different")) {
                edit(doc, () -> ExtractOperations.extractToProject(doc.archive(), d.id(), project, CollisionPolicy.KEEP_PROJECT));
            }
            if (ImGui.menuItem("Replace the project's copy")) {
                edit(doc, () -> ExtractOperations.extractToProject(doc.archive(), d.id(), project, CollisionPolicy.REPLACE));
            }
            if (ImGui.menuItem("Fail on a different copy")) {
                edit(doc, () -> ExtractOperations.extractToProject(doc.archive(), d.id(), project, CollisionPolicy.FAIL));
            }
            ImGui.endMenu();
        }
        if (!embedded && project != null && ImGui.menuItem("Relink to Project File...")) {
            relinkId = d.id();
            relinkPath.set(d.sourceHint() == null ? "" : d.sourceHint());
        }
        boolean image = d.kind() == UiDependency.Kind.TEXTURE || d.kind() == UiDependency.Kind.IMAGE;
        if (image && ImGui.menuItem("Edit Texture")) {
            ctx.editTexture(d.id());
        }
        if (image && ImGui.menuItem("Slice into Sprites...")) {
            try {
                if (ctx.actions.run(com.openmason.main.systems.uiEditor.service.UiImageAssets.newSheet(doc.archive(),
                    ctx.project, d.id()))) {
                    ctx.editSheetRequest = d.id() + "_sprites";
                }
            } catch (java.io.IOException | com.openmason.main.systems.uiEditor.command.UiCommandException e) {
                doc.setLastMessage(e.getMessage());
            }
        }
        if (d.kind() == UiDependency.Kind.SPRITES && ImGui.menuItem("Edit Sprites")) {
            ctx.editSheetRequest = d.id();
        }
        ResolvedAsset resolved = resolution == null ? null : resolution.get(d.id());
        if (!embedded && project != null && resolved != null && resolved.origin()
            == com.openmason.engine.ui.assets.AssetOrigin.PROJECT && !resolved.sha256().equals(d.sha256())
            && ImGui.menuItem("Accept Current Version")) {
            String location = resolved.location();
            edit(doc, () -> RelinkOperations.relink(doc.archive(), d.id(), location, project));
        }
        if (ImGui.menuItem("Copy Id")) {
            ImGui.setClipboardText(d.id());
        }
    }

    private void relinkRow(UiEditorDocument doc) {
        ImGui.separator();
        ImGui.textUnformatted("Relink " + relinkId + " to project path:");
        ImGui.setNextItemWidth(-150);
        ImGui.inputTextWithHint("##relink", "UI/stonebreak/ui/textures/panel.sbt", relinkPath);
        ImGui.sameLine();
        ProjectAssetSource project = ctx.project.projectSource();
        if (ImGui.button("Relink") && project != null) {
            String id = relinkId;
            String path = relinkPath.get().trim();
            edit(doc, () -> RelinkOperations.relink(doc.archive(), id, path, project));
            relinkId = null;
        }
        ImGui.sameLine();
        if (ImGui.button("Cancel")) {
            relinkId = null;
        }
    }

    @FunctionalInterface
    private interface Planner {
        AssetEdit plan() throws UiFormatException, java.io.IOException;
    }

    private void edit(UiEditorDocument doc, Planner planner) {
        try {
            AssetEdit e = planner.plan();
            ctx.actions.run(DocumentCommands.assetEdit(e));
            if (!e.diagnostics().isEmpty() && doc.lastMessage() == null) {
                doc.setLastMessage(e.diagnostics().getFirst().message());
            }
            resolvedFor = null;
        } catch (UiFormatException ex) {
            doc.setLastMessage(ex.diagnostics().isEmpty() ? ex.getMessage() : ex.diagnostics().getFirst().message());
        } catch (java.io.IOException | RuntimeException ex) {
            doc.setLastMessage(ex.getMessage());
        }
    }
}
