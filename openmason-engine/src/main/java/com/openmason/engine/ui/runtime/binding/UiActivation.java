package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiRequirements;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataRoot;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.runtime.UiDocumentSource;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The gate a screen passes before it activates (#289): what the document (and every component
 * it instantiates) needs from the host, checked against what the host offers, so a missing
 * capability shows up front instead of as a dead button or an empty label later.
 *
 * <ul>
 *   <li>the manifest contracts: {@code uiApi}, layout semantics, required features, host APIs
 *       and providers ({@link UiHost#check}) — a missing required one is an error, a missing
 *       optional one a warning;</li>
 *   <li>every absolute data path (node {@code dataSource}s and binding paths) must name a root
 *       the host registered ({@code UNKNOWN_DATA_SOURCE});</li>
 *   <li>a root whose contract the manifest does not declare is a warning
 *       ({@code UNDECLARED_HOST_API}): the document works here but would not tell a host that
 *       lacks it.</li>
 * </ul>
 */
public final class UiActivation {

    private UiActivation() {
    }

    /** Checks an exported screen: its manifest's requirement union and its embedded source. */
    public static List<UiDiagnostic> check(SbuiArchive sbui, UiDocumentSource components, UiHost host) {
        return check(sbui.source(), sbui.manifest(), components, host);
    }

    /** Checks an authoring document against {@code host}. */
    public static List<UiDiagnostic> check(OmuiArchive doc, UiDocumentSource components, UiHost host) {
        return check(doc, doc.manifest(), components, host);
    }

    /**
     * @throws UiActivationException when anything is an error; the diagnostics travel with it
     */
    public static List<UiDiagnostic> require(SbuiArchive sbui, UiDocumentSource components, UiHost host) {
        return require(sbui.manifest().assetId(), check(sbui, components, host));
    }

    public static List<UiDiagnostic> require(OmuiArchive doc, UiDocumentSource components, UiHost host) {
        return require(doc.manifest().documentId(), check(doc, components, host));
    }

    private static List<UiDiagnostic> require(String id, List<UiDiagnostic> found) {
        if (found.stream().anyMatch(UiDiagnostic::isError)) {
            throw new UiActivationException(id, found);
        }
        return found;
    }

    private static List<UiDiagnostic> check(OmuiArchive doc, UiRequirements req, UiDocumentSource components,
                                            UiHost host) {
        UiDiagnostics d = new UiDiagnostics();
        d.addAll(host.check(req));
        Set<String> declared = req.hostApis().stream().map(UiManifest.HostRequirement::id).collect(Collectors.toSet());
        Deque<String> stack = new ArrayDeque<>();
        walk(doc, OmuiFormat.DOCUMENT, components == null ? UiDocumentSource.EMPTY : components, host, declared, d, stack);
        return d.list();
    }

    private static void walk(OmuiArchive doc, String entry, UiDocumentSource components, UiHost host,
                             Set<String> declared, UiDiagnostics d, Deque<String> stack) {
        String id = doc.manifest().documentId();
        if (stack.contains(id)) {
            return; // recursion is the builder's diagnostic
        }
        stack.push(id);
        node(doc.document().root(), "/root", entry, components, host, declared, d, stack);
        stack.pop();
    }

    private static void node(UiNode n, String ptr, String entry, UiDocumentSource components, UiHost host,
                             Set<String> declared, UiDiagnostics d, Deque<String> stack) {
        if (n.dataSource() != null) {
            path(n.dataSource(), ptr + "/dataSource", entry, host, declared, d);
        }
        for (int i = 0; i < n.bindings().size(); i++) {
            path(n.bindings().get(i).path(), ptr + "/bindings/" + i + "/path", entry, host, declared, d);
        }
        for (int i = 0; i < n.children().size(); i++) {
            node(n.children().get(i), ptr + "/children/" + i, entry, components, host, declared, d, stack);
        }
        if (n.instance() != null) {
            for (Map.Entry<String, List<UiNode>> slot : n.instance().slots().entrySet()) {
                for (int i = 0; i < slot.getValue().size(); i++) {
                    node(slot.getValue().get(i), ptr + "/instance/slots/" + slot.getKey() + "/" + i, entry,
                        components, host, declared, d, stack);
                }
            }
            OmuiArchive component = components.component(n.instance().component());
            if (component != null) {
                walk(component, "component " + n.instance().component() + " " + OmuiFormat.DOCUMENT,
                    components, host, declared, d, stack);
            }
        }
    }

    private static void path(String raw, String ptr, String entry, UiHost host, Set<String> declared,
                             UiDiagnostics d) {
        DataPath p;
        try {
            p = DataPath.parse(raw);
        } catch (IllegalArgumentException e) {
            return; // the format validator reports malformed paths
        }
        if (p.relative()) {
            return;
        }
        DataRoot root = host.data().root(p.rootName());
        if (root == null) {
            d.error(UiDiagnostic.Code.UNKNOWN_DATA_SOURCE, entry, ptr,
                "data root '" + p.rootName() + "' (" + raw + ") is not provided by this host");
        } else if (!declared.contains(root.contract().id())) {
            d.warning(UiDiagnostic.Code.UNDECLARED_HOST_API, entry, ptr,
                "'" + p.rootName() + "' belongs to host contract " + root.contract().id()
                    + ", which the manifest does not list in hostApis");
        }
    }
}
