package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.ui.data.FixtureHost;
import com.openmason.engine.ui.script.UiScriptConsole;
import com.openmason.engine.ui.script.UiScriptDiagnostic;
import com.openmason.engine.ui.script.UiScriptRuntime;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.UiDiagnosticsService;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import com.openmason.main.systems.uiEditor.view.widgets.Glyphs;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImBoolean;

import java.util.List;
import java.util.Locale;

/**
 * Problems of the active document from every layer (format, assets, runtime, references,
 * scripts), click to select the element; plus, while previewing, the script console, the host
 * requests scripts made and the fixture actions they called.
 */
final class DiagnosticsPanel {

    static final String TITLE = "Diagnostics###uiDiagnostics";

    private final UiEditorContext ctx;
    private final UiDiagnosticsService service;
    private final ImBoolean errors = new ImBoolean(true);
    private final ImBoolean warnings = new ImBoolean(true);
    private final ImBoolean infos = new ImBoolean(false);
    private int consoleSeen;

    DiagnosticsPanel(UiEditorContext ctx) {
        this.ctx = ctx;
        this.service = new UiDiagnosticsService(ctx.project);
    }

    /** Error count of the active document (status bar). */
    int errorCount() {
        UiEditorDocument doc = ctx.doc();
        if (doc == null) {
            return 0;
        }
        return (int) service.findings(doc.archive(), ctx.instance()).stream()
            .filter(f -> f.severity() == UiDiagnosticsService.Severity.ERROR).count();
    }

