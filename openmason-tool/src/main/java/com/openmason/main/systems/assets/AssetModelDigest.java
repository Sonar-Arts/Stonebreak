package com.openmason.main.systems.assets;

import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.omt.OMTArchive;
import com.openmason.engine.format.omt.OMTReader;
import com.openmason.engine.format.omt.OmtCompositor;
import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbe.SBEParser;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.rendering.model.gmr.analysis.WindingAnalyzer;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.MeshImporter;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Normalized, GL-free read model of one SBO/SBE asset (optionally one of its
 * states/variants): manifest, per-part editable meshes with authoritative face
 * loops, winding reports, face→material mappings, and decoded texture pixels.
 *
 * <p>Construction never touches OpenGL or the UI thread, so asset-lens tools
 * run entirely on the MCP handler thread. Texture pixels decode lazily and are
 * memoized per material.
 */
public final class AssetModelDigest {

    /** Decoded texture pixels, packed row-major {@code 0xRRGGBBAA}. */
    public record Pixels(int width, int height, int[] packed) {
    }

    /** One model part with its imported mesh and winding analysis. */
    public record PartDigest(String id, String name, String parentId, boolean visible,
                             EditableMesh mesh, WindingAnalyzer.PartReport winding,
                             float[] bboxMin, float[] bboxMax) {
        public List<Integer> faceIds() {
            return mesh.faces().stream().map(f -> f.faceId()).sorted().toList();
        }
    }

    private final AssetEntry entry;
    private final String state;
    private final String variant;
    private final Object sboManifest; // SBOFormat.Document or SBEFormat.Document
    private final boolean modelBearing;
    private final String uvMode;
    private final ParsedMeshData meshData;
    private final List<PartDigest> parts;
    private final Map<Integer, ParsedFaceMapping> faceMappings;
    private final Map<Integer, String> faceToPart;
    private final List<ParsedMaterialData> materials;
    private final List<OMOFormat.AttachmentPointEntry> attachmentPoints;
    private final byte[] defaultOmtBytes; // OMT archive (texture-only SBOs / model default texture)
    private final Map<Integer, Pixels> materialPixelCache = new ConcurrentHashMap<>();
    private volatile Pixels defaultPixelsCache;

    private AssetModelDigest(AssetEntry entry, String state, String variant, Object manifest,
                             boolean modelBearing, String uvMode, ParsedMeshData meshData,
                             List<PartDigest> parts, Map<Integer, ParsedFaceMapping> faceMappings,
                             Map<Integer, String> faceToPart, List<ParsedMaterialData> materials,
                             List<OMOFormat.AttachmentPointEntry> attachmentPoints,
                             byte[] defaultOmtBytes) {
        this.entry = entry;
        this.state = state;
        this.variant = variant;
        this.sboManifest = manifest;
        this.modelBearing = modelBearing;
        this.uvMode = uvMode;
        this.meshData = meshData;
        this.parts = parts;
        this.faceMappings = faceMappings;
        this.faceToPart = faceToPart;
        this.materials = materials;
        this.attachmentPoints = attachmentPoints;
        this.defaultOmtBytes = defaultOmtBytes;
    }

    // ---------------------------------------------------------------- build

    /**
     * Parse and digest an asset.
     *
     * @param state   SBO/SBE state name, or null for the default model
     * @param variant SBE variant name, or null
     * @throws IllegalArgumentException for unknown state/variant or a
     *                                  texture-only asset when a model is required
     * @throws IOException              when the file cannot be parsed
     */
    public static AssetModelDigest build(AssetEntry entry, String state, String variant)
            throws IOException {
        return switch (entry.kind()) {
            case SBO -> buildSbo(entry, state, variant);
            case SBE -> buildSbe(entry, state, variant);
        };
    }

    private static AssetModelDigest buildSbo(AssetEntry entry, String state, String variant)
            throws IOException {
        if (variant != null) {
            throw new IllegalArgumentException("SBO assets have no variants — omit 'variant'");
        }
        SBOParser.RawParse raw = new SBOParser().parseRaw(entry.sourcePath());
        SBOFormat.Document manifest = raw.manifest();
        byte[] modelBytes;
        if (state != null) {
            modelBytes = raw.stateBytes().get(state);
            if (modelBytes == null) {
                throw new IllegalArgumentException("unknown state '" + state + "' — available: "
                        + raw.stateBytes().keySet());
            }
        } else {
            modelBytes = raw.defaultBytes();
        }
        if (!manifest.isModelBearing()) {
            // Texture-only SBO: the payload is an OMT archive, not a model.
            return new AssetModelDigest(entry, state, null, manifest, false, null, null,
                    List.of(), Map.of(), Map.of(), List.of(), List.of(), modelBytes);
        }
        return fromOmoBytes(entry, state, null, manifest, modelBytes);
    }

