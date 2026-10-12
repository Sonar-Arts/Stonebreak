package com.openmason.main.systems.uiEditor.canvas;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.layout.HitTester;
import com.openmason.main.systems.uiEditor.command.NodeCommands;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.UiTree;

import java.util.List;
import java.util.Set;

/**
 * Where a drop on the canvas lands in flow layout: the innermost document container under the
 * pointer and the insertion index along its main axis (before the first child whose centre lies
 * past the pointer). The indicator is the insertion line, in frame pixels, for the designer to
 * draw, as in Unreal's and Figma's auto-layout drop feedback.
 */
public final class DropTargets {

    /** A resolved drop: the location, the container's key and the indicator line (x0,y0)-(x1,y1). */
    public record Drop(NodeLocation location, String containerKey, float x0, float y0, float x1, float y1) {
    }

    private DropTargets() {
    }

    /**
     * @param excluded document node ids being dragged; they and their subtrees are never targets
     * @return the drop, or null when the pointer is over no container
     */
    public static Drop resolve(UiDocumentInstance ui, UiNode docRoot, float fx, float fy, Set<String> excluded) {
        UiElement hit = HitTester.pickDesign(ui.paintOrder(), fx, fy, el -> true);
        UiElement container = null;
        for (UiElement e = hit; e != null; e = e.parent()) {
            if (e.key().indexOf('/') >= 0 || excluded.contains(e.key()) || isInsideExcluded(docRoot, e.key(), excluded)) {
                continue;
            }
            UiNode n = UiTree.find(docRoot, e.key());
            if (n != null && NodeCommands.acceptsChildren(n.type())) {
                container = e;
                break;
            }
        }
        if (container == null) {
            container = ui.root();
            if (container == null || excluded.contains(container.key())) {
                return null;
            }
        }
        UiNode node = UiTree.find(docRoot, container.key());
        if (node == null || !NodeCommands.acceptsChildren(node.type())) {
            return null;
        }
        boolean row = container.computedStyle().keyword("flex-direction", "column").startsWith("row");
        boolean reverse = container.computedStyle().keyword("flex-direction", "column").endsWith("reverse");
        List<UiNode> kids = node.children();
        UiRect cr = container.rect();
        int index = kids.size();
        UiRect before = null;
        UiRect after = null;
        for (int i = 0; i < kids.size(); i++) {
            UiElement child = ui.find(kids.get(i).id());
            if (child == null || child.rect().isEmpty() || isAbsolute(child)) {
                continue;
            }
            UiRect r = child.rect();
            float centre = row ? r.x() + r.width() / 2f : r.y() + r.height() / 2f;
            float p = row ? fx : fy;
            boolean pointerFirst = reverse ? p > centre : p < centre;
            if (pointerFirst) {
                index = i;
                after = r;
                break;
            }
            before = r;
        }
        float x0;
        float y0;
        float x1;
        float y1;
        if (row) {
            float x = after != null && before != null ? (before.right() + after.x()) / 2f
                : after != null ? after.x() - 2 : before != null ? before.right() + 2 : cr.x() + 4;
            UiRect ref = after != null ? after : before;
            y0 = ref != null ? ref.y() : cr.y() + 4;
            y1 = ref != null ? ref.bottom() : cr.bottom() - 4;
            x0 = x;
            x1 = x;
        } else {
            float y = after != null && before != null ? (before.bottom() + after.y()) / 2f
                : after != null ? after.y() - 2 : before != null ? before.bottom() + 2 : cr.y() + 4;
            UiRect ref = after != null ? after : before;
            x0 = ref != null ? ref.x() : cr.x() + 4;
            x1 = ref != null ? ref.right() : cr.right() - 4;
            y0 = y;
            y1 = y;
        }
        return new Drop(NodeLocation.childAt(node.id(), index), container.key(), x0, y0, x1, y1);
    }

    static boolean isAbsolute(UiElement e) {
        return "absolute".equals(e.computedStyle().keyword("position", "relative"));
    }

    private static boolean isInsideExcluded(UiNode root, String key, Set<String> excluded) {
        for (String id : UiTree.pathTo(root, key)) {
            if (excluded.contains(id)) {
                return true;
            }
        }
        return false;
    }
}
