package com.stonebreak.rendering.gameWorld.clouds;

import com.stonebreak.world.weather.CloudField;
import org.lwjgl.BufferUtils;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * The GPU copy of the {@link CloudField}'s raw channels around the camera: a
 * {@link #SIZE}² RGBA8 texture addressed toroidally in cloud space (texel =
 * cell mod SIZE, wrap mode REPEAT). As the camera and wind move, only the
 * newly exposed strips are computed and uploaded; weather changes never touch
 * it, because the bias is applied on the GPU.
 */
final class CloudMap {

    /** Cells per side: {@code SIZE * CloudField.CELL} blocks of sky. */
    static final int SIZE = 1024;
    /** Blocks one wrap of the map spans; offsets are reduced modulo this. */
    static final int PERIOD_BLOCKS = SIZE * CloudField.CELL;

    private final int texture;
    private IntBuffer scratch = BufferUtils.createIntBuffer(SIZE * 64);

    private CloudField field;
    private int windowX;
    private int windowZ;

    CloudMap() {
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (IntBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Brings the map up to date for a camera over cloud-space cell
     * ({@code centerX}, {@code centerZ}) of {@code field}. A new field (another
     * world) refills everything. Leaves the map bound on the active unit.
     */
    void update(CloudField field, int centerX, int centerZ) {
        int newX = centerX - SIZE / 2;
        int newZ = centerZ - SIZE / 2;
        glBindTexture(GL_TEXTURE_2D, texture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        if (field != this.field) {
            this.field = field;
            fill(new CloudMapWindow.Rect(newX, newZ, SIZE, SIZE));
        } else if (newX != windowX || newZ != windowZ) {
            for (CloudMapWindow.Rect r : CloudMapWindow.exposed(windowX, windowZ, newX, newZ, SIZE)) {
                fill(r);
            }
        }
        windowX = newX;
        windowZ = newZ;
    }

    private void fill(CloudMapWindow.Rect cells) {
        for (CloudMapWindow.Rect[] piece : CloudMapWindow.wrap(cells, SIZE)) {
            CloudMapWindow.Rect src = piece[0];
            CloudMapWindow.Rect dst = piece[1];
            if (scratch.capacity() < src.area()) {
                scratch = BufferUtils.createIntBuffer(src.area());
            }
            scratch.clear();
            for (int z = 0; z < src.h(); z++) {
                for (int x = 0; x < src.w(); x++) {
                    scratch.put(field.raw(src.x() + x, src.z() + z));
                }
            }
            scratch.flip();
            glTexSubImage2D(GL_TEXTURE_2D, 0, dst.x(), dst.z(), dst.w(), dst.h(),
                    GL_RGBA, GL_UNSIGNED_BYTE, scratch);
        }
    }

    int texture() {
        return texture;
    }

    void cleanup() {
        glDeleteTextures(texture);
    }
}
