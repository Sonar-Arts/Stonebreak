package com.stonebreak.mobs.sbe;

import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.oma.OMAReader;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.sbe.AnimationCompatibility;
import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbe.SBEParser;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic, entity-blind loader for SBE entity assets.
 *
 * <p>Decodes any {@code .sbe} classpath resource — using the engine's
 * {@link SBEParser}, {@link OMOReader} and {@link OMAReader} — into a
 * render-ready {@link SbeEntityAsset}, and caches the result by resource path so
 * each file is decoded only once. The loader has no knowledge of which entity an
 * SBE describes; entity-specific glue (which path to load, AI-state → clip-state
 * mapping) lives with the entity.
 *
 * <p>Decoding is GL-free (textures are decoded to {@code byte[]}), so
 * {@link #load(String)} may be called from any thread; the renderer performs GPU
 * upload separately.
 */
public final class SbeEntityLoader {

    private static final Logger logger = LoggerFactory.getLogger(SbeEntityLoader.class);

    private static final Map<String, SbeEntityAsset> CACHE = new ConcurrentHashMap<>();

    private SbeEntityLoader() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Loads (or returns the cached) SBE entity asset for a classpath resource.
     *
     * @param resourcePath absolute classpath path, e.g. {@code /sbe/Mobs/SB_Cow.sbe}
     * @throws IllegalStateException if the resource is missing or cannot be decoded
     */
    public static SbeEntityAsset load(String resourcePath) {
        return CACHE.computeIfAbsent(resourcePath, SbeEntityLoader::decode);
    }

    /**
     * Loads (or returns the cached) attachable accessory asset from a file on
     * disk (see {@code EntityAttachments}). Dispatches on extension:
     * <ul>
     *   <li>{@code .omo} — bare model; single-variant, clipless asset</li>
     *   <li>{@code .sbe} — full entity asset (variants + clips); attachments
     *       render its Default variant at rest pose</li>
     *   <li>{@code .sbo} — the embedded {@code model.omo} is extracted (for
     *       stated SBOs this is the default state's model, thanks to the
     *       format's 1.3 legacy mirror); texture-only SBOs are rejected</li>
     * </ul>
     *
     * @throws IllegalStateException if the file is missing or cannot be decoded
     */
    public static SbeEntityAsset loadAttachable(java.nio.file.Path file) {
        String key = file.toAbsolutePath().toString();
        return CACHE.computeIfAbsent(key, k -> {
            try (InputStream in = java.nio.file.Files.newInputStream(file)) {
                return decodeAttachable(k, in);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load attachable asset: " + k, e);
            }
        });
    }

    /**
     * Classpath twin of {@link #loadAttachable(java.nio.file.Path)}.
     *
     * @param resourcePath absolute classpath path,
     *                     e.g. {@code /models/accessories/mustache.sbo}
     * @throws IllegalStateException if the resource is missing or cannot be decoded
     */
    public static SbeEntityAsset loadAttachableResource(String resourcePath) {
        return CACHE.computeIfAbsent(resourcePath, k -> {
            try (InputStream in = SbeEntityLoader.class.getResourceAsStream(k)) {
                if (in == null) {
                    throw new IllegalStateException("Attachable resource not found: " + k);
                }
                return decodeAttachable(k, in);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load attachable asset: " + k, e);
            }
        });
    }

    private static SbeEntityAsset decodeAttachable(String id, InputStream in) throws IOException {
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(SBEFormat.FILE_EXTENSION)) {
            return decodeSbe(id, in);
        }
        if (lower.endsWith(com.openmason.engine.format.sbo.SBOFormat.FILE_EXTENSION)) {
            return decodeSboModel(id, in);
        }
        return decodeOmo(id, in);
    }

    private static SbeEntityAsset decodeOmo(String id, InputStream in) throws IOException {
        SbeModelGeometry geometry = buildGeometry(new OMOReader().read(in));
        return new SbeEntityAsset(id,
                Map.of(SbeEntityAsset.DEFAULT_VARIANT, geometry), Map.of());
    }

    /**
     * Extract the SBO's embedded {@code model.omo} and decode it like a bare
     * OMO. Model-bearing SBOs always carry this entry — for stated SBOs it
     * mirrors the default state's model (format 1.3+ legacy mirror) — so this
     * covers both stateless and stated files without a full SBO parse.
     */
    private static SbeEntityAsset decodeSboModel(String id, InputStream in) throws IOException {
        byte[] omoBytes = null;
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(in)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (com.openmason.engine.format.sbo.SBOFormat.EMBEDDED_OMO_FILENAME
                        .equals(entry.getName())) {
                    omoBytes = zis.readAllBytes();
                }
                zis.closeEntry();
            }
        }
        if (omoBytes == null) {
            throw new IOException("SBO has no embedded model — texture-only SBOs "
                    + "cannot be attached: " + id);
        }
        return decodeOmo(id, new ByteArrayInputStream(omoBytes));
    }

    private static SbeEntityAsset decode(String resourcePath) {
        try (InputStream in = SbeEntityLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("SBE resource not found: " + resourcePath);
            }
            return decodeSbe(resourcePath, in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load SBE asset: " + resourcePath, e);
        }
    }

    /** Decode a full SBE (variants + clips) from an already-open stream. */
    private static SbeEntityAsset decodeSbe(String sourceId, InputStream in) throws IOException {
        SBEParser.ParsedSBE parsed = new SBEParser().parse(in);
        SBEFormat.Document doc = parsed.document();

        OMOReader omoReader = new OMOReader();

        // Variants: the base OMO is the Default appearance; each manifest
        // variant supplies its own retextured OMO.
        Map<String, SbeModelGeometry> variants = new LinkedHashMap<>();
        variants.put(SbeEntityAsset.DEFAULT_VARIANT, buildGeometry(omoReader, parsed.omoBytes()));
        for (SBEFormat.VariantEntry variant : doc.variants()) {
            byte[] bytes = parsed.variantModelFor(variant.name());
            if (bytes != null) {
                variants.put(variant.name(), buildGeometry(omoReader, bytes));
            }
        }

        // States: keep the animation clip; the per-state OMO geometry is a
        // pose snapshot of the same skeleton and is intentionally ignored —
        // clips are played against the variant geometry.
        OMAReader omaReader = new OMAReader();
        List<String> basePartIds = partIds(variants.get(SbeEntityAsset.DEFAULT_VARIANT));
        Map<String, ParsedAnimClip> clips = new LinkedHashMap<>();
        for (SBEFormat.StateEntry state : doc.states()) {
            byte[] clipBytes = parsed.clipFor(state.name());
            if (clipBytes == null) continue;
            ParsedAnimClip clip = omaReader.read(clipBytes);
            clips.put(state.name(), clip);

            AnimationCompatibility.Result compat =
                    AnimationCompatibility.check(clip.requiredParts(), basePartIds);
            if (!compat.isCompatible()) {
                logger.warn("SBE '{}' state '{}' clip animates parts missing from the model: {}",
                        doc.objectName(), state.name(), compat.describeMissing());
            }
        }

        logger.info("Loaded SBE '{}' ({}) from {}: {} variant(s), {} animation state(s)",
                doc.objectName(), doc.objectId(), sourceId, variants.size(), clips.size());
        return new SbeEntityAsset(doc.objectId(), variants, clips);
    }

    private static List<String> partIds(SbeModelGeometry geometry) {
        List<String> ids = new ArrayList<>();
        if (geometry != null) {
            for (SbePart part : geometry.parts()) {
                ids.add(part.id());
            }
        }
        return ids;
    }

    /** Decode one embedded OMO into render-ready geometry. */
    private static SbeModelGeometry buildGeometry(OMOReader omoReader, byte[] omoBytes)
            throws IOException {
        return buildGeometry(omoReader.read(new ByteArrayInputStream(omoBytes)));
    }

    /**
     * Build render-ready geometry from an already-parsed OMO. Public so
     * non-SBE callers with a {@link OMOReader.ReadResult} in hand (e.g. the
     * animated-block renderer, which gets per-state OMOs from an SBO parse)
     * can reuse the exact mob geometry pipeline.
     */
    public static SbeModelGeometry buildGeometry(OMOReader.ReadResult omo) throws IOException {
        ParsedMeshData mesh = omo.meshData();
        if (mesh == null || !mesh.hasGeometry()) {
            throw new IOException("OMO model has no mesh geometry");
        }

        // faceId -> materialId
        Map<Integer, Integer> faceMaterial = new HashMap<>();
        for (ParsedFaceMapping mapping : omo.faceMappings()) {
            faceMaterial.put(mapping.faceId(), mapping.materialId());
        }

        // faceId -> [indexStart, indexCount] from the triangle->face map
        Map<Integer, int[]> faceIndexRange = buildFaceIndexRanges(mesh.triangleToFaceId());

        // materialId -> decoded texture
        Map<Integer, MaterialImage> materials = new HashMap<>();
        for (ParsedMaterialData material : omo.materials()) {
            if (material.texturePng() != null) {
                materials.put(material.materialId(), decodePng(material.texturePng()));
            }
        }

        // Parts
        List<SbePart> parts = new ArrayList<>();
        for (OMOFormat.PartEntry pe : omo.parts()) {
            List<SbeFace> faces = new ArrayList<>();
            for (int faceId = pe.faceStart(); faceId < pe.faceStart() + pe.faceCount(); faceId++) {
                int[] range = faceIndexRange.get(faceId);
                if (range == null) continue;
                int materialId = faceMaterial.getOrDefault(faceId, -1);
                faces.add(new SbeFace(faceId, materialId, range[0], range[1]));
            }
            SbePart part = new SbePart(
                    pe.id(), pe.name(), pe.parentId(),
                    new Vector3f(pe.originX(), pe.originY(), pe.originZ()),
                    new Vector3f(pe.posX(), pe.posY(), pe.posZ()),
                    new Vector3f(pe.rotX(), pe.rotY(), pe.rotZ()),
                    new Vector3f(pe.scaleX(), pe.scaleY(), pe.scaleZ()),
                    faces);
            parts.add(part);
        }

        // Partless OMOs (single-cube block models don't persist a parts array):
        // synthesize one identity-rest part covering every face, named after
        // the model so clips authored in the tool still bind — the tool's
        // per-session part UUIDs aren't stable, but clip tracks carry the part
        // NAME as a hint and the renderer falls back to name matching.
        if (parts.isEmpty()) {
            List<SbeFace> allFaces = new ArrayList<>();
            for (Map.Entry<Integer, int[]> e : faceIndexRange.entrySet()) {
                int faceId = e.getKey();
                int[] range = e.getValue();
                allFaces.add(new SbeFace(faceId, faceMaterial.getOrDefault(faceId, -1),
                        range[0], range[1]));
            }
            String name = omo.document() != null && omo.document().objectName() != null
                    ? omo.document().objectName() : "root";
            parts.add(new SbePart(
                    "synthetic:root", name, null,
                    new Vector3f(0, 0, 0),   // pivot at the model origin
                    new Vector3f(0, 0, 0),
                    new Vector3f(0, 0, 0),
                    new Vector3f(1, 1, 1),
                    allFaces));
        }

        // Mesh and UVs are used exactly as authored in the OMO — no remapping.
        // Guard against null arrays (e.g. models with no UV data) to prevent NPE in uploadVariant.
        float[] vertices  = mesh.vertices()  != null ? mesh.vertices()  : new float[0];
        float[] texCoords = mesh.texCoords() != null ? mesh.texCoords() : new float[0];
        int[]   indices   = mesh.indices()   != null ? mesh.indices()   : new int[0];

        // Attachment points (OMO v1.7+)
        List<SbeAttachmentPoint> attachmentPoints = new ArrayList<>();
        for (OMOFormat.AttachmentPointEntry ap : omo.attachmentPoints()) {
            attachmentPoints.add(new SbeAttachmentPoint(
                    ap.id(), ap.name(), ap.parentPartId(), ap.parentPartName(),
                    new Vector3f(ap.posX(), ap.posY(), ap.posZ()),
                    new Vector3f(ap.rotX(), ap.rotY(), ap.rotZ()),
                    new Vector3f(ap.scaleX(), ap.scaleY(), ap.scaleZ())));
        }

        return new SbeModelGeometry(vertices, texCoords, indices, parts, materials,
                computeRestMinY(vertices, indices), attachmentPoints);
    }

    /**
     * Lowest model-space Y across all rendered (indexed) vertices. Vertices are
     * stored in rest pose, so this is the resting height of the model's feet
     * relative to its origin — the value renderers use to ground-anchor mobs.
     */
    private static float computeRestMinY(float[] vertices, int[] indices) {
        float min = Float.POSITIVE_INFINITY;
        for (int index : indices) {
            float y = vertices[index * 3 + 1];
            if (y < min) {
                min = y;
            }
        }
        return min == Float.POSITIVE_INFINITY ? 0f : min;
    }

    /**
     * Group triangles by face id into contiguous index ranges. {@code value[0]}
     * is the first index, {@code value[1]} the index count.
     */
    private static Map<Integer, int[]> buildFaceIndexRanges(int[] triangleToFaceId) {
        Map<Integer, int[]> ranges = new HashMap<>();
        if (triangleToFaceId == null) return ranges;
        for (int triangle = 0; triangle < triangleToFaceId.length; triangle++) {
            int faceId = triangleToFaceId[triangle];
            int[] range = ranges.get(faceId);
            if (range == null) {
                ranges.put(faceId, new int[]{triangle * 3, 3});
            } else {
                range[1] += 3;
            }
        }
        return ranges;
    }

    /**
     * Decode a PNG into a tightly-packed RGBA8 buffer, rows in source order
     * (top row first) — the texture is uploaded exactly as authored and the
     * OMO's texture coordinates are used verbatim.
     */
    private static MaterialImage decodePng(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) {
            throw new IOException("Unsupported material PNG data");
        }
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] rgba = new byte[w * h * 4];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            int base = y * w * 4;
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                int o = base + x * 4;
                rgba[o]     = (byte) ((argb >> 16) & 0xFF); // R
                rgba[o + 1] = (byte) ((argb >> 8) & 0xFF);  // G
                rgba[o + 2] = (byte) (argb & 0xFF);         // B
                rgba[o + 3] = (byte) ((argb >> 24) & 0xFF); // A
            }
        }
        return new MaterialImage(w, h, rgba);
    }
}
