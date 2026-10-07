package com.openmason.main.systems.uiEditor.document;

import com.openmason.engine.format.omui.UiNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class UiTreeTest {

    private static UiNode tree() {
        UiNode a = UiNode.of("a", "Label", List.of());
        UiNode b = UiNode.of("b", "Box", List.of(UiNode.of("c", "Label", List.of())));
        return UiNode.of("root", "Box", List.of(a, b));
    }

    @Test
    void locateInsertRemoveShareUntouchedSubtrees() {
        UiNode root = tree();
        assertEquals(new NodeLocation("b", null, 0), UiTree.locate(root, "c"));
        assertNull(UiTree.locate(root, "root"));
        UiNode added = UiTree.insert(root, NodeLocation.endOf("b"), UiNode.of("d", "Box", List.of()));
        assertSame(root.children().getFirst(), added.children().getFirst(), "structural sharing");
        assertEquals(List.of("c", "d"), added.children().get(1).children().stream().map(UiNode::id).toList());
        assertEquals(List.of("root", "a", "b", "c"), UiTree.ids(UiTree.remove(added, "d")));
        assertEquals(List.of("root", "b", "c"), UiTree.pathTo(root, "c"));
        assertEquals(List.of("b"), UiTree.topmost(root, List.of("c", "b")));
    }

    @Test
    void idsAreSanitizedAndFresh() {
        assertEquals("scroll_view", UiIds.sanitize("ScrollView"));
        assertEquals("stone_button", UiIds.sanitize("stonebreak:ui/components/stone_button"));
        Set<String> taken = new HashSet<>(Set.of("box", "box_2"));
        assertEquals("box_3", UiIds.fresh("Box", taken));
        assertEquals("label", UiIds.fresh("Label", taken));
        Map<String, String> map = new HashMap<>();
        UiNode copy = UiIds.remap(tree(), new HashSet<>(UiTree.ids(tree())), new HashSet<>(), map);
        assertEquals(4, map.size());
        for (String id : UiTree.ids(copy)) {
            assertNotEquals(-1, id.indexOf('_'), id + " is a fresh id");
        }
    }
}
