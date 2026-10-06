package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiWidgets;
import com.openmason.engine.ui.runtime.widget.BuiltInWidgets;
import com.openmason.engine.ui.runtime.widget.WidgetDescriptor;
import com.openmason.main.systems.menus.panes.propertyPane.inspector.InspectorFoldout;
import com.openmason.main.systems.uiEditor.command.DocumentCommands;
import com.openmason.main.systems.uiEditor.command.NodeCommands;
import com.openmason.main.systems.uiEditor.command.UiCommand;
import com.openmason.main.systems.uiEditor.command.UiCommandException;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.UiProjectContext;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import com.openmason.main.systems.uiEditor.view.widgets.Glyphs;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiDragDropFlags;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Widgets and components to place: drag an entry onto the canvas or the hierarchy, or
 * double-click it to add it at the selection. Components come from the document's own
 * dependency table and from the project's {@code UI/} folder; placing a project component adds
 * its shared dependency row in the same undo step.
 */
final class PalettePanel implements AutoCloseable {

    static final String TITLE = "Palette###uiPalette";
    /** Drag payload: {@code widget:<Type>} or {@code component:<id>}. */
    static final String PAYLOAD = "OM_UI_PALETTE";

    private record Item(String payload, String type, String label, String description, String feature) {
    }

    private final UiEditorContext ctx;
    private final ImString search = new ImString(64);
    private final Map<String, InspectorFoldout> foldouts = new LinkedHashMap<>();

    PalettePanel(UiEditorContext ctx) {
        this.ctx = ctx;
    }

    void render() {
        if (!ImGui.begin(TITLE)) {
            ImGui.end();
            return;
        }
        ctx.noteFocus();
        UiEditorDocument doc = ctx.doc();
        EditorWidgets.searchField("##paletteSearch", search, "Search widgets and components");
        ImGui.spacing();
        if (ImGui.beginChild("##paletteList")) {
            String q = search.get().trim().toLowerCase(Locale.ROOT);
            section("Layout", widgets("Box", "ScrollView", "ListView"), q, doc != null);
            section("Common", widgets("Label", "Button", "Image", "TextField"), q, doc != null);
            section("Game", widgets("ItemSlot", "DrawProvider", "Canvas"), q, doc != null);
            section("Components", components(doc), q, doc != null);
        }
        ImGui.endChild();
        ImGui.end();
    }

    private void section(String name, List<Item> items, String q, boolean enabled) {
        List<Item> shown = items.stream().filter(i -> q.isEmpty() || i.label().toLowerCase(Locale.ROOT).contains(q)
            || i.description().toLowerCase(Locale.ROOT).contains(q)).toList();
        if (shown.isEmpty() && !q.isEmpty()) {
            return;
        }
        InspectorFoldout f = foldouts.computeIfAbsent(name, n -> new InspectorFoldout(n, true));
        if (f.begin()) {
            if (shown.isEmpty()) {
                ImGui.textDisabled(name.equals("Components")
                    ? "No components yet. Save a Component document under UI/ to reuse it here." : "Nothing here");
            }
            for (Item i : shown) {
                row(i, enabled);
            }
        }
        f.end();
    }

