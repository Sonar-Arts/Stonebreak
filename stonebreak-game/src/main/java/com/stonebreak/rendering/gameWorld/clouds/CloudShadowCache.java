package com.stonebreak.rendering.gameWorld.clouds;

import com.openmason.engine.rendering.postfx.FullscreenQuad;
import com.openmason.engine.rendering.postfx.RenderTarget;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * The cloud-shadow column cache (2026-11-20). A cloud shadow is a ring of eight taps per lit pixel, and every tap was a
 * full cloud-column lookup (terrain relief, weather, bicubic cloud map, terrain flow): measured ~1 ms of GPU per frame
 * on the LOD rings alone, ~30% of the frame rate under clouds. Once per frame this pass evaluates the same column - the
 * values a tap needs: reach, base and sigma * clip at the cloud layer - on a render-space grid around where nearby
 * pixels' taps land, and the world shader's taps read it with one bilinear fetch ({@code cloud_shadow.glsl}).
 *
 * <p>Only past the detail terrain ({@code near}: the render distance + 64 blocks, at least 192, cross-faded over
 * {@value #BLEND_BLOCKS}): near the ground terrain relief and flow vary the column faster than the grid follows (measured
 * up to ~0.1 of the shadow on near cliffs), and the cost was in the LOD rings anyway. Same picture far out: the per-pixel geometry (sun-ray projection, penumbra ring, Beer-Lambert path through the column) is
 * unchanged; only the column's three values are interpolated over {@value #TEXEL_BLOCKS}-block texels, finer than the
 * cloud map's own texels and well inside the penumbra (radius >= 3 blocks). Taps outside the grid take the exact path.
 * {@code -Dstonebreak.cloudShadowCache=off} disables it; the world shader's {@code -Dstonebreak.debugShade=16} shows
 * |cached - exact| (red, x10) per pixel. GL thread only.
 */
public final class CloudShadowCache {

    static final boolean ENABLED = !"off".equalsIgnoreCase(System.getProperty("stonebreak.cloudShadowCache", "on"));
    /** Texels per side (RGBA16F: 8 MiB at 1024). */
    static final int SIZE = Integer.getInteger("stonebreak.cloudShadowCache.size", 1024);
    /** Blocks per texel: SIZE x TEXEL covers +-2048 blocks around the centre. */
    static final float TEXEL_BLOCKS = 4f;
    /** Texture unit the receivers sample it on (clear of 5-17, see CloudUniforms). */
    public static final int TEXTURE_UNIT = 18;
    /** How far the centre follows the sun-ray projection (low sun: the rest takes the exact path). */
    private static final float MAX_SHIFT = 1024f;

    private ShaderProgram shader;
    private RenderTarget target;
    private FullscreenQuad quad;
    private boolean failed;
    private boolean valid;
    private final Vector2f origin = new Vector2f();
    private final Vector3f eye = new Vector3f();
    private float near;
    /** Cross-fade from the exact shadow to the cache (blocks). */
    static final float BLEND_BLOCKS = 64f;
    /** Exact at least this far (blocks), whatever the render distance. */
    static final float MIN_NEAR_BLOCKS = 192f;
    private final int[] viewport = new int[4];

    /**
     * Rebuilds the cache for this frame. Call after the cloud uniforms were prepared ({@code CloudRenderer.prepareFrame})
     * and before any receiver is set up; {@code renderCam} = the camera in render space. Restores the framebuffer,
     * viewport and the GL state it touches.
     */
    public void build(CloudUniforms uniforms, Vector3f renderCam, float detailRadiusBlocks) {
        valid = false;
        eye.set(renderCam);
        near = Math.max(MIN_NEAR_BLOCKS, detailRadiusBlocks + 64f);
        if (!ENABLED || failed || !uniforms.shadowsActive()) {
            return;
        }
        Vector3f sun = uniforms.sunDirection();
        if (sun.y < 0.05f) {
            return;   // the receivers skip cloud shadows below this sun height anyway
        }
        if (shader == null && !create()) {
            return;
        }
        // Centre: where the camera's own sun ray crosses the cloud layer (nearby pixels' taps land around it).
        float rise = Math.max(CloudUniforms.shadowY() - renderCam.y, 0f);
        float sx = sun.x * rise / sun.y, sz = sun.z * rise / sun.y;
        float len = (float) Math.sqrt(sx * sx + sz * sz);
        if (len > MAX_SHIFT) {
            sx *= MAX_SHIFT / len;
            sz *= MAX_SHIFT / len;
        }
        float half = SIZE * TEXEL_BLOCKS * 0.5f;
        origin.set((float) Math.floor((renderCam.x + sx - half) / TEXEL_BLOCKS) * TEXEL_BLOCKS,
                (float) Math.floor((renderCam.z + sz - half) / TEXEL_BLOCKS) * TEXEL_BLOCKS);

        int previousFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend = GL11.glIsEnabled(GL11.GL_BLEND),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int program = GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);
        try {
            target.bind();
            shader.bind();
            uniforms.applyTo(shader);
            shader.setVec2("u_cacheOrigin", origin);
            shader.setFloat("u_cacheTexel", TEXEL_BLOCKS);
            shader.setBool("u_cloudCacheEnabled", false);   // the builder itself evaluates the exact column
            quad.draw();
            valid = true;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFbo);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
            if (blend) GL11.glEnable(GL11.GL_BLEND);
            if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glDepthMask(depthMask);
            org.lwjgl.opengl.GL20.glUseProgram(program);
        }
    }

    /** Binds the cache (when built this frame) for a BOUND receiver shader; otherwise turns its use off. */
    public void applyTo(ShaderProgram receiver) {
        receiver.setBool("u_cloudCacheEnabled", valid);
        if (!valid) {
            return;
        }
        int previous = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.getColorTexture());
        GL13.glActiveTexture(previous);
        receiver.setInt("u_cloudShadowCache", TEXTURE_UNIT);
        receiver.setVec2("u_cloudCacheOrigin", origin);
        receiver.setFloat("u_cloudCacheInvSpan", 1f / (SIZE * TEXEL_BLOCKS));
        receiver.setFloat("u_cloudCacheHalfTexel", 0.5f / SIZE);
        receiver.setVec3("u_cloudCacheEye", eye);
        receiver.setFloat("u_cloudCacheNear", near);
        receiver.setFloat("u_cloudCacheBlend", BLEND_BLOCKS);
    }

    private boolean create() {
        try {
            shader = new ShaderProgram();
            shader.createVertexShader(load("/shaders/postfx/fullscreen.vert"));
            shader.createFragmentShader(load("/shaders/clouds/cloud_shadow_cache.frag"));
            shader.link();
            target = new RenderTarget(SIZE, SIZE, false, GL30.GL_RGBA16F);
            quad = new FullscreenQuad();
            com.openmason.engine.diagnostics.GpuMemoryTracker.getInstance().track(
                com.openmason.engine.diagnostics.GpuMemoryTracker.Category.OTHER, (long) SIZE * SIZE * 8);
            return true;
        } catch (RuntimeException e) {
            failed = true;
            org.slf4j.LoggerFactory.getLogger(CloudShadowCache.class)
                .error("Cloud shadow cache unavailable; cloud shadows take the exact path", e);
            return false;
        }
    }

    /** Lambda (not a method reference) so the caller-sensitive lookup happens in this module. */
    private static String load(String path) {
        return ShaderResourceLoader.load(path, p -> CloudShadowCache.class.getResourceAsStream(p));
    }

    public void cleanup() {
        if (shader != null) shader.cleanup();
        if (target != null) {
            target.cleanup();
            com.openmason.engine.diagnostics.GpuMemoryTracker.getInstance().untrack(
                com.openmason.engine.diagnostics.GpuMemoryTracker.Category.OTHER, (long) SIZE * SIZE * 8);
        }
        if (quad != null) quad.cleanup();
        shader = null;
        target = null;
        quad = null;
        valid = false;
    }
}
