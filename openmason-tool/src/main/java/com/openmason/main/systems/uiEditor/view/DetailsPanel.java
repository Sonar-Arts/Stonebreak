package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiStyleProperties;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.style.StyleTrace;
import com.openmason.engine.ui.runtime.widget.PropertyDescriptor;
import com.openmason.engine.ui.runtime.widget.WidgetDescriptor;
import com.openmason.main.systems.menus.panes.propertyPane.inspector.InspectorFoldout;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.uiEditor.command.NodeCommands;
import com.openmason.main.systems.uiEditor.command.OverrideCommands;
import com.openmason.main.systems.uiEditor.document.Nodes;
import com.openmason.main.systems.uiEditor.service.UiProjectContext;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import com.openmason.main.systems.uiEditor.view.widgets.Glyphs;
import com.openmason.main.systems.uiEditor.view.widgets.ValueFields;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The Details panel: everything about the selection, Unreal-style. A header card (type glyph,
 * name, id, classes), a property search, and foldout categories for layout, appearance, text,
 * the widget's own properties, bindings, component parameters and overrides, the raw inline
 * style and the computed style with each value's origin. Selecting an element inside a
 * component instance switches every field to override editing, with reset-to-source.
 */
final class DetailsPanel implements AutoCloseable {

    static final String TITLE = "Details###uiDetails";

    private final UiEditorContext ctx;
    private final ImString search = new ImString(64);
    private final ImString nameBuf = new ImString(64);
    private final ImString classBuf = new ImString(64);
    private final ImString dataSourceBuf = new ImString(256);
    private final ImString newProp = new ImString(64);
    private final ImString newValue = new ImString(128);
    private final ImString bindTarget = new ImString(64);
    private final ImString bindPath = new ImString(128);
    private String nameFor;
    private boolean nameActive;
    private String dataSourceFor;
    private boolean dataSourceActive;
    private String newStyleError;
    private final Map<String, InspectorFoldout> foldouts = new LinkedHashMap<>();

    DetailsPanel(UiEditorContext ctx) {
        this.ctx = ctx;
    }

    void render() {
        if (!ImGui.begin(TITLE)) {
            ImGui.end();
            return;
        }
        ctx.noteFocus();
        InspectorTarget t = InspectorTarget.of(ctx);
        if (ctx.doc() == null) {
            EditorWidgets.emptyState("No document", null);
        } else if (t == null) {
            documentDetails();
        } else {
            header(t);
            EditorWidgets.searchField("##detailsSearch", search, "Search details");
            ImGui.spacing();
            if (ImGui.beginChild("##details")) {
                DetailRows rows = new DetailRows(ctx, t, search.get());
                boolean filtering = !search.get().isBlank();
                if (section("Layout", true, filtering)) {
                    LayoutSection.draw(ctx, rows);
                }
                endSection("Layout");
                if (section("Appearance", true, filtering)) {
                    appearance(rows);
                }
                endSection("Appearance");
                if (section("Text", isTextual(t), filtering)) {
                    rows.color("Color", "color");
                    rows.number("Font Size", "font-size", 0.25f, 0, 512, false);
                    rows.asset("Font", "font", EnumSet.of(UiDependency.Kind.FONT));
                    rows.keyword("Text Align", "text-align");
                }
                endSection("Text");
                WidgetDescriptor d = t.descriptor();
                if (d != null && hasOwnProps(d) && section(d.type(), true, filtering)) {
                    props(t, rows, d, false);
                }
                if (d != null && hasOwnProps(d)) {
                    endSection(d.type());
                }
                if (d != null && section("Interaction & Accessibility", false, filtering)) {
                    props(t, rows, d, true);
                }
                if (d != null) {
                    endSection("Interaction & Accessibility");
                }
                if (!t.internal && !t.multi() && section("Bindings", true, filtering)) {
                    bindings(t);
                }
                if (!t.internal && !t.multi()) {
                    endSection("Bindings");
                }
                UiNode n = t.node();
                if (!t.internal && !t.multi() && n != null && n.instance() != null && section("Component", true, filtering)) {
                    component(t, n);
                }
                if (!t.internal && !t.multi() && n != null && n.instance() != null) {
                    endSection("Component");
                }
                if (t.internal && section("Overrides", true, filtering)) {
                    overrides(t);
                }
                if (t.internal) {
                    endSection("Overrides");
                }
                if (section(t.internal ? "Override Style" : "Inline Style", false, filtering)) {
                    inlineStyle(t);
                }
                endSection(t.internal ? "Override Style" : "Inline Style");
                if (section("Computed Style", false, filtering)) {
                    computed(t);
                }
                endSection("Computed Style");
            }
            ImGui.endChild();
        }
        ImGui.end();
    }

