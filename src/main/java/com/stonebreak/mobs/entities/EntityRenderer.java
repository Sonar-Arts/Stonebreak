package com.stonebreak.mobs.entities;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import com.stonebreak.ShaderProgram;

import java.nio.FloatBuffer;

import static org.lwjgl.system.MemoryUtil.*;

/**
 * Simple barebones entity renderer for 3D entities.
 * Renders entities as colored cubes for now.
 */
public class EntityRenderer {
    private ShaderProgram shader;
    private int cubeVAO;
    private int cubeVBO;
    private boolean initialized = false;
    
    public void initialize() {
        if (initialized) return;
        
        createShader();
        createCubeMesh();
        initialized = true;
    }
    
    private void createShader() {
        String vertexShader = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            
            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;
            
            void main() {
                gl_Position = projection * view * model * vec4(aPos, 1.0);
            }
            """;
        
        String fragmentShader = """
            #version 330 core
            out vec4 FragColor;
            
            uniform vec3 color;
            
            void main() {
                FragColor = vec4(color, 1.0);
            }
            """;
        
        try {
            shader = new ShaderProgram();
            shader.createVertexShader(vertexShader);
            shader.createFragmentShader(fragmentShader);
            shader.link();
            
            shader.createUniform("model");
            shader.createUniform("view");
            shader.createUniform("projection");
            shader.createUniform("color");
        } catch (Exception e) {
            System.err.println("Failed to create entity shader: " + e.getMessage());
        }
    }
    
    private void createCubeMesh() {
        float[] vertices = {
            // Front face
            -0.5f, -0.5f,  0.5f,
             0.5f, -0.5f,  0.5f,
             0.5f,  0.5f,  0.5f,
             0.5f,  0.5f,  0.5f,
            -0.5f,  0.5f,  0.5f,
            -0.5f, -0.5f,  0.5f,
            
            // Back face
            -0.5f, -0.5f, -0.5f,
             0.5f, -0.5f, -0.5f,
             0.5f,  0.5f, -0.5f,
             0.5f,  0.5f, -0.5f,
            -0.5f,  0.5f, -0.5f,
            -0.5f, -0.5f, -0.5f,
            
            // Left face
            -0.5f,  0.5f,  0.5f,
            -0.5f,  0.5f, -0.5f,
            -0.5f, -0.5f, -0.5f,
            -0.5f, -0.5f, -0.5f,
            -0.5f, -0.5f,  0.5f,
            -0.5f,  0.5f,  0.5f,
            
            // Right face
             0.5f,  0.5f,  0.5f,
             0.5f,  0.5f, -0.5f,
             0.5f, -0.5f, -0.5f,
             0.5f, -0.5f, -0.5f,
             0.5f, -0.5f,  0.5f,
             0.5f,  0.5f,  0.5f,
            
            // Bottom face
            -0.5f, -0.5f, -0.5f,
             0.5f, -0.5f, -0.5f,
             0.5f, -0.5f,  0.5f,
             0.5f, -0.5f,  0.5f,
            -0.5f, -0.5f,  0.5f,
            -0.5f, -0.5f, -0.5f,
            
            // Top face
            -0.5f,  0.5f, -0.5f,
             0.5f,  0.5f, -0.5f,
             0.5f,  0.5f,  0.5f,
             0.5f,  0.5f,  0.5f,
            -0.5f,  0.5f,  0.5f,
            -0.5f,  0.5f, -0.5f
        };
        
        cubeVAO = GL30.glGenVertexArrays();
        cubeVBO = GL15.glGenBuffers();
        
        GL30.glBindVertexArray(cubeVAO);
        
        FloatBuffer vertexBuffer = memAllocFloat(vertices.length);
        vertexBuffer.put(vertices).flip();
        
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cubeVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);
        
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        
        GL30.glBindVertexArray(0);
        memFree(vertexBuffer);
    }
    
    public void renderEntity(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        if (!initialized || !entity.isAlive()) return;
        
        shader.bind();
        
        // Entity position represents feet level, so offset upward by half height for rendering
        Vector3f renderPosition = new Vector3f(entity.getPosition());
        renderPosition.y += entity.getHeight() / 2.0f;
        
        Matrix4f modelMatrix = new Matrix4f()
            .translate(renderPosition)
            .rotateY((float) Math.toRadians(entity.getRotation().y))
            .scale(entity.getScale());
        
        shader.setUniform("model", modelMatrix);
        shader.setUniform("view", viewMatrix);
        shader.setUniform("projection", projectionMatrix);
        
        Vector3f color = getEntityColor(entity);
        shader.setUniform("color", color);
        
        GL30.glBindVertexArray(cubeVAO);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 36);
        GL30.glBindVertexArray(0);
        
        shader.unbind();
    }
    
    private Vector3f getEntityColor(Entity entity) {
        switch (entity.getType()) {
            case COW:
                return new Vector3f(0.55f, 0.39f, 0.24f); // Brown
            default:
                return new Vector3f(1.0f, 1.0f, 1.0f); // White
        }
    }
    
    public void cleanup() {
        if (initialized) {
            if (shader != null) {
                shader.cleanup();
            }
            GL30.glDeleteVertexArrays(cubeVAO);
            GL15.glDeleteBuffers(cubeVBO);
        }
    }
}