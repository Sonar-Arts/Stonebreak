package com.stonebreak.rendering.gameWorld.clouds;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL12.glTexImage3D;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL30.GL_RED;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;

/**
 * The {@link CloudNoise} volumes as repeating, mipmapped R8 3D textures. Built
 * once per renderer; the noise is independent of the world, so it never
 * changes after that.
 */
final class CloudNoiseTextures {

    /** Texels per side of the shape volume, and the blocks one tile of it spans. */
    static final int SHAPE_SIZE = 64;
    static final float SHAPE_TILE_BLOCKS = 96f;
    /** Texels per side of the detail volume, and the blocks one tile of it spans. */
    static final int DETAIL_SIZE = 32;
    static final float DETAIL_TILE_BLOCKS = 48f;

    private final int shape;
    private final int detail;

    CloudNoiseTextures() {
        shape = upload(CloudNoise.shape(SHAPE_SIZE), SHAPE_SIZE);
        detail = upload(CloudNoise.detail(DETAIL_SIZE), DETAIL_SIZE);
    }

    private static int upload(byte[] texels, int size) {
        ByteBuffer buf = BufferUtils.createByteBuffer(texels.length);
        buf.put(texels).flip();
        int previous = glGetInteger(org.lwjgl.opengl.GL12.GL_TEXTURE_BINDING_3D);
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_3D, tex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, size, size, size, 0, GL_RED, GL_UNSIGNED_BYTE, buf);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glGenerateMipmap(GL_TEXTURE_3D);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_REPEAT);
        glBindTexture(GL_TEXTURE_3D, previous);
        return tex;
    }

    int shape() {
        return shape;
    }

    int detail() {
        return detail;
    }

    void cleanup() {
        glDeleteTextures(shape);
        glDeleteTextures(detail);
    }
}
