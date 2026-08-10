package com.openmason.engine.rendering.viewer.scene;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;

/**
 * The GL half of {@link TextureUploader}: decodes a PNG with STB and uploads it.
 *
 * <p>Filtering is NEAREST, matching how the game and the model editor sample block and
 * model textures — these are pixel art, and interpolating them would blur the very thing
 * the user is authoring.
 */
public final class PngTextureUploader implements TextureUploader {

    private static final Logger logger = LoggerFactory.getLogger(PngTextureUploader.class);

    @Override
    public com.openmason.engine.format.omt.OmtCompositor.PngDecoder.Decoded decode(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) {
            return null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer encoded = BufferUtils.createByteBuffer(pngBytes.length);
            encoded.put(pngBytes).flip();

            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            STBImage.stbi_set_flip_vertically_on_load(false);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, w, h, channels, 4);
            if (pixels == null) {
                logger.error("STB failed to decode PNG: {}", STBImage.stbi_failure_reason());
                return null;
            }
            byte[] rgba = new byte[w.get(0) * h.get(0) * 4];
            pixels.get(rgba);
            STBImage.stbi_image_free(pixels);
            return new com.openmason.engine.format.omt.OmtCompositor.PngDecoder.Decoded(
                    w.get(0), h.get(0), rgba);
        } catch (Exception e) {
            logger.error("Error decoding PNG", e);
            return null;
        }
    }

    @Override
    public int uploadRgba(int width, int height, byte[] rgba) {
        if (width <= 0 || height <= 0 || rgba == null || rgba.length < width * height * 4) {
            return 0;
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(rgba.length);
        pixels.put(rgba).flip();

        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        return textureId;
    }

    @Override
    public int upload(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) {
            return 0;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer encoded = BufferUtils.createByteBuffer(pngBytes.length);
            encoded.put(pngBytes).flip();

            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            STBImage.stbi_set_flip_vertically_on_load(false);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, width, height, channels, 4);
            if (pixels == null) {
                logger.error("STB failed to decode PNG: {}", STBImage.stbi_failure_reason());
                return 0;
            }

            int textureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, textureId);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width.get(0), height.get(0), 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glGenerateMipmap(GL_TEXTURE_2D);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

            STBImage.stbi_image_free(pixels);
            glBindTexture(GL_TEXTURE_2D, 0);
            return textureId;

        } catch (Exception e) {
            logger.error("Error uploading PNG texture", e);
            return 0;
        }
    }
}
