package com.openmason.engine.rendering.shaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads GLSL shader source files from the classpath.
 *
 * <p>Shared by renderers that keep their shaders as resources (e.g. {@code /shaders/sky/sky.vert});
 * new renderers should use this instead of duplicating the read-resource boilerplate.</p>
 */
public final class ShaderResourceLoader {

    private ShaderResourceLoader() {
    }

    /**
     * Reads a shader source file from the classpath.
     *
     * @param resourcePath absolute classpath location, e.g. {@code /shaders/postfx/fullscreen.vert}
     * @return the shader source as a UTF-8 string
     * @throws UncheckedIOException if the resource is missing or unreadable
     */
    public static String load(String resourcePath) {
        try (InputStream inputStream = ShaderResourceLoader.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Shader resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load shader resource: " + resourcePath, e);
        }
    }
}
