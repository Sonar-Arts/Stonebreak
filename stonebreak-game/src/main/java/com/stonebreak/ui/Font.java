package com.stonebreak.ui;

import com.stonebreak.rendering.ShaderProgram;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTBakedChar;
// import org.lwjgl.stb.STBTruetype; // Removed unused import
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
// import org.joml.Vector4f; // Removed unused import
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.stb.STBTruetype.*;

public class Font {    private final int fontTextureId;
    private final STBTTBakedChar.Buffer cdata;
    private final float fontSize;
    private final int bitmapWidth = 512;
    private final int bitmapHeight = 512;
    
    // VAO and VBO for text rendering
    private int textVao = 0;
    private int textVbo = 0;

    public Font(String fontPath, float size) {
        this.fontSize = size;
        this.cdata = STBTTBakedChar.malloc(96); // Bake 96 characters (ASCII 32-127)

        ByteBuffer ttfBuffer;
        try {
            ttfBuffer = ioResourceToByteBuffer(fontPath, 160 * 1024);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load font: " + fontPath, e);
        }

        ByteBuffer bitmap = MemoryUtil.memAlloc(bitmapWidth * bitmapHeight);
        stbtt_BakeFontBitmap(ttfBuffer, fontSize, bitmap, bitmapWidth, bitmapHeight, 32, cdata);
        MemoryUtil.memFree(ttfBuffer);


        fontTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, fontTextureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, bitmapWidth, bitmapHeight, 0, GL_ALPHA, GL_UNSIGNED_BYTE, bitmap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glBindTexture(GL_TEXTURE_2D, 0);

        MemoryUtil.memFree(bitmap);
    }    // VAO and VBO fields are declared at the class level

    public void drawText(float x, float y, String text, ShaderProgram shaderProgram, org.joml.Vector4f textColor) {
        if (text == null || text.isEmpty()) {
            return;
        }

        // Use texture unit 0 for font texture
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, fontTextureId);

        // Ensure shader is set for text rendering
        // This is typically done by the Renderer before calling this method
        shaderProgram.setUniform("u_color", textColor);
        
