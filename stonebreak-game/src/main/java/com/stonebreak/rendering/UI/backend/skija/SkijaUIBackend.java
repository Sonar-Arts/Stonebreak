package com.stonebreak.rendering.UI.backend.skija;

import com.stonebreak.rendering.UI.backend.UIBackend;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
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
    private Image woodPlanksTexture;

    private boolean inFrame;
    private Canvas currentCanvas;

    public void initialize(int width, int height) {
        context.init(width, height);
        loadAssets();
    }

    private void loadAssets() {
        minecraftTypeface = loadTypeface("/fonts/Minecraft.ttf");
        dirtTexture = loadImage("/ui/mainMenu/Dirt.png");
        woodPlanksTexture = loadWoodPlanksFace("/blocks/Textures/wood_planks_custom_texture.png");
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

    // The cube-net PNG is 64x48 with 16x16 faces arranged in a cross.
    // Front face sits at col=1, row=1 → pixel origin (16, 16).
    // We blit just that tile into a fresh 16x16 surface so the repeating
    // shader has no blank regions from the unused corners of the net.
    private Image loadWoodPlanksFace(String resourcePath) {
        Image full = loadImage(resourcePath);
        if (full == null) return null;
        int faceSize = 16;
        int faceX = 16, faceY = 16;
        try (Surface surface = Surface.makeRasterN32Premul(faceSize, faceSize)) {
            Canvas c = surface.getCanvas();
            c.save();
            c.translate(-faceX, -faceY);
            c.drawImage(full, 0, 0);
            c.restore();
            Image face = surface.makeImageSnapshot();
            full.close();
            return face;
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
        if (woodPlanksTexture != null) { woodPlanksTexture.close(); woodPlanksTexture = null; }
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

    public Image getWoodPlanksTexture() { return woodPlanksTexture; }
}
