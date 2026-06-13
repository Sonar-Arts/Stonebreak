package com.openmason.main.systems.menus.textureCreator.icons;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.svg.SVGDOM;
import io.github.humbleui.skija.svg.SVGLength;
import io.github.humbleui.skija.svg.SVGSVG;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Skija-side tool icon store: parses the tool SVGs into {@link SVGDOM} once
 * and paints them as true vectors — crisp and antialiased at any display
 * size, with no Batik rasterization step. Shares the tool-to-SVG mapping with
 * {@link TextureToolIconManager} (which still rasterizes for plain ImGui
 * image buttons, e.g. the shape-variant popup).
 *
 * Sizing note: Skia's {@code setContainerSize} only applies to SVGs with
 * relative (percentage) dimensions; our icons declare fixed width/height, so
 * scaling is done explicitly via a canvas transform from the intrinsic size.
 *
 * Must be used and closed on the GL thread alongside the owning Skija panel.
 */
public final class SkijaToolIconStore implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SkijaToolIconStore.class);

    private static final float DEFAULT_INTRINSIC_SIZE = 32f;

    /** Parsed SVG plus its cached intrinsic edge length. */
    private record IconEntry(SVGDOM dom, float intrinsicSize) {
    }

    private final Map<String, IconEntry> icons = new HashMap<>();

    /**
     * Paint the icon for a tool key at (x, y), scaled to exactly fit the
     * given edge length and therefore centered within any slot the caller
     * positions it in. Unknown or failed icons paint nothing and return
     * false so the caller can fall back to a text label.
     */
    public boolean paint(Canvas canvas, String toolKey, float x, float y, float size) {
        // Explicit containsKey so failed loads cache as null instead of
        // retrying the resource lookup every frame
        if (!icons.containsKey(toolKey)) {
            icons.put(toolKey, load(toolKey));
        }
        IconEntry entry = icons.get(toolKey);
        if (entry == null) {
            return false;
        }

        float scale = size / entry.intrinsicSize();
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(scale, scale);
        entry.dom().render(canvas);
        canvas.restore();
        return true;
    }

    private static IconEntry load(String toolKey) {
        String resourcePath = TextureToolIconManager.getSvgResourcePath(toolKey);
        if (resourcePath == null) {
            logger.warn("No SVG mapping for tool key '{}'", toolKey);
            return null;
        }
        try (InputStream in = SkijaToolIconStore.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                logger.error("SVG resource not found: {}", resourcePath);
                return null;
            }
            byte[] bytes = in.readAllBytes();
            try (Data data = Data.makeFromBytes(bytes)) {
                SVGDOM dom = new SVGDOM(data);
                return new IconEntry(dom, intrinsicSize(dom));
            }
        } catch (Exception e) {
            logger.error("Failed to load SVG for tool key '{}'", toolKey, e);
            return null;
        }
    }

    /** Intrinsic edge length from the root width attribute, with fallback. */
    private static float intrinsicSize(SVGDOM dom) {
        try {
            SVGSVG root = dom.getRoot();
            if (root != null) {
                SVGLength width = root.getWidth();
                if (width != null && width._value > 0) {
                    return width._value;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not read intrinsic SVG size, using {}", DEFAULT_INTRINSIC_SIZE, e);
        }
        return DEFAULT_INTRINSIC_SIZE;
    }

    @Override
    public void close() {
        for (IconEntry entry : icons.values()) {
            if (entry != null && entry.dom() != null) {
                entry.dom().close();
            }
        }
        icons.clear();
    }
}
