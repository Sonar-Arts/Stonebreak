package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.engine.format.oma.AnimLayerMeta;
import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import imgui.ImGui;
import imgui.type.ImFloat;
import imgui.type.ImInt;

import java.nio.file.Path;

/**
 * Layer metadata section of the inspector (format v1.1): BASE vs OVERLAY
 * type, fade in/out, and priority — the "animation mixing" authoring
 * controls. The overlay's part mask is edited in {@link PartListPanel} (each
 * row gains a mask toggle when the clip is an overlay).
 *
 * <p>For an OVERLAY the section also offers a <em>preview base</em>: a BASE
 * clip loaded purely for the viewport, so the mask, fades and blend can be
 * seen the way the game composes them. It is not part of the edited clip.
 *
 * <p>All edits route through undoable {@code ClipMetaCommands}. Numeric
 * buffers follow the inspector's focus-guarded sync pattern.
 */
public final class LayerPanel {

    private final AnimationEditorController controller;
    private FileDialogService fileDialogService;

    private final ImFloat fadeInBuf = new ImFloat(AnimLayerMeta.DEFAULT_FADE_SECONDS);
    private final ImFloat fadeOutBuf = new ImFloat(AnimLayerMeta.DEFAULT_FADE_SECONDS);
    private final ImInt priorityBuf = new ImInt(0);
    private boolean fadeInActivePrev, fadeOutActivePrev, priorityActivePrev;

    public LayerPanel(AnimationEditorController controller) {
        this.controller = controller;
    }

    public void setFileDialogService(FileDialogService service) {
        this.fileDialogService = service;
    }

    public void render() {
        ImGui.separatorText("Layer");
        AnimationClip clip = controller.state().clip();

        AnimLayerMeta.LayerType type = clip.layerType();
        if (ImGui.beginCombo("Type", type.name())) {
            for (AnimLayerMeta.LayerType t : AnimLayerMeta.LayerType.values()) {
                if (ImGui.selectable(t.name(), t == type) && t != type) {
                    controller.setLayerType(t);
                }
            }
            ImGui.endCombo();
        }
        AnimUI.tooltip("BASE drives the whole model (locomotion). OVERLAY plays on top of a "
                + "base clip and takes over only its masked parts (e.g. attack owning the arms).");

        if (clip.layerType() != AnimLayerMeta.LayerType.OVERLAY) {
            return;
        }

        if (!fadeInActivePrev) fadeInBuf.set(clip.fadeInSeconds());
        ImGui.inputFloat("Fade In (s)", fadeInBuf, 0.05f, 0.1f, "%.2f");
        fadeInActivePrev = ImGui.isItemActive();
        if (ImGui.isItemDeactivatedAfterEdit()) {
            controller.setLayerFadeIn(Math.max(0f, fadeInBuf.get()));
        }
        AnimUI.tooltip("Blend-in duration when the overlay starts.");

        if (!fadeOutActivePrev) fadeOutBuf.set(clip.fadeOutSeconds());
        ImGui.inputFloat("Fade Out (s)", fadeOutBuf, 0.05f, 0.1f, "%.2f");
        fadeOutActivePrev = ImGui.isItemActive();
        if (ImGui.isItemDeactivatedAfterEdit()) {
            controller.setLayerFadeOut(Math.max(0f, fadeOutBuf.get()));
        }
        AnimUI.tooltip("Blend-out duration at the natural end of a non-looping overlay "
                + "(and on early exit in-game).");

        if (!priorityActivePrev) priorityBuf.set(clip.layerPriority());
        ImGui.inputInt("Priority", priorityBuf);
        priorityActivePrev = ImGui.isItemActive();
        if (ImGui.isItemDeactivatedAfterEdit()) {
            controller.setLayerPriority(priorityBuf.get());
        }
        AnimUI.tooltip("When several overlays mask the same part, the higher priority wins.");

        int maskCount = clip.maskParts().size();
        ImGui.textDisabled(maskCount == 0
                ? "Mask: all parts (toggle parts in the Parts panel)"
                : "Mask: " + maskCount + " part(s) (toggle in the Parts panel)");

        renderPreviewBase();
    }

    private void renderPreviewBase() {
        ImGui.spacing();
        String basePath = controller.state().basePreviewPath();
        AnimationClip base = controller.state().basePreviewClip();
        if (base != null) {
            String name = basePath != null ? Path.of(basePath).getFileName().toString() : base.name();
            ImGui.text("Preview base: " + name);
            AnimUI.tooltip("The viewport blends this overlay onto '" + base.name()
                    + "' exactly as the game does: masked parts lerp from the base pose by the "
                    + "fade envelope, unmasked parts follow the base. " + basePath);
            ImGui.sameLine();
            if (ImGui.smallButton("Change...")) promptBase();
            ImGui.sameLine();
            if (ImGui.smallButton("Clear")) controller.clearBasePreviewClip();
        } else {
            ImGui.textDisabled("Preview base: none");
            AnimUI.tooltip("Load a BASE clip to preview this overlay layered on top of it. "
                    + "Preview only — not saved with the clip.");
            ImGui.sameLine();
            if (ImGui.smallButton("Load...")) promptBase();
        }
    }

    private void promptBase() {
        if (fileDialogService == null) return;
        fileDialogService.showOpenOMADialog(controller::loadBasePreviewClip);
    }
}
