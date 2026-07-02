package com.openmason.engine.rendering.shadow;

import com.openmason.engine.diagnostics.GpuMemoryTracker;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.glTexImage3D;
import static org.lwjgl.opengl.GL13.GL_CLAMP_TO_BORDER;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_FUNC;
import static org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL30.*;

/**
 * GPU-side resources for cascaded sun shadows: a depth-only framebuffer rendering
 * into one layer of a {@code GL_TEXTURE_2D_ARRAY} depth texture per cascade.
 *
 * <p>The texture uses hardware depth comparison ({@code GL_COMPARE_REF_TO_TEXTURE}
 * + linear filtering), so shaders sample it through {@code sampler2DArrayShadow}
 * and get free 2×2 PCF per tap. Border color is "fully lit" so anything outside
 * a cascade's window never darkens.
 *
 * <p>Usage per frame, per cascade: {@link #beginCascade(int)} → render depth-only
 * scene with the cascade's light matrix → next cascade → {@link #endShadowPass()}.
 * The caller is responsible for restoring the framebuffer binding and viewport it
 * had before the pass. RAII: {@link #close()} releases everything. GL thread only.
 */
public final class CascadedShadowMap implements AutoCloseable {

    private final int resolution;
    private final int cascadeCount;
    private int fbo;
    private int depthTextureArray;
    private long trackedBytes;
    private boolean completenessChecked;

    public CascadedShadowMap(int resolution, int cascadeCount) {
        this.resolution = resolution;
        this.cascadeCount = cascadeCount;
        create();
    }

    private void create() {
        depthTextureArray = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, depthTextureArray);
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_DEPTH_COMPONENT24,
                resolution, resolution, cascadeCount, 0,
                GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
        // Depth 1.0 at the border: comparisons against it always pass → lit.
        glTexParameterfv(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_BORDER_COLOR,
                new float[] {1.0f, 1.0f, 1.0f, 1.0f});
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);

        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        trackedBytes = (long) resolution * resolution * 4L * cascadeCount;
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.SHADOW_MAP, trackedBytes);
    }

    /**
     * Binds the shadow FBO targeting cascade {@code index}'s layer, sets the
     * viewport to the map resolution, and clears depth. Caller renders the
     * depth-only scene next.
     */
    public void beginCascade(int index) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                depthTextureArray, 0, index);
        if (!completenessChecked) {
            completenessChecked = true;
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Shadow framebuffer incomplete (status 0x"
                        + Integer.toHexString(status) + ")");
            }
        }
        glViewport(0, 0, resolution, resolution);
        glClear(GL_DEPTH_BUFFER_BIT);
    }

    /** Unbinds the shadow FBO. The caller restores its own framebuffer and viewport. */
    public void endShadowPass() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** Binds the depth array for receiver-side sampling on the given texture unit. */
    public void bindForSampling(int textureUnit) {
        glActiveTexture(GL_TEXTURE0 + textureUnit);
        glBindTexture(GL_TEXTURE_2D_ARRAY, depthTextureArray);
        glActiveTexture(GL_TEXTURE0);
    }

    public int resolution() {
        return resolution;
    }

    @Override
    public void close() {
        if (fbo != 0) {
            glDeleteFramebuffers(fbo);
            fbo = 0;
        }
        if (depthTextureArray != 0) {
            glDeleteTextures(depthTextureArray);
            depthTextureArray = 0;
        }
        if (trackedBytes > 0) {
            GpuMemoryTracker.getInstance()
                    .untrack(GpuMemoryTracker.Category.SHADOW_MAP, trackedBytes);
            trackedBytes = 0;
        }
    }
}
