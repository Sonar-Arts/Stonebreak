package com.openmason.engine.ui.fidelity;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A captured frame for fidelity checks (#296): unpremultiplied ARGB, row-major, origin top-left,
 * one int per framebuffer pixel. Baselines are stored as PNG, which keeps every bit of it.
 */
public record FidelityImage(int width, int height, int[] argb) {

    public FidelityImage {
        Objects.requireNonNull(argb, "argb");
        if (width <= 0 || height <= 0 || argb.length != width * height) {
            throw new IllegalArgumentException("pixels do not match " + width + "x" + height);
        }
    }

    public int pixel(int x, int y) {
        return argb[y * width + x];
    }

    public static FidelityImage read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in, file.toString());
        }
    }

    public static FidelityImage read(InputStream in, String name) throws IOException {
        BufferedImage img = ImageIO.read(in);
        if (img == null) {
            throw new IOException("not an image: " + name);
        }
        int w = img.getWidth();
        int h = img.getHeight();
        return new FidelityImage(w, h, img.getRGB(0, 0, w, h, null, 0, w));
    }

    public void write(Path file) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, width, height, argb, 0, width);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!ImageIO.write(img, "png", file.toFile())) {
                throw new IOException("no PNG writer");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
