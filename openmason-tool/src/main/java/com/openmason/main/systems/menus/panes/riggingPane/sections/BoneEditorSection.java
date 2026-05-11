package com.openmason.main.systems.menus.panes.riggingPane.sections;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.skeleton.BoneStore;
import com.openmason.main.systems.themes.utils.ImGuiComponents;
import com.openmason.main.systems.themes.utils.TransformGroupWidget;
import imgui.ImGui;
import imgui.type.ImFloat;
import imgui.type.ImString;

/**
 * Inspector for the currently-selected bone. Uses the shared
 * {@link TransformGroupWidget} so the bone transform UI matches the part
 * transform slideout style.
 */
public class BoneEditorSection {

    private BoneStore boneStore;
    private final ImString nameBuffer = new ImString(64);
    private final ImFloat originX = new ImFloat(), originY = new ImFloat(), originZ = new ImFloat();
    private final ImFloat posX = new ImFloat(), posY = new ImFloat(), posZ = new ImFloat();
    private final ImFloat rotX = new ImFloat(), rotY = new ImFloat(), rotZ = new ImFloat();
    private final ImFloat endX = new ImFloat(), endY = new ImFloat(), endZ = new ImFloat();
    private String lastSyncedId;
    private Runnable onSkeletonChanged;

    public void setBoneStore(BoneStore boneStore) { this.boneStore = boneStore; }
    public void setOnSkeletonChanged(Runnable cb) { this.onSkeletonChanged = cb; }

    /**
     * Render the inspector for the given bone id (may be null).
     */
    public void render(String selectedBoneId) {
        if (boneStore == null) return;

        ImGuiComponents.renderCompactSectionHeader("Bone Inspector");
        ImGui.spacing();

        OMOFormat.BoneEntry bone = selectedBoneId == null ? null : boneStore.getById(selectedBoneId);
        if (bone == null) {
            ImGui.textDisabled("Select a bone in the hierarchy");
            return;
        }

        syncIfChanged(bone);

        if (ImGui.inputText("Name", nameBuffer)) {
            String newName = nameBuffer.get().trim();
            if (!newName.isEmpty() && !newName.equals(bone.name())) {
                replace(new OMOFormat.BoneEntry(
                        bone.id(), newName, bone.parentBoneId(),
                        bone.originX(), bone.originY(), bone.originZ(),
                        bone.posX(), bone.posY(), bone.posZ(),
                        bone.rotX(), bone.rotY(), bone.rotZ(),
                        bone.endpointX(), bone.endpointY(), bone.endpointZ()));
            }
        }

        ImGui.spacing();

        boolean changed = false;
        changed |= TransformGroupWidget.render("Origin", "bone_origin",
                originX, originY, originZ, 0.05f, "%.3f");
        ImGui.spacing();
        changed |= TransformGroupWidget.render("Position", "bone_pos",
                posX, posY, posZ, 0.05f, "%.3f");
        ImGui.spacing();
        changed |= TransformGroupWidget.render("Rotation", "bone_rot",
                rotX, rotY, rotZ, 0.5f, "%.1f");
        ImGui.spacing();
        changed |= TransformGroupWidget.render("Endpoint", "bone_end",
                endX, endY, endZ, 0.05f, "%.3f");

        if (changed) {
            replace(new OMOFormat.BoneEntry(
                    bone.id(), bone.name(), bone.parentBoneId(),
                    originX.get(), originY.get(), originZ.get(),
                    posX.get(), posY.get(), posZ.get(),
                    rotX.get(), rotY.get(), rotZ.get(),
                    endX.get(), endY.get(), endZ.get()));
        }

        ImGui.spacing();
        String parentLabel = bone.isRoot() ? "(root)" : parentNameOf(bone.parentBoneId());
        ImGui.textDisabled("Parent: " + parentLabel);
    }

    /** Pulls fresh values from the store when the selected bone changes. */
    private void syncIfChanged(OMOFormat.BoneEntry bone) {
        if (bone.id().equals(lastSyncedId)) return;
        nameBuffer.set(bone.name());
        originX.set(bone.originX());     originY.set(bone.originY());     originZ.set(bone.originZ());
        posX.set(bone.posX());           posY.set(bone.posY());           posZ.set(bone.posZ());
        rotX.set(bone.rotX());           rotY.set(bone.rotY());           rotZ.set(bone.rotZ());
        endX.set(bone.endpointX());      endY.set(bone.endpointY());      endZ.set(bone.endpointZ());
        lastSyncedId = bone.id();
    }

    private String parentNameOf(String id) {
        OMOFormat.BoneEntry parent = boneStore.getById(id);
        if (parent != null) return parent.name();
        return id == null ? "(root)" : "<part>"; // Cross-type parent (a part)
    }

    private void replace(OMOFormat.BoneEntry entry) {
        boneStore.put(entry);
        lastSyncedId = null; // Force re-sync on next frame so subsequent edits pick up latest values.
        if (onSkeletonChanged != null) onSkeletonChanged.run();
    }
}
