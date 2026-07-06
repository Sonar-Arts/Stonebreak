package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.omt.OMTArchive;
import com.openmason.engine.format.omt.OMTReader;
import com.openmason.engine.format.sbo.SBOParser;
import io.github.humbleui.skija.Image;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Lazy per-objectId icon cache for SBO ingredients. Each icon is the "primary"
 * texture PNG of the object's embedded OMO model (first face-0 material, then
 * any material, then the default OMT's first visible layer — the same
 * resolution order the Project Browser thumbnails use), served in two forms:
 *
 * <ul>
 *   <li>{@link #glIcon(String)} — a square GL texture for plain ImGui widgets
 *       ({@code ImGui.image}/{@code imageButton}); nearest-filtered so pixel
 *       art stays crisp, alpha preserved so the widget background shows.</li>
 *   <li>{@link #skijaIcon(String)} — a Skija {@link Image} for MortarUI parts
 *       painted onto a Skija canvas.</li>
 * </ul>
 *
 * <p>Failures are cached too, so a missing/broken SBO costs one parse, not one
 * per frame. All methods must run on the GL thread. {@link #clear()} frees GL
 * textures and Skija images; call it from the owning window's dispose path.</p>
 */
public final class SBOIngredientIcons {

    private static final Logger logger = LoggerFactory.getLogger(SBOIngredientIcons.class);

    /** GL icon edge in pixels — big enough for 2x-scaled 16px block art. */
    private static final int GL_ICON_PX = 32;

    /** objectId -> primary PNG bytes; contains null for known failures. */
    private static final Map<String, byte[]> pngCache = new HashMap<>();
    /** objectId -> GL texture id; contains 0 for known failures. */
    private static final Map<String, Integer> glCache = new HashMap<>();
    /** objectId -> decoded Skija image; absent when unavailable. */
    private static final Map<String, Image> skijaCache = new HashMap<>();

    private SBOIngredientIcons() {}

    /** GL texture id for the object's icon, or 0 when unavailable. */
    public static int glIcon(String objectId) {
        if (objectId == null || objectId.isEmpty()) return 0;
        Integer cached = glCache.get(objectId);
        if (cached != null) return cached;
        byte[] png = primaryPng(objectId);
        int id = png != null ? uploadNearest(png, GL_ICON_PX) : 0;
        glCache.put(objectId, id);
        return id;
    }

    /** Skija image for the object's icon, or null when unavailable. */
    public static Image skijaIcon(String objectId) {
        if (objectId == null || objectId.isEmpty()) return null;
        if (skijaCache.containsKey(objectId)) return skijaCache.get(objectId);
        byte[] png = primaryPng(objectId);
        Image image = null;
        if (png != null) {
            try {
                image = Image.makeDeferredFromEncodedBytes(png);
            } catch (Exception e) {
                logger.debug("Failed to decode Skija icon for {}: {}", objectId, e.getMessage());
            }
        }
        skijaCache.put(objectId, image);
        return image;
    }

    /** Free every cached GL texture and Skija image. GL thread only. */
    public static void clear() {
        for (Integer id : glCache.values()) {
            if (id != null && id > 0) GL11.glDeleteTextures(id);
        }
        glCache.clear();
        for (Image image : skijaCache.values()) {
            if (image != null) image.close();
        }
        skijaCache.clear();
        pngCache.clear();
    }

    // ---- PNG extraction ----------------------------------------------------

    private static byte[] primaryPng(String objectId) {
        if (pngCache.containsKey(objectId)) return pngCache.get(objectId);
        byte[] png = null;
        SBOObjectIndex.Entry entry = SBOObjectIndex.find(objectId);
        if (entry != null && entry.sourcePath() != null) {
            png = extractPrimaryPng(entry.sourcePath());
        }
        pngCache.put(objectId, png);
        return png;
    }

    private static byte[] extractPrimaryPng(Path sboPath) {
        if (!Files.exists(sboPath)) return null;
        try {
            SBOParser.RawParse raw = new SBOParser().parseRaw(sboPath);
            byte[] omoBytes = raw.defaultBytes();
            if (omoBytes == null || omoBytes.length == 0) return null;
            try (InputStream in = new ByteArrayInputStream(omoBytes)) {
                return pickPrimaryPng(new OMOReader().read(in));
            }
        } catch (Exception e) {
            logger.debug("Failed to extract icon PNG from {}: {}", sboPath, e.getMessage());
            return null;
        }
    }

    /**
     * The most representative PNG of an OMO: the material face 0 maps to,
     * then any material PNG, then the default OMT's first visible layer.
     * (Mirrors the Project Browser's ModelThumbnailRenderer resolution.)
     */
    private static byte[] pickPrimaryPng(OMOReader.ReadResult result) throws Exception {
        if (result.materials() != null && !result.materials().isEmpty()) {
            int preferredMaterialId = -1;
            if (result.faceMappings() != null) {
                for (var mapping : result.faceMappings()) {
                    if (mapping.faceId() == 0) {
                        preferredMaterialId = mapping.materialId();
                        break;
                    }
                }
            }
            if (preferredMaterialId >= 0) {
                for (ParsedMaterialData m : result.materials()) {
                    if (m.materialId() == preferredMaterialId && m.texturePng() != null) {
                        return m.texturePng();
                    }
                }
            }
            for (ParsedMaterialData m : result.materials()) {
                if (m.texturePng() != null && m.texturePng().length > 0) {
                    return m.texturePng();
                }
            }
        }
        if (result.defaultTextureBytes() != null && result.defaultTextureBytes().length > 0) {
            OMTArchive archive = new OMTReader().read(result.defaultTextureBytes());
            for (OMTArchive.Layer layer : archive.layers()) {
                if (layer.visible() && layer.pngBytes() != null && layer.pngBytes().length > 0) {
                    return layer.pngBytes();
                }
            }
            return archive.layers().isEmpty() ? null : archive.layers().get(0).pngBytes();
        }
        return null;
    }

    // ---- GL upload ---------------------------------------------------------

    /**
     * Decode PNG bytes, scale to {@code size} with nearest-neighbour, upload as
     * an RGBA GL texture with alpha preserved (no checker backdrop — icons sit
     * on the widget's own background).
     */
    private static int uploadNearest(byte[] png, int size) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(png));
            if (source == null) return 0;

            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setComposite(AlphaComposite.Src);
            g.drawImage(source, 0, 0, size, size, null);
            g.dispose();

            ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int argb = scaled.getRGB(x, y);
                    pixels.put((byte) ((argb >> 16) & 0xFF));
                    pixels.put((byte) ((argb >> 8) & 0xFF));
                    pixels.put((byte) (argb & 0xFF));
                    pixels.put((byte) ((argb >> 24) & 0xFF));
                }
            }
            pixels.flip();

            int textureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, size, size, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            return textureId;
        } catch (Exception e) {
            logger.debug("Failed to upload ingredient icon: {}", e.getMessage());
            return 0;
        }
    }
}
