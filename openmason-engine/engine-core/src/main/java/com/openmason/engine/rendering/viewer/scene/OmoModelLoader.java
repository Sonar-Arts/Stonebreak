package com.openmason.engine.rendering.viewer.scene;

import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.omt.OMTReader;
import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads an {@code .omo} into a display-ready {@link GenericModelRenderer}.
 *
 * <p><b>Display-only.</b> It deliberately skips rebuilding the part hierarchy: the OMO's
 * combined vertex buffer already has each part's effective transform baked in, which is
 * exactly what rendering needs. Reconstructing the hierarchy (inverse-baking plus a
 * topological sort) only matters for editing, and doing it here would duplicate a couple
 * hundred lines of editor logic for no visual difference.
 *
 * <p>Two entry points because a scene resolves models two ways: from a file on disk, and
 * from bytes embedded in a scene archive when the file is missing.
 */
public final class OmoModelLoader implements ModelSource {

    private static final Logger logger = LoggerFactory.getLogger(OmoModelLoader.class);

    private final TextureUploader textureUploader;

    /**
     * @param textureUploader how PNGs reach the GPU; the only GL-touching step, so tests
     *                        can substitute a stub and exercise everything else headlessly
     */
    public OmoModelLoader(TextureUploader textureUploader) {
        this.textureUploader = java.util.Objects.requireNonNull(textureUploader, "textureUploader");
    }

    /** Result of a load: the renderer plus the texture ids it now owns. */
    public record Loaded(GenericModelRenderer renderer, String modelName, int[] textureIds) {}

    @Override
    public Loaded load(Path omoPath) throws IOException {
        try (InputStream in = Files.newInputStream(omoPath)) {
            return load(in, omoPath.getFileName().toString());
        }
    }

    @Override
    public Loaded load(byte[] omoBytes, String displayName) throws IOException {
        return load(new ByteArrayInputStream(omoBytes), displayName);
    }

    private Loaded load(InputStream in, String displayName) throws IOException {
        OMOReader.ReadResult result = new OMOReader().read(in);
        if (result == null || result.meshData() == null) {
            throw new IOException("OMO contained no mesh data: " + displayName);
        }

        GenericModelRenderer renderer = new GenericModelRenderer();

        ParsedMeshData mesh = result.meshData();
        String modelName = result.document() != null && result.document().objectName() != null
                ? result.document().objectName()
                : displayName;

        // Geometry MUST be loaded before initialize(). BaseRenderer only creates the VBO
        // and configures vertex attributes when geometry already exists at init time, and
        // its updateVBO() silently no-ops while vbo == 0 — so initializing an empty
        // renderer first leaves it permanently unable to accept vertex data, and it draws
        // nothing while still reporting isInitialized() == true. (The model editor gets
        // this right by accident: it builds a default model before its GL init runs.)
        renderer.loadMeshDataAsPart(new OMOFormat.MeshData(
                mesh.vertices(), mesh.texCoords(), mesh.indices(),
                mesh.triangleToFaceId(), mesh.uvMode()), modelName);

        renderer.initialize();

        List<Integer> textureIds = new ArrayList<>();
        FaceTextureManager ftm = renderer.getFaceTextureManager();

        List<ParsedMaterialData> materials = result.materials();
        if (materials != null && !materials.isEmpty()) {
            for (ParsedMaterialData material : materials) {
                int textureId = material.texturePng() != null
                        ? textureUploader.upload(material.texturePng())
                        : 0;
                if (textureId <= 0) {
                    logger.warn("No texture for material {} ('{}') in {}",
                            material.materialId(), material.name(), displayName);
                    continue;
                }
                textureIds.add(textureId);

                MaterialDefinition.RenderLayer layer;
                try {
                    layer = MaterialDefinition.RenderLayer.valueOf(material.renderLayer());
                } catch (IllegalArgumentException | NullPointerException e) {
                    layer = MaterialDefinition.RenderLayer.OPAQUE;
                }

                ftm.registerMaterial(new MaterialDefinition(
                        material.materialId(),
                        material.name(),
                        textureId,
                        layer,
                        new MaterialDefinition.MaterialProperties(material.emissive(), material.tintColor())));
            }

            List<ParsedFaceMapping> mappings = result.faceMappings();
            if (mappings != null) {
                for (ParsedFaceMapping mapping : mappings) {
                    ftm.setFaceMapping(new FaceTextureMapping(
                            mapping.faceId(),
                            mapping.materialId(),
                            new FaceTextureMapping.UVRegion(mapping.u0(), mapping.v0(), mapping.u1(), mapping.v1()),
                            FaceTextureMapping.UVRotation.fromDegrees(mapping.uvRotationDegrees()),
                            mapping.autoResize()));
                }
            }
        } else {
            // No per-face materials: fall back to the embedded .omt's first visible layer,
            // which is what a plain single-texture model carries.
            int textureId = uploadDefaultTexture(result.defaultTextureBytes(), displayName);
            if (textureId > 0) {
                textureIds.add(textureId);
                renderer.setTexture(textureId);
            }
        }

        renderer.markDrawBatchesDirty();
        renderer.refreshUVs();

        int[] ids = new int[textureIds.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = textureIds.get(i);
        }
        logger.debug("Loaded model '{}' ({} textures)", modelName, ids.length);
        return new Loaded(renderer, modelName, ids);
    }

    /**
     * Upload the model's embedded texture.
     *
     * <p>Composites every visible layer rather than taking the first: a {@code .omt} is a
     * layer stack, so picking one layer drops everything above it, and a model whose base
     * layer is still empty comes back fully transparent — which is what made freshly
     * created models render black.
     */
    private int uploadDefaultTexture(byte[] omtBytes, String displayName) {
        if (omtBytes == null || omtBytes.length == 0) {
            return 0;
        }
        try {
            com.openmason.engine.format.omt.OMTArchive archive = new OMTReader().read(omtBytes);

            com.openmason.engine.format.omt.OmtCompositor.Composited flat =
                    com.openmason.engine.format.omt.OmtCompositor.composite(
                            archive, textureUploader::decode);
            if (flat != null) {
                int id = textureUploader.uploadRgba(flat.width(), flat.height(), flat.rgba());
                if (id > 0) {
                    return id;
                }
            }

            // Fallback: an uploader that cannot decode/composite still gets something on
            // screen from the first visible layer.
            for (com.openmason.engine.format.omt.OMTArchive.Layer layer : archive.layers()) {
                if (layer.visible() && layer.pngBytes() != null && layer.pngBytes().length > 0) {
                    return textureUploader.upload(layer.pngBytes());
                }
            }
            return 0;
        } catch (Exception e) {
            logger.warn("Could not read embedded texture for {}: {}", displayName, e.getMessage());
            return 0;
        }
    }
}
