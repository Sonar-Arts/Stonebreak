package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.ui.script.UiApiCatalog;
import com.openmason.engine.ui.script.UiApiStubs;
import com.openmason.engine.ui.script.UiScriptChecker;
import com.openmason.engine.ui.script.UiScriptDiagnostic;
import com.openmason.engine.ui.script.UiScriptRuntime;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.uiEditor.command.DocumentCommands;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import com.openmason.main.systems.uiEditor.view.widgets.Glyphs;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiInputTextCallbackData;
import imgui.callback.ImGuiInputTextCallback;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lua code-behind, minimal but real: pick or create the document's module, edit it with line
 * numbers, inline diagnostics from the static checker (and the running preview), completion of
 * the {@code ui} API from the same catalog the LuaLS stubs are generated from, and Apply (one
 * undo step) that hot-reloads the preview. Shared modules are shown read-only.
 */
final class ScriptPanel {

    static final String TITLE = "Script###uiScript";
    private static final Pattern UI_MEMBER = Pattern.compile("\\bui\\.([A-Za-z_]*)$");
    private static final Pattern METHOD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*\\)?:([A-Za-z_]*)$");
    private static final Pattern HOOK = Pattern.compile("^\\s*function\\s+(?:[A-Za-z_][A-Za-z0-9_]*\\.)?([A-Za-z_]*)$");

    private final UiEditorContext ctx;
    private final ImString buffer = new ImString(1 << 16);
    private final ImString newModule = new ImString(64);
    private UiEditorDocument loadedDoc;
    private String module;
    private String loadedSource;
    private boolean readOnly;
    private boolean edited;
    private double checkIn = -1;
    private List<UiScriptDiagnostic> diagnostics = List.of();
    // completion
    private int cursor;
    private String beforeCursor = "";
    private List<UiApiCatalog.Member> completions = List.of();
    private int completionPrefix;
    private int completionIndex;
    private String pendingInsert;
    private int pendingDelete;
    private int pendingCursor = -1;
    private boolean refocus;
    private float scrollTo = -1;
    private String status;

    private final ImGuiInputTextCallback callback = new ImGuiInputTextCallback() {
        @Override
        public void accept(ImGuiInputTextCallbackData data) {
            if (pendingInsert != null) {
                int at = Math.max(0, data.getCursorPos() - pendingDelete);
                data.deleteChars(at, pendingDelete);
                data.insertChars(at, pendingInsert);
                pendingInsert = null;
                pendingDelete = 0;
            }
            if (pendingCursor >= 0) {
                data.setCursorPos(Math.min(pendingCursor, data.getBufTextLen()));
                data.setSelectionStart(data.getCursorPos());
                data.setSelectionEnd(data.getCursorPos());
                pendingCursor = -1;
            }
            if (data.getEventFlag() == ImGuiInputTextFlags.CallbackCompletion) {
                if (!completions.isEmpty()) {
                    String name = completions.get(Math.min(completionIndex, completions.size() - 1)).name();
                    int at = data.getCursorPos() - completionPrefix;
                    data.deleteChars(at, completionPrefix);
                    data.insertChars(at, name);
                } else {
                    data.insertChars(data.getCursorPos(), "    ");
                }
            }
            cursor = data.getCursorPos();
            String buf = data.getBuf();
            beforeCursor = buf.substring(0, Math.min(byteToChar(buf, cursor), buf.length()));
        }
    };

    ScriptPanel(UiEditorContext ctx) {
        this.ctx = ctx;
        buffer.inputData.isResizable = true;
    }

    /** True when the editor holds text not yet applied to the document. */
    boolean hasUnapplied() {
        return edited;
    }

    /** True when the editor holds text not yet applied to {@code doc}. */
    boolean hasUnapplied(UiEditorDocument doc) {
        return edited && doc != null && doc == loadedDoc;
    }

    /**
     * Applies pending text (Save calls this first so a save never misses typed code). Text that
     * conflicts with a newer version of the module (an agent, a graph conversion or undo changed
     * it meanwhile) is never applied silently: it stays here until the author resolves it.
     */
    void applyPending() {
        if (edited && module != null && !readOnly && loadedDoc == ctx.doc()) {
            if (conflicted(loadedDoc)) {
                loadedDoc.setLastMessage("Unapplied Lua for " + module + " conflicts with a newer version of the"
                    + " module and was not saved: resolve it in the Script panel");
                return;
            }
            apply();
        }
    }

    /** Applies pending text to {@code doc} whether or not it is active (automation, close). */
    void flush(UiEditorDocument doc) {
        if (doc == ctx.doc()) {
            applyPending();
        } else if (hasUnapplied(doc) && !conflicted(doc)) {
            applyPendingFor(doc);
        }
    }

    /** The module changed in the document since this panel loaded it, while it holds edits. */
    private boolean conflicted(UiEditorDocument doc) {
        if (!edited || module == null || readOnly || doc == null) {
            return false;
        }
        String src = doc.archive().scripts().get(module);
        return !java.util.Objects.equals(src, loadedSource);
    }

    void render() {
        if (!ImGui.begin(TITLE)) {
            ImGui.end();
            return;
        }
        UiEditorDocument doc = ctx.doc();
        if (doc == null) {
            EditorWidgets.emptyState("No document", null);
            ImGui.end();
            return;
        }
        if (doc != loadedDoc) {
            if (conflicted(loadedDoc)) {
                loadedDoc.setLastMessage("Unapplied Lua for " + module + " was dropped: the module changed in the"
                    + " document meanwhile (the document keeps the newer version)");
                edited = false;
            }
            applyPendingFor(loadedDoc);
            loadedDoc = doc;
            module = doc.archive().document().codeBehind();
            loadModule(doc);
        }
        toolbar(doc);
        if (module == null) {
            EditorWidgets.emptyState("No code-behind", "Lua code-behind runs on open, handles events and drives "
                + "animations, in the preview and in the game alike. Create a module above to start.");
            ImGui.end();
            return;
        }
        if (!edited && !readOnly) {
            String src = doc.archive().scripts().get(module);
            if (src != null && !src.equals(loadedSource)) {
                loadModule(doc); // undo/redo or a graph conversion changed it
            }
        } else if (conflicted(doc)) {
            conflictBanner(doc);
        }
        checkIn -= ImGui.getIO().getDeltaTime();
        if (checkIn < 0 && checkIn > -1) {
            diagnostics = UiScriptChecker.check(buffer.get(), module + ".lua");
            checkIn = -1;
        }
        editor(doc);
        ImGui.end();
    }

    private void applyPendingFor(UiEditorDocument doc) {
        if (doc != null && edited && module != null && !readOnly) {
            doc.execute(DocumentCommands.setScript(module, buffer.get()));
            doc.endInteraction();
        }
        edited = false;
    }

    private void loadModule(UiEditorDocument doc) {
        edited = false;
        diagnostics = List.of();
        if (module == null) {
            buffer.set("");
            loadedSource = null;
            return;
        }
        String src = doc.archive().scripts().get(module);
        readOnly = src == null;
        if (src == null) {
            var ui = ctx.instance();
            src = ui == null ? null : ui.context().source().script(module);
            if (src == null) {
                src = "-- Shared module " + module + " could not be resolved.";
            }
        }
        loadedSource = src;
        buffer.set(src, true, Math.max(1 << 16, src.length() * 2));
        diagnostics = readOnly ? List.of() : UiScriptChecker.check(src, module + ".lua");
    }

    private void toolbar(UiEditorDocument doc) {
        String codeBehind = doc.archive().document().codeBehind();
        ImGui.alignTextToFramePadding();
        ImGui.textDisabled("Code-behind");
        ImGui.sameLine();
        ImGui.setNextItemWidth(200);
        if (ImGui.beginCombo("##module", codeBehind == null ? "(none)" : codeBehind)) {
            if (ImGui.selectable("(none)", codeBehind == null)) {
                applyPending();
                ctx.actions.run(DocumentCommands.setCodeBehind(null));
                module = null;
                loadModule(doc);
            }
            for (String id : doc.archive().scripts().keySet()) {
                if (ImGui.selectable(id + "  (embedded)", id.equals(codeBehind))) {
                    applyPending();
                    ctx.actions.run(DocumentCommands.setCodeBehind(id));
                    module = id;
                    loadModule(doc);
                }
            }
            for (UiDependency d : doc.archive().dependencies().entries()) {
                if (d.kind() == UiDependency.Kind.SCRIPT && ImGui.selectable(d.id() + "  (shared)", d.id().equals(codeBehind))) {
                    applyPending();
                    ctx.actions.run(DocumentCommands.setCodeBehind(d.id()));
                    module = d.id();
                    loadModule(doc);
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine();
        if (codeBehind == null) {
            ImGui.setNextItemWidth(140);
            if (newModule.get().isEmpty()) {
                newModule.set(defaultModuleId(doc));
            }
            ImGui.inputText("##newModule", newModule);
            ImGui.sameLine();
            ThemeColors.pushAccentButton();
            if (ImGui.button("Create Module")) {
                String id = newModule.get().trim();
                if (ctx.actions.run(DocumentCommands.createCodeBehind(id, template()))) {
                    module = id;
                    loadModule(doc);
                    newModule.set("");
                }
            }
            ImGui.popStyleColor(4);
            return;
        }
        // module selected
        ImGui.beginDisabled(!edited || readOnly);
        ThemeColors.pushAccentButton();
        if (ImGui.button("Apply")) {
            apply();
        }
        ImGui.popStyleColor(4);
        ImGui.endDisabled();
        if (ImGui.isItemHovered(imgui.flag.ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip("Write the module into the document (one undo step) and hot-reload the preview (Ctrl+Enter)");
        }
        ImGui.sameLine();
        if (ImGui.button("Check")) {
            diagnostics = UiScriptChecker.check(buffer.get(), module + ".lua");
            status = diagnostics.isEmpty() ? "No problems found" : diagnostics.size() + " finding(s)";
        }
        ImGui.sameLine();
        if (ImGui.button("LuaLS Stubs")) {
            writeStubs(doc);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Write types/ui.d.lua and .luarc.json next to the document for completion in external editors");
        }
        ImGui.sameLine();
        int errors = (int) diagnostics.stream().filter(d -> d.severity() == UiScriptDiagnostic.Severity.ERROR).count();
        if (readOnly) {
            EditorWidgets.badge("shared module: read-only", Glyphs.rgba(0.40f, 0.72f, 0.95f, 1f));
        } else if (edited) {
            EditorWidgets.badge("not applied", ThemeColors.u32(ThemeColors.Tone.WARNING, 1f));
        } else if (errors > 0) {
            EditorWidgets.badge(errors + " error" + (errors == 1 ? "" : "s"), ThemeColors.u32(ThemeColors.Tone.ERROR, 1f));
        } else {
            EditorWidgets.badge("applied", ThemeColors.u32(ThemeColors.Tone.SUCCESS, 1f));
        }
        if (status != null) {
            ImGui.sameLine();
            ImGui.textDisabled(status);
        }
    }

    private static String defaultModuleId(UiEditorDocument doc) {
        String id = doc.archive().manifest().documentId();
        String stem = id.substring(id.lastIndexOf('/') + 1).replaceAll("[^a-z0-9_-]", "_");
        return OmuiFormat.PART_ID.matcher(stem).matches() ? stem : "main";
    }

    private static String template() {
        return """
            ---@type ui.Module
            local M = {}

            function M.on_open(ui)
              -- runs when the screen opens; may ui.await host actions
            end

            function M.update(dt)
            end

            return M
            """;
    }

    /** Shown while the typed text and a newer document version of the module disagree. */
    private void conflictBanner(UiEditorDocument doc) {
        ImGui.pushStyleColor(ImGuiCol.Text, ThemeColors.u32(ThemeColors.Tone.WARNING, 1f));
        ImGui.textWrapped("The module changed in the document (undo, an agent or the graph editor) while you were"
            + " typing here. Apply is paused so neither version is lost.");
        ImGui.popStyleColor();
        if (ImGui.button("Reload Module (discard my text)")) {
            loadModule(doc);
        }
        ImGui.sameLine();
        if (ImGui.button("Keep My Text")) {
            loadedSource = doc.archive().scripts().get(module); // the next Apply replaces the newer version
            status = "Your text will replace the newer version on Apply";
        }
    }

    private void apply() {
        if (module == null || readOnly) {
            return;
        }
        if (conflicted(ctx.doc())) {
            status = "Not applied: the module changed meanwhile (see above)";
            return;
        }
        ctx.actions.run(DocumentCommands.setScript(module, buffer.get()));
        ctx.actions.endInteraction();
        loadedSource = buffer.get();
        edited = false;
        diagnostics = UiScriptChecker.check(buffer.get(), module + ".lua");
        status = "Applied" + (ctx.runtime() != null && ctx.runtime().mode() == DesignerRuntime.Mode.PREVIEW
            ? " and hot-reloaded into the preview" : "");
    }

    private void writeStubs(UiEditorDocument doc) {
        Path dir = doc.file() != null ? doc.file().toAbsolutePath().getParent() : ctx.project.uiDir();
        if (dir == null) {
            status = "Save the document first";
            return;
        }
        try {
            UiApiStubs.write(dir);
            status = "Wrote " + UiApiStubs.STUB_FILE + " in " + dir.getFileName();
        } catch (Exception e) {
            status = "Cannot write stubs: " + e.getMessage();
        }
    }

    // ── editor ──────────────────────────────────────────────────────────────

    private void editor(UiEditorDocument doc) {
        List<UiScriptDiagnostic> all = new ArrayList<>(diagnostics);
        UiScriptRuntime rt = ctx.runtime() == null ? null : ctx.runtime().scripts();
        if (rt != null) {
            for (UiScriptDiagnostic d : rt.diagnostics()) {
                if (d.chunk() != null && d.chunk().startsWith(module)) {
                    all.add(d);
                }
            }
        }
        float listH = all.isEmpty() ? 0 : Math.min(5, all.size()) * ImGui.getTextLineHeightWithSpacing() + 8;
        float h = ImGui.getContentRegionAvailY() - listH;
        String text = buffer.get();
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        float lineH = ImGui.getTextLineHeight();
        float gutter = ImGui.calcTextSize(String.valueOf(Math.max(99, lines))).x + 22;
        ImGui.pushStyleColor(ImGuiCol.ChildBg, ImGui.getStyle().getColor(ImGuiCol.FrameBg));
        if (ImGui.beginChild("##code", 0, Math.max(80, h), true, ImGuiWindowFlags.HorizontalScrollbar)) {
            ImDrawList dl = ImGui.getWindowDrawList();
            float x0 = ImGui.getCursorScreenPosX();
            float y0 = ImGui.getCursorScreenPosY();
            float padY = ImGui.getStyle().getFramePaddingY();
            for (int i = 0; i < lines; i++) {
                float ly = y0 + padY + i * lineH;
                if (ly + lineH < ImGui.getWindowPosY() || ly > ImGui.getWindowPosY() + ImGui.getWindowHeight()) {
                    continue;
                }
                String n = String.valueOf(i + 1);
                dl.addText(x0 + gutter - 12 - ImGui.calcTextSize(n).x, ly, EditorWidgets.dim(0.8f), n);
            }
            dl.addLine(x0 + gutter - 6, y0, x0 + gutter - 6, y0 + Math.max(h, lines * lineH + 40), EditorWidgets.border(0.6f), 1f);
            for (UiScriptDiagnostic d : all) {
                if (d.line() < 1) {
                    continue;
                }
                float ly = y0 + padY + (d.line() - 1) * lineH;
                int col = d.severity() == UiScriptDiagnostic.Severity.ERROR ? ThemeColors.u32(ThemeColors.Tone.ERROR, 1f)
                    : ThemeColors.u32(ThemeColors.Tone.WARNING, 1f);
                dl.addCircleFilled(x0 + 6, ly + lineH / 2f, 3.5f, col);
                squiggle(dl, x0 + gutter, ly + lineH - 1, x0 + gutter + lineWidth(text, d.line()), col);
                if (ImGui.isMouseHoveringRect(x0, ly, x0 + gutter, ly + lineH)) {
                    ImGui.setTooltip(d.message());
                }
            }
            ImGui.setCursorScreenPos(x0 + gutter, y0);
            if (refocus) {
                ImGui.setKeyboardFocusHere();
                refocus = false;
            }
            float editW = Math.max(ImGui.getContentRegionAvailX(), longestLine(text) + 40);
            int flags = ImGuiInputTextFlags.CallbackAlways | ImGuiInputTextFlags.CallbackCompletion
                | ImGuiInputTextFlags.NoHorizontalScroll | (readOnly ? ImGuiInputTextFlags.ReadOnly : 0);
            ImGui.pushStyleColor(ImGuiCol.FrameBg, 0, 0, 0, 0);
            boolean changed = ImGui.inputTextMultiline("##lua", buffer, editW, lines * lineH + padY * 2 + lineH * 6, flags,
                callback);
            ImGui.popStyleColor();
            boolean active = ImGui.isItemActive();
            float inputX = ImGui.getItemRectMinX();
            float inputY = ImGui.getItemRectMinY();
            if (changed && !readOnly) {
                edited = !buffer.get().equals(loadedSource);
                checkIn = 0.35;
            }
            if (active && ImGui.getIO().getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.Enter)) {
                apply();
            }
            if (active && ImGui.getIO().getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.S, false)) {
                apply();
                ctx.saveRequest = true; // the editor owns the keyboard, so the workspace saves for it
            }
            if (ImGui.isItemDeactivated() && edited && !conflicted(doc)) {
                apply(); // leaving the editor applies, so the canvas never lags the code
            }
            // keep the caret visible inside the scrolling child
            int caretLine = countLines(beforeCursor);
            float caretY = inputY + padY + caretLine * lineH;
            if (active) {
                float top = ImGui.getWindowPosY();
                float bottom = top + ImGui.getWindowHeight() - lineH * 2;
                if (caretY < top + lineH) {
                    ImGui.setScrollY(ImGui.getScrollY() - (top + lineH - caretY));
                } else if (caretY > bottom) {
                    ImGui.setScrollY(ImGui.getScrollY() + (caretY - bottom));
                }
            }
            if (scrollTo >= 0) {
                ImGui.setScrollY(scrollTo);
                scrollTo = -1;
            }
            completion(active, inputX, caretY, lineH);
        }
        ImGui.endChild();
        ImGui.popStyleColor();
        if (ctx.jumpLine > 0) {
            jumpTo(ctx.jumpLine, lineH);
            ctx.jumpLine = -1;
        }
        for (UiScriptDiagnostic d : all.subList(0, Math.min(all.size(), 5))) {
            int col = d.severity() == UiScriptDiagnostic.Severity.ERROR ? ThemeColors.u32(ThemeColors.Tone.ERROR, 1f)
                : ThemeColors.u32(ThemeColors.Tone.WARNING, 1f);
            ImGui.pushStyleColor(ImGuiCol.Text, ImGui.colorConvertU32ToFloat4(col));
            if (ImGui.selectable(d.location() + "  " + d.headline() + "##diag" + d.line() + d.code())) {
                jumpTo(d.line(), lineH);
            }
            ImGui.popStyleColor();
        }
    }

    private void jumpTo(int line, float lineH) {
        String text = buffer.get();
        int pos = 0;
        for (int l = 1; l < line && pos >= 0; l++) {
            pos = text.indexOf('\n', pos);
            if (pos >= 0) {
                pos++;
            }
        }
        pendingCursor = Math.max(0, charToByte(text, Math.max(0, pos)));
        refocus = true;
        scrollTo = Math.max(0, (line - 4) * lineH);
    }

    private void completion(boolean active, float inputX, float caretY, float lineH) {
        completions = List.of();
        if (!active || readOnly) {
            return;
        }
        String line = beforeCursor.substring(beforeCursor.lastIndexOf('\n') + 1);
        String prefix = null;
        List<UiApiCatalog.Member> pool = List.of();
        Matcher m = UI_MEMBER.matcher(line);
        if (m.find()) {
            prefix = m.group(1);
            pool = UiApiCatalog.of("ui");
        } else if ((m = METHOD.matcher(line)).find()) {
            prefix = m.group(1);
            pool = new ArrayList<>();
            pool.addAll(UiApiCatalog.of("ui.Element"));
            pool.addAll(UiApiCatalog.of("ui.Canvas"));
            pool.addAll(UiApiCatalog.of("ui.Handle"));
        } else if ((m = HOOK.matcher(line)).find()) {
            prefix = m.group(1);
            pool = UiApiCatalog.of("module");
        }
        if (prefix == null) {
            return;
        }
        String p = prefix.toLowerCase(Locale.ROOT);
        List<UiApiCatalog.Member> found = new ArrayList<>();
        for (UiApiCatalog.Member mem : pool) {
            if (mem.name().toLowerCase(Locale.ROOT).startsWith(p) && found.stream().noneMatch(x -> x.name().equals(mem.name()))) {
                found.add(mem);
            }
        }
        if (found.isEmpty() || found.size() == 1 && found.getFirst().name().equals(prefix)) {
            return;
        }
        completions = found;
        completionPrefix = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        completionIndex = Math.min(completionIndex, found.size() - 1);
        int col = line.length();
        float charW = ImGui.calcTextSize("M").x;
        float px = inputX + ImGui.getStyle().getFramePaddingX() + col * charW;
        float py = caretY + lineH + 2;
        ImGui.setNextWindowPos(px, py);
        ImGui.setNextWindowBgAlpha(0.97f);
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoFocusOnAppearing | ImGuiWindowFlags.NoNav | ImGuiWindowFlags.AlwaysAutoResize
            | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.Tooltip;
        if (ImGui.begin("##luaCompletion", flags)) {
            int shown = Math.min(10, found.size());
            for (int i = 0; i < shown; i++) {
                UiApiCatalog.Member mem = found.get(i);
                boolean sel = i == completionIndex;
                String sig = mem.field() ? mem.name() + ": " + mem.returns()
                    : mem.name() + "(" + mem.params() + ")" + (mem.returns().isEmpty() ? "" : " -> " + mem.returns());
                if (ImGui.selectable(sig + "##c" + i, sel)) {
                    pendingInsert = mem.name();
                    pendingDelete = completionPrefix;
                    refocus = true;
                }
                if (sel || ImGui.isItemHovered()) {
                    ImGui.sameLine();
                    ImGui.textDisabled("  " + (mem.doc().length() > 70 ? mem.doc().substring(0, 70) + "..." : mem.doc()));
                }
            }
            ImGui.textDisabled("Tab to complete");
        }
        ImGui.end();
    }

    // ── text helpers ────────────────────────────────────────────────────────

    private static void squiggle(ImDrawList dl, float x0, float y, float x1, int col) {
        float step = 3f;
        boolean up = true;
        for (float x = x0; x < x1; x += step) {
            dl.addLine(x, y + (up ? 0 : 2), Math.min(x1, x + step), y + (up ? 2 : 0), col, 1f);
            up = !up;
        }
    }

    private static float lineWidth(String text, int line) {
        String[] lines = text.split("\n", -1);
        if (line < 1 || line > lines.length) {
            return 60;
        }
        return Math.max(30, ImGui.calcTextSize(lines[line - 1]).x);
    }

    private static float longestLine(String text) {
        float best = 0;
        for (String l : text.split("\n", -1)) {
            best = Math.max(best, l.length());
        }
        return best * ImGui.calcTextSize("M").x;
    }

    private static int countLines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** UTF-8 byte offset (ImGui's cursor) to a Java char index. */
    private static int byteToChar(String s, int bytes) {
        int b = 0;
        for (int i = 0; i < s.length(); i++) {
            if (b >= bytes) {
                return i;
            }
            char c = s.charAt(i);
            b += c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isHighSurrogate(c) ? 4 : Character.isLowSurrogate(c) ? 0 : 3;
        }
        return s.length();
    }

    private static int charToByte(String s, int chars) {
        return s.substring(0, Math.min(chars, s.length())).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
