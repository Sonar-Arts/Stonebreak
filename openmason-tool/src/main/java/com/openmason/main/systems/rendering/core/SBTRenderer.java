package com.openmason.main.systems.rendering.core;

import com.openmason.engine.format.omt.OMTArchive;
import com.openmason.engine.format.omt.OMTReader;
import com.openmason.engine.format.sbt.SBTParser;
import com.stonebreak.rendering.player.items.voxelization.VoxelData;
import com.stonebreak.rendering.player.items.voxelization.VoxelMesh;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;

/**
 * Renders a .SBT texture in the viewport by voxelising every visible pixel
 * of the embedded OMT (mirroring stonebreak-game's {@code SpriteVoxelizer}
 * geometry) and drawing each voxel with its own solid colour.
 *
 * <p>This deliberately reuses {@link VoxelMesh} / {@link VoxelData} from the
 * game module so the resulting voxel layout, voxel size, and depth match
 * what the player sees holding the same SBT in-game. The colour palette
 * step is skipped — Open Mason renders colours directly per voxel via a
 * shader uniform, the same approach {@link ItemRenderer} uses.
 */
public class SBTRenderer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SBTRenderer.class);

    // Geometry constants — must match SpriteVoxelizer for visual parity.
    private static final float VOXEL_SIZE = 0.02f;
    private static final float SPRITE_SCALE = 0.02f;
    private static final float DEPTH = 0.08f;
    private static final float VERTICAL_OFFSET = 0.3f;

    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private final Map<String, CachedSprite> cache = new HashMap<>();
    private final SBTParser sbtParser = new SBTParser();
    private final OMTReader omtReader = new OMTReader();

    private boolean initialized = false;

    public void initialize() {
        initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void renderSBT(Path file, int shaderProgram,
                          int mvpLocation, int modelLocation,
                          float[] vpMatrix, Matrix4f modelMatrix,
                          int useTextureLocation) {
        if (!initialized || file == null) {
            return;
        }
        CachedSprite sprite = loadSprite(file);
        if (sprite == null || sprite.mesh == null || !sprite.mesh.isCreated()) {
            return;
        }

        int colorLocation = glGetUniformLocation(shaderProgram, "uColor");
        if (useTextureLocation != -1) {
            glUseProgram(shaderProgram);
            glUniform1i(useTextureLocation, 0);
        }

        glUseProgram(shaderProgram);
        if (mvpLocation != -1 && vpMatrix != null) {
            glUniformMatrix4fv(mvpLocation, false, vpMatrix);
        }
        if (modelLocation != -1 && modelMatrix != null) {
            modelMatrix.get(matrixBuffer);
            glUniformMatrix4fv(modelLocation, false, matrixBuffer);
        }

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);

        sprite.mesh.bind();
        int facesPerVoxel = 6;
        int indicesPerFace = 6;
        int[] colors = sprite.colors;

        for (int i = 0; i < colors.length; i++) {
            int rgba = colors[i];
            float r = ((rgba >> 16) & 0xFF) / 255.0f;
            float g = ((rgba >> 8) & 0xFF) / 255.0f;
            float b = (rgba & 0xFF) / 255.0f;
            if (colorLocation != -1) {
                glUniform3f(colorLocation, r, g, b);
            }
            int startIndex = i * facesPerVoxel * indicesPerFace;
            glDrawElements(GL_TRIANGLES, facesPerVoxel * indicesPerFace,
                    GL_UNSIGNED_INT, (long) startIndex * Integer.BYTES);
        }
        sprite.mesh.unbind();

        glDisable(GL_BLEND);
        glDepthMask(true);
    }

    /** Force a re-load of the given file on next render. */
    public void invalidate(Path file) {
        if (file == null) return;
        CachedSprite cached = cache.remove(file.toString());
        if (cached != null && cached.mesh != null) {
            cached.mesh.cleanup();
        }
    }

    private CachedSprite loadSprite(Path file) {
        String key = file.toString();
        long mtime = readMtime(file);
        CachedSprite cached = cache.get(key);
        if (cached != null && cached.mtime == mtime) {
            return cached;
        }
        if (cached != null && cached.mesh != null) {
            cached.mesh.cleanup();
        }

        try {
            BufferedImage image = readSpriteFromSBT(file);
            if (image == null) {
                return null;
            }
            List<VoxelData> voxels = new ArrayList<>();
            int[] colors = voxeliseImage(image, voxels);

            VoxelMesh mesh = new VoxelMesh();
            mesh.createMesh(voxels, VOXEL_SIZE);

            CachedSprite fresh = new CachedSprite(mesh, colors, mtime);
            cache.put(key, fresh);
            return fresh;
        } catch (Exception e) {
            logger.warn("Failed to load SBT for viewport: {}", file, e);
            return null;
        }
    }

    private BufferedImage readSpriteFromSBT(Path file) throws Exception {
        if (!Files.exists(file)) return null;
        SBTParser.Result sbt = sbtParser.read(file);
        OMTArchive archive = omtReader.read(sbt.omtBytes());

        int w = archive.canvasSize().width();
        int h = archive.canvasSize().height();
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = canvas.createGraphics();
        try {
            g2d.setComposite(AlphaComposite.SrcOver);
            for (OMTArchive.Layer layer : archive.layers()) {
                if (!layer.visible() || layer.pngBytes() == null) continue;
                BufferedImage layerImage = ImageIO.read(new ByteArrayInputStream(layer.pngBytes()));
                if (layerImage == null) continue;
                g2d.setComposite(AlphaComposite.SrcOver.derive(layer.opacity()));
                g2d.drawImage(layerImage, 0, 0, w, h, null);
            }
        } finally {
            g2d.dispose();
        }
        return canvas;
    }

    /**
     * Walk the image and emit one voxel per non-transparent pixel, mirroring
     * {@code SpriteVoxelizer.voxelizeSprite}. Returns the per-voxel colour
     * array in the same order the voxels are added to {@code voxels}.
     */
    private int[] voxeliseImage(BufferedImage sprite, List<VoxelData> voxels) {
        int width = sprite.getWidth();
        int height = sprite.getHeight();
        float startX = -(width * SPRITE_SCALE) / 2.0f;
        float startY = -(height * SPRITE_SCALE) / 2.0f;

        List<Integer> colors = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgba = sprite.getRGB(x, y);
                int alpha = (rgba >> 24) & 0xFF;
                if (alpha < 10) continue;

                int red = (rgba >> 16) & 0xFF;
                int green = (rgba >> 8) & 0xFF;
                int blue = rgba & 0xFF;
                int finalAlpha = alpha == 0 ? 255 : alpha;
                int finalRGBA = (finalAlpha << 24) | (red << 16) | (green << 8) | blue;

                float voxelX = startX + (x * SPRITE_SCALE);
                float voxelY = startY + ((height - 1 - y) * SPRITE_SCALE) + VERTICAL_OFFSET;
                float voxelZ = (y * width + x) * 0.0001f;

                voxels.add(new VoxelData(voxelX, voxelY, voxelZ, 0.0f, finalRGBA, x, y));
                colors.add(finalRGBA);
            }
        }
        int[] arr = new int[colors.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = colors.get(i);
        return arr;
    }

    private static long readMtime(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public void close() {
        for (CachedSprite sprite : cache.values()) {
            if (sprite.mesh != null) sprite.mesh.cleanup();
        }
        cache.clear();
        initialized = false;
    }

    private record CachedSprite(VoxelMesh mesh, int[] colors, long mtime) {}
}
