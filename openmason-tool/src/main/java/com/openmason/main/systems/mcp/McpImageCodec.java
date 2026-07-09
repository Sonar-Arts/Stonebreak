package com.openmason.main.systems.mcp;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import javax.imageio.ImageIO;

/**
 * Shared image scaling/encoding helpers for MCP capture tools.
 *
 * <p>Two scaling directions for two sources: {@link #downscale} for large
 * framebuffer frames (area-averaged, photographic), {@link #upscaleNearest}
 * for tiny pixel-art canvases (hard-edged integer replication so a 16px
 * texture stays crisp instead of turning into a blur).
 */
public final class McpImageCodec {

    private McpImageCodec() {
    }

    /** Scale so the longest side is at most {@code target}, preserving aspect ratio. */
    public static BufferedImage downscale(BufferedImage source, int target) {
        int w = source.getWidth();
        int h = source.getHeight();
        int longest = Math.max(w, h);
        if (longest <= target) {
            return source;
        }
        int outW = Math.max(1, Math.round(w * (float) target / longest));
        int outH = Math.max(1, Math.round(h * (float) target / longest));
        Image scaled = source.getScaledInstance(outW, outH, Image.SCALE_AREA_AVERAGING);
        BufferedImage out = new BufferedImage(outW, outH, source.getType());
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(scaled, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Upscale by the largest whole factor that keeps the longest side at or
     * under {@code target}, replicating pixels (nearest neighbor). Factor 1
     * (already at/over target) returns the source untouched. Output is ARGB
     * so canvas transparency survives.
     */
    public static BufferedImage upscaleNearest(BufferedImage source, int target) {
        int w = source.getWidth();
        int h = source.getHeight();
        int factor = Math.max(1, target / Math.max(w, h));
        if (factor == 1) {
            return source;
        }
        BufferedImage out = new BufferedImage(w * factor, h * factor, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = source.getRGB(x, y);
                for (int dy = 0; dy < factor; dy++) {
                    for (int dx = 0; dx < factor; dx++) {
                        out.setRGB(x * factor + dx, y * factor + dy, argb);
                    }
                }
            }
        }
        return out;
    }

    /** Encode as PNG and return the base64 string MCP image content expects. */
    public static String encodePngBase64(BufferedImage image) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        try {
            if (!ImageIO.write(image, "png", bytes)) {
                throw new IllegalStateException("No PNG writer available");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode capture as PNG", e);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
