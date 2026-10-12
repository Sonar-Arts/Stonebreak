package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiSelectors;
import com.openmason.engine.format.omui.UiStyleProperties;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.style.StyleTrace;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.uiEditor.command.DocumentCommands;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import com.openmason.main.systems.uiEditor.view.widgets.Glyphs;
import com.openmason.main.systems.uiEditor.view.widgets.ValueFields;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Style sheets, USS-style: the document's sheets in precedence order, each rule as a card with
 * its selector (validated as you type), specificity and declarations. With an element selected,
 * rules that match it are marked and declarations that lose the cascade are struck through,
 * the way browser devtools show it; "Matched rules" lists the whole cascade for the selection,
 * component and shared sheets included. In-archive sheets are editable; shared sheets are shown
 * read-only (they belong to their own project file).
 */
final class StyleSheetPanel {

    static final String TITLE = "Style Sheets###uiStyles";

    private final UiEditorContext ctx;
    private String sheet;
    private final ImBoolean onlyMatching = new ImBoolean(false);
    private final ImString newSheet = new ImString(64);
    private final Map<String, ImString> selectorBufs = new HashMap<>();
    private final Map<String, String> selectorSource = new HashMap<>();
    private final ImString newProp = new ImString(64);
    private final ImString newValue = new ImString(128);
    private final ImString newVarName = new ImString(64);
    private final ImString newVarValue = new ImString(64);
    private int addingTo = -1;
    private String error;
    private int scrollToRule = -1;

    StyleSheetPanel(UiEditorContext ctx) {
        this.ctx = ctx;
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
        if (ctx.jumpSheet != null) {
            sheet = ctx.jumpSheet;
            scrollToRule = ctx.jumpRule;
            ctx.jumpSheet = null;
            ctx.jumpRule = -1;
            onlyMatching.set(false);
        }
        List<String> sheets = sheetIds(doc);
        if (sheet == null || !sheets.contains(sheet) && !"#matched".equals(sheet)) {
            sheet = sheets.isEmpty() ? "#matched" : sheets.getFirst();
        }
        float listW = Math.min(230, ImGui.getContentRegionAvailX() * 0.32f);
        if (ImGui.beginChild("##sheetList", listW, 0, true)) {
            sheetList(doc, sheets);
        }
        ImGui.endChild();
        ImGui.sameLine();
        if (ImGui.beginChild("##sheetBody")) {
            if ("#matched".equals(sheet)) {
                matched(doc);
            } else {
                sheetBody(doc, sheet);
            }
        }
        ImGui.endChild();
        ImGui.end();
    }

    private static List<String> sheetIds(UiEditorDocument doc) {
        Set<String> out = new LinkedHashSet<>(doc.archive().document().styleSheets());
        out.addAll(doc.archive().styles().keySet());
        return new ArrayList<>(out);
    }

    // ── sheet list ──────────────────────────────────────────────────────────

