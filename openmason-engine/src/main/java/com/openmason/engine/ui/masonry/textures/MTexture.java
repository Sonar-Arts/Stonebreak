package com.openmason.engine.ui.masonry.textures;

import com.openmason.engine.format.omt.OMTArchive;
import com.openmason.engine.format.omt.OMTReader;
import com.openmason.engine.format.sbt.SBTParser;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * A MasonryUI texture loaded from a Stonebreak Texture (.SBT) file.
 *
 * <p>Loads the SBT, extracts the embedded OMT, decodes each visible layer
 * with Skija, and composites them bottom-up onto a single {@link Image}
 * the size of the OMT canvas. Subsequent draws blit that image — there is
 * no per-frame compositing cost.
 *
 * <p>Use {@link MTextureRegistry} to obtain shared instances rather than
 * constructing these directly.
 */
public final class MTexture implements AutoCloseable {

    private final String resourcePath;
    private final Image image;
    private final int width;
    private final int height;

    private MTexture(String resourcePath, Image image, int width, int height) {
        this.resourcePath = resourcePath;
        this.image = image;
        this.width = width;
        this.height = height;
    }

    /** Wraps an already-decoded image (PNG dependencies, procedural textures); the texture owns it. */
    public static MTexture fromImage(String key, Image image) {
        return image == null ? null : new MTexture(key, image, image.getWidth(), image.getHeight());
    }

    public Image image() { return image; }
    public int width()   { return width; }
    public int height()  { return height; }
    public String resourcePath() { return resourcePath; }

    @Override
    public void close() {
        if (image != null) image.close();
    }

    /**
     * Load an SBT through {@code opener} and composite its layers into a single Skija
     * {@link Image}. The opener belongs to the module that owns the resource (JPMS hides game
     * resources from the engine), e.g. {@code path -> Game.class.getResourceAsStream(path)}.
     *
     * @param resourcePath key and path, e.g. {@code "/ui/HUD/Health Icon/SB_Full_Health_Icon.sbt"}
     * @return a ready-to-draw texture, or {@code null} if loading failed
     */
    public static MTexture loadFromResource(String resourcePath, ResourceOpener opener) {
        if (resourcePath == null || resourcePath.isBlank() || opener == null) return null;

        byte[] sbtBytes;
        try (InputStream in = opener.open(resourcePath)) {
            if (in == null) {
                System.err.println("[MTexture] Missing SBT resource: " + resourcePath);
                return null;
            }
            sbtBytes = in.readAllBytes();
        } catch (IOException e) {
            System.err.println("[MTexture] Failed to read SBT resource " + resourcePath
                    + ": " + e.getMessage());
            return null;
        }
        return loadFromSbtBytes(resourcePath, sbtBytes);
    }

    /**
     * Decode SBT bytes (a Stonebreak Texture wrapping an OMT) and composite its layers.
     *
     * @param cacheKey identifier for diagnostics and cache lookup only
     * @return a ready-to-draw texture, or {@code null} if decoding failed
     */
    public static MTexture loadFromSbtBytes(String cacheKey, byte[] sbtBytes) {
        if (sbtBytes == null || sbtBytes.length == 0) return null;
        try {
            SBTParser.Result sbt = new SBTParser().read(sbtBytes);
            OMTArchive archive = new OMTReader().read(sbt.omtBytes());
            Image composited = compositeLayers(archive);
            if (composited == null) {
                System.err.println("[MTexture] Compositing produced no image: " + cacheKey);
                return null;
            }
            return new MTexture(cacheKey, composited,
                    archive.canvasSize().width(), archive.canvasSize().height());
        } catch (IOException e) {
            System.err.println("[MTexture] Failed to parse SBT " + cacheKey
                    + ": " + e.getMessage());
            return null;
        }
    }

    /** Opens a resource stream; {@code null} when it does not exist. */
    @FunctionalInterface
    public interface ResourceOpener {
        InputStream open(String path) throws IOException;
    }

    /**
     * Build an MTexture from raw OMT bytes (e.g. unwrapped from a texture-only
     * SBO). Used by SBO-backed item icons. The {@code cacheKey} is the
     * synthetic identifier used for cache lookup and debugging only — there is
     * no on-disk resource at that path.
     *
     * @return a ready-to-draw texture, or {@code null} if decoding failed
     */
    public static MTexture loadFromOmtBytes(String cacheKey, byte[] omtBytes) {
        if (omtBytes == null || omtBytes.length == 0) return null;
        try {
            OMTArchive archive = new OMTReader().read(omtBytes);
            Image composited = compositeLayers(archive);
            if (composited == null) {
                System.err.println("[MTexture] Compositing produced no image for: " + cacheKey);
                return null;
            }
            return new MTexture(cacheKey, composited,
                    archive.canvasSize().width(), archive.canvasSize().height());
        } catch (IOException e) {
            System.err.println("[MTexture] Failed to decode OMT bytes for " + cacheKey
                    + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Composite all visible layers of an OMT archive bottom-up into a single
     * {@link Image}. Returns {@code null} if no layers contributed pixels.
     */
    private static Image compositeLayers(OMTArchive archive) {
        int w = archive.canvasSize().width();
        int h = archive.canvasSize().height();

        ImageInfo info = ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL);
        try (Surface surface = Surface.makeRaster(info)) {
            boolean drewAnything = false;
            List<OMTArchive.Layer> layers = archive.layers();
            for (OMTArchive.Layer layer : layers) {
                if (!layer.visible() || layer.opacity() <= 0f) continue;
                Image layerImage = Image.makeFromEncoded(layer.pngBytes());
                if (layerImage == null) continue;
                try (Paint paint = new Paint()) {
                    paint.setAlphaf(layer.opacity());
                    Rect dst = Rect.makeWH(w, h);
                    Rect src = Rect.makeWH(layerImage.getWidth(), layerImage.getHeight());
                    surface.getCanvas().drawImageRect(layerImage, src, dst, paint);
                } finally {
                    layerImage.close();
                }
                drewAnything = true;
            }
            return drewAnything ? surface.makeImageSnapshot() : null;
        }
    }
}
