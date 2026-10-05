package com.stonebreak.rendering.UI.backend.skija;

import com.openmason.engine.ui.rendering.GpuMasonryBackend;
import com.openmason.engine.ui.rendering.UiRenderTarget;
import com.stonebreak.rendering.UI.backend.UIBackend;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Typeface;

import java.io.IOException;
import java.io.InputStream;

/**
 * Stonebreak's Skija {@link UIBackend}: the engine's {@link GpuMasonryBackend} drawing into the
 * game window ({@link UiRenderTarget#gameWindow}, legacy reset-to-baseline GL policy), plus the
 * menu assets the Stonebreak screens reuse across frames (typeface, dirt texture, logo). Game
 * resources are read here because only this module can open them.
 */
/*
 * Not final: headless widget tests substitute a CPU-raster canvas by overriding
 * getCanvas()/isAvailable()/getMinecraftTypeface() without ever calling initialize().
 */
public class SkijaUIBackend extends GpuMasonryBackend implements UIBackend {

    private Image dirtTexture;
    private Image stonebreakLogo;

    public void initialize(int width, int height) {
        initialize(UiRenderTarget.gameWindow(Math.max(1, width), Math.max(1, height), 1f));
        loadAssets();
    }

    private void loadAssets() {
        setTypeface(loadTypeface("/fonts/Minecraft.ttf"));
        dirtTexture = loadImage("/ui/mainMenu/Dirt.png");
        stonebreakLogo = loadImage("/ui/mainMenu/Stonebreak_Logo.png");
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
    public void dispose() {
        if (dirtTexture != null) { dirtTexture.close(); dirtTexture = null; }
        if (stonebreakLogo != null) { stonebreakLogo.close(); stonebreakLogo = null; }
        super.dispose();
    }

    /** Active canvas for the current frame. Throws if no frame is in progress. */
    @Override
    public Canvas getCanvas() {
        return super.getCanvas();
    }

    /** Masonry text uses the game font; test fixtures override {@link #getMinecraftTypeface()}. */
    @Override
    public Typeface typeface() {
        return getMinecraftTypeface();
    }

    public Typeface getMinecraftTypeface() { return super.typeface(); }

    public Image getDirtTexture() { return dirtTexture; }

    public Image getStonebreakLogo() { return stonebreakLogo; }
}
