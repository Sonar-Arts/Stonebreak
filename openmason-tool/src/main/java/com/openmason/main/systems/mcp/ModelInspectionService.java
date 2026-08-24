package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.rendering.model.gmr.analysis.WindingAnalyzer;
import com.openmason.engine.rendering.model.gmr.editable.EditableFace;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.MeshImporter;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@code model_describe} and {@code model_check_winding} — the live-model
 * counterparts of the asset-lens inspection tools.
 *
 * <p>A short main-thread hop snapshots per-part geometry arrays; face-loop
 * reconstruction and winding analysis then run on the MCP handler thread.
 * Orientation comes from {@link WindingAnalyzer}'s geometric ground truth —
 * deliberately NOT the winding-trusting cross product that
 * {@code model_face_list_textures} reports.
 */
public class ModelInspectionService {

    static final int FACE_PAGE_DEFAULT = 24;
    static final int FACE_PAGE_MAX = 96;
    /** texture:true is heavy (a glyph grid per face) — keep pages tiny. */
    static final int TEXTURE_PAGE_MAX = 8;

    private final MainImGuiInterface mainInterface;
    private final FaceTextureEditingService faceTextures;
    protected final ObjectMapper mapper;

    public ModelInspectionService(MainImGuiInterface mainInterface,
                                  FaceTextureEditingService faceTextures, ObjectMapper mapper) {
        this.mainInterface = mainInterface;
        this.faceTextures = faceTextures;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------- snapshots

    private record PartSnapshot(String id, String name, String parentId, boolean visible,
                                PartMeshRebuilder.PartGeometry geometry) {
    }

    private List<PartSnapshot> snapshotParts() {
        return await(MainThreadExecutor.submit(() -> {
            ModelPartManager pm = requirePartManager();
            List<PartSnapshot> out = new ArrayList<>();
            for (ModelPartDescriptor d : pm.getAllParts()) {
                out.add(new PartSnapshot(d.id(), d.name(), d.parentId(), d.visible(),
                        pm.getPartGeometry(d.id())));
            }
            return out;
        }));
    }

    private record AnalyzedPart(PartSnapshot snapshot, EditableMesh mesh,
                                WindingAnalyzer.PartReport winding) {
        List<Integer> faceIds() {
            return mesh.faces().stream().map(EditableFace::faceId).sorted().toList();
        }
    }

    private static AnalyzedPart analyze(PartSnapshot p) {
        List<MeshImporter.ImportWarning> warnings = new ArrayList<>();
        PartMeshRebuilder.PartGeometry g = p.geometry();
        MeshImporter.ImportResult imported = g == null
                ? MeshImporter.importSoup(new float[0], null, new int[0], null, warnings::add)
                : MeshImporter.importSoup(g.vertices(), g.texCoords(), g.indices(),
                        g.triangleToFaceId(), warnings::add);
        return new AnalyzedPart(p, imported.mesh(),
                WindingAnalyzer.analyze(p.name(), imported.mesh(), warnings));
    }

    // --------------------------------------------------------- model_describe

    public Map<String, Object> describe(String detail, String part, int[] faceIds,
                                        int offset, int limit, boolean texture, int[] rect,
                                        PixelTextCodec.Options options) {
        String level = detail == null || detail.isBlank() ? "summary"
                : detail.trim().toLowerCase(Locale.ROOT);
        if (!List.of("summary", "parts", "faces").contains(level)) {
            throw McpErrors.invalidEnum("detail", detail, List.of("summary", "parts", "faces"));
        }
        List<PartSnapshot> snapshots = snapshotParts();
        List<PartSnapshot> scope = filterParts(snapshots, part);

        Map<String, Object> out = new LinkedHashMap<>();
        var modelState = mainInterface.getModelState();
        if (modelState != null) {
            out.put("model", modelState.getCurrentModelPath());
            out.put("dirty", modelState.hasUnsavedChanges());
        }

        List<AnalyzedPart> analyzed = scope.stream().map(ModelInspectionService::analyze).toList();
        int vertices = 0;
        int triangles = 0;
        int faces = 0;
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (AnalyzedPart ap : analyzed) {
            PartMeshRebuilder.PartGeometry g = ap.snapshot().geometry();
            if (g != null) {
                vertices += g.vertexCount();
                triangles += g.indexCount() / 3;
            }
            faces += ap.mesh().faceCount();
            for (int v = 0; v < ap.mesh().vertexCount(); v++) {
                min.min(ap.mesh().position(v));
                max.max(ap.mesh().position(v));
            }
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("parts", analyzed.size());
        totals.put("vertices", vertices);
        totals.put("triangles", triangles);
        totals.put("faces", faces);
        out.put("totals", totals);
        if (Float.isFinite(min.x)) {
            out.put("bboxMin", AssetLensService.round3(new float[]{min.x, min.y, min.z}));
            out.put("bboxMax", AssetLensService.round3(new float[]{max.x, max.y, max.z}));
        }

        // Face texture join (materialId, uv region) via the GPU-side mapping list.
        Map<Integer, FaceTextureEditingService.FaceTextureInfo> textureInfo = new LinkedHashMap<>();
        if (!"summary".equals(level)) {
            try {
                for (FaceTextureEditingService.FaceTextureInfo info : faceTextures.listFaceTextures()) {
                    textureInfo.put(info.faceId(), info);
                }
            } catch (RuntimeException ignored) {
                // No texture pipeline (headless/tests) — geometry data still flows.
            }
        }

        if (!"summary".equals(level)) {
            List<Map<String, Object>> partRows = new ArrayList<>();
            for (AnalyzedPart ap : analyzed) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("part", ap.snapshot().name());
                row.put("id", ap.snapshot().id());
                if (ap.snapshot().parentId() != null) {
                    row.put("parent", ap.snapshot().parentId());
                }
                row.put("visible", ap.snapshot().visible());
                row.put("faces", ap.mesh().faceCount());
                row.put("windingStatus", ap.winding().mixedWinding() ? "mixed"
                        : ap.winding().inward() > 0 ? "inverted"
                        : ap.winding().indeterminateReason() != null
                                ? ap.winding().indeterminateReason() : "ok");
                partRows.add(row);
            }
            out.put("parts", partRows);
        }

        if ("faces".equals(level)) {
            int pageSize = limit <= 0 ? FACE_PAGE_DEFAULT : Math.min(limit, FACE_PAGE_MAX);
            if (texture) {
                boolean bounded = (faceIds != null && faceIds.length > 0
                        && faceIds.length <= TEXTURE_PAGE_MAX) || pageSize <= TEXTURE_PAGE_MAX;
                if (!bounded) {
                    throw new IllegalArgumentException("texture:true is heavy — pass face_ids "
                            + "(<= " + TEXTURE_PAGE_MAX + ") or limit <= " + TEXTURE_PAGE_MAX);
                }
                pageSize = Math.min(pageSize, TEXTURE_PAGE_MAX);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            int total = 0;
            for (AnalyzedPart ap : analyzed) {
                Map<Integer, WindingAnalyzer.FaceResult> verdicts = new LinkedHashMap<>();
                for (WindingAnalyzer.FaceResult f : ap.winding().faces()) {
                    verdicts.put(f.faceId(), f);
                }
                for (int faceId : ap.faceIds()) {
                    if (faceIds != null && faceIds.length > 0 && !contains(faceIds, faceId)) {
                        continue;
                    }
                    total++;
                    if (total <= offset || rows.size() >= pageSize) {
                        continue;
                    }
                    rows.add(faceRow(ap, faceId, verdicts.get(faceId),
                            textureInfo.get(faceId), texture, rect, options));
                }
            }
            out.put("faces", rows);
            out.put("total", total);
            out.put("truncated", offset + rows.size() < total);
        }
        return out;
    }

    private Map<String, Object> faceRow(AnalyzedPart ap, int faceId,
                                        WindingAnalyzer.FaceResult verdict,
                                        FaceTextureEditingService.FaceTextureInfo info,
                                        boolean texture, int[] rect,
                                        PixelTextCodec.Options options) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("faceId", faceId);
        row.put("part", ap.snapshot().name());
        EditableFace face = ap.mesh().face(faceId);
        if (face != null) {
            int[] ids = face.loop();
            float[][] loop = new float[ids.length][];
            Vector3f acc = new Vector3f();
            for (int i = 0; i < ids.length; i++) {
                Vector3f p = ap.mesh().position(ids[i]);
                loop[i] = new float[]{AssetLensService.round4(p.x),
                        AssetLensService.round4(p.y), AssetLensService.round4(p.z)};
                acc.add(p);
            }
            row.put("loop", loop);
            acc.div(ids.length);
            row.put("centroid", AssetLensService.round3(new float[]{acc.x, acc.y, acc.z}));
        }
        if (verdict != null) {
            row.put("normal", AssetLensService.round3(verdict.normal()));
            row.put("area", AssetLensService.round4(verdict.area()));
            row.put("planar", !verdict.nonPlanar());
            row.put("orientation", verdict.orientation().name().toLowerCase(Locale.ROOT));
            row.put("orientationMethod", verdict.method());
            if (verdict.degenerate()) {
                row.put("degenerate", true);
            }
            if (verdict.contractViolation()) {
                row.put("contractViolation", true);
            }
        }
        if (info != null) {
            row.put("materialId", info.materialId());
            row.put("materialName", info.materialName());
            row.put("textureSize", info.textureWidth() + "x" + info.textureHeight());
            row.put("uvRegion", info.uvRegion());
            row.put("uvRotation", info.uvRotationDegrees());
        }
        if (texture && info != null && info.hasMapping()) {
            try {
                row.put("texture", faceTextures.describe(faceId, rect, options));
            } catch (RuntimeException e) {
                row.put("textureError", e.getMessage());
            }
        }
        return row;
    }

    // --------------------------------------------------- model_check_winding

    public Map<String, Object> checkWinding(String part) {
        List<PartSnapshot> scope = filterParts(snapshotParts(), part);
        List<WindingAnalyzer.PartReport> reports = scope.stream()
                .map(ModelInspectionService::analyze)
                .map(AnalyzedPart::winding)
                .toList();
        return WindingReportJson.aggregate(reports);
    }

    // ---------------------------------------------------------------- helpers

    private List<PartSnapshot> filterParts(List<PartSnapshot> all, String part) {
        if (part == null || part.isBlank()) {
            return all;
        }
        for (PartSnapshot p : all) {
            if (p.name().equalsIgnoreCase(part) || p.id().equalsIgnoreCase(part)) {
                return List.of(p);
            }
        }
        throw McpErrors.unknownEntity("part", part,
                all.stream().map(PartSnapshot::name).toList(), "list_parts");
    }

    private ModelPartManager requirePartManager() {
        ViewportController vp = mainInterface.getViewport3D();
        if (vp == null) {
            throw new IllegalStateException("Viewport is not connected — load a model first");
        }
        ModelPartManager pm = vp.getPartManager();
        if (pm == null) {
            throw new IllegalStateException("Part manager not available — load a model first");
        }
        return pm;
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for main thread");
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Main thread busy — try again");
        }
    }
}
