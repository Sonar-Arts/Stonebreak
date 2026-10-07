package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.ui.runtime.widget.WidgetDescriptor;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.uiEditor.command.NodeCommands;
import com.openmason.main.systems.uiEditor.command.OverrideCommands;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import imgui.ImGui;

import java.util.List;

/**
 * The element context menu, shared by the canvas and the hierarchy so both offer the same
 * actions with the same shortcut labels (read from the keybind registry).
 */
final class ContextMenus {

    private ContextMenus() {
    }

    static void elementMenu(UiEditorContext ctx) {
        UiEditorDocument doc = ctx.doc();
        if (doc == null) {
            return;
        }
        UiEditorActions a = ctx.actions;
        List<String> sel = doc.selection();
        List<String> nodes = NodeCommands.documentNodes(sel);
        boolean any = !sel.isEmpty();
        boolean internalOnly = any && nodes.isEmpty();
        String rootId = doc.archive().document().root().id();
        boolean onlyRoot = nodes.size() == 1 && nodes.getFirst().equals(rootId);

        if (ImGui.beginMenu("Add Child", !internalOnly)) {
            for (WidgetDescriptor d : NodeCommandsAccess.builtIns()) {
                if (ImGui.menuItem(d.type())) {
                    a.add(d.type());
                }
            }
            ImGui.endMenu();
        }
        if (ImGui.beginMenu("Wrap With", !nodes.isEmpty() && !onlyRoot)) {
            for (String type : List.of("Box", "Button", "ScrollView")) {
                if (ImGui.menuItem(type)) {
                    a.wrapSelection(type);
                }
            }
            ImGui.endMenu();
        }
        ImGui.separator();
        if (ImGui.menuItem("Cut", shortcut("ui.cut"), false, !nodes.isEmpty() && !onlyRoot)) {
            a.cutSelection();
        }
        if (ImGui.menuItem("Copy", shortcut("ui.copy"), false, !nodes.isEmpty() && !onlyRoot)) {
            a.copySelection();
        }
        if (ImGui.menuItem("Paste", shortcut("ui.paste"), false, a.canPaste())) {
            a.paste();
        }
        if (ImGui.menuItem("Duplicate", shortcut("ui.duplicate"), false, !nodes.isEmpty() && !onlyRoot)) {
            a.duplicateSelection();
        }
        if (internalOnly) {
            if (ImGui.menuItem("Reset to Component Source", shortcut("ui.delete"))) {
                a.deleteSelection();
            }
        } else if (ImGui.menuItem("Delete", shortcut("ui.delete"), false, !nodes.isEmpty() && !onlyRoot)) {
            a.deleteSelection();
        }
        ImGui.separator();
        if (ImGui.menuItem("Rename", shortcut("ui.rename"), false, nodes.size() == 1)) {
            ctx.renameRequest = nodes.getFirst();
            ctx.focusWindow = HierarchyPanel.TITLE;
        }
        if (ImGui.beginMenu("Arrange", !nodes.isEmpty() && !onlyRoot)) {
            if (ImGui.menuItem("Bring to Front")) {
                a.reorderSelection(Integer.MAX_VALUE);
            }
            if (ImGui.menuItem("Bring Forward", shortcut("ui.forward"))) {
                a.reorderSelection(1);
            }
            if (ImGui.menuItem("Send Backward", shortcut("ui.backward"))) {
                a.reorderSelection(-1);
            }
            if (ImGui.menuItem("Send to Back")) {
                a.reorderSelection(Integer.MIN_VALUE);
            }
            ImGui.endMenu();
        }
        if (ImGui.menuItem("Select Parent", shortcut("ui.select_parent"), false, any && !onlyRoot)) {
            a.selectParent();
        }
        if (ImGui.menuItem("Frame Selection", shortcut("ui.frame"), false, any)) {
            ctx.frameSelectionRequest = true;
        }
        if (sel.size() == 1) {
            String key = sel.getFirst();
            ImGui.separator();
            DesignerRuntime rt = ctx.runtime();
            if (rt != null && ImGui.beginMenu("Force State")) {
                for (String st : List.of("hover", "active", "focus", "focus-visible", "checked", "disabled", "invalid")) {
                    boolean on = rt.forcedStates(key).contains(st);
                    if (ImGui.menuItem(":" + st, "", on)) {
                        rt.forceState(key, st, !on);
                    }
                }
                ImGui.endMenu();
            }
            if (key.indexOf('/') >= 0) {
                OverrideCommands.Target t = OverrideCommands.Target.of(key);
                UiNode inst = ctx.node(t.instanceId());
                boolean overridden = inst != null && inst.instance() != null
                    && !OverrideCommands.under(inst.instance(), t.target()).isEmpty();
                if (ImGui.menuItem("Select Instance")) {
                    doc.select(List.of(t.instanceId()));
                }
                if (ImGui.menuItem("Reset Overrides", "", false, overridden)) {
                    a.run(OverrideCommands.reset(key));
                }
            }
            DocumentViewState v = ctx.view();
            if (v != null) {
                boolean hidden = v.hidden.contains(key);
                if (ImGui.menuItem(hidden ? "Show in Designer" : "Hide in Designer")) {
                    toggleHidden(ctx, key);
                }
                boolean locked = v.locked.contains(key);
                if (ImGui.menuItem(locked ? "Unlock" : "Lock")) {
                    if (locked) {
                        v.locked.remove(key);
                    } else {
                        v.locked.add(key);
                    }
                }
            }
            if (ImGui.menuItem("Copy ID")) {
                ImGui.setClipboardText(key);
            }
        }
    }

    /** Designer-only visibility: a runtime style on the design view, never the source. */
    static void toggleHidden(UiEditorContext ctx, String key) {
        DocumentViewState v = ctx.view();
        if (v == null) {
            return;
        }
        if (!v.hidden.remove(key)) {
            v.hidden.add(key);
        }
        DesignerRuntime rt = ctx.runtime();
        if (rt != null) {
            rt.applyDesignerVisibility(v.hidden);
        }
    }

    static String shortcut(String actionId) {
        try {
            String s = KeybindRegistry.getInstance().getShortcutDisplayName(actionId);
            return s == null ? "" : s;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Built-in widget types an author places (Instance comes from components). */
    private static final class NodeCommandsAccess {
        static List<WidgetDescriptor> builtIns() {
            return com.openmason.engine.ui.runtime.widget.BuiltInWidgets.all().stream()
                .filter(d -> !UiNode.INSTANCE_TYPE.equals(d.type())).toList();
        }
    }
}
