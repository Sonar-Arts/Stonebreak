package com.stonebreak.rendering.gameWorld.weather;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import com.stonebreak.world.World;
import com.stonebreak.world.weather.ClientWeather;
import com.stonebreak.world.weather.CloudType;
import com.stonebreak.world.weather.LightningScheduler;
import com.stonebreak.world.weather.WeatherSurface;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Lightning the player sees: picks up this frame's strikes from the
 * {@link LightningScheduler} (via {@link ClientWeather}), draws each as a
 * jagged additive ribbon from the storm cloud's base to the ground for a few
 * tenths of a second, and reports the flash that lights up the scene and the
 * flash points that light the cloud right around each bolt.
 */
public final class LightningRenderer {

    /** Strikes farther than this are neither seen nor lit. */
    static final double RANGE = 1500;
    private static final float BOLT_SECONDS = 0.35f;
    private static final float FLASH_DECAY_SECONDS = 0.12f;
    /** Distance at which a strike's flash has halved. */
    private static final float FLASH_HALF_DISTANCE = 250f;
    private static final int SEGMENTS = 16;
    private static final int FLOATS_PER_VERTEX = 4;
    private static final int MAX_BOLTS = 8;
    /** Most flashes the cloud shader lights at once ({@code u_flashes} in clouds_march.frag). */
    public static final int MAX_FLASHES = 4;
    /** How far into the storm cloud, above its base, the flash glows from. */
    private static final float FLASH_DEPTH = 32f;
    /** Ground assumed under a strike whose chunk isn't loaded. */
    private static final int FALLBACK_GROUND = 64;

    private record Bolt(LightningScheduler.Strike strike, float groundY, double distance) {}

    private final List<Bolt> bolts = new ArrayList<>();
    private double lastSeconds = Double.NaN;

    private final ShaderProgram shader;
    private final int vao;
    private final int vbo;
    private final FloatBuffer vertices =
            BufferUtils.createFloatBuffer(MAX_BOLTS * SEGMENTS * 6 * FLOATS_PER_VERTEX);
    private final Vector3f a = new Vector3f();
    private final Vector3f b = new Vector3f();
    private final Vector3f side = new Vector3f();
    private final Vector4f[] flashes = new Vector4f[MAX_FLASHES];

    {
        for (int i = 0; i < MAX_FLASHES; i++) flashes[i] = new Vector4f();
    }

