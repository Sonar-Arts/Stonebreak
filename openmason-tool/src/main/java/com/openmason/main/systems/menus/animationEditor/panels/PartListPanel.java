package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.engine.format.oma.AnimLayerMeta;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import com.openmason.main.systems.mortar.core.MortarFrameResult;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.parts.MortarBadge;
import com.openmason.main.systems.mortar.parts.MortarListRow;
import com.openmason.main.systems.mortar.theme.Argb;
import imgui.ImGui;
import imgui.type.ImBoolean;

import java.util.List;

/**
 * Scrollable list of all parts on the bound model. Selection drives the
 * timeline target and the keyframe inspector. Rows are Mortar-painted
 * ({@link MortarListRow} with the keyframe count as the trailing label) when
 * a Skija context exists; ImGui selectables are the fallback.
 *
 * <p>When the clip is an OVERLAY, each row also gets a mask toggle: clicking
 * the badge (or the fallback checkbox) adds/removes the part <em>name</em>
 * from the clip's overlay mask. An empty mask means the overlay owns ALL
 * parts; non-masked rows are washed out as a visual cue.
 */
public final class PartListPanel implements AutoCloseable {

    private static final float ROW_HEIGHT = 32f;
    private static final float ROW_GAP = 4f;
    private static final float MASK_BADGE_WIDTH = 56f;
    private static final float MASK_BADGE_GAP = 4f;

    private final AnimationEditorController controller;
    private final MortarRegion region = new MortarRegion();
    private final ImBoolean maskBuf = new ImBoolean(false);

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

        ImGui.beginChild("##partListBody");
        if (region.isAvailable()) {
            renderMortar(parts);
        } else {
            renderImGuiFallback(parts);
        }
        ImGui.endChild();
    }

    private void renderMortar(List<ModelPartDescriptor> parts) {
        AnimationClip clip = controller.state().clip();
        String selected = controller.state().selectedPartId();
        boolean overlay = clip.layerType() == AnimLayerMeta.LayerType.OVERLAY;
        boolean maskAll = clip.maskParts().isEmpty();

        float width = Math.max(60f, ImGui.getContentRegionAvailX());
        float rowWidth = overlay ? width - MASK_BADGE_WIDTH - MASK_BADGE_GAP : width;
        float totalHeight = parts.size() * (ROW_HEIGHT + ROW_GAP);

        region.begin(width, totalHeight);
        float y = 0f;
        for (ModelPartDescriptor part : parts) {
            Track track = clip.trackFor(part.id());
            int kfCount = track != null ? track.size() : 0;
            String trailing = kfCount > 0 ? kfCount + " kf" : null;
            boolean masked = !overlay || isMasked(clip, part.name());

            MortarListRow row = new MortarListRow(part.name(), null, trailing);
            // Non-masked overlay rows get a background wash so the mask reads at a glance.
            region.add(part.id(), 0f, y, rowWidth, ROW_HEIGHT,
                    part.id().equals(selected),
                    masked ? row : (g, x, ry, w, h, state) -> {
                        row.paint(g, x, ry, w, h, state);
                        g.fillRoundRect(x, ry, w, h, 6f,
                                Argb.withAlpha(g.theme().background, 0.45f));
                    });

            if (overlay) {
                region.add("mask." + part.id(),
                        rowWidth + MASK_BADGE_GAP, y + (ROW_HEIGHT - 20f) / 2f,
                        MASK_BADGE_WIDTH, 20f,
                        masked,
                        new MortarBadge(maskAll ? "all" : (masked ? "masked" : "mask")));
            }
            y += ROW_HEIGHT + ROW_GAP;
        }
        MortarFrameResult input = region.render();
        region.update(ImGui.getIO().getDeltaTime());

        for (ModelPartDescriptor part : parts) {
            if (input.isClicked(part.id())) {
                controller.state().setSelectedPartId(part.id());
                break;
            }
            if (overlay && input.isClicked("mask." + part.id())) {
                controller.toggleMaskPart(part.name());
                break;
            }
        }
    }

    private void renderImGuiFallback(List<ModelPartDescriptor> parts) {
        AnimationClip clip = controller.state().clip();
        String selected = controller.state().selectedPartId();
        boolean overlay = clip.layerType() == AnimLayerMeta.LayerType.OVERLAY;

        for (ModelPartDescriptor part : parts) {
            boolean isSel = part.id().equals(selected);
            Track track = clip.trackFor(part.id());
            int kfCount = track != null ? track.size() : 0;
            String label = kfCount > 0
                    ? String.format("%s  (%d kf)", part.name(), kfCount)
                    : part.name();

            if (overlay) {
                maskBuf.set(isMasked(clip, part.name()));
                if (ImGui.checkbox("##mask_" + part.id(), maskBuf)) {
                    controller.toggleMaskPart(part.name());
                }
                AnimUI.tooltip("Overlay mask: whether this overlay owns '" + part.name()
                        + "'. Empty mask = all parts.");
                ImGui.sameLine();
            }
            if (ImGui.selectable(label, isSel)) {
                controller.state().setSelectedPartId(part.id());
            }
        }
    }

    /** Mask coverage for display: empty mask = every part is owned. */
    private static boolean isMasked(AnimationClip clip, String partName) {
        if (clip.maskParts().isEmpty()) return true;
        for (String mask : clip.maskParts()) {
            if (mask.equalsIgnoreCase(partName)) return true;
        }
        return false;
    }

    @Override
    public void close() {
        region.close();
    }
}
