package com.stonebreak.rendering.core;

import org.joml.Matrix4f;

public class RenderingConfigurationManager {
    private int windowWidth;
    private int windowHeight;
    private final Matrix4f projectionMatrix;
    
    public RenderingConfigurationManager(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
        this.projectionMatrix = new Matrix4f();
        updateProjectionMatrix();
    }
    
    public void updateWindowSize(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
        updateProjectionMatrix();
    }
    
    private void updateProjectionMatrix() {
        float aspectRatio = (float) windowWidth / windowHeight;
        projectionMatrix.setPerspective((float) Math.toRadians(70.0f), aspectRatio, 0.1f, 1000.0f);
    }
    
    public int getWindowWidth() {
        return windowWidth;
    }
    
    public int getWindowHeight() {
        return windowHeight;
    }
    
    public Matrix4f getProjectionMatrix() {
        return projectionMatrix;
    }
}