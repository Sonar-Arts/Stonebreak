package com.stonebreak.rendering.UI.core;

import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public abstract class BaseRenderer {
    protected long vg;
    protected int fontRegular = -1;
    protected int fontBold = -1;
    protected int fontMinecraft = -1;
    
    protected static final int UI_FONT_SIZE = 18;
    protected static final int UI_TITLE_SIZE = 48;
    protected static final int UI_BUTTON_HEIGHT = 40;
    protected static final int UI_BUTTON_WIDTH = 400;
    
    public BaseRenderer(long vg) {
        this.vg = vg;
    }
    
    protected void loadFonts() {
        String[] fontPaths = {
            "fonts/Roboto-VariableFont_wdth,wght.ttf"
        };
        
        String[] boldFontPaths = {
            "fonts/Roboto-Italic-VariableFont_wdth,wght.ttf",
            "fonts/Roboto-VariableFont_wdth,wght.ttf"
        };
        
        String[] minecraftPaths = {
            "fonts/Minecraft.ttf"
        };
        
        for (String path : fontPaths) {
            try {
                ByteBuffer fontBuffer = loadResourceToByteBuffer(path, 512 * 1024);
                fontRegular = nvgCreateFontMem(vg, "sans", fontBuffer, true);
                if (fontRegular != -1) {
                    System.out.println("Successfully loaded regular font from: " + path);
                    break;
                } else {
                    MemoryUtil.memFree(fontBuffer);
                }
            } catch (IOException e) {
                System.err.println("Failed to load regular font from: " + path + " - " + e.getMessage());
            }
        }
        
        for (String path : boldFontPaths) {
            try {
                ByteBuffer fontBuffer = loadResourceToByteBuffer(path, 512 * 1024);
                fontBold = nvgCreateFontMem(vg, "sans-bold", fontBuffer, true);
                if (fontBold != -1) {
                    System.out.println("Successfully loaded bold font from: " + path);
                    break;
                } else {
                    MemoryUtil.memFree(fontBuffer);
                }
            } catch (IOException e) {
                System.err.println("Failed to load bold font from: " + path + " - " + e.getMessage());
            }
        }
        
        for (String path : minecraftPaths) {
            try {
                ByteBuffer fontBuffer = loadResourceToByteBuffer(path, 512 * 1024);
                fontMinecraft = nvgCreateFontMem(vg, "minecraft", fontBuffer, true);
                if (fontMinecraft != -1) {
                    System.out.println("Successfully loaded Minecraft font from: " + path);
                    break;
                } else {
                    MemoryUtil.memFree(fontBuffer);
                }
            } catch (IOException e) {
                System.err.println("Failed to load Minecraft font from: " + path + " - " + e.getMessage());
            }
        }
        
        if (fontRegular == -1 || fontBold == -1) {
            System.err.println("Warning: Could not load regular/bold fonts from classpath resources, using default");
        }
        if (fontMinecraft == -1) {
            System.err.println("Warning: Could not load Minecraft font from classpath resources, using regular font for title");
        }
    }
    
    protected String getFontName() {
        if (fontMinecraft != -1) {
            return "minecraft";
        } else if (fontRegular != -1) {
            return "sans";
        } else if (fontBold != -1) {
            return "sans-bold";
        } else {
            return "default";
        }
    }
    
    protected String getBoldFontName() {
        if (fontMinecraft != -1) {
            return "minecraft";
        } else if (fontBold != -1) {
            return "sans-bold";
        } else if (fontRegular != -1) {
            return "sans";
        } else {
            return "default";
        }
    }
    
    public NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }
    
    protected ByteBuffer loadResourceToByteBuffer(String resource, int bufferSize) throws IOException {
        ByteBuffer buffer;
        
        InputStream source = null;
        
        source = BaseRenderer.class.getClassLoader().getResourceAsStream(resource);
        
        if (source == null) {
            source = BaseRenderer.class.getResourceAsStream("/" + resource);
        }
        
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
    }
    
    public float getTextWidth(String text, float fontSize, String fontFaceName) {
        if (vg == 0 || text == null || text.isEmpty()) {
            return 0;
        }
        nvgFontSize(vg, fontSize);
        nvgFontFace(vg, fontFaceName);
        float[] bounds = new float[4];
        org.lwjgl.nanovg.NanoVG.nvgTextBounds(vg, 0, 0, text, bounds);
        return bounds[2] - bounds[0];
    }
    
    public void drawText(String text, float x, float y, String fontFaceName, float fontSize, float r, float g, float b, float a) {
        if (vg == 0 || text == null || text.isEmpty()) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            nvgFontSize(vg, fontSize);
            nvgFontFace(vg, fontFaceName);
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA((int)(r*255), (int)(g*255), (int)(b*255), (int)(a*255), NVGColor.malloc(stack)));
            nvgText(vg, x, y, text);
        }
    }
}