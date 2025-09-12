package com.stonebreak.rendering.core;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM;

public class OpenGLErrorHandler {
    
    public static void checkGLError(String context) {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            String errorString = getErrorString(error);
            
            try {
                int[] currentProgram = new int[1];
                glGetIntegerv(GL_CURRENT_PROGRAM, currentProgram);
                int[] activeTexture = new int[1];
                glGetIntegerv(GL_ACTIVE_TEXTURE, activeTexture);
                int[] boundTexture = new int[1];
                glGetIntegerv(GL_TEXTURE_BINDING_2D, boundTexture);
                int[] viewport = new int[4];
                glGetIntegerv(GL_VIEWPORT, viewport);
                
                System.err.println("OPENGL ERROR: " + errorString + " (0x" + Integer.toHexString(error) + ") at: " + context);
                System.err.println("Time: " + java.time.LocalDateTime.now());
                System.err.println("Thread: " + Thread.currentThread().getName());
                System.err.println("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB used");
                System.err.println("GL State - Program: " + currentProgram[0] + ", Active Texture: " + (activeTexture[0] - GL_TEXTURE0) + ", Bound Texture: " + boundTexture[0]);
                System.err.println("Viewport: " + viewport[0] + "," + viewport[1] + "," + viewport[2] + "," + viewport[3]);
                
                logToFile(errorString, error, context, currentProgram[0], activeTexture[0], boundTexture[0], viewport);
                
            } catch (Exception stateEx) {
                System.err.println("OPENGL ERROR: " + errorString + " (0x" + Integer.toHexString(error) + ") at: " + context);
                System.err.println("Failed to get additional GL state: " + stateEx.getMessage());
            }
            
            if (error == 0x0505) { // GL_OUT_OF_MEMORY
                throw new RuntimeException("OpenGL OUT OF MEMORY error at: " + context);
            }
        }
    }
    
    private static String getErrorString(int error) {
        return switch (error) {
            case 0x0500 -> "GL_INVALID_ENUM";
            case 0x0501 -> "GL_INVALID_VALUE";
            case 0x0502 -> "GL_INVALID_OPERATION";
            case 0x0503 -> "GL_STACK_OVERFLOW";
            case 0x0504 -> "GL_STACK_UNDERFLOW";
            case 0x0505 -> "GL_OUT_OF_MEMORY";
            case 0x0506 -> "GL_INVALID_FRAMEBUFFER_OPERATION";
            default -> "UNKNOWN_ERROR_" + Integer.toHexString(error);
        };
    }
    
    private static void logToFile(String errorString, int error, String context, 
                                 int currentProgram, int activeTexture, int boundTexture, int[] viewport) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter("opengl_errors.txt", true);
            fw.write("=== OpenGL ERROR " + java.time.LocalDateTime.now() + " ===\n");
            fw.write("Error: " + errorString + " (0x" + Integer.toHexString(error) + ")\n");
            fw.write("Context: " + context + "\n");
            fw.write("Thread: " + Thread.currentThread().getName() + "\n");
            fw.write("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB\n");
            fw.write("GL State - Program: " + currentProgram + ", Active Texture: " + (activeTexture - GL_TEXTURE0) + ", Bound Texture: " + boundTexture + "\n");
            fw.write("Viewport: " + viewport[0] + "," + viewport[1] + "," + viewport[2] + "," + viewport[3] + "\n\n");
            fw.close();
        } catch (Exception logEx) {
            System.err.println("Failed to write OpenGL error log: " + logEx.getMessage());
        }
    }
}