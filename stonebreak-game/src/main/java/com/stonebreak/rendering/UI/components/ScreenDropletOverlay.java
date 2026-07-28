package com.stonebreak.rendering.UI.components;

import com.stonebreak.player.Player;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Renders a couple of droplets clinging to the screen when the player splashes into water,
 * sliding down slightly and fading out. Mirrors the legacy-GL fullscreen approach of
 * {@link UnderwaterOverlayRenderer}.
 */
public class ScreenDropletOverlay {

    private static final int MAX_DROPLETS = 8;
    private static final float FALL_SPEED = 0.06f; // fraction of screen height per second
    private static final float DROPLET_RED = 0.85f;
    private static final float DROPLET_GREEN = 0.93f;
    private static final float DROPLET_BLUE = 1.0f;

    private static class Droplet {
        float x, y; // fraction of screen [0,1]
        float size; // point size in pixels
        float lifetime;
        float initialLifetime;
    }

    private final List<Droplet> droplets = new ArrayList<>();
    private final Random random = new Random();

    /**
     * Spawns a couple of droplets scattered across the screen. Call once on the frame the
     * player enters water.
     */
    public void update(Player player, float deltaTime) {
        if (player != null && player.justEnteredWaterThisFrame()) {
            spawn();
        }

        droplets.removeIf(d -> {
            d.lifetime -= deltaTime;
            d.y += FALL_SPEED * deltaTime;
            return d.lifetime <= 0f;
        });
    }

    private void spawn() {
        int count = Math.min(2 + random.nextInt(3), MAX_DROPLETS - droplets.size()); // 2-4
        for (int i = 0; i < count; i++) {
            Droplet d = new Droplet();
            d.x = 0.15f + random.nextFloat() * 0.7f;
            d.y = 0.1f + random.nextFloat() * 0.55f;
            d.size = 10.0f + random.nextFloat() * 18.0f;
            d.initialLifetime = 0.8f + random.nextFloat() * 0.6f;
            d.lifetime = d.initialLifetime;
            droplets.add(d);
        }
    }

    /**
     * Renders the droplets if any are visible. Call BEFORE UI rendering, like the underwater
     * overlay.
     */
    public void render(int windowWidth, int windowHeight) {
        if (droplets.isEmpty()) return;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, windowWidth, windowHeight, 0, -1, 1);

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_POINT_SMOOTH); // soft round points instead of hard squares

        for (Droplet d : droplets) {
            float opacity = Math.max(0f, d.lifetime / d.initialLifetime);
            GL11.glPointSize(d.size);
            GL11.glColor4f(DROPLET_RED, DROPLET_GREEN, DROPLET_BLUE, opacity * 0.65f);
            GL11.glBegin(GL11.GL_POINTS);
            GL11.glVertex2f(d.x * windowWidth, d.y * windowHeight);
            GL11.glEnd();
        }

        GL11.glDisable(GL11.GL_POINT_SMOOTH);
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    public boolean isVisible() { return !droplets.isEmpty(); }
}
