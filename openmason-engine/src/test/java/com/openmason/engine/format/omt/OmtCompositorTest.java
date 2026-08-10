package com.openmason.engine.format.omt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Layer flattening for {@code .omt} textures.
 *
 * <p>These exist because taking a single layer instead of compositing is what made freshly
 * created models render black: their base layer is empty, and everything the user had
 * actually drawn lived above it.
 *
 * <p>Pure pixel math — the PNG decoder is injected, so no image library and no GL.
 */
class OmtCompositorTest {

    private static final int W = 2;
    private static final int H = 2;

    /** Solid fill of one colour, used as a stand-in for a decoded layer. */
    private static byte[] fill(int r, int g, int b, int a) {
        byte[] px = new byte[W * H * 4];
        for (int i = 0; i < px.length; i += 4) {
            px[i] = (byte) r;
            px[i + 1] = (byte) g;
            px[i + 2] = (byte) b;
            px[i + 3] = (byte) a;
        }
        return px;
    }

    /** Maps a layer's "png bytes" (here a 1-byte tag) to prepared pixels. */
    private static OmtCompositor.PngDecoder decoderOf(java.util.Map<Byte, byte[]> table) {
        return png -> png == null || png.length == 0
                ? null
                : new OmtCompositor.PngDecoder.Decoded(W, H, table.get(png[0]));
    }

    private static OMTArchive archive(List<OMTArchive.Layer> layers) {
        return new OMTArchive(new OMTArchive.CanvasSize(W, H), layers, 0);
    }

    private static OMTArchive.Layer layer(String name, boolean visible, float opacity, byte tag) {
        return new OMTArchive.Layer(name, visible, opacity, new byte[]{tag});
    }

    @Test
    @DisplayName("an opaque upper layer covers the one beneath it")
    void upperLayerCovers() {
        var decoder = decoderOf(java.util.Map.of(
                (byte) 1, fill(255, 0, 0, 255),
                (byte) 2, fill(0, 0, 255, 255)));

        OmtCompositor.Composited out = OmtCompositor.composite(archive(List.of(
                layer("base", true, 1.0f, (byte) 1),
                layer("top", true, 1.0f, (byte) 2))), decoder);

        assertNotNull(out);
        assertEquals(0, out.rgba()[0] & 0xFF, "red channel replaced");
        assertEquals(255, out.rgba()[2] & 0xFF, "blue on top");
        assertEquals(255, out.rgba()[3] & 0xFF);
    }

    @Test
    @DisplayName("content above an EMPTY base layer still shows — the black-model case")
    void contentAboveEmptyBaseSurvives() {
        // A freshly created model: layer 0 is fully transparent, the user's art is above.
        // Taking "the first visible layer" yields transparent pixels, which render black.
        var decoder = decoderOf(java.util.Map.of(
                (byte) 1, fill(0, 0, 0, 0),
                (byte) 2, fill(10, 200, 30, 255)));

        OmtCompositor.Composited out = OmtCompositor.composite(archive(List.of(
                layer("empty base", true, 1.0f, (byte) 1),
                layer("art", true, 1.0f, (byte) 2))), decoder);

        assertNotNull(out);
        assertEquals(10, out.rgba()[0] & 0xFF);
        assertEquals(200, out.rgba()[1] & 0xFF);
        assertEquals(255, out.rgba()[3] & 0xFF, "the result must be opaque, not transparent");
    }

    @Test
    @DisplayName("hidden layers are skipped")
    void hiddenLayersSkipped() {
        var decoder = decoderOf(java.util.Map.of(
                (byte) 1, fill(255, 0, 0, 255),
                (byte) 2, fill(0, 0, 255, 255)));

        OmtCompositor.Composited out = OmtCompositor.composite(archive(List.of(
                layer("base", true, 1.0f, (byte) 1),
                layer("hidden", false, 1.0f, (byte) 2))), decoder);

        assertNotNull(out);
        assertEquals(255, out.rgba()[0] & 0xFF, "the hidden blue layer must not apply");
        assertEquals(0, out.rgba()[2] & 0xFF);
    }

    @Test
    @DisplayName("layer opacity scales its contribution")
    void opacityIsApplied() {
        var decoder = decoderOf(java.util.Map.of(
                (byte) 1, fill(0, 0, 0, 255),
                (byte) 2, fill(255, 255, 255, 255)));

        OmtCompositor.Composited out = OmtCompositor.composite(archive(List.of(
                layer("black", true, 1.0f, (byte) 1),
                layer("white 50%", true, 0.5f, (byte) 2))), decoder);

        assertNotNull(out);
        int red = out.rgba()[0] & 0xFF;
        assertEquals(128, red, 2, "half-opacity white over black lands near mid grey");
    }

    @Test
    @DisplayName("a fully transparent stack composites to nothing visible but is still returned")
    void transparentStackIsOpaqueNowhere() {
        var decoder = decoderOf(java.util.Map.of((byte) 1, fill(0, 0, 0, 0)));

        OmtCompositor.Composited out = OmtCompositor.composite(archive(List.of(
                layer("empty", true, 1.0f, (byte) 1))), decoder);

        assertNotNull(out, "a decodable layer still yields an image");
        assertEquals(0, out.rgba()[3] & 0xFF);
    }

    @Test
    @DisplayName("no visible layers yields null so the caller can fall back")
    void noVisibleLayersYieldsNull() {
        var decoder = decoderOf(java.util.Map.of((byte) 1, fill(255, 0, 0, 255)));

        assertNull(OmtCompositor.composite(archive(List.of(
                layer("hidden", false, 1.0f, (byte) 1))), decoder));
        assertNull(OmtCompositor.composite(null, decoder));
    }

    @Test
    @DisplayName("a layer that cannot be decoded is skipped rather than failing the whole texture")
    void undecodableLayerSkipped() {
        OmtCompositor.PngDecoder decoder = png ->
                png[0] == 1 ? new OmtCompositor.PngDecoder.Decoded(W, H, fill(255, 0, 0, 255)) : null;

        OmtCompositor.Composited out = OmtCompositor.composite(archive(List.of(
                layer("good", true, 1.0f, (byte) 1),
                layer("corrupt", true, 1.0f, (byte) 9))), decoder);

        assertNotNull(out);
        assertEquals(255, out.rgba()[0] & 0xFF);
    }

    @Test
    @DisplayName("a layer whose size does not match the canvas is skipped")
    void mismatchedLayerSkipped() {
        OmtCompositor.PngDecoder decoder = png -> png[0] == 1
                ? new OmtCompositor.PngDecoder.Decoded(W, H, fill(255, 0, 0, 255))
                : new OmtCompositor.PngDecoder.Decoded(W + 1, H, new byte[(W + 1) * H * 4]);

        OmtCompositor.Composited out = OmtCompositor.composite(archive(List.of(
                layer("good", true, 1.0f, (byte) 1),
                layer("wrong size", true, 1.0f, (byte) 2))), decoder);

        assertNotNull(out);
        assertEquals(255, out.rgba()[0] & 0xFF, "the mismatched layer must not corrupt the result");
    }
}
