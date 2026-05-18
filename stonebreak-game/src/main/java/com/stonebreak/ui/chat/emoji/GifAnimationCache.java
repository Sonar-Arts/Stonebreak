package com.stonebreak.ui.chat.emoji;

import io.github.humbleui.skija.Image;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class GifAnimationCache {

    private static final Map<GifEmojiType, List<GifFrame>> CACHE = new EnumMap<>(GifEmojiType.class);

    private GifAnimationCache() {}

    public static Image getCurrentFrame(GifEmojiType type) {
        List<GifFrame> frames = getFrames(type);
        if (frames.isEmpty()) return null;
        if (frames.size() == 1) return frames.get(0).image();

        long totalMs = 0;
        for (GifFrame f : frames) totalMs += f.durationMs();
        if (totalMs <= 0) return frames.get(0).image();

        long elapsed = System.currentTimeMillis() % totalMs;
        long acc = 0;
        for (GifFrame f : frames) {
            acc += f.durationMs();
            if (elapsed < acc) return f.image();
        }
        return frames.get(frames.size() - 1).image();
    }

    private static List<GifFrame> getFrames(GifEmojiType type) {
        return CACHE.computeIfAbsent(type, GifAnimationCache::loadFrames);
    }

    private static List<GifFrame> loadFrames(GifEmojiType type) {
        try (InputStream in = GifAnimationCache.class.getResourceAsStream(type.resourcePath)) {
            if (in == null) {
                System.err.println("[GifAnimationCache] Missing resource: " + type.resourcePath);
                return Collections.emptyList();
            }
            byte[] bytes = in.readAllBytes();

            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            try (ImageInputStream iis = ImageIO.createImageInputStream(
                    new java.io.ByteArrayInputStream(bytes))) {
                reader.setInput(iis);
                int frameCount = reader.getNumImages(true);
                List<GifFrame> frames = new ArrayList<>(frameCount);

                // Composite canvas to handle partial-frame GIFs correctly.
                BufferedImage canvas = null;

                for (int i = 0; i < frameCount; i++) {
                    BufferedImage raw = reader.read(i);
                    IIOMetadata meta = reader.getImageMetadata(i);

                    int delayMs = extractDelay(meta);
                    if (delayMs <= 0) delayMs = 100;

                    // Composite onto a persistent canvas so partial frames render correctly.
                    if (canvas == null) {
                        canvas = new BufferedImage(raw.getWidth(), raw.getHeight(),
                                BufferedImage.TYPE_INT_ARGB);
                    }
                    java.awt.Graphics2D g = canvas.createGraphics();
                    g.drawImage(raw, 0, 0, null);
                    g.dispose();

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(canvas, "PNG", baos);
                    Image skijaImage = Image.makeFromEncoded(baos.toByteArray());
                    frames.add(new GifFrame(skijaImage, delayMs));
                }
                return frames;
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            System.err.println("[GifAnimationCache] Failed to load " + type.resourcePath + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static int extractDelay(IIOMetadata meta) {
        if (meta == null) return 0;
        try {
            String[] formatNames = meta.getMetadataFormatNames();
            for (String name : formatNames) {
                if (!name.contains("gif") && !name.contains("GIF")) continue;
                Node root = meta.getAsTree(name);
                int delay = searchForDelay(root);
                if (delay >= 0) return delay;
            }
            // Fall back to native format if gif-specific format not found.
            String nativeName = meta.getNativeMetadataFormatName();
            if (nativeName != null) {
                Node root = meta.getAsTree(nativeName);
                return searchForDelay(root);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static int searchForDelay(Node node) {
        if (node == null) return -1;
        if ("graphicControlExtension".equals(node.getNodeName())) {
            Node delayAttr = node.getAttributes().getNamedItem("delayTime");
            if (delayAttr != null) {
                try {
                    // GIF delay is in centiseconds; multiply by 10 for ms.
                    return Integer.parseInt(delayAttr.getNodeValue()) * 10;
                } catch (NumberFormatException ignored) {}
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            int result = searchForDelay(children.item(i));
            if (result >= 0) return result;
        }
        return -1;
    }
}
