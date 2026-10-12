package com.openmason.engine.ui.masonry.textures;

import com.openmason.engine.format.omt.OMTArchive;
import com.openmason.engine.format.omt.OMTReader;
import com.openmason.engine.format.omt.OmtCompositor;
import com.openmason.engine.format.omt.TextureBytes;
import com.openmason.engine.format.sbt.SBTParser;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Codec;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.types.IRect;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A MasonryUI texture loaded from a Stonebreak Texture (.SBT) file.
 *
 * <p>Loads the SBT, extracts the embedded OMT, decodes each visible layer
 * and composites them bottom-up onto a single {@link Image} the size of the
 * OMT canvas with {@link OmtCompositor} — the engine's one layer rule
 * (straight-alpha source-over, layer opacity, bottom to top), shared with the
 * 3D viewport — so a texture looks the same in the UI preview, the game and
 * every other host (#294). Subsequent draws blit that image — there is no
 * per-frame compositing cost. Sub-images of sprite regions are cut once and
 * owned (closed) by the texture.
 *
 * <p>Use {@link MTextureRegistry} to obtain shared instances rather than
 * constructing these directly.
 */
public final class MTexture implements AutoCloseable {

    private final String resourcePath;
    private final Image image;
    private final int width;
    private final int height;
    private final Map<IRect, Image> regions = new HashMap<>();
    private volatile boolean closed;

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

    /** The composited image; null once the texture is {@link #close closed} (a caller still holding a released texture draws nothing). */
    public Image image() { return closed ? null : image; }
    public boolean isClosed() { return closed; }
    public int width()   { return width; }
    public int height()  { return height; }
    public String resourcePath() { return resourcePath; }

    /**
     * The sub-image {@code (x, y, w, h)}, cut once and cached (sprite regions that tile need their
     * own image so repetition never reaches neighbouring pixels). Owned by this texture: callers
     * borrow it and never close it. Null when the rect is outside the texture.
     */
    public synchronized Image region(int x, int y, int w, int h) {
        if (closed || image == null || w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > width || y + h > height) {
            return null;
        }
        if (x == 0 && y == 0 && w == width && h == height) {
            return image;
        }
        return regions.computeIfAbsent(IRect.makeXYWH(x, y, w, h), image::makeSubset);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        regions.values().forEach(Image::close);
        regions.clear();
        if (image != null) image.close();
    }

    /**
     * Load an SBT through {@code opener} and composite its layers into a single Skija
     * {@link Image}. The opener belongs to the module that owns the resource (JPMS hides game
     * resources from the engine), e.g. {@code path -> Game.class.getResourceAsStream(path)}.
     *
     * @param resourcePath key and path, e.g. {@code "/ui/shared/stonebreak/ui/hud/heart_full.sbt"}
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

    /**
     * Decodes texture bytes of any kind a UI dependency may hold — SBT, OMT or PNG — without
     * logging the formats it rules out. PNGs decode to straight alpha like OMT layers.
     *
     * @return a ready-to-draw texture, or {@code null} when the bytes are none of them
     */
    public static MTexture decode(String cacheKey, byte[] bytes) {
        if (TextureBytes.isPng(bytes)) {
            Image png = decodePngPremul(bytes);
            return png == null ? null : fromImage(cacheKey, png);
        }
        OMTArchive archive = TextureBytes.archive(bytes);
        if (archive == null) {
            System.err.println("[MTexture] " + cacheKey + " is not an SBT, OMT or PNG texture");
            return null;
        }
        return new MTexture(cacheKey, compositeLayers(archive), archive.canvasSize().width(),
                archive.canvasSize().height());
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
     * Composite all visible layers of an OMT archive bottom-up into a single {@link Image} with
     * {@link OmtCompositor}. A layer whose PNG is not canvas-sized (older files, canvases resized
     * without resampling) is scaled to the canvas with nearest sampling, as the Skia path this
     * replaced did, rather than dropped. Nothing visible gives a transparent canvas-sized image, so
     * the texture keeps the size headless checks ({@code TextureSizes}) report.
     */
    private static Image compositeLayers(OMTArchive archive) {
        int cw = archive.canvasSize().width();
        int ch = archive.canvasSize().height();
        OmtCompositor.Composited c = OmtCompositor.composite(archive, png -> fitToCanvas(decodePng(png), cw, ch));
        return c != null ? rgbaImage(c.width(), c.height(), c.rgba())
                : rgbaImage(Math.max(1, cw), Math.max(1, ch), new byte[Math.max(1, cw) * Math.max(1, ch) * 4]);
    }

    /** {@code d} resampled (nearest) to {@code w x h}; unchanged when it already matches. */
    static OmtCompositor.PngDecoder.Decoded fitToCanvas(OmtCompositor.PngDecoder.Decoded d, int w, int h) {
        if (d == null || (d.width() == w && d.height() == h) || w <= 0 || h <= 0) {
            return d;
        }
        byte[] out = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            int sy = (int) ((y + 0.5) * d.height() / h);
            for (int x = 0; x < w; x++) {
                int sx = (int) ((x + 0.5) * d.width() / w);
                System.arraycopy(d.rgba(), (sy * d.width() + sx) * 4, out, (y * w + x) * 4, 4);
            }
        }
        return new OmtCompositor.PngDecoder.Decoded(w, h, out);
    }

    /** Straight-alpha RGBA bytes as an image; Skia premultiplies once, at draw time. */
    public static Image rgbaImage(int w, int h, byte[] rgba) {
        return Image.makeRasterFromBytes(new ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL),
                rgba, w * 4L);
    }

    /**
     * A plain PNG texture as the game's own image loading decodes it (premultiplied by the decoder,
     * no colour management): a document image then draws exactly what a legacy screen's
     * {@code Image.makeFromEncoded} drew, down to the rounding of translucent pixels (#299).
     */
    static Image decodePngPremul(byte[] png) {
        try (Data data = Data.makeFromBytes(png); Codec codec = Codec.makeFromData(data)) {
            int w = codec.getSize().getX();
            int h = codec.getSize().getY();
            ImageInfo info = new ImageInfo(w, h, ColorType.N32, ColorAlphaType.PREMUL);
            try (Bitmap bitmap = new Bitmap()) {
                if (!bitmap.allocPixels(info)) {
                    return null;
                }
                codec.readPixels(bitmap);
                return Image.makeRasterFromBytes(info, bitmap.readPixels(), w * 4L);
            }
        } catch (RuntimeException e) {
            System.err.println("[MTexture] Failed to decode a PNG texture: " + e.getMessage());
            return null;
        }
    }

    /** Decodes a PNG to straight-alpha RGBA without colour management (texture bytes are data). */
    public static OmtCompositor.PngDecoder.Decoded decodePng(byte[] png) {
        if (png == null || png.length == 0) return null;
        try (Data data = Data.makeFromBytes(png); Codec codec = Codec.makeFromData(data)) {
            int w = codec.getSize().getX();
            int h = codec.getSize().getY();
            try (Bitmap bitmap = new Bitmap()) {
                if (!bitmap.allocPixels(new ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))) {
                    return null;
                }
                codec.readPixels(bitmap);
                return new OmtCompositor.PngDecoder.Decoded(w, h, bitmap.readPixels());
            }
        } catch (RuntimeException e) {
            System.err.println("[MTexture] Failed to decode a PNG layer: " + e.getMessage());
            return null;
        }
    }
}
