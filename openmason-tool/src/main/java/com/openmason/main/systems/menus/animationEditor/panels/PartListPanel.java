package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import imgui.ImGui;

import java.util.List;

/**
 * Scrollable list of all parts on the bound model. Selection drives the
 * timeline target and the keyframe inspector.
 */
public final class PartListPanel {

    private final AnimationEditorController controller;

    public PartListPanel(AnimationEditorController controller) {
        this.controller = controller;
    }

    public void render() {
        ImGui.separatorText("Parts");

        ModelPartManager pm = controller.partManager();
        if (pm == null) {
            ImGui.textDisabled("No model bound.");
            return;
        }
        List<ModelPartDescriptor> parts = pm.getAllParts();
        if (parts.isEmpty()) {
            ImGui.textDisabled("Model has no parts.");
            return;
        }

        AnimationClip clip = controller.state().clip();
        String selected = controller.state().selectedPartId();
        for (ModelPartDescriptor part : parts) {
            boolean isSel = part.id().equals(selected);
            Track track = clip.trackFor(part.id());
            int kfCount = track != null ? track.size() : 0;
            String label = kfCount > 0
                    ? String.format("%s  (%d kf)", part.name(), kfCount)
                    : part.name();
            if (ImGui.selectable(label, isSel)) {
                controller.state().setSelectedPartId(part.id());
            }
        }
    }
}
