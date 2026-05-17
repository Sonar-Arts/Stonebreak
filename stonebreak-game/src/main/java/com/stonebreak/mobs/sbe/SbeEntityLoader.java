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

    private static SbeEntityAsset decode(String resourcePath) {
        try (InputStream in = SbeEntityLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("SBE resource not found: " + resourcePath);
            }
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
                    doc.objectName(), doc.objectId(), resourcePath, variants.size(), clips.size());
            return new SbeEntityAsset(doc.objectId(), variants, clips);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load SBE asset: " + resourcePath, e);
        }
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
        OMOReader.ReadResult omo = omoReader.read(new ByteArrayInputStream(omoBytes));
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

        // Mesh and UVs are used exactly as authored in the OMO — no remapping.
        return new SbeModelGeometry(
                mesh.vertices(), mesh.texCoords(), mesh.indices(),
                parts, materials);
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
