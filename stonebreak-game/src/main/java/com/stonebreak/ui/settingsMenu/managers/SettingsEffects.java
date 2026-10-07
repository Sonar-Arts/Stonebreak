package com.stonebreak.ui.settingsMenu.managers;

import com.stonebreak.core.Game;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import org.joml.Vector3f;

/**
 * The settings whose change takes effect immediately, before Apply, and what "immediately"
 * does for each. Shared by the legacy settings menu and the {@code stonebreak:settings}
 * UI-document contract (#289), so a migrated settings screen behaves exactly like the old one.
 * Each method assumes the value is already stored in {@link com.stonebreak.config.Settings};
 * persistence still waits for Apply.
 */
public final class SettingsEffects {

    private SettingsEffects() {
    }

    /** Music is audibly playing while the slider moves. */
    public static void musicVolume(float volume) {
        com.stonebreak.audio.MusicManager musicManager = Game.getMusicManager();
        if (musicManager != null) {
            musicManager.setVolume(volume);
        }
    }

    public static void musicEnabled(boolean enabled) {
        com.stonebreak.audio.MusicManager musicManager = Game.getMusicManager();
        if (musicManager != null) {
            musicManager.setEnabled(enabled);
        }
    }

    /** Pushes the LOD toggle to the live world config. */
    public static void lodEnabled(boolean enabled) {
        com.stonebreak.world.World world = Game.getWorld();
        if (world != null && world.getConfig() != null) {
            world.getConfig().setLodEnabled(enabled);
        }
    }

    /** Pushes the LOD preset to the live world config; the ring re-levels node by node. */
    public static void lodQuality(String quality) {
        com.stonebreak.world.World world = Game.getWorld();
        if (world != null && world.getConfig() != null) {
            world.getConfig().setLodQuality(com.stonebreak.world.fastlod.FastLodQuality.parse(quality));
        }
    }

    /** The frame limiter picks VSync up next frame; this also tells GLFW. */
    public static void vsync() {
        com.stonebreak.core.Main.applyVsyncSetting();
    }

    /** Leaf transparency changes face culling and render layers: refresh definitions, remesh. */
    public static void leafTransparency() {
        try {
            CBRResourceManager.refreshLeafDefinitions();
        } catch (Exception e) {
            System.err.println("Failed to refresh leaf definitions: " + e.getMessage());
        }
        rebuildLoadedChunks("leaf transparency");
    }

    /** Smooth lighting is baked into meshes: remesh so the change is visible at once. */
    public static void smoothLighting() {
        rebuildLoadedChunks("smooth lighting");
    }

    private static void rebuildLoadedChunks(String why) {
        try {
            if (Game.getWorld() != null && Game.getPlayer() != null) {
                Vector3f playerPos = Game.getPlayer().getPosition();
                int playerChunkX = (int) Math.floor(playerPos.x / 16);
                int playerChunkZ = (int) Math.floor(playerPos.z / 16);
                Game.getWorld().rebuildAllLoadedChunks(playerChunkX, playerChunkZ);
            }
        } catch (Exception e) {
            System.err.println("Failed to rebuild chunks after " + why + " change: " + e.getMessage());
        }
    }
}