    private void row(Item item, boolean enabled) {
        ImDrawList dl = ImGui.getWindowDrawList();
        float h = ImGui.getTextLineHeight() + 12;
        float w = ImGui.getContentRegionAvailX();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGui.invisibleButton("##pal_" + item.payload(), w, h);
        boolean hovered = ImGui.isItemHovered();
        if (hovered) {
            dl.addRectFilled(x, y, x + w, y + h, EditorWidgets.accent(0.14f), 4f);
        }
        float icon = h - 8;
        int tint = Glyphs.typeColor(item.type(), 1f);
        dl.addRectFilled(x + 4, y + 4, x + 4 + icon, y + 4 + icon, Glyphs.withAlpha(tint, 0.18f), 4f);
        Glyphs.widget(dl, item.type(), x + 6, y + 6, icon - 4, tint);
        dl.addText(x + icon + 12, y + (h - ImGui.getTextLineHeight()) / 2f,
            enabled ? EditorWidgets.text(1f) : EditorWidgets.dim(1f), item.label());
        if (item.feature() != null) {
            float fw = ImGui.calcTextSize(item.feature()).x + 10;
            float fx = x + w - fw - 4;
            dl.addRectFilled(fx, y + 5, fx + fw, y + h - 5, EditorWidgets.frame(1f), 4f);
            dl.addText(fx + 5, y + (h - ImGui.getTextLineHeight()) / 2f, EditorWidgets.dim(1f), item.feature());
        }
        if (hovered && !ImGui.isMouseDragging(0)) {
            ImGui.beginTooltip();
            ImGui.textUnformatted(item.label());
            ImGui.textDisabled(item.description());
            if (item.feature() != null) {
                ImGui.textDisabled("Adds \"" + item.feature() + "\" to the document's requires.");
            }
            ImGui.textDisabled("Drag onto the canvas or hierarchy, or double-click to add.");
            ImGui.endTooltip();
        }
        if (enabled && hovered && ImGui.isMouseDoubleClicked(0)) {
            ctx.actions.run(createCommand(ctx, item.payload(), ctx.actions.insertionPoint()));
        }
        if (enabled && ImGui.beginDragDropSource(ImGuiDragDropFlags.SourceAllowNullID)) {
            ImGui.setDragDropPayload(PAYLOAD, item.payload(), ImGuiCond.Once);
            float s = ImGui.getTextLineHeight();
            float px = ImGui.getCursorScreenPosX();
            float py = ImGui.getCursorScreenPosY();
            Glyphs.widget(ImGui.getWindowDrawList(), item.type(), px, py, s, tint);
            ImGui.dummy(s, s);
            ImGui.sameLine();
            ImGui.textUnformatted(item.label());
            ImGui.endDragDropSource();
        }
    }

    private static List<Item> widgets(String... types) {
        List<Item> out = new ArrayList<>();
        for (String t : types) {
            WidgetDescriptor d = BuiltInWidgets.all().stream().filter(x -> x.type().equals(t)).findFirst().orElse(null);
            if (d != null) {
                out.add(new Item("widget:" + t, t, t, d.description(), UiWidgets.requiredFeature(t)));
            }
        }
        return out;
    }

    private List<Item> components(UiEditorDocument doc) {
        Map<String, Item> byId = new LinkedHashMap<>();
        if (doc != null) {
            for (UiDependency d : doc.archive().dependencies().entries()) {
                if (d.kind() == UiDependency.Kind.COMPONENT) {
                    byId.put(d.id(), new Item("component:" + d.id(), "Instance", label(d.id()),
                        d.id() + (d.mode() == UiDependency.Mode.EMBEDDED ? " (embedded)" : " (shared)"), null));
                }
            }
        }
        String own = doc == null ? null : doc.archive().manifest().documentId();
        for (UiProjectContext.Entry e : ctx.project.components()) {
            if (e.documentId() != null && !e.documentId().equals(own) && !byId.containsKey(e.documentId())) {
                byId.put(e.documentId(), new Item("component:" + e.documentId(), "Instance", e.label(),
                    e.documentId() + " (project: " + e.relative() + ")", null));
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static String label(String id) {
        String stem = id.substring(id.lastIndexOf('/') + 1);
        StringBuilder sb = new StringBuilder();
        for (String part : stem.split("[_-]")) {
            if (!part.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }

    /** The command that places palette entry {@code payload} at {@code at}. */
    static UiCommand createCommand(UiEditorContext ctx, String payload, NodeLocation at) {
        if (payload.startsWith("widget:")) {
            return NodeCommands.create(payload.substring("widget:".length()), at, null);
        }
        String id = payload.substring("component:".length());
        UiEditorDocument doc = ctx.doc();
        UiDependency row = doc == null ? null : doc.archive().dependencies().find(id);
        if (row == null) {
            for (UiProjectContext.Entry e : ctx.project.components()) {
                if (id.equals(e.documentId())) {
                    try {
                        row = ctx.project.sharedRow(id, UiDependency.Kind.COMPONENT, e.path());
                    } catch (java.io.IOException ex) {
                        return UiCommand.of("Add component", c -> {
                            throw new UiCommandException("Cannot read " + e.relative() + ": " + ex.getMessage());
                        });
                    }
                    OmuiArchive comp = ctx.project.read(e);
                    if (comp == null) {
                        return UiCommand.of("Add component", c -> {
                            throw new UiCommandException(e.relative() + " is not a readable component");
                        });
                    }
                    break;
                }
            }
        }
        return DocumentCommands.addInstance(id, row, at);
    }

    @Override
    public void close() {
        foldouts.values().forEach(InspectorFoldout::close);
    }
}
