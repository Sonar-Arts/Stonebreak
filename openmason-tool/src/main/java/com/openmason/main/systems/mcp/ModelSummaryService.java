package com.openmason.main.systems.mcp;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.scripting.commands.ModelSummary;
import com.openmason.main.systems.threading.MainThreadExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One-call orientation digest of the whole editor state — replaces the
 * get_model_info / list_parts / get_selection / bone_get_skeleton_info /
 * attach_get_info opening sequence with a single compact response.
 */
public final class ModelSummaryService {

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final MainImGuiInterface mainInterface;

    public ModelSummaryService(MainImGuiInterface mainInterface) {
        this.mainInterface = mainInterface;
    }

    /** {@code {loaded}} or the full digest; nulls (empty sections) are omitted by the mapper. */
    public record Summary(
            boolean loaded,
            ModelSummary.Totals totals,
            float[][] bbox,
            List<ModelSummary.PartRow> parts,
            List<Map<String, String>> bones,
            List<Map<String, String>> sockets,
            List<String> selection) {
    }

    public Summary summarize() {
        return await(MainThreadExecutor.submit(() -> {
            ViewportController vp = mainInterface.getViewport3D();
            ModelPartManager pm = vp != null ? vp.getPartManager() : null;
            if (vp == null || pm == null || pm.getPartCount() == 0) {
                return new Summary(false, null, null, null, null, null, null);
            }

            GenericModelRenderer gmr = vp.getModelRenderer();
            int materials = gmr != null
                    ? Math.max(0, gmr.getFaceTextureManager().getMaterialCount() - 1)
                    : 0;
            ModelSummary model = ModelSummary.from(pm, materials);

            List<Map<String, String>> bones = null;
            if (vp.getBoneStore() != null && !vp.getBoneStore().isEmpty()) {
                bones = new ArrayList<>();
                for (OMOFormat.BoneEntry bone : vp.getBoneStore().getBones()) {
                    String parentName = null;
                    if (bone.parentBoneId() != null) {
                        OMOFormat.BoneEntry parent = vp.getBoneStore().getById(bone.parentBoneId());
                        parentName = parent != null ? parent.name() : null;
                    }
                    bones.add(compactRow("name", bone.name(), "parent", parentName));
                }
            }

            List<Map<String, String>> sockets = null;
            if (vp.getAttachmentStore() != null && !vp.getAttachmentStore().isEmpty()) {
                sockets = new ArrayList<>();
                for (OMOFormat.AttachmentPointEntry point : vp.getAttachmentStore().getPoints()) {
                    sockets.add(compactRow("name", point.name(), "part", hostPartName(pm, point)));
                }
            }

            List<String> selection = null;
            if (!pm.getSelectedPartIds().isEmpty()) {
                selection = pm.getSelectedPartIds().stream()
                        .map(id -> pm.getPartById(id).map(ModelPartDescriptor::name).orElse(id))
                        .toList();
            }

            return new Summary(true, model.totals(), model.bbox(), model.parts(),
                    bones, sockets, selection);
        }));
    }

    private static String hostPartName(ModelPartManager pm, OMOFormat.AttachmentPointEntry point) {
        if (point.parentPartId() != null) {
            var part = pm.getPartById(point.parentPartId());
            if (part.isPresent()) return part.get().name();
        }
        return point.parentPartName();
    }

    /** Small ordered map; null values dropped (NON_NULL only skips bean fields). */
    private static Map<String, String> compactRow(String k1, String v1, String k2, String v2) {
        Map<String, String> row = new java.util.LinkedHashMap<>();
        if (v1 != null) row.put(k1, v1);
        if (v2 != null) row.put(k2, v2);
        return row;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }
}
