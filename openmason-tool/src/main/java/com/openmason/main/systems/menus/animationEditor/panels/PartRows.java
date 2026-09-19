package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.menus.animationEditor.data.Track;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The row order shared by the part list and the timeline: model parts in
 * hierarchy order (depth-first from the roots, children indented under their
 * parent), followed by any <em>unbound</em> tracks — tracks whose partId
 * matches no part on the model. Keeping one ordering here means the two
 * panels always line up and both show orphans, which would otherwise be
 * invisible yet still saved.
 */
public final class PartRows {

    /**
     * One row.
     *
     * @param id     part id (or the orphan track's saved partId)
     * @param label  display name
     * @param depth  hierarchy depth (0 = root); orphans are 0
     * @param part   the model part, or null for an orphan track row
     * @param track  the orphan track, or null for a part row
     */
    public record Row(String id, String label, int depth, ModelPartDescriptor part, Track track) {
        public boolean isOrphan() { return part == null; }
    }

    private PartRows() {}

    public static List<Row> build(ModelPartManager pm, List<Track> orphans) {
        List<Row> rows = new ArrayList<>();
        if (pm != null) {
            Set<String> visited = new HashSet<>();
            List<ModelPartDescriptor> all = pm.getAllParts();
            Set<String> ids = new HashSet<>();
            for (ModelPartDescriptor p : all) ids.add(p.id());
            // Roots: no parent, or a parent that is not a part (e.g. a bone).
            for (ModelPartDescriptor p : all) {
                if (p.parentId() == null || !ids.contains(p.parentId())) {
                    walk(pm, p, 0, rows, visited);
                }
            }
            // Anything left (cycles in malformed data) is appended flat.
            for (ModelPartDescriptor p : all) {
                if (visited.add(p.id())) {
                    rows.add(new Row(p.id(), p.name(), 0, p, null));
                }
            }
        }
        if (orphans != null) {
            for (Track t : orphans) {
                String hint = t.partNameHint();
                String label = (hint != null && !hint.isBlank()) ? hint : shortId(t.partId());
                rows.add(new Row(t.partId(), label, 0, null, t));
            }
        }
        return rows;
    }

    private static void walk(ModelPartManager pm, ModelPartDescriptor p, int depth,
                             List<Row> out, Set<String> visited) {
        if (!visited.add(p.id())) return;
        out.add(new Row(p.id(), p.name(), depth, p, null));
        for (ModelPartDescriptor child : pm.getChildren(p.id())) {
            walk(pm, child, depth + 1, out, visited);
        }
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) + "…" : id;
    }
}