    private void sheetList(UiEditorDocument doc, List<String> sheets) {
        EditorWidgets.caption("Cascade");
        boolean any = ctx.doc().primary() != null;
        if (ImGui.selectable("Matched rules" + (any ? "" : " (select an element)") + "##matched", "#matched".equals(sheet))) {
            sheet = "#matched";
        }
        EditorWidgets.caption("Sheets (low to high)");
        List<String> attached = doc.archive().document().styleSheets();
        for (String id : sheets) {
            boolean local = doc.archive().styles().containsKey(id);
            boolean on = attached.contains(id);
            boolean missing = !local && resolveShared(id) == null;
            ImDrawList dl = ImGui.getWindowDrawList();
            float x = ImGui.getCursorScreenPosX();
            float y = ImGui.getCursorScreenPosY();
            float h = ImGui.getTextLineHeight();
            if (ImGui.selectable("##sh" + id, id.equals(sheet), 0, 0, h + 4)) {
                sheet = id;
            }
            int col = missing ? ThemeColors.u32(ThemeColors.Tone.ERROR, 1f) : on ? EditorWidgets.text(1f) : EditorWidgets.dim(1f);
            String badge = missing ? "missing" : !on ? "detached" : local ? "local" : "shared";
            float bw = ImGui.calcTextSize(badge).x;
            float right = x + ImGui.getContentRegionAvailX() - bw - 4;
            dl.pushClipRect(x, y, right - 6, y + h + 4, true);
            dl.addText(x + 4, y + 2, col, id);
            dl.popClipRect();
            dl.addText(right, y + 2, EditorWidgets.dim(0.8f), badge);
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(id);
            }
            if (ImGui.beginPopupContextItem("##shctx" + id)) {
                if (ImGui.menuItem(on ? "Detach" : "Attach")) {
                    ctx.actions.run(DocumentCommands.attachStyleSheet(id, !on));
                }
                if (on && ImGui.menuItem("Move Up (lower precedence)")) {
                    ctx.actions.run(DocumentCommands.moveStyleSheet(id, -1));
                }
                if (on && ImGui.menuItem("Move Down (higher precedence)")) {
                    ctx.actions.run(DocumentCommands.moveStyleSheet(id, 1));
                }
                ImGui.endPopup();
            }
        }
        ImGui.spacing();
        ImGui.setNextItemWidth(-ImGui.getFrameHeight() - 4);
        boolean enter = ImGui.inputTextWithHint("##newSheet", "new sheet id", newSheet, ImGuiInputTextFlags.EnterReturnsTrue);
        ImGui.sameLine(0, 4);
        if ((EditorWidgets.iconButton("##addSheet", ImGui.getFrameHeight(), Glyphs::plus, "Create a local style sheet",
                false, !newSheet.get().isBlank()) || enter) && !newSheet.get().isBlank()) {
            if (ctx.actions.run(DocumentCommands.addStyleSheet(newSheet.get().trim()))) {
                sheet = newSheet.get().trim();
                newSheet.set("");
            }
        }
        List<String> sharedIds = new ArrayList<>();
        for (UiDependency d : doc.archive().dependencies().entries()) {
            if (d.kind() == UiDependency.Kind.STYLESHEET && !attached.contains(d.id())) {
                sharedIds.add(d.id());
            }
        }
        for (var e : ctx.project.entries(false)) {
            if (e.kind() == com.openmason.main.systems.uiEditor.service.UiProjectContext.Entry.Kind.STYLESHEET
                    && e.documentId() != null && !attached.contains(e.documentId()) && !sharedIds.contains(e.documentId())) {
                sharedIds.add(e.documentId());
            }
        }
        if (!sharedIds.isEmpty()) {
            ImGui.setNextItemWidth(-1);
            if (ImGui.beginCombo("##attachShared", "Attach shared sheet...")) {
                for (String id : sharedIds) {
                    if (ImGui.selectable(id)) {
                        attachShared(doc, id);
                    }
                }
                ImGui.endCombo();
            }
        }
    }

    private void attachShared(UiEditorDocument doc, String id) {
        List<com.openmason.main.systems.uiEditor.command.UiCommand> steps = new ArrayList<>();
        if (doc.archive().dependencies().find(id) == null) {
            for (var e : ctx.project.entries(false)) {
                if (id.equals(e.documentId())) {
                    try {
                        steps.add(DocumentCommands.ensureDependency(ctx.project.sharedRow(id, UiDependency.Kind.STYLESHEET,
                            e.path())));
                    } catch (java.io.IOException ex) {
                        doc.setLastMessage("Cannot read " + e.relative() + ": " + ex.getMessage());
                        return;
                    }
                }
            }
        }
        steps.add(DocumentCommands.attachStyleSheet(id, true));
        ctx.actions.run(com.openmason.main.systems.uiEditor.command.UiCommand.compound("Attach " + id, steps));
        sheet = id;
    }

    private UiStyleSheet resolveShared(String id) {
        UiDocumentInstance ui = ctx.instance();
        return ui == null ? null : ui.context().source().styleSheet(id);
    }

    // ── sheet body ──────────────────────────────────────────────────────────

    private void sheetBody(UiEditorDocument doc, String id) {
        UiStyleSheet local = doc.archive().styles().get(id);
        UiStyleSheet s = local != null ? local : resolveShared(id);
        boolean editable = local != null;
        StyleTrace trace = traceOfSelection();
        ImGui.alignTextToFramePadding();
        ImGui.textUnformatted(id);
        ImGui.sameLine();
        EditorWidgets.badge(editable ? "local" : "shared, read-only", editable ? EditorWidgets.accent(1f)
            : Glyphs.rgba(0.40f, 0.72f, 0.95f, 1f));
        ImGui.sameLine();
        ImGui.checkbox("Only rules matching the selection", onlyMatching);
        if (s == null) {
            ThemeColors.push(ImGuiCol.Text, ThemeColors.Tone.ERROR);
            ImGui.textWrapped("This sheet cannot be resolved. The reference is kept; check the UI Assets panel.");
            ImGui.popStyleColor();
            return;
        }
        if (!editable) {
            ImGui.textDisabled("Shared sheets are edited in their own file and affect every document that attaches them.");
        }
        if (error != null) {
            ThemeColors.push(ImGuiCol.Text, ThemeColors.Tone.ERROR);
            ImGui.textWrapped(error);
            ImGui.popStyleColor();
        }
        variables(id, s, editable);
        EditorWidgets.caption("Rules");
        for (int i = 0; i < s.rules().size(); i++) {
            UiStyleSheet.StyleRule r = s.rules().get(i);
            StyleTrace.MatchedRule match = null;
            for (StyleTrace.MatchedRule m : trace.rules()) {
                if (m.sheetId().equals(id) && m.ruleIndex() == i) {
                    match = m;
                }
            }
            if (onlyMatching.get() && match == null) {
                continue;
            }
            ruleCard(id, s, i, r, match, trace, editable);
        }
        if (editable) {
            ImGui.spacing();
            newRuleButtons(id);
        }
    }

    private void variables(String id, UiStyleSheet s, boolean editable) {
        if (s.variables().isEmpty() && !editable) {
            return;
        }
        EditorWidgets.caption("Design Tokens");
        for (Map.Entry<String, UiValue> v : s.variables().entrySet()) {
            ImGui.alignTextToFramePadding();
            float[] c = ValueFields.parseHex(v.getValue() instanceof UiValue.Str str ? str.value() : "");
            if (c[3] > 0) {
                ImGui.colorButton("##tokc" + v.getKey(), c, imgui.flag.ImGuiColorEditFlags.AlphaPreviewHalf, 14, 14);
                ImGui.sameLine();
            }
            ImGui.textUnformatted(v.getKey());
            ImGui.sameLine(DetailRows.labelWidth() + 30);
            if (editable) {
                ValueFields.Result r = ValueFields.text("var_" + id + v.getKey(), ValueFields.display(v.getValue()), "",
                    ImGui.getContentRegionAvailX() - ImGui.getFrameHeight() - 4, ValueFields::parseLoose);
                if (r.changed()) {
                    ctx.actions.run(DocumentCommands.setVariable(id, v.getKey(), r.value()));
                }
                ImGui.sameLine(0, 4);
                if (EditorWidgets.iconButton("##rmvar" + v.getKey(), ImGui.getFrameHeight(), Glyphs::cross, "Remove token",
                    false, true)) {
                    ctx.actions.run(DocumentCommands.setVariable(id, v.getKey(), null));
                }
            } else {
                ImGui.textDisabled(ValueFields.display(v.getValue()));
            }
        }
        if (editable) {
            float w = ImGui.getContentRegionAvailX();
            ImGui.setNextItemWidth(w * 0.4f);
            ImGui.inputTextWithHint("##newVar", "--token", newVarName);
            ImGui.sameLine(0, 4);
            ImGui.setNextItemWidth(w * 0.6f - ImGui.getFrameHeight() - 8);
            boolean enter = ImGui.inputTextWithHint("##newVarVal", "value (#E8D9A8, 12)", newVarValue,
                ImGuiInputTextFlags.EnterReturnsTrue);
            ImGui.sameLine(0, 4);
            String name = newVarName.get().trim();
            if (!name.startsWith("--") && !name.isEmpty()) {
                name = "--" + name;
            }
            boolean ok = UiStyleProperties.isCustom(name) && !newVarValue.get().isBlank();
            if ((EditorWidgets.iconButton("##addVar", ImGui.getFrameHeight(), Glyphs::plus, "Add token", false, ok) || enter)
                    && ok) {
                ctx.actions.run(DocumentCommands.setVariable(id, name, ValueFields.parseLoose(newVarValue.get())));
                newVarName.set("");
                newVarValue.set("");
            }
        }
    }

    private void ruleCard(String sheetId, UiStyleSheet s, int index, UiStyleSheet.StyleRule r,
                          StyleTrace.MatchedRule match, StyleTrace trace, boolean editable) {
        ImDrawList dl = ImGui.getWindowDrawList();
        ImGui.pushID(sheetId + "#" + index);
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = ImGui.getContentRegionAvailX();
        dl.channelsSplit(2);
        dl.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(x + 8, y + 6);
        ImGui.beginGroup();
        // selector line
        if (match != null) {
            int green = ThemeColors.u32(ThemeColors.Tone.SUCCESS, 1f);
            float cy = ImGui.getCursorScreenPosY() + ImGui.getFrameHeight() / 2f;
            dl.addCircleFilled(ImGui.getCursorScreenPosX() + 4, cy, 4f, green);
            ImGui.dummy(10, ImGui.getFrameHeight());
            ImGui.sameLine(0, 4);
        }
        String key = sheetId + "#" + index;
        ImString buf = selectorBufs.computeIfAbsent(key, k -> new ImString(256));
        if (!r.selector().equals(selectorSource.get(key))) {
            buf.set(r.selector());
            selectorSource.put(key, r.selector());
        }
        float selW = w - 16 - (match != null ? 14 : 0) - (editable ? ImGui.getFrameHeight() * 3 + 12 : 0) - 70;
        ImGui.setNextItemWidth(Math.max(80, selW));
        String problem = UiSelectors.problem(buf.get(), new HashSet<>(s.customStates()));
        if (problem != null) {
            ThemeColors.push(ImGuiCol.Text, ThemeColors.Tone.ERROR);
        }
        int flags = editable ? 0 : ImGuiInputTextFlags.ReadOnly;
        ImGui.inputText("##sel", buf, flags);
        if (problem != null) {
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(problem);
            }
        }
        if (editable && ImGui.isItemDeactivatedAfterEdit()) {
            if (problem == null) {
                ctx.actions.run(DocumentCommands.setRuleSelector(sheetId, index, buf.get().trim()));
            } else {
                buf.set(r.selector());
            }
        }
        ImGui.sameLine(0, 6);
        ImGui.alignTextToFramePadding();
        int spec = match != null ? match.specificity() : specificity(r.selector());
        ImGui.textDisabled(String.format("(%d,%d,%d)", spec >>> 20, spec >>> 10 & 1023, spec & 1023));
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Specificity (ids, classes and states, types). Higher wins; on a tie the later rule wins.");
        }
        if (editable) {
            ImGui.sameLine(0, 6);
            if (EditorWidgets.iconButton("##up", ImGui.getFrameHeight(), (d, gx, gy, gs, c) ->
                Glyphs.arrow(d, gx + gs / 2, gy + gs * 0.85f, gx + gs / 2, gy + gs * 0.15f, c, 1.5f, gs), "Move up (earlier)",
                false, index > 0)) {
                ctx.actions.run(DocumentCommands.moveRule(sheetId, index, -1));
            }
            ImGui.sameLine(0, 2);
            if (EditorWidgets.iconButton("##down", ImGui.getFrameHeight(), (d, gx, gy, gs, c) ->
                Glyphs.arrow(d, gx + gs / 2, gy + gs * 0.15f, gx + gs / 2, gy + gs * 0.85f, c, 1.5f, gs),
                "Move down (later, wins ties)", false, index < s.rules().size() - 1)) {
                ctx.actions.run(DocumentCommands.moveRule(sheetId, index, 1));
            }
            ImGui.sameLine(0, 2);
            if (EditorWidgets.iconButton("##del", ImGui.getFrameHeight(), Glyphs::cross, "Delete rule", false, true)) {
                ctx.actions.run(DocumentCommands.removeRule(sheetId, index));
            }
        }
        // declarations
        for (Map.Entry<String, UiValue> d : r.style().entrySet()) {
            boolean overridden = match != null && trace.overridden(match, d.getKey());
            ImGui.alignTextToFramePadding();
            ImGui.dummy(12, 0);
            ImGui.sameLine();
            float px = ImGui.getCursorScreenPosX();
            float py = ImGui.getCursorScreenPosY() + ImGui.getFrameHeight() / 2f;
            if (overridden) {
                ImGui.textDisabled(d.getKey());
            } else {
                ImGui.textUnformatted(d.getKey());
            }
            if (overridden) {
                dl.addLine(px, py, ImGui.getItemRectMaxX(), py, EditorWidgets.dim(1f), 1f);
                if (ImGui.isItemHovered()) {
                    StyleTrace.Declaration win = trace.winners().get(d.getKey());
                    ImGui.setTooltip("Overridden by " + (win == null ? "?" : win.rule() != null ? win.rule().selector()
                        : win.layer().name().toLowerCase(java.util.Locale.ROOT)));
                }
            }
            ImGui.sameLine(DetailRows.labelWidth() + 20);
            float fw = ImGui.getContentRegionAvailX() - (editable ? ImGui.getFrameHeight() + 4 : 0) - 8;
            if (editable) {
                ValueFields.Result res = ValueFields.text("decl_" + key + d.getKey(), ValueFields.display(d.getValue()), "",
                    fw, ValueFields::parseLoose);
                if (res.changed()) {
                    UiValue v = res.value();
                    String p = v == null ? null : UiStyleProperties.problem(d.getKey(), v);
                    if (p != null) {
                        error = d.getKey() + ": " + p;
                    } else {
                        error = null;
                        ctx.actions.run(DocumentCommands.setRuleDeclaration(sheetId, index, d.getKey(), v));
                    }
                }
                ImGui.sameLine(0, 4);
                if (EditorWidgets.iconButton("##rmd" + d.getKey(), ImGui.getFrameHeight(), Glyphs::cross,
                    "Remove declaration", false, true)) {
                    ctx.actions.run(DocumentCommands.setRuleDeclaration(sheetId, index, d.getKey(), null));
                }
            } else {
                ImGui.textDisabled(ValueFields.display(d.getValue()));
            }
        }
        transitions(sheetId, index, r, editable);
        if (editable) {
            if (addingTo == index) {
                addDeclaration(sheetId, index);
            } else {
                ImGui.dummy(12, 0);
                ImGui.sameLine();
                ThemeColors.pushAccentSoftButton();
                if (ImGui.smallButton("+ declaration")) {
                    addingTo = index;
                    newProp.set("");
                    newValue.set("");
                }
                ImGui.popStyleColor(4);
            }
        }
        ImGui.endGroup();
        float bottom = ImGui.getItemRectMaxY() + 6;
        dl.channelsSetCurrent(0);
        int bg = match != null ? ThemeColors.surfaceU32(ThemeColors.Tone.SUCCESS, 0.06f, 1f) : EditorWidgets.frame(0.55f);
        dl.addRectFilled(x, y, x + w, bottom, bg, 5f);
        if (match != null) {
            dl.addRectFilled(x, y, x + 3, bottom, ThemeColors.u32(ThemeColors.Tone.SUCCESS, 1f), 2f);
        }
        dl.channelsMerge();
        ImGui.setCursorScreenPos(x, bottom + 6);
        ImGui.dummy(w, 0);
        if (scrollToRule == index) {
            ImGui.setScrollHereY(0.3f);
            scrollToRule = -1;
        }
        ImGui.popID();
    }

    /**
     * The rule's transitions (#295): property, duration, easing or custom curve, delay. Any change
     * of a matching element's cascade value of that property animates on the UI clock.
     */
    private void transitions(String sheetId, int index, UiStyleSheet.StyleRule r, boolean editable) {
        for (UiStyleSheet.StyleTransition t : r.transitions()) {
            String id = sheetId + "#" + index + ":" + t.property();
            ImGui.dummy(12, 0);
            ImGui.sameLine();
            ImGui.alignTextToFramePadding();
            ImGui.textDisabled("transition " + t.property());
            if (!editable) {
                ImGui.sameLine();
                ImGui.textDisabled(t.duration() + " s " + (t.bezier() != null ? t.bezier() : t.easing().wire()));
                continue;
            }
            ImGui.sameLine(DetailRows.labelWidth() + 20);
            float[] dur = {(float) t.duration()};
            float[] delay = {(float) t.delay()};
            ImGui.setNextItemWidth(70);
            boolean changed = ImGui.dragFloat("##td" + id, dur, 0.01f, 0f, 60f, "%.2f s");
            boolean ended = ImGui.isItemDeactivated();
            ImGui.sameLine(0, 4);
            ImGui.setNextItemWidth(80);
            changed |= ImGui.dragFloat("##tl" + id, delay, 0.01f, 0f, 60f, "delay %.2f");
            ended |= ImGui.isItemDeactivated();
            ImGui.sameLine(0, 4);
            if (EditorWidgets.iconButton("##trm" + id, ImGui.getFrameHeight(), Glyphs::cross, "Remove transition",
                false, true)) {
                ctx.actions.run(DocumentCommands.setRuleTransition(sheetId, index, t.property(), null));
                continue;
            }
            ImGui.dummy(DetailRows.labelWidth() + 8, 0);
            ImGui.sameLine();
            CurveField.Result curve = CurveField.edit(id, t.easing(), t.bezier(), 200);
            ended |= curve.ended();
            if (changed || curve.changed()) {
                ctx.actions.run(DocumentCommands.setRuleTransition(sheetId, index, t.property(),
                    new UiStyleSheet.StyleTransition(t.property(), Math.round(dur[0] * 1000) / 1000.0,
                        curve.changed() ? curve.easing() : t.easing(), Math.round(delay[0] * 1000) / 1000.0,
                        curve.changed() ? curve.bezier() : t.bezier(), t.unknown())));
            }
            if (ended) {
                ctx.actions.endInteraction();
            }
        }
        if (!editable) {
            return;
        }
        ImGui.dummy(12, 0);
        ImGui.sameLine();
        ImGui.setNextItemWidth(180);
        if (ImGui.beginCombo("##addTr", "+ transition")) {
            java.util.List<String> props = new java.util.ArrayList<>();
            props.add("all");
            for (String p : UiStyleProperties.names()) {
                var ap = com.openmason.engine.ui.runtime.anim.AnimProperty.style(p);
                if (ap != null && ap.interpolates()) {
                    props.add(p);
                }
            }
            for (String p : props) {
                boolean used = r.transitions().stream().anyMatch(t -> t.property().equals(p));
                if (ImGui.selectable(p, false, used ? imgui.flag.ImGuiSelectableFlags.Disabled : 0)) {
                    ctx.actions.run(DocumentCommands.setRuleTransition(sheetId, index, p,
                        new UiStyleSheet.StyleTransition(p, 0.2, com.openmason.engine.format.omui.UiEasing.EASE_OUT, 0,
                            java.util.Map.of())));
                }
            }
            ImGui.endCombo();
        }
    }

    private void addDeclaration(String sheetId, int index) {
        float w = ImGui.getContentRegionAvailX();
        ImGui.dummy(12, 0);
        ImGui.sameLine();
        ImGui.setNextItemWidth(w * 0.42f);
        if (ImGui.beginCombo("##np", newProp.get().isEmpty() ? "property" : newProp.get())) {
            for (String p : UiStyleProperties.names()) {
                if (ImGui.selectable(p)) {
                    newProp.set(p);
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine(0, 4);
        ImGui.setNextItemWidth(w * 0.58f - ImGui.getFrameHeight() * 2 - 30);
        boolean enter = ImGui.inputTextWithHint("##nv", "value", newValue, ImGuiInputTextFlags.EnterReturnsTrue);
        ImGui.sameLine(0, 4);
        boolean ok = !newProp.get().isBlank() && !newValue.get().isBlank();
        if ((EditorWidgets.iconButton("##addDecl", ImGui.getFrameHeight(), Glyphs::plus, "Add", false, ok) || enter) && ok) {
            UiValue v = ValueFields.parseLoose(newValue.get());
            String p = UiStyleProperties.problem(newProp.get(), v);
            if (p != null) {
                error = newProp.get() + ": " + p;
            } else {
                error = null;
                ctx.actions.run(DocumentCommands.setRuleDeclaration(sheetId, index, newProp.get(), v));
                addingTo = -1;
            }
        }
        ImGui.sameLine(0, 2);
        if (EditorWidgets.iconButton("##cancelDecl", ImGui.getFrameHeight(), Glyphs::cross, "Cancel", false, true)) {
            addingTo = -1;
        }
    }

    /** New-rule shortcuts from the selection: #name, each .class, the Type, or empty. */
    private void newRuleButtons(String sheetId) {
        UiElement el = ctx.element(ctx.doc().primary());
        List<String> suggestions = new ArrayList<>();
        if (el != null) {
            if (el.name() != null) {
                suggestions.add("#" + el.name());
            }
            el.classes().forEach(c -> suggestions.add("." + c));
            suggestions.add(el.type());
        }
        ThemeColors.pushAccentSoftButton();
        if (ImGui.button("+ Rule")) {
            ImGui.openPopup("##newRule");
        }
        ImGui.popStyleColor(4);
        if (ImGui.beginPopup("##newRule")) {
            ImGui.textDisabled("New rule for");
            for (String sel : suggestions) {
                if (ImGui.selectable(sel)) {
                    ctx.actions.run(DocumentCommands.addRule(sheetId, sel, Map.of()));
                }
            }
            if (ImGui.selectable("Box (edit the selector after)")) {
                ctx.actions.run(DocumentCommands.addRule(sheetId, "Box", Map.of()));
            }
            ImGui.endPopup();
        }
    }

    // ── matched cascade ─────────────────────────────────────────────────────

    private void matched(UiEditorDocument doc) {
        String key = doc.primary();
        UiElement el = ctx.element(key);
        if (el == null) {
            EditorWidgets.emptyState("Nothing selected", "Select an element to see every rule that styles it, "
                + "in cascade order, and which declarations win.");
            return;
        }
        StyleTrace trace = el.owner().styleTrace(el);
        ImGui.textUnformatted("Cascade for " + (el.name() != null ? "#" + el.name() : el.key()) + "  (" + el.type() + ")");
        ImGui.textDisabled("Lowest precedence first. Later sheets and higher specificity win; inline style wins over rules.");
        if (trace.rules().isEmpty()) {
            ImGui.textDisabled("No rule matches this element.");
        }
        if (ImGui.beginTable("##matched", 3, ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.BordersInnerH)) {
            ImGui.tableSetupColumn("Selector", ImGuiTableColumnFlags.WidthStretch, 1.2f);
            ImGui.tableSetupColumn("Sheet", ImGuiTableColumnFlags.WidthStretch, 0.8f);
            ImGui.tableSetupColumn("Declarations", ImGuiTableColumnFlags.WidthStretch, 1.6f);
            for (StyleTrace.MatchedRule m : trace.rules()) {
                ImGui.tableNextRow();
                ImGui.tableNextColumn();
                boolean local = doc.archive().styles().containsKey(m.sheetId());
                if (ImGui.selectable(m.selector() + "##m" + m.sheetId() + m.ruleIndex(), false)) {
                    sheet = m.sheetId();
                    scrollToRule = m.ruleIndex();
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip((local ? "Open in this document's sheet" : "Shared or component sheet (read-only)")
                        + "\nrank " + m.rank() + ", specificity " + m.specificity());
                }
                ImGui.tableNextColumn();
                ImGui.textDisabled(m.sheetId());
                ImGui.tableNextColumn();
                for (Map.Entry<String, UiValue> d : m.style().entrySet()) {
                    boolean lost = trace.overridden(m, d.getKey());
                    String text = d.getKey() + ": " + ValueFields.display(d.getValue());
                    float px = ImGui.getCursorScreenPosX();
                    if (lost) {
                        ImGui.textDisabled(text);
                        float py = ImGui.getItemRectMinY() + ImGui.getTextLineHeight() / 2f;
                        ImGui.getWindowDrawList().addLine(px, py, ImGui.getItemRectMaxX(), py, EditorWidgets.dim(1f), 1f);
                    } else {
                        ImGui.textUnformatted(text);
                    }
                }
            }
            ImGui.endTable();
        }
        List<String> inline = new ArrayList<>();
        trace.winners().forEach((p, d) -> {
            if (d.layer() != StyleTrace.Layer.RULE) {
                inline.add(p + ": " + ValueFields.display(d.value()) + "  (" + d.layer().name().toLowerCase(java.util.Locale.ROOT)
                    + ")");
            }
        });
        if (!inline.isEmpty()) {
            EditorWidgets.caption("Above the sheets");
            inline.forEach(ImGui::bulletText);
        }
    }

    private StyleTrace traceOfSelection() {
        UiEditorDocument doc = ctx.doc();
        UiElement el = doc == null ? null : ctx.element(doc.primary());
        return el == null ? StyleTrace.EMPTY : el.owner().styleTrace(el);
    }

    /** Specificity of a selector list (its highest), via the runtime's parser. */
    private static int specificity(String selector) {
        try {
            int best = 0;
            for (var s : com.openmason.engine.ui.runtime.style.SelectorParser.parseList(selector, Set.of())) {
                best = Math.max(best, s.specificity());
            }
            return best;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
