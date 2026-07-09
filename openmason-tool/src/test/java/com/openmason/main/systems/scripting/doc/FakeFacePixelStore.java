package com.openmason.main.systems.scripting.doc;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory {@link FacePixelStore} test fake: textures are plain
 * {@link PixelCanvas} copies keyed by incrementing ids (first texture is id 1),
 * material ids come from the document's registry (max + 1), and face
 * assignment delegates to {@link FaceTextureManager#assignDefaultMapping} —
 * the same data-level effect the live store produces.
 *
 * <p>{@link #createTexture} stores a COPY of the canvas, so the fake's bytes
 * only change through {@link #writeRegion} — exactly the seam the command
 * layer must flush through. Test hooks read those bytes back.
 */
public final class FakeFacePixelStore implements FacePixelStore {

    private final FaceTextureManager faceTextures;
    private final Map<Integer, PixelCanvas> textures = new LinkedHashMap<>();
    private int nextTextureId = 1;
    private int nextMaterialId = 1;

    public FakeFacePixelStore(FaceTextureManager faceTextures) {
        this.faceTextures = faceTextures;
    }

    /** A headless document whose {@code pixels()} seam is the given fake store. */
    public static ModelDocument document(HeadlessModelDocument inner, FakeFacePixelStore store) {
        return new ModelDocument() {
            @Override
            public ModelPartManager parts() {
                return inner.parts();
            }

            @Override
            public FaceTextureManager faceTextures() {
                return inner.faceTextures();
            }

            @Override
            public OMOFormat.MeshData extractMeshData() {
                return inner.extractMeshData();
            }

            @Override
            public FacePixelStore pixels() {
                return store;
            }
        };
    }

    @Override
    public int[] textureSize(int gpuTextureId) {
        PixelCanvas canvas = textures.get(gpuTextureId);
        return canvas == null ? null : new int[]{canvas.getWidth(), canvas.getHeight()};
    }

    @Override
    public byte[] readPixels(int gpuTextureId) {
        PixelCanvas canvas = textures.get(gpuTextureId);
        return canvas == null ? null : canvas.getPixelsAsRGBABytes();
    }

    @Override
    public void writeRegion(int gpuTextureId, int x, int y, int width, int height, byte[] rgbaBytes) {
        PixelCanvas canvas = textures.get(gpuTextureId);
        if (canvas == null) {
            throw new IllegalStateException("writeRegion on unknown texture " + gpuTextureId);
        }
        int[] pixels = canvas.getPixels();
        int i = 0;
        for (int yy = y; yy < y + height; yy++) {
            for (int xx = x; xx < x + width; xx++) {
                pixels[yy * canvas.getWidth() + xx] = PixelCanvas.packRGBA(
                        rgbaBytes[i] & 0xFF, rgbaBytes[i + 1] & 0xFF,
                        rgbaBytes[i + 2] & 0xFF, rgbaBytes[i + 3] & 0xFF);
                i += 4;
            }
        }
    }

    @Override
    public int createTexture(PixelCanvas canvas) {
        int id = nextTextureId++;
        // Copy: the command layer keeps the original as its CPU mirror; the
        // store's bytes must only change through writeRegion flushes.
        textures.put(id, canvas.copy());
        return id;
    }

    @Override
    public int allocateMaterialId() {
        int max = 0;
        for (MaterialDefinition material : faceTextures.getAllMaterials()) {
            max = Math.max(max, material.materialId());
        }
        nextMaterialId = Math.max(nextMaterialId, max + 1);
        return nextMaterialId++;
    }

    @Override
    public void assignFaceMaterials(int[] globalFaceIds, int[] materialIds) {
        for (int i = 0; i < globalFaceIds.length; i++) {
            faceTextures.assignDefaultMapping(globalFaceIds[i], materialIds[i]);
        }
    }

    @Override
    public void deleteTexture(int gpuTextureId) {
        textures.remove(gpuTextureId);
    }

    // ===================== Test hooks =====================

    public boolean hasTexture(int textureId) {
        return textures.containsKey(textureId);
    }

    public int textureCount() {
        return textures.size();
    }

    /** The store's bytes for one pixel, as [r,g,b,a] 0..255. */
    public int[] pixel(int textureId, int x, int y) {
        PixelCanvas canvas = textures.get(textureId);
        if (canvas == null) {
            throw new AssertionError("no texture " + textureId + " in the fake store");
        }
        return PixelCanvas.unpackRGBA(canvas.getPixel(x, y));
    }
}
