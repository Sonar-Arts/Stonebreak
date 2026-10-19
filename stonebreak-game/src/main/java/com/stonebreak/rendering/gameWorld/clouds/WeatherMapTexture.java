package com.stonebreak.rendering.gameWorld.clouds;

import com.stonebreak.world.weather.WeatherMap;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_RG;
import static org.lwjgl.opengl.GL30.GL_RG16F;

/**
 * The GPU copy of a {@link WeatherMap}: RG = coverage, wetness bias, in the
 * map's own toroidal layout (GL_REPEAT, bilinear — the filter
 * {@link WeatherMap#sample} reproduces). Re-uploaded whole when the map refreshes.
 */
final class WeatherMapTexture {

    private final int texture;
    private final FloatBuffer data = BufferUtils.createFloatBuffer(WeatherMap.SIZE * WeatherMap.SIZE * 2);
    private WeatherMap uploaded;
    private int uploadedVersion;

    WeatherMapTexture() {
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG16F, WeatherMap.SIZE, WeatherMap.SIZE, 0, GL_RG, GL_FLOAT, (FloatBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /** Uploads {@code map} if it changed since the last upload. Leaves the active unit's 2D binding changed. */
    void update(WeatherMap map) {
        if (map == uploaded && map.version() == uploadedVersion) return;
        uploaded = map;
        uploadedVersion = map.version();
        float[] coverage = map.coverage(), wetness = map.wetness();
        data.clear();
        for (int i = 0; i < coverage.length; i++) data.put(coverage[i]).put(wetness[i]);
        data.flip();
        glBindTexture(GL_TEXTURE_2D, texture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, WeatherMap.SIZE, WeatherMap.SIZE, GL_RG, GL_FLOAT, data);
    }

    int texture() {
        return texture;
    }

    void cleanup() {
        glDeleteTextures(texture);
    }
}
