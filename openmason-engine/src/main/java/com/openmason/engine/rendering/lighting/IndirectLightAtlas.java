package com.openmason.engine.rendering.lighting;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;
import static org.lwjgl.opengl.GL33.*;

/** Sixteen local irradiance volumes tiled 4x4 in a small, linearly filtered R16F 3D texture. */
public final class IndirectLightAtlas implements AutoCloseable {
    public static final int SIZE = 23;
    public static final int GRID = 4;
    public static final int CAPACITY = GRID * GRID;
    private static final long BYTES = (long) SIZE * SIZE * SIZE * CAPACITY * 2;
    private final FloatBuffer upload = BufferUtils.createFloatBuffer(SIZE * SIZE * SIZE);
    private int texture;

    public IndirectLightAtlas() {
        int previous = glGetInteger(GL_TEXTURE_BINDING_3D);
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_3D, texture);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_R16F, SIZE * GRID, SIZE * GRID, SIZE,
                0, GL_RED, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_3D, previous);
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.OTHER, BYTES);
    }

    public void upload(int slot, float[] values) {
        if (slot < 0 || slot >= CAPACITY || values.length != SIZE * SIZE * SIZE)
            throw new IllegalArgumentException("Invalid irradiance volume");
        int previous = glGetInteger(GL_TEXTURE_BINDING_3D);
        glBindTexture(GL_TEXTURE_3D, texture);
        upload.clear(); upload.put(values); upload.flip();
        glTexSubImage3D(GL_TEXTURE_3D, 0, (slot % GRID) * SIZE, (slot / GRID) * SIZE, 0,
                SIZE, SIZE, SIZE, GL_RED, GL_FLOAT, upload);
        glBindTexture(GL_TEXTURE_3D, previous);
    }

    public void bind(int unit) {
        int previous = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_3D, texture);
        glActiveTexture(previous);
    }

    @Override public void close() {
        if (texture == 0) return;
        glDeleteTextures(texture);
        texture = 0;
        GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.OTHER, BYTES);
    }
}
