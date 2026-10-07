package com.openmason.main.systems.uiEditor.command;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiReferences;
import com.openmason.engine.ui.runtime.widget.WidgetDescriptor;
import com.openmason.engine.ui.runtime.widget.WidgetRegistry;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.Nodes;
import com.openmason.main.systems.uiEditor.document.UiIds;
import com.openmason.main.systems.uiEditor.document.UiTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Commands over the element tree: create, delete, move (reparent and reorder), duplicate,
 * wrap, rename and per-node field edits. Selections are element keys; the tree commands act
 * on document nodes (keys without {@code /}); component internals are edited through
 * {@link OverrideCommands}.
 */
public final class NodeCommands {

    /** The registry the editor validates containers against (built-ins; providers are leaves). */
    static final WidgetRegistry WIDGETS = WidgetRegistry.withBuiltIns();

    private NodeCommands() {
    }

    /** True when nodes of {@code type} may have children. */
    public static boolean acceptsChildren(String type) {
        WidgetDescriptor d = WIDGETS.get(type);
        return d != null && d.acceptsChildren() && !UiNode.INSTANCE_TYPE.equals(type);
    }

    // ── create / delete ─────────────────────────────────────────────────────

    /** Inserts a new {@code type} node at {@code at} and selects it. */
    public static UiCommand create(String type, NodeLocation at, UnaryOperator<UiNode> init) {
        return UiCommand.of("Add " + type, ctx -> {
            UiNode parent = ctx.require(at.parentId());
            requireContainer(parent, at.slot());
            Set<String> ids = new HashSet<>(UiTree.ids(ctx.root()));
            UiNode node = Nodes.create(UiIds.fresh(type, ids), type);
            if (init != null) {
                node = init.apply(node);
            }
            ctx.setRoot(UiTree.insert(ctx.root(), at, node));
            ctx.select(List.of(node.id()));
        });
    }

    /** Inserts a prepared node (ids already unique) at {@code at} and selects it. */
    public static UiCommand insert(String label, UiNode node, NodeLocation at) {
        return UiCommand.of(label, ctx -> {
            requireContainer(ctx.require(at.parentId()), at.slot());
            for (String id : UiTree.ids(node)) {
                if (UiTree.find(ctx.root(), id) != null) {
                    throw new UiCommandException("Element id '" + id + "' is already used");
                }
            }
            ctx.setRoot(UiTree.insert(ctx.root(), at, node));
            ctx.select(List.of(node.id()));
        });
    }

    /** Deletes the document nodes among {@code keys} (and their subtrees); the root is refused. */
    public static UiCommand delete(List<String> keys) {
        return UiCommand.of(keys.size() == 1 ? "Delete " + keys.getFirst() : "Delete " + keys.size() + " elements", ctx -> {
            List<String> ids = UiTree.topmost(ctx.root(), documentNodes(keys));
            if (ids.isEmpty()) {
                throw new UiCommandException("Nothing to delete (component internals are reset, not deleted)");
            }
            if (ids.contains(ctx.root().id())) {
                throw new UiCommandException("The root element cannot be deleted");
            }
            NodeLocation first = UiTree.locate(ctx.root(), ids.getFirst());
            UiNode root = ctx.root();
            for (String id : ids) {
                root = UiTree.remove(root, id);
            }
            ctx.setRoot(root);
            ctx.select(first == null ? List.of() : List.of(first.parentId()));
        });
    }

    // ── move / reorder ──────────────────────────────────────────────────────

    /**
     * Moves the document nodes among {@code keys} to {@code target} in document order, keeping
     * their ids (identity and every reference survive a reparent). {@code target.index} is the
     * position in the target list as it is before the move.
     */
    public static UiCommand move(List<String> keys, NodeLocation target) {
        return UiCommand.of(keys.size() == 1 ? "Move " + keys.getFirst() : "Move " + keys.size() + " elements", ctx -> {
            List<String> ids = UiTree.topmost(ctx.root(), documentNodes(keys));
            if (ids.isEmpty()) {
                throw new UiCommandException("Nothing to move (component internals stay inside their component)");
            }
            UiNode parent = ctx.require(target.parentId());
            requireContainer(parent, target.slot());
            for (String id : ids) {
                if (id.equals(ctx.root().id())) {
                    throw new UiCommandException("The root element cannot be moved");
                }
                if (UiTree.contains(ctx.root(), id, target.parentId())) {
                    throw new UiCommandException("Cannot move " + id + " into itself");
                }
            }
            int index = target.index();
            List<UiNode> moving = new ArrayList<>();
            UiNode root = ctx.root();
            for (String id : ids) {
                NodeLocation from = UiTree.locate(root, id);
                if (from.sameList(target) && from.index() < index) {
                    index--; // removing an earlier sibling shifts the drop point left
                }
                moving.add(UiTree.find(root, id));
                root = UiTree.remove(root, id);
            }
            UiNode p = UiTree.find(root, target.parentId());
            index = Math.min(index, UiTree.list(p, target.slot()).size());
            for (int i = 0; i < moving.size(); i++) {
                root = UiTree.insert(root, target.at(index + i), moving.get(i));
            }
            ctx.setRoot(root);
            ctx.select(ids);
        });
    }

