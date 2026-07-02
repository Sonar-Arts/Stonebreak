package com.stonebreak.ui.settingsMenu.managers;

import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;
import com.stonebreak.core.Main;

/**
 * Manages the application of settings to various game subsystems.
 * Handles audio, display, and crosshair setting application.
 */
public class SettingsManager {
    
    private final Settings settings;
    
    public SettingsManager(Settings settings) {
        this.settings = settings;
    }
    
    /**
     * Applies audio-related settings to the sound system.
     */
    public void applyAudioSettings() {
        SoundSystem soundSystem = SoundSystem.getInstance();
        if (soundSystem != null) {
            soundSystem.setMasterVolume(settings.getMasterVolume());
            System.out.println("Applied master volume: " + settings.getMasterVolume());
        }
    }
    
    /**
     * Applies display-related settings like window resolution.
     */
    public void applyDisplaySettings() {
        applyWindowResize();
    }
    
    /**
     * Applies crosshair-related settings to the crosshair renderer.
     */
    public void applyCrosshairSettings() {
        Game game = Game.getInstance();
        if (game != null && game.getRenderer() != null && game.getRenderer().getUIRenderer() != null) {
            var crosshairRenderer = game.getRenderer().getUIRenderer().getMCrosshairRenderer();
            if (crosshairRenderer != null) {
                applyCrosshairStyle(crosshairRenderer);
                applyCrosshairProperties(crosshairRenderer);

                System.out.println("Applied crosshair settings: style=" + settings.getCrosshairStyle() +
                                 ", size=" + settings.getCrosshairSize());
            }
        }
    }

    /**
     * Applies the crosshair style to the renderer.
     */
    private void applyCrosshairStyle(com.stonebreak.rendering.UI.components.MCrosshairRenderer crosshairRenderer) {
        try {
            var styleEnum = com.stonebreak.rendering.UI.components.MCrosshairRenderer.CrosshairStyle
                .valueOf(settings.getCrosshairStyle());
            crosshairRenderer.setStyle(styleEnum);
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid crosshair style: " + settings.getCrosshairStyle());
        }
    }

    /**
     * Applies crosshair visual properties to the renderer.
     */
    private void applyCrosshairProperties(com.stonebreak.rendering.UI.components.MCrosshairRenderer crosshairRenderer) {
        crosshairRenderer.setSize(settings.getCrosshairSize());
        crosshairRenderer.setThickness(settings.getCrosshairThickness());
        crosshairRenderer.setGap(settings.getCrosshairGap());
        crosshairRenderer.setOpacity(settings.getCrosshairOpacity());
        crosshairRenderer.setColor(settings.getCrosshairColorR(), settings.getCrosshairColorG(), settings.getCrosshairColorB());
        crosshairRenderer.setOutline(settings.getCrosshairOutline());
    }
    
    /**
     * Pushes render + LOD distance from Settings into the live world config.
     * ChunkManager and FastLodManager both read from that config each tick, so the
     * change takes effect within ~1s without a world restart.
     *
     * <p>Also reports the new render distance to the server (ViewDistanceC2S) —
     * in the two-world model the SERVER decides how many chunks each player is
     * streamed, so without this the local config change only widens the loop
     * over chunks the client will never receive.
     */
    public void applyWorldDistanceSettings() {
        com.stonebreak.world.World world = Game.getWorld();
        if (world == null || world.getConfig() == null) return;
        world.getConfig().setRenderDistance(settings.getRenderDistance());
        world.getConfig().setLodRange(settings.getLodDistance());
        world.getConfig().setLodEnabled(settings.getLodEnabled());

        com.stonebreak.network.client.ClientWorldView client =
                com.stonebreak.network.MultiplayerSession.getClient();
        if (client != null) {
            client.sendViewDistance(settings.getRenderDistance());
        }

        System.out.println("Applied render distance: " + settings.getRenderDistance()
                + ", LOD distance: " + settings.getLodDistance()
                + ", LOD enabled: " + settings.getLodEnabled());
    }

    /**
     * Applies window resolution changes to the actual window.
     */
    private void applyWindowResize() {
        long windowHandle = Main.getWindowHandle();
        if (windowHandle == 0) {
            System.err.println("Warning: Could not apply window resolution - window handle is null");
            return;
        }
        
        int newWidth = settings.getWindowWidth();
        int newHeight = settings.getWindowHeight();

        // Skip the resize entirely when the size is unchanged. Every "Apply"
        // funnels through here, and forcing a redundant glfwSetWindowSize causes
        // a needless compositor round-trip that visibly glitches on Wayland.
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer curW = stack.mallocInt(1);
            java.nio.IntBuffer curH = stack.mallocInt(1);
            org.lwjgl.glfw.GLFW.glfwGetWindowSize(windowHandle, curW, curH);
            if (curW.get(0) == newWidth && curH.get(0) == newHeight) {
                return;
            }
        }

        // Apply window size change
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(windowHandle, newWidth, newHeight);

        // Re-sync render/UI state from the ACTUAL framebuffer size. The
        // compositor may clamp/ignore the requested size (or defer the resize
        // callback), so trusting newWidth/newHeight — which are window
        // coordinates, not framebuffer pixels — would leave the viewport, UI
        // layout, and cursor scale stale. Main owns all of that.
        Main.refreshWindowSize();

        System.out.println("Applied window resolution: " + newWidth + "x" + newHeight);
    }
}