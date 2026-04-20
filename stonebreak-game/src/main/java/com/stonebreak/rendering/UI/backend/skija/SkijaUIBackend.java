package com.stonebreak.rendering.UI.backend.skija;

import com.stonebreak.rendering.UI.backend.UIBackend;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Typeface;

import java.io.IOException;
import java.io.InputStream;

/**
 * Skija-backed {@link UIBackend}. Wraps a {@link SkiaContext} and exposes the
 * active {@link Canvas} plus shared assets (typeface, dirt texture) that the
 * Stonebreak menus reuse across frames.
 */
public final class SkijaUIBackend implements UIBackend {

    private final SkiaContext context = new SkiaContext();

    private Typeface minecraftTypeface;
    private Image dirtTexture;

    private boolean inFrame;
    private Canvas currentCanvas;

    public void initialize(int width, int height) {
        context.init(width, height);
        loadAssets();
    }

    private void loadAssets() {
        minecraftTypeface = loadTypeface("/fonts/Minecraft.ttf");
        dirtTexture = loadImage("/ui/mainMenu/Dirt.png");
    }

    private Typeface loadTypeface(String resourcePath) {
        try (InputStream in = SkijaUIBackend.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                System.err.println("[Skija] Missing typeface resource: " + resourcePath);
                return null;
            }
            byte[] bytes = in.readAllBytes();
            return FontMgr.getDefault().makeFromData(Data.makeFromBytes(bytes));
        } catch (IOException e) {
            System.err.println("[Skija] Failed to load typeface: " + e.getMessage());
            return null;
        }
    }

    private Image loadImage(String resourcePath) {
        try (InputStream in = SkijaUIBackend.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                System.err.println("[Skija] Missing image resource: " + resourcePath);
                return null;
            }
            byte[] bytes = in.readAllBytes();
            return Image.makeFromEncoded(bytes);
        } catch (IOException e) {
            System.err.println("[Skija] Failed to load image: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void beginFrame(int width, int height, float pixelRatio) {
        if (!context.isInitialized()) return;
        if (width != context.getWidth() || height != context.getHeight()) {
            context.resize(width, height);
        }
        currentCanvas = context.beginPaint();
        inFrame = true;
    }

    @Override
    public void endFrame() {
        if (!inFrame) return;
        context.endPaint();
        currentCanvas = null;
        inFrame = false;
    }

    @Override
    public void resize(int width, int height) {
        context.resize(width, height);
    }

    @Override
    public void dispose() {
        if (dirtTexture != null) { dirtTexture.close(); dirtTexture = null; }
        if (minecraftTypeface != null) { minecraftTypeface.close(); minecraftTypeface = null; }
        context.dispose();
    }

    @Override
    public boolean isAvailable() {
        return context.isInitialized();
    }

    /**
     * Active canvas for the current frame. Throws if no frame is in progress.
     */
    public Canvas getCanvas() {
        if (!inFrame || currentCanvas == null) {
            throw new IllegalStateException("No active Skija frame");
        }
        return currentCanvas;
    }

    public Typeface getMinecraftTypeface() { return minecraftTypeface; }

    public Image getDirtTexture() { return dirtTexture; }
}
