package com.stonebreak.rendering.gameWorld.clouds;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.rendering.postfx.FullscreenQuad;
import com.openmason.engine.rendering.postfx.RenderTarget;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * The cloud site cache (2026-11-21). Every cloud sample (each view step, each of its sun steps) used to resolve its
 * column from scratch: terrain relief, local weather, the bicubic cloud map and the terrain flow, 7 to 11 texture
 * reads, about 80% of a sample's reads, for values that do not change with height. Once per frame this pass resolves
 * the column ({@code cloudSiteAt}) on two render-space grids around the camera, and the march reads a site with one
 * bilinear tap per texture ({@code cloudSiteCached} in cloud_shape.glsl):
 * <ul>
 *   <li>near: {@value #SIZE}^2 texels of {@value #NEAR_TEXEL} blocks (+-2048 blocks), finer than a half-resolution
 *       pixel's footprint out there;</li>
 *   <li>far: {@value #SIZE}^2 texels of {@value #FAR_TEXEL} blocks (+-6144, past the clouds' fade distance), the
 *       cloud map's own cell size.</li>
 * </ul>
 * Both live side by side in RGBA16F textures ({@value #SIZE} x 2 wide): A = bottom, top, density, wetness;
 * B = terrain-flow shift, anvil mass, anvil mass * top; C = anvil amount, anvil top, curl xz. The noise that carves
 * the body is still read per sample, at full detail, so the clouds keep every billow; only the smooth column is
 * interpolated.
 *
 * <p>B is mipmapped: its mips blur the storms' mass, which a second pass ({@code cloud_site_cache_c.frag}) reads smoothly into C's anvils together with the column's curl noise, so a
 * sample reads its anvil and curl with one tap (measured: per-sample anvil taps cost 0.85 ms and the per-sample
 * curl fetch 0.33 ms in a storm view from above, more than the cache saved). (Empty-space skipping over A's density
 * mips was tried and removed: the per-sample occupancy check cost more than its jumps saved - the march already
 * strides through clear air - 8-15% slower in fair weather.)</p>
 *
 * <p>The near grid is rebuilt every frame, the far one every {@value #FAR_EVERY} frames or when its origin moves:
 * between, it is at most a few frames stale - the wind moves the clouds well under a block in that time.</p>
 *
 * <p>Grids are in the unleaned field (the cloud at the shear pivot); a sample at height y reads the cache at its
 * leaned position. Origins snap to {@value #SNAP} texels so the grid (and its mip cells) never crawl with the
 * camera. {@code -Dstonebreak.cloudSiteCache=off} takes the exact path everywhere (no anvils then: they are built
 * from the cache). GL thread only.</p>
 */
final class CloudSiteCache {

    static final boolean ENABLED = !"off".equalsIgnoreCase(System.getProperty("stonebreak.cloudSiteCache", "on"));
    /** Texels per grid side. */
    static final int SIZE = 1024;
    static final float NEAR_TEXEL = 4f;
    static final float FAR_TEXEL = 12f;
    /** Origins snap to this many texels (the coarsest mip the anvils read, so its cells never crawl). */
    static final int SNAP = 16;
    /** Mip levels kept on B: the anvils read 4 (near) and 2 (far). */
    private static final int MAX_LEVEL = 4;
    /** Frames between far-grid rebuilds while its origin holds. */
    static final int FAR_EVERY = 4;
    private static final long BYTES = (long) SIZE * 2 * SIZE * 8 * (1 + 4 / 3 + 1);
    private int frame;
    private boolean farBuilt;
    private float farX = Float.NaN, farZ = Float.NaN;

    private ShaderProgram shader;
    private ShaderProgram shaderC;
    private RenderTarget target;
    private RenderTarget targetC;
    private FullscreenQuad quad;
    private boolean failed;
    private boolean valid;
    private final Vector4f origin = new Vector4f();
    private final Vector2f span = new Vector2f(SIZE * NEAR_TEXEL, SIZE * FAR_TEXEL);
    private final int[] viewport = new int[4];

    /**
     * Rebuilds the cache around {@code renderCam} (render space). Call after the cloud uniforms were prepared and
     * before the march. Restores the framebuffers, viewport, program, texture bindings and the GL state it touches.
     */
    void build(CloudUniforms uniforms, Vector3f renderCam) {
        valid = false;
        if (!ENABLED || failed) return;
        if (shader == null && !create()) return;
        float fx = snap(renderCam.x, FAR_TEXEL), fz = snap(renderCam.z, FAR_TEXEL);
        boolean far = !farBuilt || fx != farX || fz != farZ || frame % FAR_EVERY == 0;
        frame++;
        origin.set(snap(renderCam.x, NEAR_TEXEL), snap(renderCam.z, NEAR_TEXEL), fx, fz);
        // Near grid = the left half of the textures; the far one, when due, the right half too.
        int width = far ? SIZE * 2 : SIZE;

        int previousFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend = GL11.glIsEnabled(GL11.GL_BLEND),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);
        try {
            target.bind();
            GL11.glViewport(0, 0, width, SIZE);
            shader.bind();
            uniforms.applyTo(shader);
            uniforms.applyShapeTo(shader);
            setGrid(shader);
            quad.draw();
            // B's mips: the anvils' blur.
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + CloudUniforms.SITE_CACHE_B_TEXTURE_UNIT);
            int prevB = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.getExtraColorTexture());
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            // Pass 2: anvils (from B's mips) and curl into C.
            targetC.bind();
            GL11.glViewport(0, 0, width, SIZE);
            shaderC.bind();
            uniforms.applyTo(shaderC);
            uniforms.applyShapeTo(shaderC);
            setGrid(shaderC);
            quad.draw();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevB);
            if (far) {
                farBuilt = true;
                farX = fx;
                farZ = fz;
            }
            valid = true;
        } finally {
            GL13.glActiveTexture(previousUnit);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFbo);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
            if (blend) GL11.glEnable(GL11.GL_BLEND);
            if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glDepthMask(depthMask);
            GL20.glUseProgram(program);
        }
    }

    /** Binds the cache (when built this frame) for a BOUND march shader; otherwise turns its use off. */
    void applyTo(ShaderProgram receiver) {
        receiver.setBool("u_siteCacheOn", valid);
        if (!valid) return;
        int previous = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + CloudUniforms.SITE_CACHE_A_TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.getColorTexture());
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + CloudUniforms.SITE_CACHE_B_TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.getExtraColorTexture());
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + CloudUniforms.SITE_CACHE_C_TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, targetC.getColorTexture());
        GL13.glActiveTexture(previous);
        setGrid(receiver);
    }

    /** Unbinds the cache's textures from their units (after the march), so no later pass samples a stale cache. */
    void unbind() {
        int previous = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + CloudUniforms.SITE_CACHE_A_TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + CloudUniforms.SITE_CACHE_B_TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + CloudUniforms.SITE_CACHE_C_TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(previous);
    }

    private void setGrid(ShaderProgram s) {
        s.setVec4("u_siteCacheOrigin", origin);
        s.setVec2("u_siteCacheSpan", span);
        s.setFloat("u_siteCacheTexels", SIZE);
    }

    /** The corner of a grid of {@code texel}-block texels centred on {@code v}, snapped to {@value #SNAP} texels. */
    static float snap(float v, float texel) {
        float cell = SNAP * texel;
        return (float) Math.floor((v - SIZE * texel * 0.5f) / cell) * cell;
    }

    private boolean create() {
        try {
            shader = new ShaderProgram();
            shader.createVertexShader(load("/shaders/postfx/fullscreen.vert"));
            shader.createFragmentShader(load("/shaders/clouds/cloud_site_cache.frag"));
            shader.link();
            shaderC = new ShaderProgram();
            shaderC.createVertexShader(load("/shaders/postfx/fullscreen.vert"));
            shaderC.createFragmentShader(load("/shaders/clouds/cloud_site_cache_c.frag"));
            shaderC.link();
            target = new RenderTarget(SIZE * 2, SIZE, false, GL30.GL_RGBA16F, GL30.GL_RGBA16F);
            targetC = new RenderTarget(SIZE * 2, SIZE, false, GL30.GL_RGBA16F);
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            // A and C: read bilinearly. B: level 0 bilinearly, its mips per level (textureLod n).
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.getExtraColorTexture());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, MAX_LEVEL);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            quad = new FullscreenQuad();
            GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.OTHER, BYTES);
            return true;
        } catch (RuntimeException e) {
            failed = true;
            org.slf4j.LoggerFactory.getLogger(CloudSiteCache.class)
                .error("Cloud site cache unavailable; clouds take the exact path", e);
            return false;
        }
    }

    /** Lambda (not a method reference) so the caller-sensitive lookup happens in this module. */
    private static String load(String path) {
        return ShaderResourceLoader.load(path, p -> CloudSiteCache.class.getResourceAsStream(p));
    }

    void cleanup() {
        if (shader != null) shader.cleanup();
        if (shaderC != null) shaderC.cleanup();
        if (targetC != null) targetC.cleanup();
        if (target != null) {
            target.cleanup();
            GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.OTHER, BYTES);
        }
        if (quad != null) quad.cleanup();
        shader = null;
        shaderC = null;
        target = null;
        targetC = null;
        farBuilt = false;
        quad = null;
        valid = false;
    }
}
