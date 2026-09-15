package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.rendering.model.gmr.analysis.WindingAnalyzer;
import com.openmason.engine.rendering.model.gmr.editable.EditableFace;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.assets.AssetCatalog;
import com.openmason.main.systems.assets.AssetEntry;
import com.openmason.main.systems.assets.AssetModelDigest;
import com.openmason.main.systems.assets.AssetParseCache;
import com.openmason.main.systems.assets.AssetPathGuard;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The asset-lens ("eyeglass") over the game's SBO/SBE folders: list/search,
 * manifest, mesh summary, per-face data, texture description/export, winding
 * checks — all read-only, file-based, and GL-free, so every handler runs on
 * the MCP handler thread with no main-thread hop.
 *
 * <p>{@code asset_open} (the one mutating action) is approval-gated and lives
 * at the end of this class.
 */
public class AssetLensService {

    /** Face pages default/cap (schema documents these). */
    static final int FACE_PAGE_DEFAULT = 32;
    static final int FACE_PAGE_MAX = 128;
    /** Describe grids above this edge are auto-downsampled (note in result). */
    static final int DESCRIBE_MAX_EDGE = 64;
    static final int INLINE_IMAGE_MAX = 512;

    protected final MainImGuiInterface mainInterface;
    protected final ObjectMapper mapper;
    private final AssetCatalog catalog = AssetCatalog.shared();
    private final AssetParseCache parseCache = new AssetParseCache();

    public AssetLensService(MainImGuiInterface mainInterface, ObjectMapper mapper) {
        this.mainInterface = mainInterface;
        this.mapper = mapper;
    }

    // -------------------------------------------------------------- asset_list

    public record AssetRow(String id, String name, String kind, String type, String folder,
                           long sizeBytes) {
    }

    public record AssetListResult(List<AssetRow> assets, int total, boolean truncated) {
    }

    public AssetListResult list(String query, String kind, String type,
                                int limit, int offset, boolean refresh) {
        AssetEntry.Kind k = null;
        if (kind != null && !kind.isBlank()) {
            try {
                k = AssetEntry.Kind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw McpErrors.invalidEnum("kind", kind, List.of("sbo", "sbe"));
            }
        }
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        AssetCatalog.SearchResult result =
                catalog.search(query, k, type, cappedLimit, Math.max(0, offset), refresh);
        List<AssetRow> rows = result.entries().stream()
                .map(e -> new AssetRow(e.id(), e.displayName(), e.kind().lower(), e.type(),
                        e.folder(), e.sizeBytes()))
                .toList();
        return new AssetListResult(rows, result.total(),
                result.total() > offset + rows.size());
    }

    // ---------------------------------------------------------- asset_manifest

    public ObjectNode manifest(String asset) throws IOException {
        AssetEntry entry = resolve(asset);
        AssetModelDigest digest = digest(entry, null, null);
        ObjectNode out = mapper.createObjectNode();
        out.put("id", entry.id());
        out.put("kind", entry.kind().lower());
        out.put("file", entry.sourcePath().toString());
        out.set("manifest", mapper.valueToTree(digest.manifest()));
        ObjectNode inventory = out.putObject("inventory");
        inventory.put("modelBearing", digest.modelBearing());
        if (digest.manifest() instanceof SBOFormat.Document doc) {
            inventory.set("states", mapper.valueToTree(
                    doc.states().stream().map(SBOFormat.StateEntry::name).toList()));
            inventory.put("defaultState", doc.defaultStateName());
            inventory.set("sounds", mapper.valueToTree(soundEventNames(doc.sounds())));
        } else if (digest.manifest() instanceof SBEFormat.Document doc) {
            inventory.set("states", mapper.valueToTree(
                    doc.states().stream().map(SBEFormat.StateEntry::name).toList()));
            inventory.set("variants", mapper.valueToTree(
                    doc.variants().stream().map(SBEFormat.VariantEntry::name).toList()));
            inventory.set("sounds", mapper.valueToTree(soundEventNames(doc.sounds())));
        }
        return out;
    }

    private static List<String> soundEventNames(com.openmason.engine.format.sound.SoundData sounds) {
        if (sounds == null || sounds.sounds() == null) {
            return List.of();
        }
        return sounds.sounds().stream().map(d -> d.event()).distinct().sorted().toList();
    }

    // ------------------------------------------------------ asset_mesh_summary

