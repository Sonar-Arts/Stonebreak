package com.stonebreak.rendering.gameWorld.clouds;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL12.glTexImage3D;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL30.GL_RED;
import static org.lwjgl.opengl.GL30.GL_RG;
import static org.lwjgl.opengl.GL30.GL_RG16F;
import static org.lwjgl.opengl.GL30.GL_RG8;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;

/**
 * The {@link CloudNoise} volumes as repeating, mipmapped 3D textures (shape RG8, detail R8) and the curl noise as a
 * repeating RG16F 2D texture. Built once per renderer; the noise is independent of the world, so it never changes
 * after that.
 */
final class CloudNoiseTextures {

    /**
     * Texels per side of the shape volume, and the blocks one tile of it spans: 1.5 blocks per texel, billows 24
     * blocks (R) and turrets 48 (G). Kept at 64³ RG8 (512 KiB): a 128³ RG8 volume over 192 blocks (4 MiB) left
     * the GPU's L2 and cost ~1 ms more per frame in a storm view (measured 2026-11-21), for less tiling only.
     */
    static final int SHAPE_SIZE = 64;
    static final float SHAPE_TILE_BLOCKS = 96f;
    /** Texels per side of the detail volume, and the blocks one tile of it spans. */
    static final int DETAIL_SIZE = 32;
    static final float DETAIL_TILE_BLOCKS = 48f;
    /** Texels per side of the curl noise, and the blocks one tile of it spans. */
    static final int CURL_SIZE = 128;
    static final float CURL_TILE_BLOCKS = 384f;   // divides the map period (12288): cloud-space lookups wrap with it

    private final int shape;
    private final int detail;
    private final int curl;

    CloudNoiseTextures() {
        shape = upload(CloudNoise.shape(SHAPE_SIZE), SHAPE_SIZE, GL_RG8, GL_RG);
        detail = upload(CloudNoise.detail(DETAIL_SIZE), DETAIL_SIZE, GL_R8, GL_RED);
        curl = uploadCurl(CloudNoise.curl(CURL_SIZE), CURL_SIZE);
    }

    private static int uploadCurl(float[] texels, int size) {
        java.nio.FloatBuffer buf = BufferUtils.createFloatBuffer(texels.length);
        buf.put(texels).flip();
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG16F, size, size, 0, GL_RG, GL_FLOAT, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glBindTexture(GL_TEXTURE_2D, previous);
        return tex;
    }

    private static int upload(byte[] texels, int size, int internalFormat, int format) {
        ByteBuffer buf = BufferUtils.createByteBuffer(texels.length);
        buf.put(texels).flip();
        int previous = glGetInteger(org.lwjgl.opengl.GL12.GL_TEXTURE_BINDING_3D);
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_3D, tex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage3D(GL_TEXTURE_3D, 0, internalFormat, size, size, size, 0, format, GL_UNSIGNED_BYTE, buf);
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

    int curl() {
        return curl;
    }

    void cleanup() {
        glDeleteTextures(shape);
        glDeleteTextures(detail);
        glDeleteTextures(curl);
    }
}