    /** Moves one node by {@code delta} places among its siblings (paint and layout order). */
    public static UiCommand reorder(String id, int delta) {
        String label = delta > 0 ? "Bring Forward" : "Send Backward";
        return UiCommand.of(label, ctx -> {
            NodeLocation at = UiTree.locate(ctx.root(), id);
            if (at == null) {
                throw new UiCommandException("The root has no siblings");
            }
            int size = UiTree.list(ctx.require(at.parentId()), at.slot()).size();
            int to = Math.max(0, Math.min(size - 1, at.index() + delta));
            if (to == at.index()) {
                return;
            }
            UiNode node = UiTree.find(ctx.root(), id);
            UiNode root = UiTree.remove(ctx.root(), id);
            ctx.setRoot(UiTree.insert(root, at.at(to), node));
        });
    }

    /** Bring to front ({@code toFront}) or send to back: last or first among siblings. */
    public static UiCommand reorderToEnd(String id, boolean toFront) {
        return UiCommand.of(toFront ? "Bring to Front" : "Send to Back", ctx -> {
            NodeLocation at = UiTree.locate(ctx.root(), id);
            if (at == null) {
                return;
            }
            UiNode node = UiTree.find(ctx.root(), id);
            UiNode root = UiTree.remove(ctx.root(), id);
            int to = toFront ? UiTree.list(UiTree.find(root, at.parentId()), at.slot()).size() : 0;
            ctx.setRoot(UiTree.insert(root, at.at(to), node));
        });
    }

    // ── duplicate / wrap ────────────────────────────────────────────────────

    /**
     * Duplicates the document nodes among {@code keys}: each copy lands right after its original
     * with fresh ids and names (all descendants and slot content remapped) and the copies become
     * the selection.
     */
    public static UiCommand duplicate(List<String> keys) {
        return UiCommand.of(keys.size() == 1 ? "Duplicate " + keys.getFirst() : "Duplicate " + keys.size() + " elements",
            ctx -> {
                List<String> ids = UiTree.topmost(ctx.root(), documentNodes(keys));
                ids.remove(ctx.root().id());
                if (ids.isEmpty()) {
                    throw new UiCommandException("Select elements to duplicate (the root cannot be)");
                }
                Set<String> takenIds = new HashSet<>(UiTree.ids(ctx.root()));
                Set<String> takenNames = UiTree.names(ctx.root());
                UiNode root = ctx.root();
                List<String> copies = new ArrayList<>();
                for (String id : ids) {
                    NodeLocation at = UiTree.locate(root, id);
                    UiNode copy = UiIds.remap(UiTree.find(root, id), takenIds, takenNames, new HashMap<>());
                    root = UiTree.insert(root, at.at(at.index() + 1), copy);
                    copies.add(copy.id());
                }
                ctx.setRoot(root);
                ctx.select(copies);
            });
    }

    /** Wraps the document nodes among {@code keys} (siblings) in a new {@code type} container. */
    public static UiCommand wrap(List<String> keys, String type) {
        return UiCommand.of("Wrap in " + type, ctx -> {
            if (!acceptsChildren(type)) {
                throw new UiCommandException(type + " cannot hold children");
            }
            List<String> ids = UiTree.topmost(ctx.root(), documentNodes(keys));
            if (ids.isEmpty() || ids.contains(ctx.root().id())) {
                throw new UiCommandException("Select elements other than the root to wrap");
            }
            NodeLocation at = UiTree.locate(ctx.root(), ids.getFirst());
            for (String id : ids) {
                if (!UiTree.locate(ctx.root(), id).sameList(at)) {
                    throw new UiCommandException("Only siblings can be wrapped together");
                }
            }
            Set<String> taken = new HashSet<>(UiTree.ids(ctx.root()));
            String wrapperId = UiIds.fresh(type, taken);
            List<UiNode> moved = new ArrayList<>();
            UiNode root = ctx.root();
            int index = Integer.MAX_VALUE;
            for (String id : ids) {
                index = Math.min(index, UiTree.locate(root, id).index());
            }
            for (String id : ids) {
                moved.add(UiTree.find(root, id));
            }
            for (String id : ids) {
                root = UiTree.remove(root, id);
            }
            UiNode wrapper = Nodes.create(wrapperId, type).withChildren(moved);
            ctx.setRoot(UiTree.insert(root, at.at(index), wrapper));
            ctx.select(List.of(wrapperId));
        });
    }