    void render() {
        if (!ImGui.begin(TITLE)) {
            ImGui.end();
            return;
        }
        ctx.noteFocus();
        UiEditorDocument doc = ctx.doc();
        if (doc == null) {
            EditorWidgets.emptyState("No document", null);
            ImGui.end();
            return;
        }
        if (ImGui.beginTabBar("##diagTabs")) {
            List<UiDiagnosticsService.Finding> all = service.findings(doc.archive(), ctx.instance());
            if (ImGui.beginTabItem("Problems (" + all.size() + ")###problems")) {
                problems(doc, all);
                ImGui.endTabItem();
            }
            DesignerRuntime rt = ctx.runtime();
            if (rt != null && rt.mode() == DesignerRuntime.Mode.PREVIEW && ImGui.beginTabItem("Preview Console###console")) {
                console(rt);
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
        ImGui.end();
    }

    private void problems(UiEditorDocument doc, List<UiDiagnosticsService.Finding> all) {
        ImGui.checkbox("Errors", errors);
        ImGui.sameLine();
        ImGui.checkbox("Warnings", warnings);
        ImGui.sameLine();
        ImGui.checkbox("Info", infos);
        if (all.isEmpty()) {
            ImGui.spacing();
            ThemeColors.push(imgui.flag.ImGuiCol.Text, ThemeColors.Tone.SUCCESS);
            ImGui.textUnformatted("No problems: the document validates, resolves and runs cleanly.");
            ImGui.popStyleColor();
            return;
        }
        if (ImGui.beginTable("##problems", 4, ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp
                | ImGuiTableFlags.ScrollY | ImGuiTableFlags.BordersInnerV | ImGuiTableFlags.Resizable)) {
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableSetupColumn("", ImGuiTableColumnFlags.WidthFixed, ImGui.getTextLineHeight() + 4);
            ImGui.tableSetupColumn("Source", ImGuiTableColumnFlags.WidthFixed, 90);
            ImGui.tableSetupColumn("Element", ImGuiTableColumnFlags.WidthFixed, 120);
            ImGui.tableSetupColumn("Message", ImGuiTableColumnFlags.WidthStretch);
            ImGui.tableHeadersRow();
            int i = 0;
            for (UiDiagnosticsService.Finding f : all) {
                if (!show(f.severity())) {
                    continue;
                }
                ImGui.tableNextRow();
                ImGui.tableNextColumn();
                ImDrawList dl = ImGui.getWindowDrawList();
                float x = ImGui.getCursorScreenPosX();
                float y = ImGui.getCursorScreenPosY();
                float s = ImGui.getTextLineHeight();
                switch (f.severity()) {
                    case ERROR -> Glyphs.error(dl, x, y, s, ThemeColors.u32(ThemeColors.Tone.ERROR, 1f));
                    case WARNING -> Glyphs.warning(dl, x, y, s, ThemeColors.u32(ThemeColors.Tone.WARNING, 1f));
                    case INFO -> Glyphs.info(dl, x, y, s, EditorWidgets.dim(1f));
                }
                ImGui.dummy(s, s);
                ImGui.tableNextColumn();
                ImGui.textDisabled(f.source().name().toLowerCase(Locale.ROOT));
                ImGui.tableNextColumn();
                ImGui.textUnformatted(f.element() == null ? "" : f.element());
                ImGui.tableNextColumn();
                if (ImGui.selectable(f.message() + "##f" + i++, false, imgui.flag.ImGuiSelectableFlags.SpanAllColumns)) {
                    if (f.element() != null) {
                        doc.select(List.of(f.element()));
                        ctx.frameSelectionRequest = true;
                    }
                    if (f.source() == UiDiagnosticsService.Source.SCRIPT && f.line() > 0) {
                        ctx.jumpLine = f.line();
                        ctx.focusWindow = ScriptPanel.TITLE;
                    }
                }
                if (ImGui.isItemHovered() && f.detail() != null && !f.detail().isEmpty()) {
                    ImGui.setTooltip(f.detail());
                }
            }
            ImGui.endTable();
        }
    }

    private boolean show(UiDiagnosticsService.Severity s) {
        return switch (s) {
            case ERROR -> errors.get();
            case WARNING -> warnings.get();
            case INFO -> infos.get();
        };
    }

    private void console(DesignerRuntime rt) {
        for (UiDiagnostic d : rt.activationFindings()) {
            ThemedLine.warning("activation: " + d.message());
        }
        UiScriptRuntime scripts = rt.scripts();
        if (scripts != null) {
            ImGui.textDisabled(String.format(Locale.ROOT, "Modules %s   Lua heap %d KiB   %d calls   last frame %.1f us",
                scripts.modules(), scripts.memoryUsed() / 1024, scripts.calls(), scripts.lastUpdateNanos() / 1e3));
            ImGui.sameLine();
            if (ImGui.smallButton("Clear")) {
                scripts.console().clear();
            }
            for (UiScriptDiagnostic d : scripts.diagnostics()) {
                ThemedLine.error(d.location() + "  " + d.headline());
            }
        }
        for (String r : rt.requests()) {
            ImGui.textDisabled("request  " + r);
        }
        FixtureHost fx = rt.fixtures();
        if (fx != null) {
            for (FixtureHost.Call c : fx.calls()) {
                ImGui.textDisabled("action   " + c.actionId() + " " + c.args());
            }
        }
        if (scripts != null && ImGui.beginChild("##console", 0, 0, true)) {
            List<UiScriptConsole.Entry> entries = scripts.console().entries();
            for (UiScriptConsole.Entry e : entries) {
                String line = String.format(Locale.ROOT, "%7.2f  %-5s %s: %s", e.time(), e.level(), e.source(),
                    e.message().split("\n", 2)[0]);
                switch (e.level()) {
                    case ERROR -> ThemedLine.error(line);
                    case WARN -> ThemedLine.warning(line);
                    default -> ImGui.textUnformatted(line);
                }
                if (ImGui.isItemHovered() && e.message().contains("\n")) {
                    ImGui.setTooltip(e.message());
                }
            }
            if (entries.size() != consoleSeen) {
                ImGui.setScrollHereY(1f);
                consoleSeen = entries.size();
            }
        }
        if (scripts != null) {
            ImGui.endChild();
        }
    }

    /** Toned single lines. */
    private static final class ThemedLine {
        static void error(String s) {
            ThemeColors.push(imgui.flag.ImGuiCol.Text, ThemeColors.Tone.ERROR);
            ImGui.textUnformatted(s);
            ImGui.popStyleColor();
        }

        static void warning(String s) {
            ThemeColors.push(imgui.flag.ImGuiCol.Text, ThemeColors.Tone.WARNING);
            ImGui.textUnformatted(s);
            ImGui.popStyleColor();
        }
    }
}
