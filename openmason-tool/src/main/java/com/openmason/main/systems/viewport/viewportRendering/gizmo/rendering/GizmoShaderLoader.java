package com.openmason.main.systems.viewport.viewportRendering.gizmo.rendering;

import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Loads and compiles the gizmo shader program from resource files.
 * Handles vertex and fragment shader compilation, linking, and cleanup.
 */
public final class GizmoShaderLoader {

    private static final Logger logger = LoggerFactory.getLogger(GizmoShaderLoader.class);

    private GizmoShaderLoader() {
        throw new AssertionError("GizmoShaderLoader is a utility class and should not be instantiated");
    }

    /**
     * Loads and compiles the gizmo shader program from resource files.
     *
     * @return Shader program ID, or -1 on failure
     */
    public static int loadGizmoShaders() {
        try {
            String vertexSource = loadShaderSource("/shaders/gizmo.vert");
            int vertexShader = compileShader(vertexSource, GL30.GL_VERTEX_SHADER);
            if (vertexShader < 0) {
                return -1;
            }

            String fragmentSource = loadShaderSource("/shaders/gizmo.frag");
            int fragmentShader = compileShader(fragmentSource, GL30.GL_FRAGMENT_SHADER);
            if (fragmentShader < 0) {
                GL30.glDeleteShader(vertexShader);
                return -1;
            }

            int program = GL30.glCreateProgram();
            GL30.glAttachShader(program, vertexShader);
            GL30.glAttachShader(program, fragmentShader);
            GL30.glLinkProgram(program);

            int linkStatus = GL30.glGetProgrami(program, GL30.GL_LINK_STATUS);
            if (linkStatus == GL30.GL_FALSE) {
                String log = GL30.glGetProgramInfoLog(program);
                logger.error("Gizmo shader link failed: {}", log);
                GL30.glDeleteShader(vertexShader);
                GL30.glDeleteShader(fragmentShader);
                GL30.glDeleteProgram(program);
                return -1;
            }

            GL30.glDeleteShader(vertexShader);
            GL30.glDeleteShader(fragmentShader);

            return program;

        } catch (Exception e) {
            logger.error("Failed to load gizmo shaders: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Loads shader source code from classpath resources.
     *
     * @param path Resource path (e.g. "/shaders/gizmo.vert")
     * @return Shader source string
     * @throws Exception if the resource cannot be found or read
     */
    private static String loadShaderSource(String path) throws Exception {
        InputStream stream = GizmoShaderLoader.class.getResourceAsStream(path);
        if (stream == null) {
            throw new Exception("Shader file not found: " + path);
        }

        StringBuilder source = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                source.append(line).append("\n");
            }
        }

        return source.toString();
    }

    /**
     * Compiles a single shader stage and validates compilation.
     *
     * @param source Shader source code
     * @param type GL_VERTEX_SHADER or GL_FRAGMENT_SHADER
     * @return Shader ID, or -1 on failure
     */
    private static int compileShader(String source, int type) {
        int shader = GL30.glCreateShader(type);
        GL30.glShaderSource(shader, source);
        GL30.glCompileShader(shader);

        int compileStatus = GL30.glGetShaderi(shader, GL30.GL_COMPILE_STATUS);
        if (compileStatus == GL30.GL_FALSE) {
            String log = GL30.glGetShaderInfoLog(shader);
            String typeName = (type == GL30.GL_VERTEX_SHADER) ? "vertex" : "fragment";
            logger.error("Gizmo {} shader compile failed: {}", typeName, log);
            GL30.glDeleteShader(shader);
            return -1;
        }

        return shader;
    }
}