    // ── field edits ─────────────────────────────────────────────────────────

    /** Renames ({@code #name}); in-archive style rules using the old name are rewritten too. */
    public static UiCommand rename(String id, String newName) {
        return UiCommand.of("Rename " + id, ctx -> {
            UiNode n = ctx.require(id);
            String name = newName == null || newName.isBlank() ? null : newName.trim();
            if (name != null && !com.openmason.engine.format.omui.UiSelectors.isIdent(name)) {
                throw new UiCommandException("'" + name + "' is not a valid name (letters, digits, _ and -)");
            }
            if (java.util.Objects.equals(name, n.name())) {
                return;
            }
            if (name != null && UiTree.names(ctx.root()).contains(name)) {
                throw new UiCommandException("Another element is already named '" + name + "'");
            }
            OmuiArchive doc = ctx.doc();
            if (n.name() != null && name != null) {
                UiReferences.RenameImpact impact = UiReferences.renameImpact(doc, id, name);
                for (UiStyleSheet sheet : impact.rewrittenSheets().values()) {
                    doc = doc.withStyle(sheet);
                }
                ctx.setDoc(doc);
            }
            ctx.setRoot(UiTree.replace(ctx.root(), id, node -> Nodes.withName(node, name)));
        });
    }

    /** Sets ({@code value != null}) or clears an authored widget property on several nodes. */
    public static UiCommand setProp(List<String> ids, String prop, UiValue value) {
        String key = "prop:" + prop + ":" + String.join(",", ids);
        return UiCommand.of((value == null ? "Reset " : "Set ") + prop, key, ctx -> {
            UiNode root = ctx.root();
            for (String id : ids) {
                ctx.require(id);
                root = UiTree.replace(root, id, n -> Nodes.withProps(n, Nodes.put(n.props(), prop, value)));
            }
            ctx.setRoot(root);
        });
    }

    /** Sets or clears inline style declarations on several nodes as one step. */
    public static UiCommand setStyle(List<String> ids, Map<String, UiValue> declarations, String label) {
        String key = "style:" + String.join(",", declarations.keySet()) + ":" + String.join(",", ids);
        return UiCommand.of(label, key, ctx -> {
            UiNode root = ctx.root();
            for (String id : ids) {
                ctx.require(id);
                root = UiTree.replace(root, id, n -> {
                    Map<String, UiValue> style = n.style();
                    for (Map.Entry<String, UiValue> e : declarations.entrySet()) {
                        style = Nodes.put(style, e.getKey(), e.getValue() == UiValue.NULL ? null : e.getValue());
                    }
                    return Nodes.withStyle(n, style);
                });
            }
            ctx.setRoot(root);
        });
    }

    /** One declaration; {@code null} removes it. */
    public static UiCommand setStyle(List<String> ids, String property, UiValue value) {
        Map<String, UiValue> m = new HashMap<>();
        m.put(property, value == null ? UiValue.NULL : value);
        return setStyle(ids, m, (value == null ? "Reset " : "Set ") + property);
    }

    public static UiCommand setClasses(String id, List<String> classes) {
        return UiCommand.of("Edit classes of " + id, ctx -> {
            for (String c : classes) {
                if (!com.openmason.engine.format.omui.UiSelectors.isIdent(c)) {
                    throw new UiCommandException("'" + c + "' is not a valid class name");
                }
            }
            ctx.require(id);
            ctx.setRoot(UiTree.replace(ctx.root(), id, n -> Nodes.withClasses(n, classes)));
        });
    }

    public static UiCommand setDataSource(String id, String path) {
        return UiCommand.of("Set data source of " + id, ctx -> {
            ctx.require(id);
            ctx.setRoot(UiTree.replace(ctx.root(), id, n -> Nodes.withDataSource(n, path)));
        });
    }

    /** Adds, replaces ({@code binding != null}) or removes the binding of {@code target}. */
    public static UiCommand setBinding(String id, String target, UiNode.UiBinding binding) {
        return UiCommand.of(binding == null ? "Unbind " + target : "Bind " + target, ctx -> {
            ctx.require(id);
            ctx.setRoot(UiTree.replace(ctx.root(), id, n -> Nodes.withBinding(n, target, binding)));
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** The document-node ids among selection keys (component internals have a {@code /}). */
    public static List<String> documentNodes(List<String> keys) {
        List<String> out = new ArrayList<>();
        for (String k : keys) {
            if (k.indexOf('/') < 0) {
                out.add(k);
            }
        }
        return out;
    }

    static void requireContainer(UiNode parent, String slot) throws UiCommandException {
        if (!UiTree.canHold(parent, slot, NodeCommands::acceptsChildren)) {
            throw new UiCommandException(parent.type() + " '" + parent.id() + "' cannot hold "
                + (slot == null ? "children" : "slot content"));
        }
    }
}