    public Map<String, Object> meshSummary(String asset, String state, String variant)
            throws IOException {
        AssetEntry entry = resolve(asset);
        AssetModelDigest digest = digest(entry, state, variant);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entry.id());
        putSelector(out, digest);
        out.put("modelBearing", digest.modelBearing());
        if (!digest.modelBearing()) {
            out.put("note", "texture-only asset — no mesh; use asset_texture_describe/export");
            return out;
        }
        out.put("uvMode", digest.uvMode());
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("parts", digest.parts().size());
        totals.put("vertices", digest.totalVertices());
        totals.put("triangles", digest.totalTriangles());
        totals.put("faces", digest.totalFaces());
        totals.put("materials", digest.materials().size());
        out.put("totals", totals);
        List<Map<String, Object>> parts = new ArrayList<>();
        for (AssetModelDigest.PartDigest p : digest.parts()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p.name());
            row.put("id", p.id());
            if (p.parentId() != null) {
                row.put("parent", p.parentId());
            }
            row.put("visible", p.visible());
            row.put("faces", p.mesh().faceCount());
            row.put("bboxMin", round3(p.bboxMin()));
            row.put("bboxMax", round3(p.bboxMax()));
            parts.add(row);
        }
        out.put("parts", parts);
        List<Map<String, Object>> materials = new ArrayList<>();
        for (ParsedMaterialData m : digest.materials()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.materialId());
            row.put("name", m.name());
            try {
                AssetModelDigest.Pixels px = digest.materialPixels(m.materialId());
                row.put("size", px.width() + "x" + px.height());
            } catch (RuntimeException ignored) {
                row.put("size", "unreadable");
            }
            if (m.renderLayer() != null) {
                row.put("renderLayer", m.renderLayer());
            }
            if (m.emissive()) {
                row.put("emissive", true);
            }
            materials.add(row);
        }
        out.put("materials", materials);
        if (!digest.attachmentPoints().isEmpty()) {
            out.put("attachmentPoints", digest.attachmentPoints().stream()
                    .map(OMOFormat.AttachmentPointEntry::name).toList());
        }
        return out;
    }

    // -------------------------------------------------------- asset_face_data

    public record FaceRow(int faceId, String part, float[][] loop, float[] normal,
                          float[] centroid, float area, boolean planar,
                          String orientation, String orientationMethod,
                          Integer materialId, String materialName, float[] uvRegion,
                          Integer uvRotation) {
    }

    public Map<String, Object> faceData(String asset, String state, String variant,
                                        String part, int[] faceIds, int offset, int limit)
            throws IOException {
        AssetEntry entry = resolve(asset);
        AssetModelDigest digest = requireModel(digest(entry, state, variant));
        int pageSize = limit <= 0 ? FACE_PAGE_DEFAULT : Math.min(limit, FACE_PAGE_MAX);

        List<AssetModelDigest.PartDigest> scope;
        if (part != null && !part.isBlank()) {
            AssetModelDigest.PartDigest pd = digest.part(part);
            if (pd == null) {
                throw McpErrors.unknownEntity("part", part,
                        digest.parts().stream().map(AssetModelDigest.PartDigest::name).toList(),
                        "asset_mesh_summary");
            }
            scope = List.of(pd);
        } else {
            scope = digest.parts();
        }

        List<FaceRow> rows = new ArrayList<>();
        int total = 0;
        for (AssetModelDigest.PartDigest pd : scope) {
            Map<Integer, WindingAnalyzer.FaceResult> verdicts = new LinkedHashMap<>();
            for (WindingAnalyzer.FaceResult f : pd.winding().faces()) {
                verdicts.put(f.faceId(), f);
            }
            for (int faceId : pd.faceIds()) {
                if (faceIds != null && faceIds.length > 0 && !contains(faceIds, faceId)) {
                    continue;
                }
                total++;
                if (total <= offset || rows.size() >= pageSize) {
                    continue;
                }
                rows.add(faceRow(pd, faceId, verdicts.get(faceId), digest));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entry.id());
        putSelector(out, digest);
        out.put("faces", rows);
        out.put("total", total);
        out.put("truncated", offset + rows.size() < total);
        return out;
    }

    private FaceRow faceRow(AssetModelDigest.PartDigest pd, int faceId,
                            WindingAnalyzer.FaceResult verdict, AssetModelDigest digest) {
        EditableFace face = pd.mesh().face(faceId);
        float[][] loop = null;
        float[] centroid = null;
        if (face != null) {
            int[] ids = face.loop();
            loop = new float[ids.length][];
            Vector3f acc = new Vector3f();
            for (int i = 0; i < ids.length; i++) {
                Vector3f p = pd.mesh().position(ids[i]);
                loop[i] = new float[]{round4(p.x), round4(p.y), round4(p.z)};
                acc.add(p);
            }
            acc.div(ids.length);
            centroid = new float[]{round4(acc.x), round4(acc.y), round4(acc.z)};
        }
        ParsedFaceMapping mapping = digest.faceMappings().get(faceId);
        String materialName = null;
        if (mapping != null) {
            materialName = digest.materials().stream()
                    .filter(m -> m.materialId() == mapping.materialId())
                    .map(ParsedMaterialData::name)
                    .findFirst().orElse(null);
        }
        return new FaceRow(faceId, pd.name(), loop,
                verdict != null ? round3(verdict.normal()) : null,
                centroid,
                verdict != null ? round4(verdict.area()) : 0f,
                verdict == null || !verdict.nonPlanar(),
                verdict != null ? verdict.orientation().name().toLowerCase(Locale.ROOT) : "unknown",
                verdict != null ? verdict.method() : "none",
                mapping != null ? mapping.materialId() : null,
                materialName,
                mapping != null
                        ? new float[]{mapping.u0(), mapping.v0(), mapping.u1(), mapping.v1()}
                        : null,
                mapping != null ? mapping.uvRotationDegrees() : null);
    }

    // -------------------------------------------------- asset_texture_describe

    public Map<String, Object> textureDescribe(String asset, String state, String variant,
                                               Integer materialId, int[] rect,
                                               PixelTextCodec.Options options) throws IOException {
        AssetEntry entry = resolve(asset);
        AssetModelDigest digest = digest(entry, state, variant);
        AssetModelDigest.Pixels pixels = pickPixels(digest, materialId);

        int x = 0;
        int y = 0;
        int w = pixels.width();
        int h = pixels.height();
        if (rect != null) {
            if (rect.length != 4) {
                throw new IllegalArgumentException("rect must be [x,y,w,h]");
            }
            x = rect[0];
            y = rect[1];
            w = rect[2];
            h = rect[3];
            if (x < 0 || y < 0 || w <= 0 || h <= 0
                    || x + w > pixels.width() || y + h > pixels.height()) {
                throw McpErrors.outOfRange("rect", x, 0, pixels.width() - 1,
                        "texture " + pixels.width() + "x" + pixels.height(), "asset_mesh_summary");
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entry.id());
        putSelector(out, digest);
        out.put("texture", materialId != null ? "material:" + materialId : "default");
        out.put("textureSize", pixels.width() + "x" + pixels.height());

        int[] packed = pixels.packed();
        int bufWidth = pixels.width();
        if (Math.max(w, h) > DESCRIBE_MAX_EDGE) {
            int factor = (int) Math.ceil(Math.max(w, h) / (double) DESCRIBE_MAX_EDGE);
            int dw = Math.max(1, w / factor);
            int dh = Math.max(1, h / factor);
            int[] down = new int[dw * dh];
            for (int yy = 0; yy < dh; yy++) {
                for (int xx = 0; xx < dw; xx++) {
                    down[yy * dw + xx] = packed[(y + yy * factor) * bufWidth + (x + xx * factor)];
                }
            }
            out.put("downsampled", w + "x" + h + " -> " + dw + "x" + dh
                    + " (pass rect for exact pixels)");
            out.put("describe", PixelTextCodec.describe(down, dw, 0, 0, dw, dh, options));
        } else {
            out.put("describe", PixelTextCodec.describe(packed, bufWidth, x, y, w, h, options));
        }
        return out;
    }

    // ---------------------------------------------------- asset_texture_export

    public Object textureExport(String asset, String state, String variant, Integer materialId,
                                String out, boolean inline, int maxSize) throws IOException {
        AssetEntry entry = resolve(asset);
        AssetModelDigest digest = digest(entry, state, variant);
        AssetModelDigest.Pixels pixels = pickPixels(digest, materialId);
        BufferedImage image = toImage(pixels);
        String baseName = out != null ? out
                : (materialId != null ? "material_" + materialId : "default")
                + (state != null ? "_" + state : "") + (variant != null ? "_" + variant : "");
        Path target = AssetPathGuard.exportPngTarget(entry.id(), baseName);
        ImageIO.write(image, "png", target.toFile());
        if (inline) {
            int cap = maxSize > 0 ? Math.min(maxSize, INLINE_IMAGE_MAX) : INLINE_IMAGE_MAX;
            BufferedImage shown = pixels.width() > cap || pixels.height() > cap
                    ? McpImageCodec.downscale(image, cap)
                    : McpImageCodec.upscaleNearest(image, Math.min(cap, 256));
            return new McpImageContent(McpImageCodec.encodePngBase64(shown), "image/png");
        }
        return McpAck.ok()
                .with("file", target.toString())
                .with("size", pixels.width() + "x" + pixels.height());
    }

    // ---------------------------------------------------- asset_check_winding

    public Map<String, Object> checkWinding(String asset, String state, String variant, String part)
            throws IOException {
        AssetEntry entry = resolve(asset);
        AssetModelDigest digest = requireModel(digest(entry, state, variant));
        List<AssetModelDigest.PartDigest> scope = digest.parts();
        if (part != null && !part.isBlank()) {
            AssetModelDigest.PartDigest pd = digest.part(part);
            if (pd == null) {
                throw McpErrors.unknownEntity("part", part,
                        digest.parts().stream().map(AssetModelDigest.PartDigest::name).toList(),
                        "asset_mesh_summary");
            }
            scope = List.of(pd);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entry.id());
        putSelector(out, digest);
        out.putAll(WindingReportJson.aggregate(
                scope.stream().map(AssetModelDigest.PartDigest::winding).toList()));
        return out;
    }

    // -------------------------------------------------------------- asset_open

    /**
     * Approval-gated open-for-edit: extracts the asset's embedded model.omo
     * (optionally a state/variant model) and loads it into the editor as an
     * UNSAVED COPY — the SBO/SBE file is never written. Blocks on the MCP
     * handler thread until the user answers the in-tool dialog or the request
     * times out; the result is structured, never a protocol error.
     */
    public Map<String, Object> open(String asset, String state, String variant, int timeoutSeconds)
            throws IOException {
        AssetEntry entry = resolve(asset);
        byte[] omoBytes = extractModelBytes(entry, blankTo(state), blankTo(variant));

        if (mainInterface == null) {
            return declined("approval_unavailable",
                    "the Open Mason UI is not running — asset_open needs a human to approve");
        }
        com.openmason.main.systems.mcp.approval.ApprovalGate gate = mainInterface.getApprovalGate();
        if (gate == null) {
            return declined("approval_unavailable", "no approval gate is wired in this session");
        }

        int timeout = timeoutSeconds <= 0 ? 60 : Math.min(timeoutSeconds, 240);
        List<String> details = new java.util.ArrayList<>();
        details.add("Asset: " + entry.id() + " (" + entry.sourcePath().getFileName() + ")");
        if (state != null && !state.isBlank()) {
            details.add("State: " + state);
        }
        if (variant != null && !variant.isBlank()) {
            details.add("Variant: " + variant);
        }
        details.add("Opens as an unsaved copy — the asset file itself is not touched.");
        com.openmason.main.systems.mcp.approval.ApprovalGate.Decision decision;
        try {
            decision = gate.request(new com.openmason.main.systems.mcp.approval.ApprovalGate.ApprovalRequest(
                            "asset_open",
                            "An agent asks to open '" + entry.displayName() + "' into the editor",
                            details, java.time.Duration.ofSeconds(timeout)))
                    .toCompletableFuture()
                    .get(timeout + 5L, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return declined("timeout", "no user decision within " + timeout + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return declined("interrupted", "approval wait interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            return declined("error", "approval failed: " + e.getCause());
        }
        switch (decision) {
            case BUSY -> {
                return declined("busy",
                        "another confirmation dialog is already open in Open Mason — retry shortly");
            }
            case DECLINED -> {
                return declined("user_declined", "the user declined the request");
            }
            case TIMEOUT -> {
                return declined("timeout", "the request timed out without a user decision");
            }
            case APPROVED -> {
                // fall through to the load below
            }
        }

        try {
            var result = com.openmason.main.systems.threading.MainThreadExecutor.submit(() ->
                            mainInterface.getModelOperations().openExtractedAssetModel(
                                    omoBytes, entry.displayName(), entry.id()))
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("opened", result.opened());
            out.put("message", result.message());
            if (!result.opened()) {
                out.put("reason", "load_failed");
            }
            return out;
        } catch (Exception e) {
            return declined("load_failed", "model load failed: " + e.getMessage());
        }
    }

    private static Map<String, Object> declined(String reason, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("opened", false);
        out.put("reason", reason);
        out.put("message", message);
        return out;
    }

    private byte[] extractModelBytes(AssetEntry entry, String state, String variant)
            throws IOException {
        switch (entry.kind()) {
            case SBO -> {
                if (variant != null) {
                    throw new IllegalArgumentException("SBO assets have no variants");
                }
                var raw = new com.openmason.engine.format.sbo.SBOParser().parseRaw(entry.sourcePath());
                if (!raw.manifest().isModelBearing()) {
                    throw new IllegalArgumentException("asset_has_no_model: '" + entry.id()
                            + "' is texture-only — use asset_texture_export / asset_texture_describe");
                }
                if (state != null) {
                    byte[] bytes = raw.stateBytes().get(state);
                    if (bytes == null) {
                        throw new IllegalArgumentException("unknown state '" + state
                                + "' — available: " + raw.stateBytes().keySet());
                    }
                    return bytes;
                }
                return raw.defaultBytes();
            }
            case SBE -> {
                var parsed = new com.openmason.engine.format.sbe.SBEParser().parse(entry.sourcePath());
                if (variant != null) {
                    byte[] bytes = parsed.variantModelBytes().get(variant);
                    if (bytes == null) {
                        throw new IllegalArgumentException("unknown variant '" + variant
                                + "' — available: " + parsed.variantModelBytes().keySet());
                    }
                    return bytes;
                }
                if (state != null) {
                    byte[] bytes = parsed.stateModelBytes().get(state);
                    if (bytes == null) {
                        throw new IllegalArgumentException("state '" + state
                                + "' has no model override — states with models: "
                                + parsed.stateModelBytes().keySet());
                    }
                    return bytes;
                }
                return parsed.omoBytes();
            }
            default -> throw new IllegalStateException("unreachable");
        }
    }

    private static String blankTo(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ---------------------------------------------------------------- helpers

    protected AssetEntry resolve(String asset) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset is required (objectId, e.g. 'SB_Oak_Door')");
        }
        AssetEntry entry = catalog.find(asset);
        if (entry != null) {
            return entry;
        }
        if (asset.contains("/") || asset.contains("\\")
                || asset.toLowerCase(Locale.ROOT).matches(".*\\.(sbo|sbe)$")) {
            Path p = AssetPathGuard.requireAssetPath(asset,
                    catalog.scanRoots().toArray(new Path[0]));
            for (AssetEntry e : catalog.listAll(true)) {
                if (e.sourcePath().equals(p)) {
                    return e;
                }
            }
            throw new IllegalArgumentException("file exists but is not an indexed asset: " + asset
                    + " (is it a valid SBO/SBE inside the game resources?)");
        }
        throw McpErrors.unknownEntity("asset", asset, catalog.candidatesFor(asset, 6), "asset_list");
    }

    protected AssetModelDigest digest(AssetEntry entry, String state, String variant)
            throws IOException {
        return parseCache.get(entry, blankToNull(state), blankToNull(variant));
    }

    private static AssetModelDigest requireModel(AssetModelDigest digest) {
        if (!digest.modelBearing()) {
            throw new IllegalArgumentException("asset_has_no_model: '" + digest.entry().id()
                    + "' is texture-only — use asset_texture_describe / asset_texture_export");
        }
        return digest;
    }

    private static AssetModelDigest.Pixels pickPixels(AssetModelDigest digest, Integer materialId)
            throws IOException {
        if (materialId != null) {
            return digest.materialPixels(materialId);
        }
        if (!digest.modelBearing() || digest.materials().isEmpty()) {
            return digest.defaultTexturePixels();
        }
        if (digest.materials().size() == 1) {
            return digest.materialPixels(digest.materials().get(0).materialId());
        }
        List<Integer> ids = digest.materials().stream()
                .map(ParsedMaterialData::materialId).sorted().toList();
        String preview = ids.size() <= 12 ? ids.toString()
                : ids.subList(0, 12) + " …+" + (ids.size() - 12);
        throw new IllegalArgumentException("asset has " + ids.size()
                + " materials — pass 'material' (ids: " + preview
                + "; see asset_mesh_summary)");
    }

    private static BufferedImage toImage(AssetModelDigest.Pixels pixels) {
        BufferedImage image =
                new BufferedImage(pixels.width(), pixels.height(), BufferedImage.TYPE_INT_ARGB);
        int[] packed = pixels.packed();
        int[] argb = new int[packed.length];
        for (int i = 0; i < packed.length; i++) {
            int px = packed[i];
            argb[i] = ((px & 0xFF) << 24) | (((px >>> 24) & 0xFF) << 16)
                    | (((px >>> 16) & 0xFF) << 8) | ((px >>> 8) & 0xFF);
        }
        image.setRGB(0, 0, pixels.width(), pixels.height(), argb, 0, pixels.width());
        return image;
    }

    private static void putSelector(Map<String, Object> out, AssetModelDigest digest) {
        if (digest.state() != null) {
            out.put("state", digest.state());
        }
        if (digest.variant() != null) {
            out.put("variant", digest.variant());
        }
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    static float round4(float v) {
        return Math.round(v * 10000f) / 10000f;
    }

    static float[] round3(float[] v) {
        if (v == null) {
            return null;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = Math.round(v[i] * 1000f) / 1000f;
        }
        return out;
    }
}