    private static AssetModelDigest buildSbe(AssetEntry entry, String state, String variant)
            throws IOException {
        SBEParser.ParsedSBE parsed = new SBEParser().parse(entry.sourcePath());
        SBEFormat.Document manifest = parsed.document();
        byte[] modelBytes = parsed.omoBytes();
        if (variant != null) {
            byte[] v = parsed.variantModelBytes().get(variant);
            if (v == null) {
                throw new IllegalArgumentException("unknown variant '" + variant + "' — available: "
                        + parsed.variantModelBytes().keySet());
            }
            modelBytes = v;
        } else if (state != null) {
            byte[] s = parsed.stateModelBytes().get(state);
            if (s == null) {
                throw new IllegalArgumentException("state '" + state + "' has no model override — "
                        + "states with models: " + parsed.stateModelBytes().keySet());
            }
            modelBytes = s;
        }
        return fromOmoBytes(entry, state, variant, manifest, modelBytes);
    }

    private static AssetModelDigest fromOmoBytes(AssetEntry entry, String state, String variant,
                                                 Object manifest, byte[] omoBytes) throws IOException {
        OMOReader.ReadResult omo = new OMOReader().read(new ByteArrayInputStream(omoBytes));
        ParsedMeshData mesh = omo.meshData();
        Map<Integer, ParsedFaceMapping> mappings = new TreeMap<>();
        if (omo.faceMappings() != null) {
            for (ParsedFaceMapping m : omo.faceMappings()) {
                mappings.put(m.faceId(), m);
            }
        }
        List<PartDigest> parts = new ArrayList<>();
        Map<Integer, String> faceToPart = new LinkedHashMap<>();
        if (mesh != null && mesh.hasGeometry()) {
            List<OMOFormat.PartEntry> partEntries = omo.parts();
            if (partEntries == null || partEntries.isEmpty()) {
                partEntries = List.of(syntheticWholeModelPart(mesh));
            }
            for (OMOFormat.PartEntry pe : partEntries) {
                PartDigest digest = digestPart(pe, mesh);
                parts.add(digest);
                for (int faceId : digest.faceIds()) {
                    faceToPart.putIfAbsent(faceId, pe.name());
                }
            }
        }
        return new AssetModelDigest(entry, state, variant, manifest, true,
                mesh != null ? mesh.uvMode() : null, mesh,
                Collections.unmodifiableList(parts), Collections.unmodifiableMap(mappings),
                Collections.unmodifiableMap(faceToPart),
                omo.materials() != null ? List.copyOf(omo.materials()) : List.of(),
                omo.attachmentPoints() != null ? List.copyOf(omo.attachmentPoints()) : List.of(),
                omo.defaultTextureBytes());
    }

    private static OMOFormat.PartEntry syntheticWholeModelPart(ParsedMeshData mesh) {
        return new OMOFormat.PartEntry("model", "model",
                0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1,
                0, mesh.getVertexCount(), 0, mesh.indices().length, 0, 0,
                true, false, null, null);
    }

    /**
     * Slice one part's soup out of the combined buffers and import + analyze it.
     *
     * <p>The part's triangle range is authoritative; referenced vertex indices
     * are gathered and compact-remapped rather than assuming they stay inside
     * {@code [vertexStart, vertexStart+vertexCount)} — shipped assets violate
     * that assumption (shared/seam vertices across part ranges).
     */
    private static PartDigest digestPart(OMOFormat.PartEntry pe, ParsedMeshData mesh) {
        int indexStart = pe.indexStart();
        int indexCount = Math.min(pe.indexCount(), Math.max(0, mesh.indices().length - indexStart));
        int[] globalIndices = mesh.indices();
        int totalVertices = mesh.getVertexCount();

        Map<Integer, Integer> remap = new LinkedHashMap<>();
        int[] indices = new int[indexCount];
        for (int i = 0; i < indexCount; i++) {
            int global = globalIndices[indexStart + i];
            if (global < 0 || global >= totalVertices) {
                indices[i] = -1; // corrupt reference — importer drops the triangle
                continue;
            }
            indices[i] = remap.computeIfAbsent(global, g -> remap.size());
        }
        float[] vertices = new float[remap.size() * 3];
        for (Map.Entry<Integer, Integer> e : remap.entrySet()) {
            System.arraycopy(mesh.vertices(), e.getKey() * 3, vertices, e.getValue() * 3, 3);
        }
        int triStart = indexStart / 3;
        int triCount = indexCount / 3;
        int[] triToFace = new int[triCount];
        int[] all = mesh.triangleToFaceId();
        for (int t = 0; t < triCount; t++) {
            triToFace[t] = all != null && triStart + t < all.length ? all[triStart + t] : triStart + t;
        }
        List<MeshImporter.ImportWarning> warnings = new ArrayList<>();
        MeshImporter.ImportResult imported =
                MeshImporter.importSoup(vertices, null, indices, triToFace, warnings::add);
        EditableMesh editable = imported.mesh();
        WindingAnalyzer.PartReport winding = WindingAnalyzer.analyze(pe.name(), editable, warnings);
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (int v = 0; v < editable.vertexCount(); v++) {
            min.min(editable.position(v));
            max.max(editable.position(v));
        }
        boolean any = editable.vertexCount() > 0;
        return new PartDigest(pe.id(), pe.name(), pe.parentId(), pe.visible(), editable, winding,
                any ? new float[]{min.x, min.y, min.z} : new float[]{0, 0, 0},
                any ? new float[]{max.x, max.y, max.z} : new float[]{0, 0, 0});
    }