    public LightningRenderer() {
        shader = new ShaderProgram();
        shader.createVertexShader(load("/shaders/weather/bolt.vert"));
        shader.createFragmentShader(load("/shaders/weather/bolt.frag"));
        shader.link();
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) vertices.capacity() * Float.BYTES, GL_STREAM_DRAW);
        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 1, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Lambda (not a method reference) so the caller-sensitive lookup happens in this module. */
    private static String load(String path) {
        return ShaderResourceLoader.load(path, p -> LightningRenderer.class.getResourceAsStream(p));
    }

    /**
     * Picks up new strikes and drops finished bolts.
     *
     * @return this frame's flash in [0, 1]
     */
    public float update(ClientWeather weather, World world, Vector3f cameraWorldPos) {
        if (weather == null) {
            bolts.clear();
            lastSeconds = Double.NaN;
            for (Vector4f f : flashes) f.w = 0f;
            return 0f;
        }
        double now = weather.seconds();
        if (!Double.isNaN(lastSeconds)) {
            for (LightningScheduler.Strike s : weather.strikesBetween(lastSeconds, now,
                    cameraWorldPos.x, cameraWorldPos.z, RANGE)) {
                int ground = world != null
                        ? WeatherSurface.groundY(world, (int) Math.floor(s.x()), (int) Math.floor(s.z()))
                        : WeatherSurface.UNKNOWN;
                double d = Math.hypot(s.x() - cameraWorldPos.x, s.z() - cameraWorldPos.z);
                bolts.add(new Bolt(s, ground == WeatherSurface.UNKNOWN ? FALLBACK_GROUND : ground, d));
                if (bolts.size() > MAX_BOLTS) bolts.removeFirst();
            }
        }
        lastSeconds = now;

        float flash = 0f;
        for (Vector4f f : flashes) f.w = 0f;
        int flashIndex = 0;
        for (Iterator<Bolt> it = bolts.iterator(); it.hasNext(); ) {
            Bolt bolt = it.next();
            float age = (float) (now - bolt.strike().seconds());
            if (age > BOLT_SECONDS * 2f || age < 0f) {
                it.remove();
                continue;
            }
            // Two or three return strokes: a decaying flicker.
            float flicker = 0.65f + 0.35f * (float) Math.cos(age * 70.0);
            float glow = (float) Math.exp(-age / FLASH_DECAY_SECONDS) * flicker;
            flash += glow / (1f + (float) bolt.distance() / FLASH_HALF_DISTANCE);
            // Bolts are in strike order, so the ring keeps the newest flashes.
            flashes[flashIndex++ % MAX_FLASHES].set(
                    (float) bolt.strike().x() - RenderOrigin.x(),
                    CloudType.CUMULONIMBUS.baseY + FLASH_DEPTH,
                    (float) bolt.strike().z() - RenderOrigin.z(),
                    glow);
        }
        return Math.min(flash, 1f);
    }

    /**
     * This frame's flash points for the cloud shader, valid after {@link #update}:
     * xyz = render-space point inside the storm cloud, w = intensity (0 = unused).
     */
    public Vector4f[] flashes() {
        return flashes;
    }

    /**
     * Draws live bolts additively, depth-tested against the scene. Writes depth
     * so the cloud pass drawn next treats bolts as scene: cloud in front of a
     * bolt veils it, cloud behind it does not cover it.
     */
    public void render(Matrix4f projection, Matrix4f view, Vector3f cameraWorldPos) {
        if (bolts.isEmpty() || Double.isNaN(lastSeconds)) return;
        vertices.clear();
        int count = 0;
        for (Bolt bolt : bolts) {
            float age = (float) (lastSeconds - bolt.strike().seconds());
            if (age > BOLT_SECONDS) continue;
            count += buildRibbon(bolt, 1f - age / BOLT_SECONDS, cameraWorldPos);
        }
        if (count == 0) return;
        vertices.flip();

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertices);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        boolean blend = glIsEnabled(GL_BLEND);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        int blendSrc = glGetInteger(GL_BLEND_SRC);
        int blendDst = glGetInteger(GL_BLEND_DST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        glDisable(GL_CULL_FACE);
        glDepthMask(true);

        shader.bind();
        shader.setMat4("uProjection", projection);
        shader.setMat4("uView", view);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, count);
        glBindVertexArray(0);
        shader.unbind();

        glBlendFunc(blendSrc, blendDst);
        if (!blend) glDisable(GL_BLEND);
        if (cull) glEnable(GL_CULL_FACE);
        glDepthMask(depthMask);
    }

    /** Appends one bolt's camera-facing ribbon (render space); returns vertices added. */
    private int buildRibbon(Bolt bolt, float alpha, Vector3f cam) {
        LightningScheduler.Strike s = bolt.strike();
        Random rng = new Random(s.seed());
        float top = CloudType.CUMULONIMBUS.baseY;
        float bottom = Math.min(bolt.groundY(), top - 1f);
        float width = Math.max(0.7f, (float) bolt.distance() * 0.004f);
        float ox = RenderOrigin.x(), oz = RenderOrigin.z();
        float camX = cam.x - ox, camZ = cam.z - oz;

        a.set((float) s.x() - ox, top, (float) s.z() - oz);
        int added = 0;
        for (int i = 1; i <= SEGMENTS; i++) {
            float y = top + (bottom - top) * i / SEGMENTS;
            float jitter = i == SEGMENTS ? 0.5f : 3.5f;
            b.set(a.x + (rng.nextFloat() - 0.5f) * 2f * jitter, y, a.z + (rng.nextFloat() - 0.5f) * 2f * jitter);
            // Ribbon edge: perpendicular to the segment and to the view.
            side.set(b).sub(a).cross(camX - a.x, cam.y - a.y, camZ - a.z);
            if (side.lengthSquared() < 1e-6f) side.set(1f, 0f, 0f);
            side.normalize(width * 0.5f);
            put(a.x - side.x, a.y - side.y, a.z - side.z, alpha);
            put(a.x + side.x, a.y + side.y, a.z + side.z, alpha);
            put(b.x + side.x, b.y + side.y, b.z + side.z, alpha);
            put(a.x - side.x, a.y - side.y, a.z - side.z, alpha);
            put(b.x + side.x, b.y + side.y, b.z + side.z, alpha);
            put(b.x - side.x, b.y - side.y, b.z - side.z, alpha);
            added += 6;
            a.set(b);
        }
        return added;
    }

    private void put(float x, float y, float z, float alpha) {
        vertices.put(x).put(y).put(z).put(alpha);
    }

    public void cleanup() {
        shader.cleanup();
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
    }
}
