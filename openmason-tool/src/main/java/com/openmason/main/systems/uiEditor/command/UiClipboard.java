package com.openmason.main.systems.uiEditor.command;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.ui.assets.DependencyRefs;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.Nodes;
import com.openmason.main.systems.uiEditor.document.UiIds;
import com.openmason.main.systems.uiEditor.document.UiTree;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copy and paste of elements between documents. The payload is itself a small OMUI archive
 * (written and read by the format codecs, so nothing is lost in transit): the copied nodes
 * under a placeholder root, the dependency rows they reference (with their {@code requires}
 * closure) and the embedded bytes of embedded rows. Pasting remaps every id and name, adds the
 * rows the target lacks and never replaces a row it already has.
 *
 * <p>The text form ({@link #toText}) goes on the system clipboard so a copy survives switching
 * documents and even editor restarts.
 */
public final class UiClipboard {

    public static final String TEXT_PREFIX = "OPENMASON-UI-ELEMENTS/1:";
    private static final String HOLDER = "clipboard_root";

    private UiClipboard() {
    }

    /** The payload for the document nodes among {@code keys}, or null when nothing copyable is selected. */
    public static OmuiArchive copy(OmuiArchive doc, List<String> keys) {
        UiNode root = doc.document().root();
        List<String> ids = UiTree.topmost(root, NodeCommands.documentNodes(keys));
        ids.remove(root.id());
        if (ids.isEmpty()) {
            return null;
        }
        List<UiNode> nodes = new ArrayList<>();
        ids.forEach(id -> nodes.add(UiTree.find(root, id)));
        UiNode holder = Nodes.create(HOLDER, "Box").withChildren(nodes);
        OmuiArchive probe = OmuiArchive.of(doc.manifest(), new UiDocument(holder, List.of(), null, null, Map.of()))
            .withDependencies(doc.dependencies());
        Set<String> needed = DependencyRefs.reachable(doc, DependencyRefs.referenced(probe));
        List<UiDependency> rows = new ArrayList<>();
        Map<String, UiBytes> assets = new LinkedHashMap<>();
        for (UiDependency d : doc.dependencies().entries()) {
            if (needed.contains(d.id())) {
                rows.add(d);
                if (d.entry() != null && doc.assets().containsKey(d.entry())) {
                    assets.put(d.entry(), doc.assets().get(d.entry()));
                }
            }
        }
        UiManifest m = UiManifest.create("openmason:clipboard", UiManifest.DocumentKind.SCREEN, "Clipboard");
        OmuiArchive payload = new OmuiArchive(m, new UiDocument(holder, List.of(), null, null, Map.of()), Map.of(),
            Map.of(), Map.of(), Map.of(), Map.of(), new UiDependencies(rows, Map.of()), assets, Map.of(), Map.of());
        return UiHistory.withRequiredFeatures(payload);
    }

    /** Encodes a payload for the system clipboard; null when the codecs refuse it. */
    public static String toText(OmuiArchive payload) {
        try {
            return TEXT_PREFIX + Base64.getEncoder().encodeToString(OmuiWriter.write(payload));
        } catch (UiFormatException e) {
            return null;
        }
    }

    /** Decodes clipboard text; null when it is not an element payload. */
    public static OmuiArchive fromText(String text) {
        if (text == null || !text.startsWith(TEXT_PREFIX)) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(text.substring(TEXT_PREFIX.length()).trim());
            return OmuiReader.read(bytes).archive();
        } catch (IllegalArgumentException | UiFormatException e) {
            return null;
        }
    }

    /** Pastes {@code payload}'s elements at {@code at}; they become the selection. */
    public static UiCommand paste(OmuiArchive payload, NodeLocation at) {
        int count = payload.document().root().children().size();
        return UiCommand.of(count == 1 ? "Paste element" : "Paste " + count + " elements", ctx -> {
            NodeCommands.requireContainer(ctx.require(at.parentId()), at.slot());
            for (UiDependency row : payload.dependencies().entries()) {
                UiBytes bytes = row.entry() == null ? null : payload.assets().get(row.entry());
                DocumentCommands.ensure(ctx, row, bytes);
            }
            Set<String> takenIds = new HashSet<>(UiTree.ids(ctx.root()));
            Set<String> takenNames = UiTree.names(ctx.root());
            UiNode root = ctx.root();
            List<String> pasted = new ArrayList<>();
            int index = at.index();
            for (UiNode n : payload.document().root().children()) {
                UiNode copy = UiIds.remap(n, takenIds, takenNames, new HashMap<>());
                root = UiTree.insert(root, at.at(index == Integer.MAX_VALUE ? index : index++), copy);
                pasted.add(copy.id());
            }
            ctx.setRoot(root);
            ctx.select(pasted);
        });
    }
}
