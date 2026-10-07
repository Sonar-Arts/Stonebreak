package com.openmason.engine.ui.assets.export;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiValidator;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.ui.assets.DependencyRefs;
import com.openmason.engine.ui.assets.Resolution;
import com.openmason.engine.ui.assets.ResolvedAsset;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Walks the components an export carries (shared ones as resolved now, and components
 * embedded inside them) and checks that the document's single dependency table covers every
 * shared dependency they need: at runtime all of them resolve through that one table. Also
 * detects recursive composition across the whole set.
 */
final class ComponentClosure {

    private static final int MAX_DEPTH = 32;

    private ComponentClosure() {
    }

    /** @return every component document reached, by id */
    static Map<String, OmuiArchive> check(OmuiArchive doc, Resolution resolution, UiDiagnostics d) {
        Map<String, OmuiArchive> byId = new LinkedHashMap<>();
        for (UiDependency row : doc.dependencies().entries()) {
            ResolvedAsset asset = resolution.get(row.id());
            if (row.kind() != UiDependency.Kind.COMPONENT || asset == null || !asset.id().equals(row.id())) {
                continue;
            }
            OmuiArchive comp = parse(asset.bytes(), row.id(), d);
            if (comp != null) {
                byId.put(row.id(), comp);
                walk(doc, DependencyRefs.closure(doc, row.id()), comp, row.id(), byId, d, 0);
            }
        }
        String self = doc.manifest().documentId();
        OmuiValidator.componentCycles(doc, id -> id.equals(self) ? doc : byId.get(id), d);
        return byId;
    }

    private static void walk(OmuiArchive host, Set<String> hostClosure, OmuiArchive comp, String path,
                             Map<String, OmuiArchive> byId, UiDiagnostics d, int depth) {
        for (UiDependency r : comp.dependencies().entries()) {
            if (r.mode() == UiDependency.Mode.SHARED) {
                if (host.dependencies().find(r.id()) == null) {
                    d.error(Code.UNRESOLVED_REFERENCE, OmuiFormat.DEPENDENCIES, "", "Component " + path
                            + " needs shared '" + r.id() + "', which the document's dependency table does not list");
                } else if (!hostClosure.contains(r.id())) {
                    d.warning(Code.UNRESOLVED_REFERENCE, OmuiFormat.DEPENDENCIES, "", "Component " + path
                            + " needs '" + r.id() + "' but its row does not require it; embedding it would leave it behind");
                }
            } else if (r.kind() == UiDependency.Kind.COMPONENT && depth < MAX_DEPTH) {
                UiBytes bytes = comp.assets().get(r.entry());
                OmuiArchive nested = bytes == null ? null : parse(bytes, r.id(), d);
                if (nested != null) {
                    byId.putIfAbsent(r.id(), nested);
                    walk(host, hostClosure, nested, path + " > " + r.id(), byId, d, depth + 1);
                }
            }
        }
    }

    private static OmuiArchive parse(UiBytes bytes, String id, UiDiagnostics d) {
        try {
            return OmuiReader.read(bytes.toArray()).archive();
        } catch (UiFormatException e) {
            d.error(Code.INVALID_VALUE, OmuiFormat.DEPENDENCIES, "", "Component '" + id + "' cannot be read");
            d.addAll(e.diagnostics());
            return null;
        }
    }
}
