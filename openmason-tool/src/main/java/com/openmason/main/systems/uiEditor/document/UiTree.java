package com.openmason.main.systems.uiEditor.document;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiNode.ComponentInstance;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Pure queries and structural edits over an immutable {@link UiNode} tree. Every edit returns a
 * new root and shares every untouched subtree with the old one, so a document snapshot per undo
 * step costs only the changed path. Nodes live in a parent's children or, for {@code Instance}
 * nodes, in a named slot; {@link NodeLocation} addresses both.
 */
public final class UiTree {

    private UiTree() {
    }

    // ── queries ─────────────────────────────────────────────────────────────

    /** @return the node with {@code id} (children and slot content), or null */
    public static UiNode find(UiNode root, String id) {
        if (root.id().equals(id)) {
            return root;
        }
        for (UiNode c : root.children()) {
            UiNode hit = find(c, id);
            if (hit != null) {
                return hit;
            }
        }
        if (root.instance() != null) {
            for (List<UiNode> slot : root.instance().slots().values()) {
                for (UiNode c : slot) {
                    UiNode hit = find(c, id);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
        }
        return null;
    }

    /** @return where {@code id} sits, or null for the root and unknown ids */
    public static NodeLocation locate(UiNode root, String id) {
        List<UiNode> kids = root.children();
        for (int i = 0; i < kids.size(); i++) {
            if (kids.get(i).id().equals(id)) {
                return new NodeLocation(root.id(), null, i);
            }
            NodeLocation inner = locate(kids.get(i), id);
            if (inner != null) {
                return inner;
            }
        }
        if (root.instance() != null) {
            for (Map.Entry<String, List<UiNode>> slot : root.instance().slots().entrySet()) {
                List<UiNode> list = slot.getValue();
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).id().equals(id)) {
                        return new NodeLocation(root.id(), slot.getKey(), i);
                    }
                    NodeLocation inner = locate(list.get(i), id);
                    if (inner != null) {
                        return inner;
                    }
                }
            }
        }
        return null;
    }

    /** Ids from the root down to and including {@code id}; empty when absent. */
    public static List<String> pathTo(UiNode root, String id) {
        List<String> out = new ArrayList<>();
        return collectPath(root, id, out) ? out : List.of();
    }

    private static boolean collectPath(UiNode n, String id, List<String> out) {
        out.add(n.id());
        if (n.id().equals(id)) {
            return true;
        }
        for (UiNode c : n.children()) {
            if (collectPath(c, id, out)) {
                return true;
            }
        }
        if (n.instance() != null) {
            for (List<UiNode> slot : n.instance().slots().values()) {
                for (UiNode c : slot) {
                    if (collectPath(c, id, out)) {
                        return true;
                    }
                }
            }
        }
        out.removeLast();
        return false;
    }

    /** True when {@code descendant} is {@code ancestor} or lies anywhere beneath it. */
    public static boolean contains(UiNode root, String ancestor, String descendant) {
        UiNode a = find(root, ancestor);
        return a != null && find(a, descendant) != null;
    }

    /** The list {@code slot} of {@code parent} ({@code null} = children). */
    public static List<UiNode> list(UiNode parent, String slot) {
        if (slot == null) {
            return parent.children();
        }
        if (parent.instance() == null) {
            return List.of();
        }
        return parent.instance().slots().getOrDefault(slot, List.of());
    }

    /** Can {@code parent} hold nodes in {@code slot}? Children need a container widget. */
    public static boolean canHold(UiNode parent, String slot, java.util.function.Predicate<String> acceptsChildren) {
        if (slot != null) {
            return parent.instance() != null;
        }
        return !UiNode.INSTANCE_TYPE.equals(parent.type()) && acceptsChildren.test(parent.type());
    }

    // ── structural edits ────────────────────────────────────────────────────

    /** Applies {@code fn} to node {@code id}; returns {@code root} itself when the id is absent. */
    public static UiNode replace(UiNode root, String id, UnaryOperator<UiNode> fn) {
        if (root.id().equals(id)) {
            return fn.apply(root);
        }
        List<UiNode> kids = root.children();
        for (int i = 0; i < kids.size(); i++) {
            UiNode next = replace(kids.get(i), id, fn);
            if (next != kids.get(i)) {
                List<UiNode> copy = new ArrayList<>(kids);
                copy.set(i, next);
                return root.withChildren(copy);
            }
        }
        if (root.instance() != null) {
            for (Map.Entry<String, List<UiNode>> slot : root.instance().slots().entrySet()) {
                List<UiNode> list = slot.getValue();
                for (int i = 0; i < list.size(); i++) {
                    UiNode next = replace(list.get(i), id, fn);
                    if (next != list.get(i)) {
                        List<UiNode> copy = new ArrayList<>(list);
                        copy.set(i, next);
                        return withList(root, slot.getKey(), copy);
                    }
                }
            }
        }
        return root;
    }

    /** {@code parent} with its list {@code slot} replaced. */
    public static UiNode withList(UiNode parent, String slot, List<UiNode> list) {
        if (slot == null) {
            return parent.withChildren(list);
        }
        ComponentInstance inst = parent.instance();
        Map<String, List<UiNode>> slots = new LinkedHashMap<>(inst.slots());
        if (list.isEmpty()) {
            slots.remove(slot);
        } else {
            slots.put(slot, list);
        }
        return Nodes.withInstance(parent, new ComponentInstance(inst.component(), inst.params(), inst.overrides(),
            slots, inst.unknown()));
    }

    /** Removes node {@code id} (and its subtree). The root cannot be removed. */
    public static UiNode remove(UiNode root, String id) {
        NodeLocation at = locate(root, id);
        if (at == null) {
            return root;
        }
        return replace(root, at.parentId(), parent -> {
            List<UiNode> list = new ArrayList<>(list(parent, at.slot()));
            list.remove(at.index());
            return withList(parent, at.slot(), list);
        });
    }

    /** Inserts {@code node} at {@code at}; an index past the end appends. */
    public static UiNode insert(UiNode root, NodeLocation at, UiNode node) {
        return replace(root, at.parentId(), parent -> {
            List<UiNode> list = new ArrayList<>(list(parent, at.slot()));
            list.add(Math.min(at.index(), list.size()), node);
            return withList(parent, at.slot(), list);
        });
    }

    /** Every node id in the tree (children and slot content), pre-order. */
    public static List<String> ids(UiNode root) {
        return root.flatten().stream().map(UiNode::id).toList();
    }

    /** Every {@code #name} in use. */
    public static java.util.Set<String> names(UiNode root) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (UiNode n : root.flatten()) {
            if (n.name() != null) {
                out.add(n.name());
            }
        }
        return out;
    }

    /**
     * Reduces a selection to its topmost members (a node whose ancestor is also selected is
     * dropped), in document pre-order: what a delete, move or copy acts on.
     */
    public static List<String> topmost(UiNode root, java.util.Collection<String> ids) {
        java.util.Set<String> set = new java.util.HashSet<>(ids);
        List<String> out = new ArrayList<>();
        for (UiNode n : root.flatten()) {
            if (!set.contains(n.id())) {
                continue;
            }
            boolean nested = false;
            List<String> path = pathTo(root, n.id());
            for (int i = 0; i < path.size() - 1; i++) {
                if (set.contains(path.get(i))) {
                    nested = true;
                    break;
                }
            }
            if (!nested) {
                out.add(n.id());
            }
        }
        return out;
    }

    /** Convenience for literal props in tests and templates. */
    public static Map<String, UiValue> map(Object... kv) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            UiValue value = switch (v) {
                case UiValue u -> u;
                case String s -> UiValue.of(s);
                case Number n -> UiValue.of(n.doubleValue());
                case Boolean b -> UiValue.of(b);
                default -> throw new IllegalArgumentException("unsupported literal " + v);
            };
            m.put((String) kv[i], value);
        }
        return m;
    }
}