    private boolean section(String name, boolean defaultOpen, boolean filtering) {
        InspectorFoldout f = foldouts.computeIfAbsent(name, k -> new InspectorFoldout(k, defaultOpen));
        if (filtering) {
            f.setOpen(true);
        }
        return f.begin();
    }

    private void endSection(String name) {
        InspectorFoldout f = foldouts.get(name);
        if (f != null) {
            f.end();
        }
    }

    // ── header ──────────────────────────────────────────────────────────────

    private void header(InspectorTarget t) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = ImGui.getContentRegionAvailX();
        float big = ImGui.getFrameHeight() * 1.6f;
        String type = t.type() == null ? "Mixed" : t.type();
        int tint = Glyphs.typeColor(type, 1f);
        dl.addRectFilled(x, y, x + big, y + big, Glyphs.withAlpha(tint, 0.18f), 6f);
        Glyphs.widget(dl, type, x + 5, y + 5, big - 10, tint);
        ImGui.setCursorScreenPos(x + big + 8, y);
        float fieldW = w - big - 8;
        if (t.multi()) {
            ImGui.alignTextToFramePadding();
            ImGui.textUnformatted(t.nodes.size() + " elements");
            ImGui.setCursorScreenPos(x + big + 8, y + ImGui.getFrameHeight());
            ImGui.textDisabled(t.type() == null ? "Mixed types: shared properties only" : "All " + type);
        } else if (t.internal) {
            ImGui.alignTextToFramePadding();
            String name = t.element == null ? t.keys.getFirst() : t.element.name() != null ? t.element.name() : t.element.id();
            ImGui.textUnformatted(name);
            ImGui.setCursorScreenPos(x + big + 8, y + ImGui.getFrameHeight());
            ImGui.textDisabled(type + "  inside " + t.overrideTarget.instanceId());
        } else {
            UiNode n = t.node();
            String nameKey = n.id() + "|" + n.name();
            if (!nameKey.equals(nameFor) && !nameActive) {
                nameBuf.set(n.name() == null ? "" : n.name());
                nameFor = nameKey;
            }
            ImGui.setNextItemWidth(fieldW);
            ImGui.inputTextWithHint("##elName", "name (#selector handle)", nameBuf, ImGuiInputTextFlags.AutoSelectAll);
            nameActive = ImGui.isItemActive();
            if (ImGui.isItemDeactivatedAfterEdit()) {
                ctx.actions.run(NodeCommands.rename(n.id(), nameBuf.get()));
                nameFor = null;
            }
            ImGui.setCursorScreenPos(x + big + 8, y + ImGui.getFrameHeight() + 2);
            ImGui.textDisabled(type + "   id: " + n.id());
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Stable id: survives renames and moves; every reference uses it. Click to copy.");
            }
            if (ImGui.isItemClicked()) {
                ImGui.setClipboardText(n.id());
            }
        }
        ImGui.setCursorScreenPos(x, y + big + 6);
        if (t.internal) {
            int purple = Glyphs.typeColor("Instance", 1f);
            float bx = ImGui.getCursorScreenPosX();
            float by = ImGui.getCursorScreenPosY();
            float bh = ImGui.getTextLineHeight() * 2 + 10;
            dl.addRectFilled(bx, by, bx + w, by + bh, Glyphs.withAlpha(purple, 0.14f), 4f);
            dl.addRectFilled(bx, by, bx + 3, by + bh, purple, 2f);
            dl.addText(bx + 10, by + 4, EditorWidgets.text(1f), "Component internals: edits become overrides");
            dl.addText(bx + 10, by + 6 + ImGui.getTextLineHeight(), EditorWidgets.dim(1f),
                "of instance '" + t.overrideTarget.instanceId() + "'. The component file is not changed.");
            ImGui.dummy(w, bh + 4);
        }
        classes(t);
        ImGui.separator();
    }

    private void classes(InspectorTarget t) {
        if (t.multi()) {
            return;
        }
        List<String> current = new ArrayList<>(t.internal ? effectiveClasses(t) : t.node().classes());
        int tint = Glyphs.rgba(0.55f, 0.62f, 0.72f, 1f);
        for (String c : current) {
            boolean added = t.internal && t.override != null && t.override.addClasses().contains(c);
            if (EditorWidgets.chip("##cls_" + c, "." + c, true, added ? Glyphs.typeColor("Instance", 1f) : tint)) {
                removeClass(t, c);
            }
            ImGui.sameLine(0, 4);
        }
        float w = Math.max(90, ImGui.getContentRegionAvailX());
        if (w < 90) {
            ImGui.newLine();
        }
        ImGui.setNextItemWidth(Math.min(160, w));
        if (ImGui.inputTextWithHint("##addClass", "+ class", classBuf, ImGuiInputTextFlags.EnterReturnsTrue)
                && !classBuf.get().isBlank()) {
            addClass(t, classBuf.get().trim().replaceFirst("^\\.", ""));
            classBuf.set("");
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Add a style class (Enter). Classes select sheet rules (.name).");
        }
    }

    private List<String> effectiveClasses(InspectorTarget t) {
        return t.element == null ? List.of() : new ArrayList<>(t.element.classes());
    }

    private void addClass(InspectorTarget t, String c) {
        if (t.internal) {
            UiNode.InstanceOverride o = t.override;
            List<String> add = new ArrayList<>(o == null ? List.of() : o.addClasses());
            List<String> remove = new ArrayList<>(o == null ? List.of() : o.removeClasses());
            if (!remove.remove(c)) {
                add.add(c);
            }
            ctx.actions.run(OverrideCommands.setClasses(t.keys.getFirst(), add, remove));
            return;
        }
        List<String> cs = new ArrayList<>(t.node().classes());
        if (!cs.contains(c)) {
            cs.add(c);
            ctx.actions.run(NodeCommands.setClasses(t.node().id(), cs));
        }
    }

    private void removeClass(InspectorTarget t, String c) {
        if (t.internal) {
            UiNode.InstanceOverride o = t.override;
            List<String> add = new ArrayList<>(o == null ? List.of() : o.addClasses());
            List<String> remove = new ArrayList<>(o == null ? List.of() : o.removeClasses());
            if (!add.remove(c)) {
                remove.add(c);
            }
            ctx.actions.run(OverrideCommands.setClasses(t.keys.getFirst(), add, remove));
            return;
        }
        List<String> cs = new ArrayList<>(t.node().classes());
        cs.remove(c);
        ctx.actions.run(NodeCommands.setClasses(t.node().id(), cs));
    }

    // ── categories ──────────────────────────────────────────────────────────

    private void appearance(DetailRows rows) {
        rows.color("Background", "background-color");
        rows.asset("Background Image", "background-image",
            EnumSet.of(UiDependency.Kind.TEXTURE, UiDependency.Kind.IMAGE, UiDependency.Kind.SPRITES));
        rows.keyword("Image Scale", "-sb-image-scale");
        rows.keyword("Sampling", "-sb-sampling");
        rows.color("Tint", "-sb-tint");
        rows.unit("Opacity", "opacity");
        EditorWidgets.caption("Border");
        rows.color("Border Color", "border-color");
        rows.length("Radius", "border-radius", false);
        rows.length("Left Width", "border-left-width", false);
        rows.length("Top Width", "border-top-width", false);
        rows.length("Right Width", "border-right-width", false);
        rows.length("Bottom Width", "border-bottom-width", false);
        EditorWidgets.caption("Visibility");
        rows.keyword("Display", "display");
        rows.keyword("Visibility", "visibility");
        rows.keyword("Overflow", "overflow");
        rows.keyword("Picking", "picking-mode");
    }

    private static boolean isTextual(InspectorTarget t) {
        String type = t.type();
        return "Label".equals(type) || "TextField".equals(type) || "Button".equals(type) || "Box".equals(type)
            || t.style("color") != null || t.style("font-size") != null;
    }

    private static boolean hasOwnProps(WidgetDescriptor d) {
        for (String p : d.properties().keySet()) {
            if (!UiFeatures.INPUT_PROPS.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /** The widget's properties: its own ({@code inputGroup == false}) or the shared input/a11y ones. */
    private void props(InspectorTarget t, DetailRows rows, WidgetDescriptor d, boolean inputGroup) {
        for (PropertyDescriptor p : d.properties().values()) {
            boolean input = UiFeatures.INPUT_PROPS.contains(p.name());
            if (input != inputGroup || !rows.visible(p.name(), p.name())) {
                continue;
            }
            UiValue a = t.prop(p.name());
            boolean bound = t.bound("prop:" + p.name());
            String feature = UiFeatures.forProp(p.name());
            String tip = p.name() + (p.description().isEmpty() ? "" : "\n" + p.description())
                + "\nDefault: " + ValueFields.display(p.defaultValue())
                + (feature != null ? "\nNeeds \"" + feature + "\" (added automatically)" : "");
            float w = rows.begin(label(p.name()), tip, a != null, bound);
            UiValue eff = t.effectiveProp(p.name()) == null ? p.defaultValue() : t.effectiveProp(p.name());
            String id = "pr_" + p.name();
            ValueFields.Result r = switch (p.type()) {
                case BOOL -> ValueFields.bool(id, a, eff);
                case INT -> ValueFields.number(id, a, eff, 0.2f, -1_000_000, 1_000_000, true, w);
                case NUMBER -> ValueFields.number(id, a, eff, 0.25f, -1_000_000, 1_000_000, false, w);
                case COLOR -> ValueFields.color(id, a, eff, rows.tokens(), w);
                case ASSET -> ValueFields.asset(id, a, eff, rows.assetIds(EnumSet.allOf(UiDependency.Kind.class)), w);
                case LIST, OBJECT -> ValueFields.json(id, a, w);
                default -> p.keywords().isEmpty()
                    ? ValueFields.text(id, a instanceof UiValue.Str s && a != ValueFields.MIXED ? s.value() : null,
                        ValueFields.display(eff), w, v -> v.isEmpty() && p.defaultValue() instanceof UiValue.Null ? null
                            : UiValue.of(v))
                    : ValueFields.keyword(id, a, eff, new TreeSet<>(p.keywords()), w);
            };
            rows.apply(r, v -> t.setProp(p.name(), v));
            if (rows.end(a != null, p.name())) {
                ctx.actions.run(t.setProp(p.name(), null));
            }
        }
    }

    private static String label(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (i == 0) {
                sb.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c)) {
                sb.append(' ').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ── bindings ────────────────────────────────────────────────────────────

    private void bindings(InspectorTarget t) {
        UiNode n = t.node();
        float w = ImGui.getContentRegionAvailX();
        ImGui.alignTextToFramePadding();
        ImGui.textDisabled("Data Source");
        ImGui.sameLine(DetailRows.labelWidth());
        String ds = n.id() + "|" + (n.dataSource() == null ? "" : n.dataSource());
        if (!ds.equals(dataSourceFor) && !dataSourceActive) {
            dataSourceBuf.set(n.dataSource() == null ? "" : n.dataSource());
            dataSourceFor = ds;
        }
        ImGui.setNextItemWidth(-1);
        ImGui.inputTextWithHint("##dataSource", "inherited (e.g. session or .item)", dataSourceBuf);
        dataSourceActive = ImGui.isItemActive();
        if (ImGui.isItemDeactivatedAfterEdit()) {
            dataSourceFor = null;
            ctx.actions.run(NodeCommands.setDataSource(n.id(), dataSourceBuf.get().trim()));
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("The data path this element supplies to its subtree (Unity-style inherited data source)."
                + "\nA leading '.' is relative to the inherited source.");
        }
        if (n.bindings().isEmpty()) {
            ImGui.textDisabled("No bindings. Bind a property, style or class to host data:");
        } else if (ImGui.beginTable("##bindings", 4, ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.BordersInnerV)) {
            ImGui.tableSetupColumn("Target", ImGuiTableColumnFlags.WidthStretch, 1.1f);
            ImGui.tableSetupColumn("Path", ImGuiTableColumnFlags.WidthStretch, 1.2f);
            ImGui.tableSetupColumn("Mode", ImGuiTableColumnFlags.WidthStretch, 0.8f);
            ImGui.tableSetupColumn("", ImGuiTableColumnFlags.WidthFixed, ImGui.getFrameHeight());
            for (UiNode.UiBinding b : n.bindings()) {
                ImGui.tableNextRow();
                ImGui.tableNextColumn();
                ImGui.alignTextToFramePadding();
                ImGui.textUnformatted(b.target());
                ImGui.tableNextColumn();
                ImGui.alignTextToFramePadding();
                ImGui.textUnformatted(b.path() + (b.converter() != null ? "  |" + b.converter() : ""));
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(b.path() + (b.converter() != null ? "\nconverter: " + b.converter() : ""));
                }
                ImGui.tableNextColumn();
                ImGui.setNextItemWidth(-1);
                if (ImGui.beginCombo("##mode" + b.target(), b.mode().wire())) {
                    for (UiNode.BindingMode m : UiNode.BindingMode.values()) {
                        if (ImGui.selectable(m.wire(), m == b.mode())) {
                            ctx.actions.run(NodeCommands.setBinding(n.id(), b.target(),
                                new UiNode.UiBinding(b.target(), b.path(), m, b.converter(), b.unknown())));
                        }
                    }
                    ImGui.endCombo();
                }
                ImGui.tableNextColumn();
                if (EditorWidgets.iconButton("##unbind" + b.target(), ImGui.getFrameHeight(), Glyphs::cross,
                    "Remove binding", false, true)) {
                    ctx.actions.run(NodeCommands.setBinding(n.id(), b.target(), null));
                }
            }
            ImGui.endTable();
        }
        // add row
        float third = (w - ImGui.getFrameHeight() - 12) / 2f;
        ImGui.setNextItemWidth(third);
        if (ImGui.beginCombo("##bindTarget", bindTarget.get().isEmpty() ? "target" : bindTarget.get())) {
            WidgetDescriptor d = t.descriptor();
            if (d != null) {
                for (String p : d.properties().keySet()) {
                    if (ImGui.selectable("prop:" + p)) {
                        bindTarget.set("prop:" + p);
                    }
                }
            }
            if (n.instance() != null) {
                for (String p : componentParams(n)) {
                    if (ImGui.selectable("prop:" + p)) {
                        bindTarget.set("prop:" + p);
                    }
                }
            }
            for (String s : List.of("display", "visibility", "opacity", "color", "background-color", "width", "height")) {
                if (ImGui.selectable("style:" + s)) {
                    bindTarget.set("style:" + s);
                }
            }
            for (String c : n.classes()) {
                if (ImGui.selectable("class:" + c)) {
                    bindTarget.set("class:" + c);
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine(0, 4);
        ImGui.setNextItemWidth(third);
        ImGui.inputTextWithHint("##bindPath", "path (.field or root.x)", bindPath);
        ImGui.sameLine(0, 4);
        boolean ready = !bindTarget.get().isBlank() && !bindPath.get().isBlank();
        if (EditorWidgets.iconButton("##addBinding", ImGui.getFrameHeight(), Glyphs::plus, "Add binding", false, ready)) {
            ctx.actions.run(NodeCommands.setBinding(n.id(), bindTarget.get(),
                new UiNode.UiBinding(bindTarget.get(), bindPath.get().trim())));
            bindPath.set("");
        }
    }

    private List<String> componentParams(UiNode n) {
        var ui = ctx.instance();
        if (ui == null) {
            return List.of();
        }
        OmuiArchive comp = ui.context().source().component(n.instance().component());
        UiDocument.ComponentDef def = comp == null ? null : comp.document().component();
        return def == null ? List.of() : def.params().stream().map(UiDocument.Param::name).toList();
    }

    // ── component ───────────────────────────────────────────────────────────

    private void component(InspectorTarget t, UiNode n) {
        UiNode.ComponentInstance inst = n.instance();
        UiDependency row = ctx.doc().archive().dependencies().find(inst.component());
        ImGui.alignTextToFramePadding();
        ImGui.textDisabled("Component");
        ImGui.sameLine(DetailRows.labelWidth());
        ImGui.textUnformatted(inst.component());
        if (row != null) {
            ImGui.sameLine();
            EditorWidgets.badge(row.mode() == UiDependency.Mode.EMBEDDED ? "embedded" : "shared",
                row.mode() == UiDependency.Mode.EMBEDDED ? Glyphs.rgba(0.86f, 0.62f, 0.30f, 1f)
                    : Glyphs.rgba(0.40f, 0.72f, 0.95f, 1f));
        } else {
            ImGui.sameLine();
            EditorWidgets.badge("missing row", ThemeColors.u32(ThemeColors.Tone.ERROR, 1f));
        }
        UiProjectContext.Entry file = null;
        for (UiProjectContext.Entry e : ctx.project.components()) {
            if (inst.component().equals(e.documentId())) {
                file = e;
            }
        }
        if (file != null) {
            if (ImGui.button("Open Component")) {
                UiProjectContext.Entry f = file;
                ctx.service.open(f.path());
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Opens " + file.relative() + " in a new tab");
            }
        }
        var ui = ctx.instance();
        OmuiArchive comp = ui == null ? null : ui.context().source().component(inst.component());
        UiDocument.ComponentDef def = comp == null ? null : comp.document().component();
        EditorWidgets.caption("Parameters");
        if (def == null) {
            ThemeColors.push(imgui.flag.ImGuiCol.Text, ThemeColors.Tone.WARNING);
            ImGui.textWrapped("The component cannot be resolved, so its parameters are unknown. Existing values are kept.");
            ImGui.popStyleColor();
            for (Map.Entry<String, UiValue> e : inst.params().entrySet()) {
                ImGui.bulletText(e.getKey() + " = " + ValueFields.display(e.getValue()));
            }
        } else {
            DetailRows rows = new DetailRows(ctx, t, search.get());
            for (UiDocument.Param p : def.params()) {
                UiValue a = inst.params().get(p.name());
                UiValue eff = p.defaultValue() == null ? UiValue.NULL : p.defaultValue();
                float w = rows.begin(label(p.name()), p.name() + " (" + p.type().wire() + ")\nDefault: "
                    + ValueFields.display(eff), a != null, t.bound("prop:" + p.name()));
                String id = "par_" + p.name();
                ValueFields.Result r = switch (p.type()) {
                    case BOOL -> ValueFields.bool(id, a, eff);
                    case INT -> ValueFields.number(id, a, eff, 0.2f, -1_000_000, 1_000_000, true, w);
                    case NUMBER -> ValueFields.number(id, a, eff, 0.25f, -1_000_000, 1_000_000, false, w);
                    case COLOR -> ValueFields.color(id, a, eff, rows.tokens(), w);
                    case LIST, OBJECT -> ValueFields.json(id, a, w);
                    default -> ValueFields.text(id, a instanceof UiValue.Str s ? s.value() : null, ValueFields.display(eff),
                        w, UiValue::of);
                };
                rows.apply(r, v -> OverrideCommands.setParam(n.id(), p.name(), v));
                if (rows.end(a != null, p.name())) {
                    ctx.actions.run(OverrideCommands.setParam(n.id(), p.name(), null));
                }
            }
            if (!def.slots().isEmpty()) {
                EditorWidgets.caption("Slots");
                for (UiDocument.Slot s : def.slots()) {
                    int count = inst.slots().getOrDefault(s.name(), List.of()).size();
                    ImGui.bulletText(s.name() + "  (" + count + (count == 1 ? " element)" : " elements)"));
                }
                ImGui.textDisabled("Drop elements on a slot row in the Hierarchy to fill it.");
            }
        }
        EditorWidgets.caption("Overrides");
        if (inst.overrides().isEmpty()) {
            ImGui.textDisabled("None. Double-click into the instance on the canvas to override its internals.");
        }
        for (UiNode.InstanceOverride o : inst.overrides()) {
            String key = n.id() + "/" + o.target();
            String summary = o.target() + ":  " + parts(o);
            ImGui.alignTextToFramePadding();
            if (ImGui.selectable(summary + "##ov" + o.target(), false, 0, ImGui.getContentRegionAvailX() - 70, 0)) {
                ctx.doc().select(List.of(key));
            }
            ImGui.sameLine();
            if (ImGui.smallButton("Reset##ov" + o.target())) {
                ctx.actions.run(OverrideCommands.reset(key));
            }
        }
    }

    private static String parts(UiNode.InstanceOverride o) {
        List<String> p = new ArrayList<>();
        if (!o.props().isEmpty()) {
            p.add(o.props().size() + " prop" + (o.props().size() == 1 ? "" : "s"));
        }
        if (!o.style().isEmpty()) {
            p.add(o.style().size() + " style");
        }
        if (!o.addClasses().isEmpty() || !o.removeClasses().isEmpty()) {
            p.add("classes");
        }
        return p.isEmpty() ? "(empty)" : String.join(", ", p);
    }

    private void overrides(InspectorTarget t) {
        UiNode.InstanceOverride o = t.override;
        if (o == null || Nodes.isEmpty(o)) {
            ImGui.textDisabled("Nothing overridden: this element shows the component's own values.");
            return;
        }
        ImGui.textUnformatted("Overridden here: " + parts(o));
        if (ThemedButton.danger("Reset All to Component Source")) {
            ctx.actions.run(OverrideCommands.reset(t.keys.getFirst()));
        }
    }

    // ── raw style tables ────────────────────────────────────────────────────

    private void inlineStyle(InspectorTarget t) {
        Map<String, UiValue> style = t.internal ? (t.override == null ? Map.of() : t.override.style())
            : t.multi() ? Map.of() : t.node().style();
        if (t.multi()) {
            ImGui.textDisabled("Select a single element to see its raw declarations.");
            return;
        }
        if (ImGui.beginTable("##inline", 3, ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.RowBg)) {
            ImGui.tableSetupColumn("Property", ImGuiTableColumnFlags.WidthStretch, 1f);
            ImGui.tableSetupColumn("Value", ImGuiTableColumnFlags.WidthStretch, 1.2f);
            ImGui.tableSetupColumn("", ImGuiTableColumnFlags.WidthFixed, ImGui.getFrameHeight());
            for (Map.Entry<String, UiValue> e : style.entrySet()) {
                ImGui.tableNextRow();
                ImGui.tableNextColumn();
                ImGui.alignTextToFramePadding();
                ImGui.textUnformatted(e.getKey());
                ImGui.tableNextColumn();
                ValueFields.Result r = ValueFields.text("raw_" + e.getKey(), ValueFields.display(e.getValue()), "", -1,
                    ValueFields::parseLoose);
                if (r.changed()) {
                    ctx.actions.run(t.setStyle(e.getKey(), r.value()));
                }
                String problem = UiStyleProperties.problem(e.getKey(), e.getValue());
                if (problem != null) {
                    ThemeColors.push(imgui.flag.ImGuiCol.Text, ThemeColors.Tone.ERROR);
                    ImGui.textWrapped(problem);
                    ImGui.popStyleColor();
                }
                ImGui.tableNextColumn();
                if (EditorWidgets.iconButton("##rm" + e.getKey(), ImGui.getFrameHeight(), Glyphs::cross, "Remove",
                    false, true)) {
                    ctx.actions.run(t.setStyle(e.getKey(), null));
                }
            }
            ImGui.endTable();
        }
        float w = ImGui.getContentRegionAvailX();
        ImGui.setNextItemWidth(w * 0.45f);
        if (ImGui.beginCombo("##newProp", newProp.get().isEmpty() ? "+ property" : newProp.get())) {
            for (String p : UiStyleProperties.names()) {
                if (ImGui.selectable(p)) {
                    newProp.set(p);
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine(0, 4);
        ImGui.setNextItemWidth(w * 0.55f - ImGui.getFrameHeight() - 8);
        boolean enter = ImGui.inputTextWithHint("##newValue", "value", newValue, ImGuiInputTextFlags.EnterReturnsTrue);
        ImGui.sameLine(0, 4);
        if ((EditorWidgets.iconButton("##addDecl", ImGui.getFrameHeight(), Glyphs::plus, "Add declaration", false,
                !newProp.get().isBlank()) || enter) && !newProp.get().isBlank()) {
            UiValue v = ValueFields.parseLoose(newValue.get());
            String problem = v == null ? "value required" : UiStyleProperties.problem(newProp.get().trim(), v);
            if (problem == null) {
                ctx.actions.run(t.setStyle(newProp.get().trim(), v));
                newProp.set("");
                newValue.set("");
                newStyleError = null;
            } else {
                newStyleError = newProp.get() + ": " + problem;
            }
        }
        if (newStyleError != null) {
            ThemeColors.push(imgui.flag.ImGuiCol.Text, ThemeColors.Tone.ERROR);
            ImGui.textWrapped(newStyleError);
            ImGui.popStyleColor();
        }
    }

    private void computed(InspectorTarget t) {
        if (t.element == null) {
            ImGui.textDisabled("Not in the running preview.");
            return;
        }
        StyleTrace trace = t.trace;
        Map<String, UiValue> values = new java.util.TreeMap<>(t.element.computedStyle().values());
        if (ImGui.beginTable("##computed", 3, ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.RowBg)) {
            ImGui.tableSetupColumn("Property", ImGuiTableColumnFlags.WidthStretch, 1f);
            ImGui.tableSetupColumn("Value", ImGuiTableColumnFlags.WidthStretch, 0.9f);
            ImGui.tableSetupColumn("From", ImGuiTableColumnFlags.WidthStretch, 1.3f);
            for (Map.Entry<String, UiValue> e : values.entrySet()) {
                ImGui.tableNextRow();
                ImGui.tableNextColumn();
                ImGui.textUnformatted(e.getKey());
                ImGui.tableNextColumn();
                ImGui.textUnformatted(ValueFields.display(e.getValue()));
                ImGui.tableNextColumn();
                StyleTrace.Declaration d = trace.winners().get(e.getKey());
                String from = d == null ? "inherited" : d.layer() == StyleTrace.Layer.RULE
                    ? d.rule().selector() : d.layer().name().toLowerCase(java.util.Locale.ROOT);
                if (d != null && d.layer() == StyleTrace.Layer.RULE) {
                    if (ImGui.selectable(from + "##cmp" + e.getKey())) {
                        ctx.jumpSheet = d.rule().sheetId();
                        ctx.jumpRule = d.rule().ruleIndex();
                        ctx.focusWindow = StyleSheetPanel.TITLE;
                    }
                    if (ImGui.isItemHovered()) {
                        ImGui.setTooltip("Sheet " + d.rule().sheetId() + ", rule " + d.rule().ruleIndex()
                            + ". Click to open it in Style Sheets.");
                    }
                } else {
                    ImGui.textDisabled(from);
                }
            }
            ImGui.endTable();
        }
    }

    // ── nothing selected: the document itself ───────────────────────────────

    private final ImString displayName = new ImString(128);
    private String displayFor;

    private void documentDetails() {
        var doc = ctx.doc();
        var m = doc.archive().manifest();
        EditorWidgets.caption("Document");
        if (!m.documentId().equals(displayFor)) {
            displayName.set(m.displayName());
            displayFor = m.documentId();
        }
        ImGui.alignTextToFramePadding();
        ImGui.textDisabled("Display Name");
        ImGui.sameLine(DetailRows.labelWidth());
        ImGui.setNextItemWidth(-1);
        ImGui.inputText("##docName", displayName);
        if (ImGui.isItemDeactivatedAfterEdit()) {
            ctx.actions.run(com.openmason.main.systems.uiEditor.command.DocumentCommands.setDisplayName(displayName.get()));
            ctx.actions.endInteraction();
            displayFor = null;
        }
        row("Id", m.documentId());
        row("Kind", m.kind().wire());
        row("Schema", m.schemaVersion() + "   ui api " + m.uiApi() + "   " + m.layoutSemantics());
        row("Requires", m.requires().isEmpty() ? "(none)" : String.join(", ", m.requires()));
        row("File", doc.file() == null ? "(unsaved)" : String.valueOf(ctx.project.relative(doc.file()) != null
            ? ctx.project.relative(doc.file()) : doc.file()));
        if (doc.importedFrom() != null) {
            row("Copied From", doc.importedFrom().getFileName().toString());
        }
        EditorWidgets.caption("Host Contracts");
        if (m.hostApis().isEmpty() && m.providers().isEmpty()) {
            ImGui.textDisabled("None declared");
        }
        m.hostApis().forEach(h -> ImGui.bulletText(h.id() + " v" + h.version() + (h.optional() ? " (optional)" : "")));
        m.providers().forEach(h -> ImGui.bulletText(h.id() + " v" + h.version() + " (provider)"));
        if (doc.archive().document().component() != null) {
            UiDocument.ComponentDef def = doc.archive().document().component();
            EditorWidgets.caption("Component Contract");
            def.params().forEach(p -> ImGui.bulletText("param " + p.name() + ": " + p.type().wire()
                + (p.defaultValue() != null ? " = " + ValueFields.display(p.defaultValue()) : "")));
            def.events().forEach(e -> ImGui.bulletText("event " + e.name()));
            def.slots().forEach(s -> ImGui.bulletText("slot " + s.name() + " -> " + s.host()));
        }
        ImGui.spacing();
        ImGui.textDisabled("Select an element on the canvas or in the Hierarchy to edit it.");
    }

    private static void row(String label, String value) {
        ImGui.textDisabled(label);
        ImGui.sameLine(DetailRows.labelWidth());
        ImGui.textWrapped(value);
    }

    @Override
    public void close() {
        foldouts.values().forEach(InspectorFoldout::close);
    }

    /** A danger button through the theme helpers. */
    private static final class ThemedButton {
        static boolean danger(String label) {
            return com.openmason.main.systems.themes.utils.ThemedWidgets.dangerSoftButton(label, 0, 0);
        }
    }
}