    // -------------------------------------------------------------- queries

    public AssetEntry entry() {
        return entry;
    }

    public String state() {
        return state;
    }

    public String variant() {
        return variant;
    }

    /** {@code SBOFormat.Document} or {@code SBEFormat.Document}. */
    public Object manifest() {
        return sboManifest;
    }

    public boolean modelBearing() {
        return modelBearing;
    }

    public String uvMode() {
        return uvMode;
    }

    public List<PartDigest> parts() {
        return parts;
    }

    public PartDigest part(String name) {
        for (PartDigest p : parts) {
            if (p.name().equalsIgnoreCase(name) || p.id().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    public Map<Integer, ParsedFaceMapping> faceMappings() {
        return faceMappings;
    }

    public String partOfFace(int faceId) {
        return faceToPart.get(faceId);
    }

    public List<ParsedMaterialData> materials() {
        return materials;
    }

    public List<OMOFormat.AttachmentPointEntry> attachmentPoints() {
        return attachmentPoints;
    }

    public int totalVertices() {
        return meshData != null ? meshData.getVertexCount() : 0;
    }

    public int totalTriangles() {
        return meshData != null && meshData.indices() != null ? meshData.indices().length / 3 : 0;
    }

    public int totalFaces() {
        return faceToPart.size();
    }

    /** Decoded pixels of a material's PNG. Throws for unknown materials. */
    public Pixels materialPixels(int materialId) {
        Pixels cached = materialPixelCache.get(materialId);
        if (cached != null) {
            return cached;
        }
        ParsedMaterialData material = materials.stream()
                .filter(m -> m.materialId() == materialId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown material id " + materialId
                        + " — known: " + materials.stream().map(ParsedMaterialData::materialId).toList()));
        if (material.texturePng() == null) {
            throw new IllegalArgumentException("material " + materialId + " has no embedded texture");
        }
        Pixels pixels = decodePng(material.texturePng());
        materialPixelCache.put(materialId, pixels);
        return pixels;
    }

    /**
     * Decoded default texture: for texture-only SBOs the OMT payload, for
     * model-bearing assets the embedded default OMT (when present). Flattened
     * with {@link OmtCompositor} — never a single layer.
     */
    public Pixels defaultTexturePixels() throws IOException {
        Pixels cached = defaultPixelsCache;
        if (cached != null) {
            return cached;
        }
        if (defaultOmtBytes == null) {
            throw new IllegalArgumentException("asset has no default texture archive"
                    + (materials.isEmpty() ? "" : " — use its material textures instead"));
        }
        OMTArchive archive = new OMTReader().read(defaultOmtBytes);
        OmtCompositor.Composited flat = OmtCompositor.composite(archive, pngBytes -> {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
                if (img == null) {
                    return null;
                }
                int w = img.getWidth();
                int h = img.getHeight();
                byte[] rgba = new byte[w * h * 4];
                int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
                for (int i = 0; i < argb.length; i++) {
                    int px = argb[i];
                    rgba[i * 4] = (byte) ((px >> 16) & 0xFF);
                    rgba[i * 4 + 1] = (byte) ((px >> 8) & 0xFF);
                    rgba[i * 4 + 2] = (byte) (px & 0xFF);
                    rgba[i * 4 + 3] = (byte) ((px >>> 24) & 0xFF);
                }
                return new OmtCompositor.PngDecoder.Decoded(w, h, rgba);
            } catch (IOException e) {
                return null;
            }
        });
        int[] packed = new int[flat.width() * flat.height()];
        byte[] rgba = flat.rgba();
        for (int i = 0; i < packed.length; i++) {
            int o = i * 4;
            packed[i] = ((rgba[o] & 0xFF) << 24) | ((rgba[o + 1] & 0xFF) << 16)
                    | ((rgba[o + 2] & 0xFF) << 8) | (rgba[o + 3] & 0xFF);
        }
        Pixels pixels = new Pixels(flat.width(), flat.height(), packed);
        defaultPixelsCache = pixels;
        return pixels;
    }

    /** Decode a PNG into packed {@code 0xRRGGBBAA} pixels. */
    public static Pixels decodePng(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) {
                throw new IllegalArgumentException("embedded texture is not a decodable PNG");
            }
            int w = img.getWidth();
            int h = img.getHeight();
            int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
            int[] packed = new int[w * h];
            for (int i = 0; i < packed.length; i++) {
                int px = argb[i];
                packed[i] = (((px >> 16) & 0xFF) << 24) | (((px >> 8) & 0xFF) << 16)
                        | ((px & 0xFF) << 8) | ((px >>> 24) & 0xFF);
            }
            return new Pixels(w, h, packed);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to decode embedded PNG: " + e.getMessage());
        }
    }
}
