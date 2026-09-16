package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.engine.format.oma.AnimLayerMeta;
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
 * Scrollable list of all parts on the bound model, in hierarchy order
 * (children indented under their parent) and followed by any unbound tracks
 * — see {@link PartRows}. Selection drives the timeline target and the
 * keyframe inspector. Rows are Mortar-painted ({@link MortarListRow} with the
 * keyframe count as the trailing label) when a Skija context exists; ImGui
 * selectables are the fallback.
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
    private static final float INDENT = 12f;

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
        List<PartRows.Row> rows = PartRows.build(pm, controller.orphanTracks());
        if (rows.isEmpty()) {
            ImGui.textDisabled("Model has no parts.");
            return;
        }

        ImGui.beginChild("##partListBody");
        if (region.isAvailable()) {
            renderMortar(rows);
        } else {
            renderImGuiFallback(rows);
        }
        ImGui.endChild();
    }

    private void renderMortar(List<PartRows.Row> rows) {
        AnimationClip clip = controller.state().clip();
        String selected = controller.state().selectedPartId();
        boolean overlay = clip.layerType() == AnimLayerMeta.LayerType.OVERLAY;
        boolean maskAll = clip.maskParts().isEmpty();

        float width = Math.max(60f, ImGui.getContentRegionAvailX());
        float rowWidth = overlay ? width - MASK_BADGE_WIDTH - MASK_BADGE_GAP : width;
        float totalHeight = rows.size() * (ROW_HEIGHT + ROW_GAP);

        region.begin(width, totalHeight);
        float y = 0f;
        for (PartRows.Row r : rows) {
            Track track = clip.trackFor(r.id());
            int kfCount = track != null ? track.size() : 0;
            String trailing = kfCount > 0 ? kfCount + " kf" : null;
            boolean masked = r.isOrphan() || !overlay || isMasked(clip, r.label());
            float x = r.depth() * INDENT;

            MortarListRow row = new MortarListRow(
                    r.isOrphan() ? "? " + r.label() : r.label(),
                    r.isOrphan() ? "unbound track" : null, trailing);
            // Non-masked overlay rows and orphans get a background wash.
            boolean wash = !masked || r.isOrphan();
            region.add(r.id(), x, y, rowWidth - x, ROW_HEIGHT,
                    r.id().equals(selected),
                    !wash ? row : (g, rx, ry, w, h, state) -> {
                        row.paint(g, rx, ry, w, h, state);
                        g.fillRoundRect(rx, ry, w, h, 6f,
                                Argb.withAlpha(g.theme().background, 0.45f));
                    });

            if (overlay && !r.isOrphan()) {
                region.add("mask." + r.id(),
                        rowWidth + MASK_BADGE_GAP, y + (ROW_HEIGHT - 20f) / 2f,
                        MASK_BADGE_WIDTH, 20f,
                        masked,
                        new MortarBadge(maskAll ? "all" : (masked ? "masked" : "mask")));
            }
            y += ROW_HEIGHT + ROW_GAP;
        }
        MortarFrameResult input = region.render();
        region.update(ImGui.getIO().getDeltaTime());

        for (PartRows.Row r : rows) {
            if (input.isClicked(r.id())) {
                controller.state().setSelectedPartId(r.id());
                break;
            }
            if (overlay && !r.isOrphan() && input.isClicked("mask." + r.id())) {
                controller.toggleMaskPart(r.label());
                break;
            }
        }
    }

    private void renderImGuiFallback(List<PartRows.Row> rows) {
        AnimationClip clip = controller.state().clip();
        String selected = controller.state().selectedPartId();
        boolean overlay = clip.layerType() == AnimLayerMeta.LayerType.OVERLAY;

        for (PartRows.Row r : rows) {
            boolean isSel = r.id().equals(selected);
            Track track = clip.trackFor(r.id());
            int kfCount = track != null ? track.size() : 0;
            String base = r.isOrphan() ? "? " + r.label() + " (unbound)" : r.label();
            String label = kfCount > 0
                    ? String.format("%s  (%d kf)", base, kfCount)
                    : base;

            if (r.depth() > 0) ImGui.indent(r.depth() * INDENT);
            if (overlay && !r.isOrphan()) {
                maskBuf.set(isMasked(clip, r.label()));
                if (ImGui.checkbox("##mask_" + r.id(), maskBuf)) {
                    controller.toggleMaskPart(r.label());
                }
                AnimUI.tooltip("Overlay mask: whether this overlay owns '" + r.label()
                        + "'. Empty mask = all parts.");
                ImGui.sameLine();
            }
            if (ImGui.selectable(label + "##" + r.id(), isSel)) {
                controller.state().setSelectedPartId(r.id());
            }
            if (r.isOrphan()) {
                AnimUI.tooltip("This track's part is not on the model. Right-click its "
                        + "timeline row to rebind it to a part or delete it.");
            }
            if (r.depth() > 0) ImGui.unindent(r.depth() * INDENT);
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
