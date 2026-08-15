package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.scene.ModelInstance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which scene instances are selected, with the usual click / ctrl-click / shift-click
 * semantics.
 *
 * <p>Ordered: the first selected instance is the "primary", which is what the inspector
 * shows and what a gizmo drag pivots on. Deliberately free of ImGui imports so the
 * selection rules can be tested without a UI.
 */
public class SceneSelectionState {

    private final Set<String> selectedIds = new LinkedHashSet<>();

    /** Anchor for shift-range selection: the last id chosen by a plain or ctrl click. */
    private String anchorId;

    /** Replace the selection with one instance. */
    public void select(String id) {
        selectedIds.clear();
        if (id != null) {
            selectedIds.add(id);
            anchorId = id;
        }
    }

    /** Ctrl-click: add or remove one instance, leaving the rest alone. */
    public void toggle(String id) {
        if (id == null) {
            return;
        }
        if (!selectedIds.remove(id)) {
            selectedIds.add(id);
        }
        anchorId = id;
    }

    /**
     * Shift-click: select the inclusive range between the anchor and {@code id}, in the
     * scene's own order. With no anchor this degenerates to a plain click.
     */
    public void selectRangeTo(String id, List<ModelInstance> ordered) {
        if (id == null || ordered == null || ordered.isEmpty()) {
            return;
        }
        if (anchorId == null) {
            select(id);
            return;
        }

        int from = indexOf(ordered, anchorId);
        int to = indexOf(ordered, id);
        if (from < 0 || to < 0) {
            select(id);
            return;
        }

        selectedIds.clear();
        for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
            selectedIds.add(ordered.get(i).id());
        }
        // The anchor deliberately stays put, so dragging the shift-selection back and
        // forth grows and shrinks from the same end.
    }

    /** Drop an instance from the selection — call when it is deleted. */
    public void remove(String id) {
        selectedIds.remove(id);
        if (id != null && id.equals(anchorId)) {
            anchorId = selectedIds.isEmpty() ? null : selectedIds.iterator().next();
        }
    }

    public void clear() {
        selectedIds.clear();
        anchorId = null;
    }

    public boolean isSelected(String id) {
        return id != null && selectedIds.contains(id);
    }

    public boolean isEmpty() {
        return selectedIds.isEmpty();
    }

    public int size() {
        return selectedIds.size();
    }

    /** The instance the inspector edits; null when nothing is selected. */
    public String primary() {
        return selectedIds.isEmpty() ? null : selectedIds.iterator().next();
    }

    public List<String> selectedIds() {
        return List.copyOf(selectedIds);
    }

    /** Selected instances, in scene order. */
    public List<ModelInstance> resolve(List<ModelInstance> ordered) {
        List<ModelInstance> out = new ArrayList<>();
        if (ordered == null) {
            return out;
        }
        for (ModelInstance instance : ordered) {
            if (selectedIds.contains(instance.id())) {
                out.add(instance);
            }
        }
        return out;
    }

    private static int indexOf(List<ModelInstance> ordered, String id) {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