        // Initialize VAO and VBO on first use if not yet created
        if (textVao == 0) {
            textVao = org.lwjgl.opengl.GL30.glGenVertexArrays();
            textVbo = org.lwjgl.opengl.GL15.glGenBuffers();
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer xPos = stack.floats(x);
            FloatBuffer yPos = stack.floats(y);
            STBTTAlignedQuad q = STBTTAlignedQuad.malloc(stack);
            
            // Each character has 6 vertices (2 triangles), each with position (2 floats) and texture coords (2 floats)
            // So 6 vertices * 4 floats per vertex = 24 floats per character
            FloatBuffer vertexData = MemoryUtil.memAllocFloat(text.length() * 24);
            
            int vertexCount = 0;
            
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= 32 && c < 128) {
                    stbtt_GetBakedQuad(cdata, bitmapWidth, bitmapHeight, c - 32, xPos, yPos, q, true);
                    
                    // First triangle (bottom-left, bottom-right, top-right)
                    // Vertex 1 (bottom-left)
                    vertexData.put(q.x0()).put(q.y0());  // Position
                    vertexData.put(q.s0()).put(q.t0());  // Texture coords
                    
                    // Vertex 2 (bottom-right)
                    vertexData.put(q.x1()).put(q.y0());
                    vertexData.put(q.s1()).put(q.t0());
                    
                    // Vertex 3 (top-right)
                    vertexData.put(q.x1()).put(q.y1());
                    vertexData.put(q.s1()).put(q.t1());
                    
                    // Second triangle (top-right, top-left, bottom-left)
                    // Vertex 4 (top-right) - same as Vertex 3
                    vertexData.put(q.x1()).put(q.y1());
                    vertexData.put(q.s1()).put(q.t1());
                    
                    // Vertex 5 (top-left)
                    vertexData.put(q.x0()).put(q.y1());
                    vertexData.put(q.s0()).put(q.t1());
                    
                    // Vertex 6 (bottom-left) - same as Vertex 1
                    vertexData.put(q.x0()).put(q.y0());
                    vertexData.put(q.s0()).put(q.t0());
                    
                    vertexCount += 6; // 6 vertices for each character
                }
            }
            
            vertexData.flip();
            
            // If we have vertices to render
            if (vertexCount > 0) {
                // Bind the VAO
                org.lwjgl.opengl.GL30.glBindVertexArray(textVao);
                
                // Bind and fill the VBO
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, textVbo);
                org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, vertexData, org.lwjgl.opengl.GL15.GL_STREAM_DRAW);
                
                // Set attribute pointers
                // Position attribute (2 floats)
                org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0);
                org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
                
                // Texture coordinate attribute (2 floats)
                org.lwjgl.opengl.GL20.glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
                org.lwjgl.opengl.GL20.glEnableVertexAttribArray(1);
                
                // Draw the text
                glDrawArrays(GL_TRIANGLES, 0, vertexCount);
                
                // Unbind the VAO
                org.lwjgl.opengl.GL30.glBindVertexArray(0);
            }
            
            // Free the buffer
            MemoryUtil.memFree(vertexData);
        }

        // The caller (Renderer) is responsible for resetting shader state (like u_isText)
        // and unbinding textures if necessary after all text/UI drawing is done.
        // glBindTexture(GL_TEXTURE_2D, 0); // Optionally unbind if this is the last text draw
        // glDisable(GL_TEXTURE_2D); // Optionally disable if no more 2D textures
    }

    /**
     * Draws text on the screen using specified RGB integer colors.
     * Assumes the provided shader program is already bound and configured for text rendering.
     * @param x X-coordinate
     * @param y Y-coordinate
     * @param text The string to draw
     * @param r Red component (0-255)
     * @param g Green component (0-255)
     * @param b Blue component (0-255)
     * @param shader The shader program to use for rendering text.
     */
    public void drawString(float x, float y, String text, int r, int g, int b, ShaderProgram shader) {
        if (shader == null) {
            System.err.println("Font.drawString called with a null ShaderProgram. Text not drawn: " + text);
            return;
        }
        // Normalize color components from 0-255 to 0.0-1.0f
        float red = r / 255.0f;
        float green = g / 255.0f;
        float blue = b / 255.0f;
        // Assuming alpha is 1.0f (opaque) for this simplified method
        org.joml.Vector4f textColor = new org.joml.Vector4f(red, green, blue, 1.0f);
        
        // Call the more detailed drawText method
        drawText(x, y, text, shader, textColor);
    }

    public float getTextWidth(String text) {
        float width;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer xPos = stack.floats(0.0f); // Start at 0
            FloatBuffer yPos = stack.floats(0.0f); // y doesn't matter for width
            STBTTAlignedQuad q = STBTTAlignedQuad.malloc(stack);

            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= 32 && c < 128) {
                    stbtt_GetBakedQuad(cdata, bitmapWidth, bitmapHeight, c - 32, xPos, yPos, q, false);
                    // xPos is updated by stbtt_GetBakedQuad to the new cursor position
                }
            }
            width = xPos.get(0); // The final x position is the width
        }
        return width;
    }

    /**
     * Gets the approximate line height for the font.
     * This is often related to the font size used during baking.
     * For more accurate metrics, STB Truetype provides ascent, descent, and lineGap.
     * For simplicity, we'll use fontSize as an approximation.
     */
    public float getLineHeight() {
        // A common approximation. For precise layout, you'd use font metrics
        // (ascent - descent + lineGap) from stbtt_GetFontVMetrics.
        // Since we bake at a specific 'fontSize', this is a reasonable estimate for baked fonts.
        return fontSize;
    }
    
    private static ByteBuffer ioResourceToByteBuffer(String resource, int bufferSize) throws IOException {
        ByteBuffer buffer;
        
        // Try different approaches for module compatibility
        InputStream source = null;
        
        // First try: Module's class loader
        source = Font.class.getClassLoader().getResourceAsStream(resource);
        
        // Second try: Module class itself
        if (source == null) {
            source = Font.class.getResourceAsStream("/" + resource);
        }
        
        // Third try: Context class loader
        if (source == null) {
            source = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        }
        
        try (InputStream finalSource = source) {
            if (finalSource == null) {
                throw new IOException("Resource not found: " + resource);
            }
            
            try (ReadableByteChannel rbc = Channels.newChannel(finalSource)) {
                buffer = MemoryUtil.memAlloc(bufferSize);
                while (true) {
                    int bytes = rbc.read(buffer);
                    if (bytes == -1) {
                        break;
                    }
                    if (buffer.remaining() == 0) {
                        buffer = MemoryUtil.memRealloc(buffer, buffer.capacity() * 2);
                    }
                }
            }
        }
        
        buffer.flip();
        return buffer;
    }    public void cleanup() {
        glDeleteTextures(fontTextureId);
        
        // Cleanup VAO and VBO if they were created
        if (textVao != 0) {
            org.lwjgl.opengl.GL30.glDeleteVertexArrays(textVao);
            org.lwjgl.opengl.GL15.glDeleteBuffers(textVbo);
            textVao = 0;
            textVbo = 0;
        }
        
        cdata.free();
    }

    // Getters if needed, e.g., for font size to adjust y position based on ascent/descent
    public float getFontSize() {
        return fontSize;
    }
}