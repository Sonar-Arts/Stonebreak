package com.stonebreak.rendering;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

/**
 * Manages texture atlases for all mob entities in the game.
 * Provides efficient texture mapping and UV coordinate generation.
 */
public class MobTextureAtlas {
    
    // Texture configuration constants
    private static final int ATLAS_GRID_SIZE = 16;  // 16x16 grid
    private static final int TILE_SIZE = 16;        // 16x16 pixels per tile
    private static final int ATLAS_PIXEL_SIZE = ATLAS_GRID_SIZE * TILE_SIZE;
    
    // Mob types enum for better organization
    public enum MobType {
        COW,
        COW_JERSEY,    // Warm biome variant
        COW_ANGUS,     // Cold biome variant  
        COW_HIGHLAND,  // Mountain biome variant
        PIG,
        SHEEP,
        CHICKEN,
        ZOMBIE,
        SKELETON
    }
    
    // Body part types for texture mapping
    public enum BodyPart {
        HEAD_FRONT,
        HEAD_BACK,
        HEAD_LEFT,
        HEAD_RIGHT,
        HEAD_TOP,
        HEAD_BOTTOM,
        BODY_FRONT,
        BODY_BACK,
        BODY_LEFT,
        BODY_RIGHT,
        BODY_TOP,
        BODY_BOTTOM,
        LEG_FRONT,
        LEG_BACK,
        LEG_LEFT,
        LEG_RIGHT,
        LEG_TOP,
        LEG_BOTTOM,
        TAIL,
        TAIL_FRONT,
        TAIL_BACK,
        TAIL_LEFT,
        TAIL_RIGHT,
        TAIL_TOP,
        TAIL_BOTTOM,
        UDDER,
        UDDER_FRONT,
        UDDER_BACK,
        UDDER_LEFT,
        UDDER_RIGHT,
        UDDER_TOP,
        UDDER_BOTTOM,
        HORNS,
        HORNS_FRONT,
        HORNS_BACK,
        HORNS_LEFT,
        HORNS_RIGHT,
        HORNS_TOP,
        HORNS_BOTTOM,
        EARS,
        SNOUT,
        WOOL,
        WINGS,
        ARM_LEFT,
        ARM_RIGHT
    }
    
    // Texture mapping entry
    public static class TextureMapping {
        public final int atlasX;
        public final int atlasY;
        public final int width;
        public final int height;
        
        public TextureMapping(int atlasX, int atlasY, int width, int height) {
            this.atlasX = atlasX;
            this.atlasY = atlasY;
            this.width = width;
            this.height = height;
        }
        
        public TextureMapping(int atlasX, int atlasY) {
            this(atlasX, atlasY, 1, 1);
        }
    }
    
    // OpenGL texture ID
    private int textureId;
    
    // Texture mappings for each mob type and body part
    private final Map<MobType, Map<BodyPart, TextureMapping>> textureMappings;
    
    // Pixel data buffer
    private ByteBuffer pixelBuffer;
    
    /**
     * Constructor initializes the texture atlas system.
     */
    public MobTextureAtlas() {
        this.textureMappings = new EnumMap<>(MobType.class);
        initializeTextureMappings();
        createTexture();
    }
    
    /**
     * Constructor with atlas size parameter for backward compatibility.
     * @param atlasSize Size parameter (ignored, uses fixed 16x16)
     */
    public MobTextureAtlas(int atlasSize) {
        this();
    }
    
    /**
     * Initialize all texture mappings for mob body parts.
     */
    private void initializeTextureMappings() {
        // Cow texture mappings with detailed face-specific textures
        Map<BodyPart, TextureMapping> cowMappings = new EnumMap<>(BodyPart.class);
        
        // Head textures (detailed faces)
        cowMappings.put(BodyPart.HEAD_FRONT, new TextureMapping(0, 0));     // Cow face with eyes/nose
        cowMappings.put(BodyPart.HEAD_BACK, new TextureMapping(1, 0));      // Back of head
        cowMappings.put(BodyPart.HEAD_LEFT, new TextureMapping(2, 0));      // Left side of head
        cowMappings.put(BodyPart.HEAD_RIGHT, new TextureMapping(3, 0));     // Right side of head
        cowMappings.put(BodyPart.HEAD_TOP, new TextureMapping(0, 1));       // Top of head
        cowMappings.put(BodyPart.HEAD_BOTTOM, new TextureMapping(1, 1));    // Bottom of head
        
        // Body textures (varied patterns)
        cowMappings.put(BodyPart.BODY_FRONT, new TextureMapping(4, 0));     // Body front
        cowMappings.put(BodyPart.BODY_BACK, new TextureMapping(5, 0));      // Body back
        cowMappings.put(BodyPart.BODY_LEFT, new TextureMapping(6, 0));      // Body left side
        cowMappings.put(BodyPart.BODY_RIGHT, new TextureMapping(7, 0));     // Body right side
        cowMappings.put(BodyPart.BODY_TOP, new TextureMapping(4, 1));       // Body top (back)
        cowMappings.put(BodyPart.BODY_BOTTOM, new TextureMapping(5, 1));    // Body bottom (belly)
        
        // Leg textures (with hooves)
        cowMappings.put(BodyPart.LEG_FRONT, new TextureMapping(8, 0));      // Leg front
        cowMappings.put(BodyPart.LEG_BACK, new TextureMapping(9, 0));       // Leg back
        cowMappings.put(BodyPart.LEG_LEFT, new TextureMapping(10, 0));      // Leg left
        cowMappings.put(BodyPart.LEG_RIGHT, new TextureMapping(11, 0));     // Leg right
        cowMappings.put(BodyPart.LEG_TOP, new TextureMapping(8, 1));        // Leg top
        cowMappings.put(BodyPart.LEG_BOTTOM, new TextureMapping(9, 1));     // Leg bottom (hoof)
        
        // Other parts
        cowMappings.put(BodyPart.TAIL, new TextureMapping(12, 0));
        cowMappings.put(BodyPart.UDDER, new TextureMapping(13, 0));
        cowMappings.put(BodyPart.HORNS, new TextureMapping(14, 0));
        cowMappings.put(BodyPart.EARS, new TextureMapping(15, 0));
        
        // Ultra-detailed horn face textures (row 5)
        cowMappings.put(BodyPart.HORNS_FRONT, new TextureMapping(0, 5));      // Horn front face
        cowMappings.put(BodyPart.HORNS_BACK, new TextureMapping(1, 5));       // Horn back face
        cowMappings.put(BodyPart.HORNS_LEFT, new TextureMapping(2, 5));       // Horn left face
        cowMappings.put(BodyPart.HORNS_RIGHT, new TextureMapping(3, 5));      // Horn right face
        cowMappings.put(BodyPart.HORNS_TOP, new TextureMapping(4, 5));        // Horn top (pointed tip)
        cowMappings.put(BodyPart.HORNS_BOTTOM, new TextureMapping(5, 5));     // Horn bottom (base)
        
        // Ultra-detailed tail face textures (row 5, continued)
        cowMappings.put(BodyPart.TAIL_FRONT, new TextureMapping(6, 5));       // Tail front face
        cowMappings.put(BodyPart.TAIL_BACK, new TextureMapping(7, 5));        // Tail back face
        cowMappings.put(BodyPart.TAIL_LEFT, new TextureMapping(8, 5));        // Tail left face
        cowMappings.put(BodyPart.TAIL_RIGHT, new TextureMapping(9, 5));       // Tail right face
        cowMappings.put(BodyPart.TAIL_TOP, new TextureMapping(10, 5));        // Tail top (attachment)
        cowMappings.put(BodyPart.TAIL_BOTTOM, new TextureMapping(11, 5));     // Tail bottom (tuft)
        
        // Ultra-detailed udder face textures (row 5, final positions + row 9)
        cowMappings.put(BodyPart.UDDER_FRONT, new TextureMapping(12, 5));     // Udder front face
        cowMappings.put(BodyPart.UDDER_BACK, new TextureMapping(13, 5));      // Udder back face
        cowMappings.put(BodyPart.UDDER_LEFT, new TextureMapping(14, 5));      // Udder left face
        cowMappings.put(BodyPart.UDDER_RIGHT, new TextureMapping(15, 5));     // Udder right face
        cowMappings.put(BodyPart.UDDER_TOP, new TextureMapping(0, 9));        // Udder top (attachment)
        cowMappings.put(BodyPart.UDDER_BOTTOM, new TextureMapping(1, 9));     // Udder bottom (teats)
        textureMappings.put(MobType.COW, cowMappings);
        
        // Jersey cow texture mappings (row 2)
        Map<BodyPart, TextureMapping> jerseyCowMappings = new EnumMap<>(BodyPart.class);
        jerseyCowMappings.put(BodyPart.HEAD_FRONT, new TextureMapping(0, 2));
        jerseyCowMappings.put(BodyPart.HEAD_BACK, new TextureMapping(1, 2));
        jerseyCowMappings.put(BodyPart.HEAD_LEFT, new TextureMapping(2, 2));
        jerseyCowMappings.put(BodyPart.HEAD_RIGHT, new TextureMapping(3, 2));
        jerseyCowMappings.put(BodyPart.BODY_FRONT, new TextureMapping(4, 2));
        jerseyCowMappings.put(BodyPart.BODY_BACK, new TextureMapping(5, 2));
        jerseyCowMappings.put(BodyPart.BODY_LEFT, new TextureMapping(6, 2));
        jerseyCowMappings.put(BodyPart.BODY_RIGHT, new TextureMapping(7, 2));
        jerseyCowMappings.put(BodyPart.LEG_FRONT, new TextureMapping(8, 2));
        jerseyCowMappings.put(BodyPart.LEG_BACK, new TextureMapping(9, 2));
        jerseyCowMappings.put(BodyPart.LEG_LEFT, new TextureMapping(10, 2));
        jerseyCowMappings.put(BodyPart.LEG_RIGHT, new TextureMapping(11, 2));
        jerseyCowMappings.put(BodyPart.TAIL, new TextureMapping(12, 2));
        jerseyCowMappings.put(BodyPart.UDDER, new TextureMapping(13, 2));
        jerseyCowMappings.put(BodyPart.HORNS, new TextureMapping(14, 2));
        jerseyCowMappings.put(BodyPart.EARS, new TextureMapping(15, 2));
        
        // Ultra-detailed Jersey horn face textures (row 6)
        jerseyCowMappings.put(BodyPart.HORNS_FRONT, new TextureMapping(0, 6));
        jerseyCowMappings.put(BodyPart.HORNS_BACK, new TextureMapping(1, 6));
        jerseyCowMappings.put(BodyPart.HORNS_LEFT, new TextureMapping(2, 6));
        jerseyCowMappings.put(BodyPart.HORNS_RIGHT, new TextureMapping(3, 6));
        jerseyCowMappings.put(BodyPart.HORNS_TOP, new TextureMapping(4, 6));
        jerseyCowMappings.put(BodyPart.HORNS_BOTTOM, new TextureMapping(5, 6));
        
        // Ultra-detailed Jersey tail face textures (row 6, continued)
        jerseyCowMappings.put(BodyPart.TAIL_FRONT, new TextureMapping(6, 6));
        jerseyCowMappings.put(BodyPart.TAIL_BACK, new TextureMapping(7, 6));
        jerseyCowMappings.put(BodyPart.TAIL_LEFT, new TextureMapping(8, 6));
        jerseyCowMappings.put(BodyPart.TAIL_RIGHT, new TextureMapping(9, 6));
        jerseyCowMappings.put(BodyPart.TAIL_TOP, new TextureMapping(10, 6));
        jerseyCowMappings.put(BodyPart.TAIL_BOTTOM, new TextureMapping(11, 6));
        
        // Ultra-detailed Jersey udder face textures (row 6, final positions + row 10)
        jerseyCowMappings.put(BodyPart.UDDER_FRONT, new TextureMapping(12, 6));     // Udder front face
        jerseyCowMappings.put(BodyPart.UDDER_BACK, new TextureMapping(13, 6));      // Udder back face
        jerseyCowMappings.put(BodyPart.UDDER_LEFT, new TextureMapping(14, 6));      // Udder left face
        jerseyCowMappings.put(BodyPart.UDDER_RIGHT, new TextureMapping(15, 6));     // Udder right face
        jerseyCowMappings.put(BodyPart.UDDER_TOP, new TextureMapping(0, 10));       // Udder top (attachment)
        jerseyCowMappings.put(BodyPart.UDDER_BOTTOM, new TextureMapping(1, 10));    // Udder bottom (teats)
        textureMappings.put(MobType.COW_JERSEY, jerseyCowMappings);
        
        // Angus cow texture mappings (row 3)
        Map<BodyPart, TextureMapping> angusCowMappings = new EnumMap<>(BodyPart.class);
        angusCowMappings.put(BodyPart.HEAD_FRONT, new TextureMapping(0, 3));
        angusCowMappings.put(BodyPart.HEAD_BACK, new TextureMapping(1, 3));
        angusCowMappings.put(BodyPart.HEAD_LEFT, new TextureMapping(2, 3));
        angusCowMappings.put(BodyPart.HEAD_RIGHT, new TextureMapping(3, 3));
        angusCowMappings.put(BodyPart.BODY_FRONT, new TextureMapping(4, 3));
        angusCowMappings.put(BodyPart.BODY_BACK, new TextureMapping(5, 3));
        angusCowMappings.put(BodyPart.BODY_LEFT, new TextureMapping(6, 3));
        angusCowMappings.put(BodyPart.BODY_RIGHT, new TextureMapping(7, 3));
        angusCowMappings.put(BodyPart.LEG_FRONT, new TextureMapping(8, 3));
        angusCowMappings.put(BodyPart.LEG_BACK, new TextureMapping(9, 3));
        angusCowMappings.put(BodyPart.LEG_LEFT, new TextureMapping(10, 3));
        angusCowMappings.put(BodyPart.LEG_RIGHT, new TextureMapping(11, 3));
        angusCowMappings.put(BodyPart.TAIL, new TextureMapping(12, 3));
        angusCowMappings.put(BodyPart.UDDER, new TextureMapping(13, 3));
        angusCowMappings.put(BodyPart.HORNS, new TextureMapping(14, 3));
        angusCowMappings.put(BodyPart.EARS, new TextureMapping(15, 3));
        
        // Ultra-detailed Angus horn face textures (row 7)
        angusCowMappings.put(BodyPart.HORNS_FRONT, new TextureMapping(0, 7));
        angusCowMappings.put(BodyPart.HORNS_BACK, new TextureMapping(1, 7));
        angusCowMappings.put(BodyPart.HORNS_LEFT, new TextureMapping(2, 7));
        angusCowMappings.put(BodyPart.HORNS_RIGHT, new TextureMapping(3, 7));
        angusCowMappings.put(BodyPart.HORNS_TOP, new TextureMapping(4, 7));
        angusCowMappings.put(BodyPart.HORNS_BOTTOM, new TextureMapping(5, 7));
        
        // Ultra-detailed Angus tail face textures (row 7, continued)
        angusCowMappings.put(BodyPart.TAIL_FRONT, new TextureMapping(6, 7));
        angusCowMappings.put(BodyPart.TAIL_BACK, new TextureMapping(7, 7));
        angusCowMappings.put(BodyPart.TAIL_LEFT, new TextureMapping(8, 7));
        angusCowMappings.put(BodyPart.TAIL_RIGHT, new TextureMapping(9, 7));
        angusCowMappings.put(BodyPart.TAIL_TOP, new TextureMapping(10, 7));
        angusCowMappings.put(BodyPart.TAIL_BOTTOM, new TextureMapping(11, 7));
        
        // Ultra-detailed Angus udder face textures (row 7, final positions + row 11)
        angusCowMappings.put(BodyPart.UDDER_FRONT, new TextureMapping(12, 7));      // Udder front face
        angusCowMappings.put(BodyPart.UDDER_BACK, new TextureMapping(13, 7));       // Udder back face
        angusCowMappings.put(BodyPart.UDDER_LEFT, new TextureMapping(14, 7));       // Udder left face
        angusCowMappings.put(BodyPart.UDDER_RIGHT, new TextureMapping(15, 7));      // Udder right face
        angusCowMappings.put(BodyPart.UDDER_TOP, new TextureMapping(0, 11));        // Udder top (attachment)
        angusCowMappings.put(BodyPart.UDDER_BOTTOM, new TextureMapping(1, 11));     // Udder bottom (teats)
        textureMappings.put(MobType.COW_ANGUS, angusCowMappings);
        
        // Highland cow texture mappings (row 4)
        Map<BodyPart, TextureMapping> highlandCowMappings = new EnumMap<>(BodyPart.class);
        highlandCowMappings.put(BodyPart.HEAD_FRONT, new TextureMapping(0, 4));
        highlandCowMappings.put(BodyPart.HEAD_BACK, new TextureMapping(1, 4));
        highlandCowMappings.put(BodyPart.HEAD_LEFT, new TextureMapping(2, 4));
        highlandCowMappings.put(BodyPart.HEAD_RIGHT, new TextureMapping(3, 4));
        highlandCowMappings.put(BodyPart.BODY_FRONT, new TextureMapping(4, 4));
        highlandCowMappings.put(BodyPart.BODY_BACK, new TextureMapping(5, 4));
        highlandCowMappings.put(BodyPart.BODY_LEFT, new TextureMapping(6, 4));
        highlandCowMappings.put(BodyPart.BODY_RIGHT, new TextureMapping(7, 4));
        highlandCowMappings.put(BodyPart.LEG_FRONT, new TextureMapping(8, 4));
        highlandCowMappings.put(BodyPart.LEG_BACK, new TextureMapping(9, 4));
        highlandCowMappings.put(BodyPart.LEG_LEFT, new TextureMapping(10, 4));
        highlandCowMappings.put(BodyPart.LEG_RIGHT, new TextureMapping(11, 4));
        highlandCowMappings.put(BodyPart.TAIL, new TextureMapping(12, 4));
        highlandCowMappings.put(BodyPart.UDDER, new TextureMapping(13, 4));
        highlandCowMappings.put(BodyPart.HORNS, new TextureMapping(14, 4));
        highlandCowMappings.put(BodyPart.EARS, new TextureMapping(15, 4));
        
        // Ultra-detailed Highland horn face textures (row 8)
        highlandCowMappings.put(BodyPart.HORNS_FRONT, new TextureMapping(0, 8));
        highlandCowMappings.put(BodyPart.HORNS_BACK, new TextureMapping(1, 8));
        highlandCowMappings.put(BodyPart.HORNS_LEFT, new TextureMapping(2, 8));
        highlandCowMappings.put(BodyPart.HORNS_RIGHT, new TextureMapping(3, 8));
        highlandCowMappings.put(BodyPart.HORNS_TOP, new TextureMapping(4, 8));
        highlandCowMappings.put(BodyPart.HORNS_BOTTOM, new TextureMapping(5, 8));
        
        // Ultra-detailed Highland tail face textures (row 8, continued)
        highlandCowMappings.put(BodyPart.TAIL_FRONT, new TextureMapping(6, 8));
        highlandCowMappings.put(BodyPart.TAIL_BACK, new TextureMapping(7, 8));
        highlandCowMappings.put(BodyPart.TAIL_LEFT, new TextureMapping(8, 8));
        highlandCowMappings.put(BodyPart.TAIL_RIGHT, new TextureMapping(9, 8));
        highlandCowMappings.put(BodyPart.TAIL_TOP, new TextureMapping(10, 8));
        highlandCowMappings.put(BodyPart.TAIL_BOTTOM, new TextureMapping(11, 8));
        
        // Ultra-detailed Highland udder face textures (row 8, final positions + row 12)
        highlandCowMappings.put(BodyPart.UDDER_FRONT, new TextureMapping(12, 8));   // Udder front face
        highlandCowMappings.put(BodyPart.UDDER_BACK, new TextureMapping(13, 8));    // Udder back face
        highlandCowMappings.put(BodyPart.UDDER_LEFT, new TextureMapping(14, 8));    // Udder left face
        highlandCowMappings.put(BodyPart.UDDER_RIGHT, new TextureMapping(15, 8));   // Udder right face
        highlandCowMappings.put(BodyPart.UDDER_TOP, new TextureMapping(0, 12));     // Udder top (attachment)
        highlandCowMappings.put(BodyPart.UDDER_BOTTOM, new TextureMapping(1, 12));  // Udder bottom (teats)
        textureMappings.put(MobType.COW_HIGHLAND, highlandCowMappings);
        
        // Placeholder mappings for future mobs
        Map<BodyPart, TextureMapping> pigMappings = new EnumMap<>(BodyPart.class);
        pigMappings.put(BodyPart.HEAD_FRONT, new TextureMapping(0, 3));
        pigMappings.put(BodyPart.BODY_FRONT, new TextureMapping(1, 3, 2, 1));
        pigMappings.put(BodyPart.SNOUT, new TextureMapping(3, 3));
        textureMappings.put(MobType.PIG, pigMappings);
        
        Map<BodyPart, TextureMapping> sheepMappings = new EnumMap<>(BodyPart.class);
        sheepMappings.put(BodyPart.HEAD_FRONT, new TextureMapping(0, 4));
        sheepMappings.put(BodyPart.BODY_FRONT, new TextureMapping(1, 4));
        sheepMappings.put(BodyPart.WOOL, new TextureMapping(2, 4, 2, 2));
        textureMappings.put(MobType.SHEEP, sheepMappings);
    }
    
    /**
     * Create the OpenGL texture and generate pixel data.
     */
    private void createTexture() {
        // Generate OpenGL texture
        textureId = GL11.glGenTextures();
        
        // Create pixel buffer
        pixelBuffer = BufferUtils.createByteBuffer(ATLAS_PIXEL_SIZE * ATLAS_PIXEL_SIZE * 4);
        
        // Generate texture data
        generateAtlasPixelData();
        
        // Upload to GPU
        uploadTextureToGPU();
    }
    
    /**
     * Generate pixel data for the entire atlas.
     */
    private void generateAtlasPixelData() {
        // Clear to transparent
        for (int i = 0; i < pixelBuffer.capacity(); i += 4) {
            pixelBuffer.put(i, (byte) 0);     // R
            pixelBuffer.put(i + 1, (byte) 0); // G
            pixelBuffer.put(i + 2, (byte) 0); // B
            pixelBuffer.put(i + 3, (byte) 0); // A
        }
        
        // Generate cow textures (Holstein - default)
        generateCowTextures();
        
        // Generate biome variant cow textures
        generateCowVariantTextures();
        
        // Generate additional cow face-specific textures
        generateCowFaceSpecificTextures();
        
        // Generate other mob textures
        generatePigTextures();
        generateSheepTextures();
        
        pixelBuffer.flip();
    }
    
    /**
     * Generate cow-specific textures with ultra-realistic Minecraft-style patterns.
     * Using authentic Holstein cow color palette with premium detail.
     */
    private void generateCowTextures() {
        // Premium Holstein cow color palette
        Color cowBrown = new Color(139, 90, 43);           // Base brown
        Color cowLightBrown = new Color(180, 130, 80);     // Light brown highlights
        Color cowDarkBrown = new Color(101, 67, 33);       // Dark brown shadows
        Color cowWhite = new Color(250, 248, 240);         // Cream white (not pure white)
        Color cowBlack = new Color(25, 20, 15);            // Rich black
        Color cowPink = new Color(255, 182, 193);          // Pink for nose/udder
        Color cowDarkPink = new Color(200, 120, 140);      // Darker pink for shading
        Color cowGray = new Color(120, 115, 110);          // Gray for hooves/horns
        Color cowBeige = new Color(220, 200, 170);         // Beige for belly
        
        // ULTRA-DETAILED HEAD TEXTURES
        generateUltraCowFace(0, 0, cowBrown, cowWhite, cowBlack, cowPink, cowDarkPink);     // Ultra-detailed front face
        generateUltraCowHeadBack(1, 0, cowBrown, cowWhite, cowDarkBrown);                   // Detailed back of head
        generateUltraCowHeadSide(2, 0, cowBrown, cowWhite, cowDarkBrown, true);             // Ultra left side
        generateUltraCowHeadSide(3, 0, cowBrown, cowWhite, cowDarkBrown, false);            // Ultra right side
        generateUltraCowHeadTop(0, 1, cowBrown, cowWhite, cowDarkBrown, cowGray);           // Detailed top with horn attachments
        generateUltraCowHeadBottom(1, 1, cowLightBrown, cowBeige);                          // Detailed bottom
        
        // Additional head textures for better coverage
        generateUltraCowBodyTop(4, 1, cowBrown, cowWhite, cowDarkBrown);                   // Ultra top (back)
        generateUltraCowBodyBottom(5, 1, cowBeige, cowWhite);                              // Ultra bottom (belly)
        generateUltraCowLegTop(8, 1, cowDarkBrown, cowBrown);                              // Ultra leg top
        generateUltraHoof(9, 1, cowBlack, cowGray);                                        // Ultra detailed hoof
        
        // PREMIUM BODY TEXTURES
        generateUltraCowBodyFront(4, 0, cowLightBrown, cowWhite, cowDarkBrown);            // Ultra front
        generateUltraCowBodyBack(5, 0, cowBrown, cowWhite, cowDarkBrown);                  // Ultra back
        generateUltraCowBodySide(6, 0, cowLightBrown, cowWhite, cowDarkBrown, true);       // Ultra left side
        generateUltraCowBodySide(7, 0, cowLightBrown, cowWhite, cowDarkBrown, false);      // Ultra right side
        generateUltraCowBodyTop(4, 1, cowBrown, cowWhite, cowDarkBrown);                   // Ultra top (back)
        generateUltraCowBodyBottom(5, 1, cowBeige, cowWhite);                              // Ultra bottom (belly)
        
        // ULTRA-DETAILED LEG TEXTURES
        generateUltraCowLegFront(8, 0, cowDarkBrown, cowBrown, cowBlack);                  // Ultra front
        generateUltraCowLegBack(9, 0, cowDarkBrown, cowBrown, cowBlack);                   // Ultra back
        generateUltraCowLegSide(10, 0, cowDarkBrown, cowBrown, cowBlack, true);            // Ultra left
        generateUltraCowLegSide(11, 0, cowDarkBrown, cowBrown, cowBlack, false);           // Ultra right
        generateUltraCowLegTop(8, 1, cowDarkBrown, cowBrown);                              // Ultra top
        generateUltraHoof(9, 1, cowBlack, cowGray);                                        // Ultra detailed hoof
        
        // ULTRA-PREMIUM SPECIAL PARTS
        generateUltraCowTail(12, 0, cowDarkBrown, cowBlack, cowBrown);                      // Ultra tail with tuft
        generateUltraCowUdder(13, 0, cowPink, cowDarkPink, cowBeige);                       // Ultra realistic udder
        generateUltraCowHorns(14, 0, new Color(245, 245, 220), cowGray, cowBlack);         // Ultra detailed horns
        generateUltraCowEars(15, 0, cowBrown, cowPink, cowDarkBrown);                       // Ultra detailed ears
        
        // BILLIONAIRE BUDGET ULTRA-PREMIUM HORN FACE TEXTURES (Row 5)
        generateUltraHornFace(0, 5, new Color(245, 245, 220), cowGray, cowBlack, "front"); // Horn front face
        generateUltraHornFace(1, 5, new Color(245, 245, 220), cowGray, cowBlack, "back");  // Horn back face
        generateUltraHornFace(2, 5, new Color(245, 245, 220), cowGray, cowBlack, "left");  // Horn left face
        generateUltraHornFace(3, 5, new Color(245, 245, 220), cowGray, cowBlack, "right"); // Horn right face
        generateUltraHornTip(4, 5, new Color(245, 245, 220), cowGray, cowBlack);           // Horn top (pointed tip)
        generateUltraHornBase(5, 5, cowBrown, cowGray, cowBlack);                          // Horn bottom (base)
        
        // BILLIONAIRE BUDGET ULTRA-PREMIUM TAIL FACE TEXTURES (Row 5 continued)
        generateUltraTailFace(6, 5, cowDarkBrown, cowBlack, cowBrown, "front");            // Tail front face
        generateUltraTailFace(7, 5, cowDarkBrown, cowBlack, cowBrown, "back");             // Tail back face
        generateUltraTailFace(8, 5, cowDarkBrown, cowBlack, cowBrown, "left");             // Tail left face
        generateUltraTailFace(9, 5, cowDarkBrown, cowBlack, cowBrown, "right");            // Tail right face
        generateUltraTailAttachment(10, 5, cowBrown, cowDarkBrown, cowBlack);              // Tail top (attachment)
        generateUltraTailTuft(11, 5, cowBlack, cowDarkBrown, cowBrown);                    // Tail bottom (tuft)
        
        // BILLIONAIRE BUDGET ULTRA-PREMIUM UDDER FACE TEXTURES (Row 5 final + Row 9)
        generateUltraUdderFace(12, 5, cowPink, cowDarkPink, cowBeige, "front");           // Udder front face
        generateUltraUdderFace(13, 5, cowPink, cowDarkPink, cowBeige, "back");            // Udder back face
        generateUltraUdderFace(14, 5, cowPink, cowDarkPink, cowBeige, "left");            // Udder left face
        generateUltraUdderFace(15, 5, cowPink, cowDarkPink, cowBeige, "right");           // Udder right face
        generateUltraUdderAttachment(0, 9, cowBeige, cowPink, cowDarkPink);               // Udder top (attachment)
        generateUltraUdderTeats(1, 9, cowPink, cowDarkPink, cowBeige);                    // Udder bottom (teats)
    }
    
    /**
     * Generate biome-specific cow variant textures for premium diversity.
     */
    private void generateCowVariantTextures() {
        // JERSEY COW VARIANT (Warm biomes) - Row 2
        generateJerseyCowVariant();
        
        // ANGUS COW VARIANT (Cold biomes) - Row 3  
        generateAngusCowVariant();
        
        // HIGHLAND COW VARIANT (Mountain biomes) - Row 4
        generateHighlandCowVariant();
    }
    
    /**
     * Generate Jersey cow variant (cream/tan colored for warm biomes).
     */
    private void generateJerseyCowVariant() {
        // Jersey cow color palette
        Color jerseyTan = new Color(210, 180, 140);         // Light tan base
        Color jerseyDarkTan = new Color(160, 130, 90);      // Darker tan
        Color jerseyCream = new Color(255, 248, 220);       // Cream highlights
        Color jerseyBrown = new Color(139, 115, 85);        // Medium brown
        Color jerseyBlack = new Color(25, 20, 15);          // Eyes/hooves
        Color jerseyPink = new Color(255, 182, 193);        // Nose/udder
        Color jerseyDarkPink = new Color(200, 120, 140);    // Darker pink
        Color jerseyGray = new Color(120, 115, 110);        // Hooves/horns
        
        // Generate Jersey textures in row 2 (y = 2)
        generateUltraCowFace(0, 2, jerseyTan, jerseyCream, jerseyBlack, jerseyPink, jerseyDarkPink);
        generateUltraCowHeadBack(1, 2, jerseyTan, jerseyCream, jerseyDarkTan);
        generateUltraCowHeadSide(2, 2, jerseyTan, jerseyCream, jerseyDarkTan, true);
        generateUltraCowHeadSide(3, 2, jerseyTan, jerseyCream, jerseyDarkTan, false);
        generateUltraCowBodyFront(4, 2, jerseyTan, jerseyCream, jerseyDarkTan);
        generateUltraCowBodyBack(5, 2, jerseyBrown, jerseyCream, jerseyDarkTan);
        generateUltraCowBodySide(6, 2, jerseyTan, jerseyCream, jerseyDarkTan, true);
        generateUltraCowBodySide(7, 2, jerseyTan, jerseyCream, jerseyDarkTan, false);
        generateUltraCowLegFront(8, 2, jerseyDarkTan, jerseyTan, jerseyBlack);
        generateUltraCowLegBack(9, 2, jerseyDarkTan, jerseyTan, jerseyBlack);
        generateUltraCowLegSide(10, 2, jerseyDarkTan, jerseyTan, jerseyBlack, true);
        generateUltraCowLegSide(11, 2, jerseyDarkTan, jerseyTan, jerseyBlack, false);
        generateUltraCowTail(12, 2, jerseyDarkTan, jerseyBlack, jerseyTan);
        generateUltraCowUdder(13, 2, jerseyPink, jerseyDarkPink, jerseyCream);
        generateUltraCowHorns(14, 2, new Color(245, 245, 220), jerseyGray, jerseyBlack);
        generateUltraCowEars(15, 2, jerseyTan, jerseyPink, jerseyDarkTan);
        
        // BILLIONAIRE BUDGET JERSEY HORN FACE TEXTURES (Row 6)
        generateUltraHornFace(0, 6, new Color(245, 245, 220), jerseyGray, jerseyBlack, "front");
        generateUltraHornFace(1, 6, new Color(245, 245, 220), jerseyGray, jerseyBlack, "back");
        generateUltraHornFace(2, 6, new Color(245, 245, 220), jerseyGray, jerseyBlack, "left");
        generateUltraHornFace(3, 6, new Color(245, 245, 220), jerseyGray, jerseyBlack, "right");
        generateUltraHornTip(4, 6, new Color(245, 245, 220), jerseyGray, jerseyBlack);
        generateUltraHornBase(5, 6, jerseyTan, jerseyGray, jerseyBlack);
        
        // BILLIONAIRE BUDGET JERSEY TAIL FACE TEXTURES (Row 6 continued)
        generateUltraTailFace(6, 6, jerseyDarkTan, jerseyBlack, jerseyTan, "front");
        generateUltraTailFace(7, 6, jerseyDarkTan, jerseyBlack, jerseyTan, "back");
        generateUltraTailFace(8, 6, jerseyDarkTan, jerseyBlack, jerseyTan, "left");
        generateUltraTailFace(9, 6, jerseyDarkTan, jerseyBlack, jerseyTan, "right");
        generateUltraTailAttachment(10, 6, jerseyTan, jerseyDarkTan, jerseyBlack);
        generateUltraTailTuft(11, 6, jerseyBlack, jerseyDarkTan, jerseyTan);
        
        // BILLIONAIRE BUDGET JERSEY UDDER FACE TEXTURES (Row 6 final + Row 10)
        generateUltraUdderFace(12, 6, jerseyPink, jerseyDarkPink, jerseyCream, "front");    // Udder front face
        generateUltraUdderFace(13, 6, jerseyPink, jerseyDarkPink, jerseyCream, "back");     // Udder back face
        generateUltraUdderFace(14, 6, jerseyPink, jerseyDarkPink, jerseyCream, "left");     // Udder left face
        generateUltraUdderFace(15, 6, jerseyPink, jerseyDarkPink, jerseyCream, "right");    // Udder right face
        generateUltraUdderAttachment(0, 10, jerseyCream, jerseyPink, jerseyDarkPink);       // Udder top (attachment)
        generateUltraUdderTeats(1, 10, jerseyPink, jerseyDarkPink, jerseyCream);            // Udder bottom (teats)
    }
    
    /**
     * Generate Angus cow variant (black colored for cold biomes).
     */
    private void generateAngusCowVariant() {
        // Angus cow color palette (mostly black)
        Color angusBlack = new Color(35, 30, 25);           // Deep black base
        Color angusGray = new Color(60, 55, 50);            // Dark gray highlights
        Color angusLightGray = new Color(85, 80, 75);       // Light gray
        Color angusDarkBlack = new Color(15, 10, 8);        // Very dark black
        Color angusBlackPure = new Color(0, 0, 0);          // Pure black for eyes
        Color angusPink = new Color(255, 182, 193);         // Nose/udder
        Color angusDarkPink = new Color(200, 120, 140);     // Darker pink
        Color angusHornGray = new Color(120, 115, 110);     // Horn color
        
        // Generate Angus textures in row 3 (y = 3)
        generateUltraCowFace(0, 3, angusBlack, angusGray, angusBlackPure, angusPink, angusDarkPink);
        generateUltraCowHeadBack(1, 3, angusBlack, angusGray, angusDarkBlack);
        generateUltraCowHeadSide(2, 3, angusBlack, angusGray, angusDarkBlack, true);
        generateUltraCowHeadSide(3, 3, angusBlack, angusGray, angusDarkBlack, false);
        generateUltraCowBodyFront(4, 3, angusBlack, angusLightGray, angusDarkBlack);
        generateUltraCowBodyBack(5, 3, angusDarkBlack, angusGray, angusBlack);
        generateUltraCowBodySide(6, 3, angusBlack, angusGray, angusDarkBlack, true);
        generateUltraCowBodySide(7, 3, angusBlack, angusGray, angusDarkBlack, false);
        generateUltraCowLegFront(8, 3, angusDarkBlack, angusBlack, angusBlackPure);
        generateUltraCowLegBack(9, 3, angusDarkBlack, angusBlack, angusBlackPure);
        generateUltraCowLegSide(10, 3, angusDarkBlack, angusBlack, angusBlackPure, true);
        generateUltraCowLegSide(11, 3, angusDarkBlack, angusBlack, angusBlackPure, false);
        generateUltraCowTail(12, 3, angusDarkBlack, angusBlackPure, angusBlack);
        generateUltraCowUdder(13, 3, angusPink, angusDarkPink, angusLightGray);
        generateUltraCowHorns(14, 3, angusHornGray, angusDarkBlack, angusBlackPure);
        generateUltraCowEars(15, 3, angusBlack, angusPink, angusDarkBlack);
        
        // BILLIONAIRE BUDGET ANGUS HORN FACE TEXTURES (Row 7)
        generateUltraHornFace(0, 7, angusHornGray, angusDarkBlack, angusBlackPure, "front");
        generateUltraHornFace(1, 7, angusHornGray, angusDarkBlack, angusBlackPure, "back");
        generateUltraHornFace(2, 7, angusHornGray, angusDarkBlack, angusBlackPure, "left");
        generateUltraHornFace(3, 7, angusHornGray, angusDarkBlack, angusBlackPure, "right");
        generateUltraHornTip(4, 7, angusHornGray, angusDarkBlack, angusBlackPure);
        generateUltraHornBase(5, 7, angusBlack, angusDarkBlack, angusBlackPure);
        
        // BILLIONAIRE BUDGET ANGUS TAIL FACE TEXTURES (Row 7 continued)
        generateUltraTailFace(6, 7, angusDarkBlack, angusBlackPure, angusBlack, "front");
        generateUltraTailFace(7, 7, angusDarkBlack, angusBlackPure, angusBlack, "back");
        generateUltraTailFace(8, 7, angusDarkBlack, angusBlackPure, angusBlack, "left");
        generateUltraTailFace(9, 7, angusDarkBlack, angusBlackPure, angusBlack, "right");
        generateUltraTailAttachment(10, 7, angusBlack, angusDarkBlack, angusBlackPure);
        generateUltraTailTuft(11, 7, angusBlackPure, angusDarkBlack, angusBlack);
        
        // BILLIONAIRE BUDGET ANGUS UDDER FACE TEXTURES (Row 7 final + Row 11)
        generateUltraUdderFace(12, 7, angusPink, angusDarkPink, angusLightGray, "front");   // Udder front face
        generateUltraUdderFace(13, 7, angusPink, angusDarkPink, angusLightGray, "back");    // Udder back face
        generateUltraUdderFace(14, 7, angusPink, angusDarkPink, angusLightGray, "left");    // Udder left face
        generateUltraUdderFace(15, 7, angusPink, angusDarkPink, angusLightGray, "right");   // Udder right face
        generateUltraUdderAttachment(0, 11, angusLightGray, angusPink, angusDarkPink);      // Udder top (attachment)
        generateUltraUdderTeats(1, 11, angusPink, angusDarkPink, angusLightGray);           // Udder bottom (teats)
    }
    
    /**
     * Generate Highland cow variant (reddish-brown with long hair for mountain biomes).
     */
    private void generateHighlandCowVariant() {
        // Highland cow color palette (reddish-brown, shaggy)
        Color highlandRed = new Color(160, 80, 40);         // Reddish-brown base
        Color highlandDarkRed = new Color(120, 60, 30);     // Dark reddish-brown
        Color highlandLightRed = new Color(200, 120, 80);   // Light reddish highlights
        Color highlandGinger = new Color(180, 100, 60);     // Ginger tones
        Color highlandBlack = new Color(25, 20, 15);        // Eyes/hooves
        Color highlandPink = new Color(255, 182, 193);      // Nose/udder
        Color highlandDarkPink = new Color(200, 120, 140);  // Darker pink
        Color highlandGray = new Color(120, 115, 110);      // Horns
        
        // Generate Highland textures in row 4 (y = 4)
        generateUltraCowFace(0, 4, highlandRed, highlandLightRed, highlandBlack, highlandPink, highlandDarkPink);
        generateUltraCowHeadBack(1, 4, highlandRed, highlandGinger, highlandDarkRed);
        generateUltraCowHeadSide(2, 4, highlandRed, highlandGinger, highlandDarkRed, true);
        generateUltraCowHeadSide(3, 4, highlandRed, highlandGinger, highlandDarkRed, false);
        generateUltraCowBodyFront(4, 4, highlandGinger, highlandLightRed, highlandDarkRed);
        generateUltraCowBodyBack(5, 4, highlandDarkRed, highlandLightRed, highlandRed);
        generateUltraCowBodySide(6, 4, highlandGinger, highlandLightRed, highlandDarkRed, true);
        generateUltraCowBodySide(7, 4, highlandGinger, highlandLightRed, highlandDarkRed, false);
        generateUltraCowLegFront(8, 4, highlandDarkRed, highlandRed, highlandBlack);
        generateUltraCowLegBack(9, 4, highlandDarkRed, highlandRed, highlandBlack);
        generateUltraCowLegSide(10, 4, highlandDarkRed, highlandRed, highlandBlack, true);
        generateUltraCowLegSide(11, 4, highlandDarkRed, highlandRed, highlandBlack, false);
        generateUltraCowTail(12, 4, highlandDarkRed, highlandBlack, highlandRed);
        generateUltraCowUdder(13, 4, highlandPink, highlandDarkPink, highlandLightRed);
        generateUltraCowHorns(14, 4, new Color(245, 245, 220), highlandGray, highlandBlack);
        generateUltraCowEars(15, 4, highlandRed, highlandPink, highlandDarkRed);
        
        // BILLIONAIRE BUDGET HIGHLAND HORN FACE TEXTURES (Row 8)
        generateUltraHornFace(0, 8, new Color(245, 245, 220), highlandGray, highlandBlack, "front");
        generateUltraHornFace(1, 8, new Color(245, 245, 220), highlandGray, highlandBlack, "back");
        generateUltraHornFace(2, 8, new Color(245, 245, 220), highlandGray, highlandBlack, "left");
        generateUltraHornFace(3, 8, new Color(245, 245, 220), highlandGray, highlandBlack, "right");
        generateUltraHornTip(4, 8, new Color(245, 245, 220), highlandGray, highlandBlack);
        generateUltraHornBase(5, 8, highlandRed, highlandGray, highlandBlack);
        
        // BILLIONAIRE BUDGET HIGHLAND TAIL FACE TEXTURES (Row 8 continued)
        generateUltraTailFace(6, 8, highlandDarkRed, highlandBlack, highlandRed, "front");
        generateUltraTailFace(7, 8, highlandDarkRed, highlandBlack, highlandRed, "back");
        generateUltraTailFace(8, 8, highlandDarkRed, highlandBlack, highlandRed, "left");
        generateUltraTailFace(9, 8, highlandDarkRed, highlandBlack, highlandRed, "right");
        generateUltraTailAttachment(10, 8, highlandRed, highlandDarkRed, highlandBlack);
        generateUltraTailTuft(11, 8, highlandBlack, highlandDarkRed, highlandRed);
        
        // BILLIONAIRE BUDGET HIGHLAND UDDER FACE TEXTURES (Row 8 final + Row 12)
        generateUltraUdderFace(12, 8, highlandPink, highlandDarkPink, highlandLightRed, "front");  // Udder front face
        generateUltraUdderFace(13, 8, highlandPink, highlandDarkPink, highlandLightRed, "back");   // Udder back face
        generateUltraUdderFace(14, 8, highlandPink, highlandDarkPink, highlandLightRed, "left");   // Udder left face
        generateUltraUdderFace(15, 8, highlandPink, highlandDarkPink, highlandLightRed, "right");  // Udder right face
        generateUltraUdderAttachment(0, 12, highlandLightRed, highlandPink, highlandDarkPink);     // Udder top (attachment)
        generateUltraUdderTeats(1, 12, highlandPink, highlandDarkPink, highlandLightRed);          // Udder bottom (teats)
    }
    
    
    
    
    
    
    
    
    /**
     * Generate additional cow face-specific textures.
     */
    private void generateCowFaceSpecificTextures() {
        // Head top texture (8, 0)
        generateHeadTopTexture(8, 0, new Color(139, 90, 43));
        
        // Head bottom texture (9, 0)
        generateHeadBottomTexture(9, 0, new Color(160, 110, 60));
        
        // Body top texture (8, 1) - back of cow
        generateBodyTopTexture(8, 1, new Color(120, 80, 40));
        
        // Body bottom texture (9, 1) - belly
        generateBodyBottomTexture(9, 1, new Color(180, 130, 80));
    }
    
    /**
     * Generate head top texture.
     */
    private void generateHeadTopTexture(int tileX, int tileY, Color baseColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = baseColor;
                
                // Add ear attachment areas
                if ((x <= 3 || x >= 12) && (y >= 6 && y <= 10)) {
                    pixelColor = new Color(
                        Math.max(0, baseColor.r - 20),
                        Math.max(0, baseColor.g - 15),
                        Math.max(0, baseColor.b - 10)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    /**
     * Generate head bottom texture.
     */
    private void generateHeadBottomTexture(int tileX, int tileY, Color baseColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = baseColor;
                
                // Darker bottom of head
                pixelColor = new Color(
                    (int)(baseColor.r * 0.8),
                    (int)(baseColor.g * 0.8),
                    (int)(baseColor.b * 0.8)
                );
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    /**
     * Generate body top texture (back).
     */
    private void generateBodyTopTexture(int tileX, int tileY, Color baseColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = baseColor;
                
                // Add spine/back pattern
                if (x >= 7 && x <= 8) {
                    pixelColor = new Color(
                        Math.max(0, baseColor.r - 15),
                        Math.max(0, baseColor.g - 10),
                        Math.max(0, baseColor.b - 5)
                    );
                }
                
                // Add spots
                if ((x + y * 2) % 5 == 0) {
                    if (Math.random() < 0.4) {
                        pixelColor = new Color(255, 255, 255);
                    }
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    /**
     * Generate body bottom texture (belly).
     */
    private void generateBodyBottomTexture(int tileX, int tileY, Color baseColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                // Lighter belly color
                Color pixelColor = new Color(
                    Math.min(255, baseColor.r + 30),
                    Math.min(255, baseColor.g + 20),
                    Math.min(255, baseColor.b + 10)
                );
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    /**
     * Generate pig textures (placeholder).
     */
    private void generatePigTextures() {
        // Simple pink pig texture
        fillTile(0, 3, new Color(255, 184, 184));
        fillTile(1, 3, new Color(255, 184, 184));
        fillTile(2, 3, new Color(255, 184, 184));
        fillTile(3, 3, new Color(255, 140, 140)); // Snout
    }
    
    /**
     * Generate sheep textures (placeholder).
     */
    private void generateSheepTextures() {
        // Simple white wool texture
        fillTile(0, 4, new Color(200, 200, 200));
        fillTile(1, 4, new Color(220, 220, 220));
        fillTile(2, 4, new Color(248, 248, 255));
        fillTile(3, 4, new Color(248, 248, 255));
        fillTile(2, 5, new Color(248, 248, 255));
        fillTile(3, 5, new Color(248, 248, 255));
    }
    
    /**
     * Fill an entire tile with a solid color.
     */
    private void fillTile(int tileX, int tileY, Color color) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                setPixel(startX + x, startY + y, color);
            }
        }
    }
    
    /**
     * Set a pixel in the buffer.
     */
    private void setPixel(int x, int y, Color color) {
        if (x < 0 || x >= ATLAS_PIXEL_SIZE || y < 0 || y >= ATLAS_PIXEL_SIZE) {
            return;
        }
        
        int index = (y * ATLAS_PIXEL_SIZE + x) * 4;
        pixelBuffer.put(index, (byte) color.r);
        pixelBuffer.put(index + 1, (byte) color.g);
        pixelBuffer.put(index + 2, (byte) color.b);
        pixelBuffer.put(index + 3, (byte) color.a);
    }
    
    /**
     * Upload the generated texture to GPU.
     */
    private void uploadTextureToGPU() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        
        // Set texture parameters
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        
        // Upload texture data
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            ATLAS_PIXEL_SIZE, ATLAS_PIXEL_SIZE, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixelBuffer);
        
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }
    
    /**
     * Get UV coordinates for a specific mob body part.
     */
    public float[] getUVCoordinates(MobType mobType, BodyPart bodyPart) {
        Map<BodyPart, TextureMapping> mobMappings = textureMappings.get(mobType);
        if (mobMappings == null) {
            return null;
        }
        
        TextureMapping mapping = mobMappings.get(bodyPart);
        if (mapping == null) {
            return null;
        }
        
        float tileSize = 1.0f / ATLAS_GRID_SIZE;
        float u1 = mapping.atlasX * tileSize;
        float v1 = mapping.atlasY * tileSize;
        float u2 = u1 + (mapping.width * tileSize);
        float v2 = v1 + (mapping.height * tileSize);
        
        // Return UV coordinates for a quad (bottom-left, bottom-right, top-right, top-left)
        // Flip V coordinates to fix texture inversion
        return new float[] {
            u1, v1,  // Bottom-left
            u2, v1,  // Bottom-right
            u2, v2,  // Top-right
            u1, v2   // Top-left
        };
    }
    
    /**
     * Get UV coordinates for all 6 faces of a cuboid.
     * Returns different textures for different faces based on body part.
     */
    public float[][] getFaceTextureCoords(MobType mobType, String bodyPartName) {
        float[][] faceCoords = new float[6][];
        
        if (bodyPartName.equals("cow_head")) {
            // Head: detailed face textures for each side with proper orientations
            // Face indices: 0=front, 1=back, 2=left, 3=right, 4=top, 5=bottom
            faceCoords[0] = getUVCoordinates(mobType, BodyPart.HEAD_BACK);                        // Front face (no face features)
            faceCoords[1] = rotateUVCoords180(getUVCoordinates(mobType, BodyPart.HEAD_FRONT));    // Back face (eyes/nose - actual cow face) - rotated 180°
            faceCoords[2] = rotateUVCoords90CCW(getUVCoordinates(mobType, BodyPart.HEAD_LEFT));   // Left face - rotated 90° CCW
            faceCoords[3] = rotateUVCoords90CW(getUVCoordinates(mobType, BodyPart.HEAD_RIGHT));   // Right face - rotated 90° CW
            faceCoords[4] = getUVCoordinates(mobType, BodyPart.HEAD_TOP);                         // Top face - no rotation needed
            faceCoords[5] = rotateUVCoords180(getUVCoordinates(mobType, BodyPart.HEAD_BOTTOM));   // Bottom face - rotated 180°
        } else if (bodyPartName.equals("cow_body")) {
            // Body: different textures for each face with proper orientations
            faceCoords[0] = getUVCoordinates(mobType, BodyPart.BODY_FRONT);                         // Front face
            faceCoords[1] = rotateUVCoords180(getUVCoordinates(mobType, BodyPart.BODY_BACK));       // Back face - rotated 180°
            faceCoords[2] = rotateUVCoords90CCW(getUVCoordinates(mobType, BodyPart.BODY_LEFT));     // Left face - rotated 90° CCW
            faceCoords[3] = rotateUVCoords90CW(getUVCoordinates(mobType, BodyPart.BODY_RIGHT));     // Right face - rotated 90° CW
            faceCoords[4] = getUVCoordinates(mobType, BodyPart.BODY_TOP);                           // Top face
            faceCoords[5] = rotateUVCoords180(getUVCoordinates(mobType, BodyPart.BODY_BOTTOM));     // Bottom face - rotated 180°
        } else if (bodyPartName.equals("cow_legs")) {
            // Legs: different textures for each face including hooves with proper orientations
            faceCoords[0] = getUVCoordinates(mobType, BodyPart.LEG_FRONT);                          // Front face
            faceCoords[1] = rotateUVCoords180(getUVCoordinates(mobType, BodyPart.LEG_BACK));        // Back face - rotated 180°
            faceCoords[2] = rotateUVCoords90CCW(getUVCoordinates(mobType, BodyPart.LEG_LEFT));      // Left face - rotated 90° CCW
            faceCoords[3] = rotateUVCoords90CW(getUVCoordinates(mobType, BodyPart.LEG_RIGHT));      // Right face - rotated 90° CW
            faceCoords[4] = getUVCoordinates(mobType, BodyPart.LEG_TOP);                            // Top face
            faceCoords[5] = rotateUVCoords180(getUVCoordinates(mobType, BodyPart.LEG_BOTTOM));      // Bottom face (hoof) - rotated 180°
        } else if (bodyPartName.equals("cow_horns")) {
            // BILLIONAIRE BUDGET HORNS: Ultra-detailed face-specific textures
            faceCoords[0] = getUVCoordinates(mobType, BodyPart.HORNS_FRONT);                      // Front face - custom front horn texture
            faceCoords[1] = getUVCoordinates(mobType, BodyPart.HORNS_BACK);                       // Back face - custom back horn texture
            faceCoords[2] = getUVCoordinates(mobType, BodyPart.HORNS_LEFT);                       // Left face - custom left horn texture
            faceCoords[3] = getUVCoordinates(mobType, BodyPart.HORNS_RIGHT);                      // Right face - custom right horn texture
            faceCoords[4] = getUVCoordinates(mobType, BodyPart.HORNS_TOP);                        // Top face - custom horn tip texture
            faceCoords[5] = getUVCoordinates(mobType, BodyPart.HORNS_BOTTOM);                     // Bottom face - custom horn base texture
        } else if (bodyPartName.equals("cow_tail")) {
            // BILLIONAIRE BUDGET TAIL: Ultra-detailed face-specific textures
            faceCoords[0] = getUVCoordinates(mobType, BodyPart.TAIL_FRONT);                       // Front face - custom front tail texture
            faceCoords[1] = getUVCoordinates(mobType, BodyPart.TAIL_BACK);                        // Back face - custom back tail texture
            faceCoords[2] = getUVCoordinates(mobType, BodyPart.TAIL_LEFT);                        // Left face - custom left tail texture
            faceCoords[3] = getUVCoordinates(mobType, BodyPart.TAIL_RIGHT);                       // Right face - custom right tail texture
            faceCoords[4] = getUVCoordinates(mobType, BodyPart.TAIL_TOP);                         // Top face - custom tail attachment texture
            faceCoords[5] = getUVCoordinates(mobType, BodyPart.TAIL_BOTTOM);                      // Bottom face - custom tail tuft texture
        } else if (bodyPartName.equals("cow_udder")) {
            // BILLIONAIRE BUDGET UDDER: Ultra-detailed face-specific textures
            faceCoords[0] = getUVCoordinates(mobType, BodyPart.UDDER_FRONT);                      // Front face - custom front udder texture
            faceCoords[1] = getUVCoordinates(mobType, BodyPart.UDDER_BACK);                       // Back face - custom back udder texture
            faceCoords[2] = getUVCoordinates(mobType, BodyPart.UDDER_LEFT);                       // Left face - custom left udder texture
            faceCoords[3] = getUVCoordinates(mobType, BodyPart.UDDER_RIGHT);                      // Right face - custom right udder texture
            faceCoords[4] = getUVCoordinates(mobType, BodyPart.UDDER_TOP);                        // Top face - custom udder attachment texture
            faceCoords[5] = getUVCoordinates(mobType, BodyPart.UDDER_BOTTOM);                     // Bottom face - custom udder teats texture
        } else {
            // Default: same texture for all faces (ears, etc.)
            BodyPart bodyPart = parseBodyPart(bodyPartName.replace("cow_", ""));
            float[] defaultCoords = getUVCoordinates(mobType, bodyPart);
            for (int i = 0; i < 6; i++) {
                faceCoords[i] = defaultCoords;
            }
        }
        
        return faceCoords;
    }
    
    /**
     * Bind the texture for rendering.
     */
    public void bind() {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
    }
    
    /**
     * Unbind the texture.
     */
    public void unbind() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }
    
    /**
     * Get the OpenGL texture ID.
     */
    public int getTextureId() {
        return textureId;
    }
    
    /**
     * Get texture coordinates by region name for backward compatibility.
     * @param regionName Name of the texture region (e.g., "cow_head", "cow_body")
     * @return UV coordinates array or null if not found
     */
    public float[] getTextureCoords(String regionName) {
        // Parse region name to extract mob type and body part
        if (regionName.startsWith("cow_")) {
            String partName = regionName.substring(4);
            BodyPart bodyPart = parseBodyPart(partName);
            if (bodyPart != null) {
                return getUVCoordinates(MobType.COW, bodyPart);
            }
        } else if (regionName.startsWith("pig_")) {
            String partName = regionName.substring(4);
            BodyPart bodyPart = parseBodyPart(partName);
            if (bodyPart != null) {
                return getUVCoordinates(MobType.PIG, bodyPart);
            }
        } else if (regionName.startsWith("sheep_")) {
            String partName = regionName.substring(6);
            BodyPart bodyPart = parseBodyPart(partName);
            if (bodyPart != null) {
                return getUVCoordinates(MobType.SHEEP, bodyPart);
            }
        }
        return null;
    }
    
    /**
     * Rotate UV coordinates 90 degrees clockwise.
     */
    private float[] rotateUVCoords90CW(float[] coords) {
        if (coords == null || coords.length != 8) {
            return coords;
        }
        
        // Original order: bottom-left, bottom-right, top-right, top-left
        // After 90° CW rotation: top-left, bottom-left, bottom-right, top-right
        return new float[] {
            coords[6], coords[7],  // top-left becomes bottom-left
            coords[0], coords[1],  // bottom-left becomes bottom-right
            coords[2], coords[3],  // bottom-right becomes top-right
            coords[4], coords[5]   // top-right becomes top-left
        };
    }
    
    /**
     * Rotate UV coordinates 90 degrees counter-clockwise.
     */
    private float[] rotateUVCoords90CCW(float[] coords) {
        if (coords == null || coords.length != 8) {
            return coords;
        }
        
        // Original order: bottom-left, bottom-right, top-right, top-left
        // After 90° CCW rotation: bottom-right, top-right, top-left, bottom-left
        return new float[] {
            coords[2], coords[3],  // bottom-right becomes bottom-left
            coords[4], coords[5],  // top-right becomes bottom-right
            coords[6], coords[7],  // top-left becomes top-right
            coords[0], coords[1]   // bottom-left becomes top-left
        };
    }
    
    /**
     * Rotate UV coordinates 180 degrees around the center.
     * This flips both U and V coordinates for texture rotation.
     */
    private float[] rotateUVCoords180(float[] coords) {
        if (coords == null || coords.length != 8) {
            return coords;
        }
        
        // Original order: bottom-left, bottom-right, top-right, top-left
        // After 180° rotation: top-right, top-left, bottom-left, bottom-right
        return new float[] {
            coords[4], coords[5],  // top-right becomes bottom-left
            coords[6], coords[7],  // top-left becomes bottom-right  
            coords[0], coords[1],  // bottom-left becomes top-right
            coords[2], coords[3]   // bottom-right becomes top-left
        };
    }
    
    /**
     * Parse body part name from string.
     */
    private BodyPart parseBodyPart(String partName) {
        switch (partName.toLowerCase()) {
            case "head": return BodyPart.HEAD_FRONT;
            case "head_top": return BodyPart.HEAD_TOP;
            case "head_bottom": return BodyPart.HEAD_BOTTOM;
            case "body": return BodyPart.BODY_FRONT;
            case "body_top": return BodyPart.BODY_TOP;
            case "body_bottom": return BodyPart.BODY_BOTTOM;
            case "body_side": return BodyPart.BODY_LEFT;
            case "legs": case "leg": return BodyPart.LEG_FRONT;
            case "tail": return BodyPart.TAIL;
            case "udder": return BodyPart.UDDER;
            case "horns": return BodyPart.HORNS;
            case "ears": return BodyPart.EARS;
            case "snout": return BodyPart.SNOUT;
            case "wool": return BodyPart.WOOL;
            default: return null;
        }
    }
    
    /**
     * Print debug information about the atlas for backward compatibility.
     */
    public void printDebugInfo() {
        System.out.println("=== MobTextureAtlas Debug Info ===");
        System.out.println("Atlas size: " + ATLAS_GRID_SIZE + "x" + ATLAS_GRID_SIZE + " tiles");
        System.out.println("Tile size: " + TILE_SIZE + "x" + TILE_SIZE + " pixels");
        System.out.println("Total size: " + ATLAS_PIXEL_SIZE + "x" + ATLAS_PIXEL_SIZE + " pixels");
        System.out.println("Texture ID: " + textureId);
        System.out.println("Supported mob types:");
        
        for (MobType mobType : textureMappings.keySet()) {
            System.out.println("  " + mobType + ":");
            Map<BodyPart, TextureMapping> parts = textureMappings.get(mobType);
            for (Map.Entry<BodyPart, TextureMapping> entry : parts.entrySet()) {
                TextureMapping mapping = entry.getValue();
                System.out.printf("    %s: (%d, %d) %dx%d%n", 
                    entry.getKey(), mapping.atlasX, mapping.atlasY, mapping.width, mapping.height);
            }
        }
        
        System.out.println("==================================");
    }
    
    /**
     * Clean up GPU resources.
     */
    public void cleanup() {
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
            textureId = 0;
        }
        
        if (pixelBuffer != null) {
            pixelBuffer = null;
        }
    }
    
    /**
     * Simple color class for internal use.
     */
    private static class Color {
        final int r, g, b, a;
        
        Color(int r, int g, int b) {
            this(r, g, b, 255);
        }
        
        Color(int r, int g, int b, int a) {
            this.r = Math.max(0, Math.min(255, r));
            this.g = Math.max(0, Math.min(255, g));
            this.b = Math.max(0, Math.min(255, b));
            this.a = Math.max(0, Math.min(255, a));
        }
    }
    // ULTRA-DETAILED TEXTURE GENERATION METHODS
    private void generateUltraCowFace(int tileX, int tileY, Color baseColor, Color spotColor, Color blackColor, Color pinkColor, Color darkPinkColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = baseColor;
                
                // AUTHENTIC COW FACE STRUCTURE
                // Narrow white blaze down center (classic Holstein pattern)
                if (x >= 7 && x <= 8 && y >= 1 && y <= 13) {
                    pixelColor = spotColor; // White center blaze
                }
                
                // Small white forehead patch (adjusted to not interfere with cute eyes)
                if (x >= 6 && x <= 9 && y >= 1 && y <= 2) {
                    pixelColor = spotColor; // Forehead white
                }
                
                // ULTRA-CUTE COW EYES (bigger, rounder, more expressive)
                // Left eye (larger and cuter)
                if (x >= 1 && x <= 5 && y >= 3 && y <= 7) {
                    // Eye white/sclera base
                    pixelColor = new Color(250, 245, 240);
                    
                    // Outer eye rim for definition
                    if (x == 1 || x == 5 || y == 3 || y == 7) {
                        pixelColor = new Color(
                            Math.max(0, baseColor.r - 20),
                            Math.max(0, baseColor.g - 15),
                            Math.max(0, baseColor.b - 10)
                        );
                    }
                    
                    // Beautiful brown iris (large and detailed)
                    if (x >= 2 && x <= 4 && y >= 4 && y <= 6) {
                        // Iris gradient - darker at edges, lighter in middle
                        if (x == 2 || x == 4 || y == 4 || y == 6) {
                            pixelColor = new Color(45, 35, 25); // Dark brown iris edge
                        } else {
                            pixelColor = new Color(80, 60, 40); // Medium brown iris
                        }
                        
                        // Central pupil
                        if (x == 3 && y == 5) {
                            pixelColor = blackColor; // Main pupil
                        }
                        
                        // Iris highlights for depth
                        if (x == 3 && y == 4) {
                            pixelColor = new Color(120, 90, 60); // Upper iris highlight
                        }
                    }
                    
                    // CUTE EYE SHINE (multiple highlights for sparkle effect)
                    // Primary eye shine (large)
                    if (x == 2 && y == 4) {
                        pixelColor = new Color(255, 255, 255); // Bright white shine
                    }
                    // Secondary eye shine (smaller)
                    if (x == 4 && y == 5) {
                        pixelColor = new Color(220, 220, 220); // Softer shine
                    }
                    // Tiny sparkle
                    if (x == 1 && y == 5) {
                        pixelColor = new Color(200, 200, 200); // Subtle sparkle
                    }
                    
                    // Subtle upper eyelid shadow for depth
                    if (y == 3 && x >= 2 && x <= 4) {
                        pixelColor = new Color(
                            (pixelColor.r + baseColor.r) / 2,
                            (pixelColor.g + baseColor.g) / 2,
                            (pixelColor.b + baseColor.b) / 2
                        );
                    }
                }
                
                // Right eye (larger and cuter - mirrored)
                if (x >= 10 && x <= 14 && y >= 3 && y <= 7) {
                    // Eye white/sclera base
                    pixelColor = new Color(250, 245, 240);
                    
                    // Outer eye rim for definition
                    if (x == 10 || x == 14 || y == 3 || y == 7) {
                        pixelColor = new Color(
                            Math.max(0, baseColor.r - 20),
                            Math.max(0, baseColor.g - 15),
                            Math.max(0, baseColor.b - 10)
                        );
                    }
                    
                    // Beautiful brown iris (large and detailed)
                    if (x >= 11 && x <= 13 && y >= 4 && y <= 6) {
                        // Iris gradient - darker at edges, lighter in middle
                        if (x == 11 || x == 13 || y == 4 || y == 6) {
                            pixelColor = new Color(45, 35, 25); // Dark brown iris edge
                        } else {
                            pixelColor = new Color(80, 60, 40); // Medium brown iris
                        }
                        
                        // Central pupil
                        if (x == 12 && y == 5) {
                            pixelColor = blackColor; // Main pupil
                        }
                        
                        // Iris highlights for depth
                        if (x == 12 && y == 4) {
                            pixelColor = new Color(120, 90, 60); // Upper iris highlight
                        }
                    }
                    
                    // CUTE EYE SHINE (multiple highlights for sparkle effect)
                    // Primary eye shine (large)
                    if (x == 13 && y == 4) {
                        pixelColor = new Color(255, 255, 255); // Bright white shine
                    }
                    // Secondary eye shine (smaller)
                    if (x == 11 && y == 5) {
                        pixelColor = new Color(220, 220, 220); // Softer shine
                    }
                    // Tiny sparkle
                    if (x == 14 && y == 5) {
                        pixelColor = new Color(200, 200, 200); // Subtle sparkle
                    }
                    
                    // Subtle upper eyelid shadow for depth
                    if (y == 3 && x >= 11 && x <= 13) {
                        pixelColor = new Color(
                            (pixelColor.r + baseColor.r) / 2,
                            (pixelColor.g + baseColor.g) / 2,
                            (pixelColor.b + baseColor.b) / 2
                        );
                    }
                }
                
                // PROMINENT COW MUZZLE (wide and rectangular)
                // Muzzle area - much wider than the previous design
                if (x >= 4 && x <= 11 && y >= 8 && y <= 13) {
                    // Muzzle base color (darker than face)
                    pixelColor = new Color(
                        Math.max(0, baseColor.r - 25),
                        Math.max(0, baseColor.g - 20),
                        Math.max(0, baseColor.b - 15)
                    );
                    
                    // Pink nose area (upper muzzle)
                    if (y >= 8 && y <= 10) {
                        pixelColor = pinkColor;
                        
                        // Nose bridge highlight
                        if (y == 8 && x >= 6 && x <= 9) {
                            pixelColor = new Color(
                                Math.min(255, pinkColor.r + 20),
                                Math.min(255, pinkColor.g + 15),
                                Math.min(255, pinkColor.b + 10)
                            );
                        }
                    }
                }
                
                // LARGE COW NOSTRILS (widely spaced)
                // Left nostril
                if (x >= 5 && x <= 6 && y >= 9 && y <= 10) {
                    if (x == 5 && y == 9) {
                        pixelColor = blackColor; // Nostril opening
                    } else {
                        pixelColor = darkPinkColor; // Nostril edge
                    }
                }
                // Right nostril
                if (x >= 9 && x <= 10 && y >= 9 && y <= 10) {
                    if (x == 10 && y == 9) {
                        pixelColor = blackColor; // Nostril opening
                    } else {
                        pixelColor = darkPinkColor; // Nostril edge
                    }
                }
                
                // COW MOUTH (wide, straight line)
                if (x >= 5 && x <= 10 && y == 12) {
                    pixelColor = blackColor; // Mouth line
                }
                
                // Lower muzzle/chin area
                if (x >= 6 && x <= 9 && y >= 13 && y <= 14) {
                    pixelColor = new Color(
                        Math.max(0, baseColor.r - 15),
                        Math.max(0, baseColor.g - 12),
                        Math.max(0, baseColor.b - 8)
                    );
                }
                
                // FACE SHADING FOR DEPTH
                // Cheek definition
                if ((x <= 2 || x >= 13) && y >= 6 && y <= 10) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 12),
                        Math.max(0, pixelColor.g - 9),
                        Math.max(0, pixelColor.b - 6)
                    );
                }
                
                // Forehead prominence
                if (x >= 5 && x <= 10 && y >= 2 && y <= 4) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 8),
                        Math.min(255, pixelColor.g + 6),
                        Math.min(255, pixelColor.b + 3)
                    );
                }
                
                // SUBTLE FUR TEXTURE
                // Natural fur variation
                if ((x + y * 2) % 9 == 0) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 6),
                        Math.max(0, pixelColor.g - 4),
                        Math.max(0, pixelColor.b - 3)
                    );
                } else if ((x * 2 + y) % 11 == 0) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 4),
                        Math.min(255, pixelColor.g + 3),
                        Math.min(255, pixelColor.b + 2)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowHeadBack(int tileX, int tileY, Color baseColor, Color spotColor, Color darkColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = baseColor;
                
                // ULTRA-REALISTIC BACK-OF-HEAD PATTERN
                // Main white blaze continuation
                if (x >= 6 && x <= 9 && y >= 0 && y <= 8) {
                    pixelColor = spotColor;
                }
                
                // Detailed spot pattern (Holstein-style)
                // Large irregular spots
                if ((x >= 1 && x <= 4 && y >= 3 && y <= 8) || 
                    (x >= 11 && x <= 14 && y >= 2 && y <= 7)) {
                    pixelColor = spotColor;
                }
                
                // Small accent spots
                if ((x >= 2 && x <= 3 && y >= 10 && y <= 12) || 
                    (x >= 12 && x <= 13 && y >= 9 && y <= 11)) {
                    pixelColor = spotColor;
                }
                
                // SKULL CONTOUR AND DEPTH
                // Central ridge (skull structure)
                if (x >= 7 && x <= 8 && y >= 2 && y <= 6) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 15),
                        Math.max(0, pixelColor.g - 12),
                        Math.max(0, pixelColor.b - 8)
                    );
                }
                
                // Ear attachment shadows
                if ((x <= 2 || x >= 13) && y >= 8 && y <= 12) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 20),
                        Math.max(0, pixelColor.g - 15),
                        Math.max(0, pixelColor.b - 10)
                    );
                }
                
                // Neck shadow at bottom
                if (y >= 13) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 25),
                        Math.max(0, pixelColor.g - 20),
                        Math.max(0, pixelColor.b - 15)
                    );
                }
                
                // FUR TEXTURE DETAILS
                // Subtle directional fur pattern
                if ((x * 2 + y) % 9 == 0) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 6),
                        Math.max(0, pixelColor.g - 4),
                        Math.max(0, pixelColor.b - 3)
                    );
                }
                
                // Light reflection on top areas
                if (y <= 4 && (x + y) % 8 == 0) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 10),
                        Math.min(255, pixelColor.g + 8),
                        Math.min(255, pixelColor.b + 5)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowHeadSide(int tileX, int tileY, Color baseColor, Color spotColor, Color darkColor, boolean isLeft) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = baseColor;
                
                // ULTRA-DETAILED SIDE PROFILE FEATURES
                // Realistic white face marking extension
                if (isLeft) {
                    // Left side - white blaze visible from angle
                    if (x >= 10 && x <= 15 && y >= 1 && y <= 10) {
                        pixelColor = spotColor;
                    }
                    // Eye area marking
                    if (x >= 8 && x <= 11 && y >= 3 && y <= 6) {
                        pixelColor = spotColor;
                    }
                } else {
                    // Right side - mirror pattern
                    if (x >= 0 && x <= 5 && y >= 1 && y <= 10) {
                        pixelColor = spotColor;
                    }
                    // Eye area marking
                    if (x >= 4 && x <= 7 && y >= 3 && y <= 6) {
                        pixelColor = spotColor;
                    }
                }
                
                // DETAILED SIDE EYE STRUCTURE
                if (isLeft) {
                    // Left side eye - partially visible
                    if (x >= 11 && x <= 13 && y >= 4 && y <= 6) {
                        if (x == 12 && y == 5) {
                            pixelColor = new Color(0, 0, 0); // Pupil
                        } else if (x == 11 && y == 5) {
                            pixelColor = new Color(40, 30, 20); // Iris edge
                        } else if (x == 12 && y == 4) {
                            pixelColor = new Color(255, 255, 255, 120); // Highlight
                        } else {
                            pixelColor = new Color(60, 45, 35); // Eye area
                        }
                    }
                } else {
                    // Right side eye - partially visible
                    if (x >= 2 && x <= 4 && y >= 4 && y <= 6) {
                        if (x == 3 && y == 5) {
                            pixelColor = new Color(0, 0, 0); // Pupil
                        } else if (x == 4 && y == 5) {
                            pixelColor = new Color(40, 30, 20); // Iris edge
                        } else if (x == 3 && y == 4) {
                            pixelColor = new Color(255, 255, 255, 120); // Highlight
                        } else {
                            pixelColor = new Color(60, 45, 35); // Eye area
                        }
                    }
                }
                
                // JAW AND SKULL CONTOURS
                // Jawline definition
                if (y >= 8 && y <= 10) {
                    if (isLeft && x >= 6 && x <= 8) {
                        pixelColor = new Color(
                            Math.max(0, pixelColor.r - 18),
                            Math.max(0, pixelColor.g - 14),
                            Math.max(0, pixelColor.b - 10)
                        );
                    } else if (!isLeft && x >= 7 && x <= 9) {
                        pixelColor = new Color(
                            Math.max(0, pixelColor.r - 18),
                            Math.max(0, pixelColor.g - 14),
                            Math.max(0, pixelColor.b - 10)
                        );
                    }
                }
                
                // EAR ATTACHMENT AREA
                if (y <= 5 && ((isLeft && x <= 3) || (!isLeft && x >= 12))) {
                    pixelColor = new Color(
                        Math.max(0, baseColor.r - 20),
                        Math.max(0, baseColor.g - 15),
                        Math.max(0, baseColor.b - 10)
                    );
                }
                
                // NOSTRIL AREA (side view)
                if (y >= 8 && y <= 9) {
                    if (isLeft && x >= 12 && x <= 14) {
                        pixelColor = new Color(
                            Math.max(0, pixelColor.r - 12),
                            Math.max(0, pixelColor.g - 8),
                            Math.max(0, pixelColor.b - 6)
                        );
                    } else if (!isLeft && x >= 1 && x <= 3) {
                        pixelColor = new Color(
                            Math.max(0, pixelColor.r - 12),
                            Math.max(0, pixelColor.g - 8),
                            Math.max(0, pixelColor.b - 6)
                        );
                    }
                }
                
                // DETAILED FUR TEXTURE
                // Directional fur flow
                int furPattern = (x + y * 2) % 7;
                if (furPattern == 0) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 8),
                        Math.max(0, pixelColor.g - 6),
                        Math.max(0, pixelColor.b - 4)
                    );
                } else if (furPattern == 3) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 6),
                        Math.min(255, pixelColor.g + 4),
                        Math.min(255, pixelColor.b + 2)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowHeadTop(int tileX, int tileY, Color baseColor, Color spotColor, Color darkColor, Color hornColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = baseColor;
                
                // ULTRA-REALISTIC TOP-OF-HEAD PATTERN
                // Central white blaze (top view)
                if (x >= 6 && x <= 9 && y >= 8 && y <= 15) {
                    pixelColor = spotColor;
                }
                
                // Forehead white patches
                if ((x >= 2 && x <= 5 && y >= 6 && y <= 10) || 
                    (x >= 10 && x <= 13 && y >= 6 && y <= 10)) {
                    pixelColor = spotColor;
                }
                
                // DETAILED HORN ATTACHMENT POINTS
                // Left horn base
                if (x >= 3 && x <= 5 && y >= 1 && y <= 4) {
                    if (x == 4 && y >= 2 && y <= 3) {
                        pixelColor = hornColor; // Horn base
                    } else {
                        pixelColor = new Color(
                            Math.max(0, baseColor.r - 25),
                            Math.max(0, baseColor.g - 20),
                            Math.max(0, baseColor.b - 15)
                        ); // Deep horn socket
                    }
                }
                // Right horn base
                if (x >= 10 && x <= 12 && y >= 1 && y <= 4) {
                    if (x == 11 && y >= 2 && y <= 3) {
                        pixelColor = hornColor; // Horn base
                    } else {
                        pixelColor = new Color(
                            Math.max(0, baseColor.r - 25),
                            Math.max(0, baseColor.g - 20),
                            Math.max(0, baseColor.b - 15)
                        ); // Deep horn socket
                    }
                }
                
                // SKULL STRUCTURE AND RIDGES
                // Central skull ridge (frontal bone)
                if (x >= 7 && x <= 8 && y >= 1 && y <= 8) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 15),
                        Math.max(0, pixelColor.g - 12),
                        Math.max(0, pixelColor.b - 8)
                    );
                }
                
                // Temporal ridges (side skull bones)
                if ((x >= 5 && x <= 6 && y >= 3 && y <= 7) || 
                    (x >= 9 && x <= 10 && y >= 3 && y <= 7)) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 10),
                        Math.max(0, pixelColor.g - 8),
                        Math.max(0, pixelColor.b - 5)
                    );
                }
                
                // EAR ATTACHMENT AREAS (detailed)
                // Left ear attachment
                if (x <= 2 && y >= 10 && y <= 14) {
                    if (x <= 1) {
                        pixelColor = new Color(
                            Math.max(0, baseColor.r - 30),
                            Math.max(0, baseColor.g - 25),
                            Math.max(0, baseColor.b - 20)
                        ); // Deep ear socket
                    } else {
                        pixelColor = new Color(
                            Math.max(0, baseColor.r - 18),
                            Math.max(0, baseColor.g - 14),
                            Math.max(0, baseColor.b - 10)
                        ); // Ear edge
                    }
                }
                // Right ear attachment
                if (x >= 13 && y >= 10 && y <= 14) {
                    if (x >= 14) {
                        pixelColor = new Color(
                            Math.max(0, baseColor.r - 30),
                            Math.max(0, baseColor.g - 25),
                            Math.max(0, baseColor.b - 20)
                        ); // Deep ear socket
                    } else {
                        pixelColor = new Color(
                            Math.max(0, baseColor.r - 18),
                            Math.max(0, baseColor.g - 14),
                            Math.max(0, baseColor.b - 10)
                        ); // Ear edge
                    }
                }
                
                // DETAILED FUR TEXTURE AND GROWTH PATTERNS
                // Natural fur whorl patterns
                int whorlPattern = (int)(Math.sin(x * 0.4) * Math.cos(y * 0.3) * 10);
                if (whorlPattern > 5) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 6),
                        Math.max(0, pixelColor.g - 4),
                        Math.max(0, pixelColor.b - 3)
                    );
                } else if (whorlPattern < -5) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 8),
                        Math.min(255, pixelColor.g + 6),
                        Math.min(255, pixelColor.b + 3)
                    );
                }
                
                // Highlight on raised areas
                if (y <= 6 && (x >= 6 && x <= 9)) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 12),
                        Math.min(255, pixelColor.g + 10),
                        Math.min(255, pixelColor.b + 6)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowHeadBottom(int tileX, int tileY, Color baseColor, Color lightColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                // ULTRA-DETAILED UNDERSIDE OF HEAD
                Color pixelColor = lightColor; // Start with lighter base
                
                // WHITE THROAT MARKING
                if (x >= 5 && x <= 10 && y >= 4 && y <= 12) {
                    pixelColor = new Color(248, 245, 240); // Cream white throat
                }
                
                // DETAILED JAW STRUCTURE
                // Upper jaw definition
                if (y >= 1 && y <= 3) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 20),
                        Math.max(0, pixelColor.g - 15),
                        Math.max(0, pixelColor.b - 10)
                    );
                }
                
                // Lower jaw muscles
                if ((x >= 2 && x <= 5 && y >= 6 && y <= 9) || 
                    (x >= 10 && x <= 13 && y >= 6 && y <= 9)) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 12),
                        Math.max(0, pixelColor.g - 9),
                        Math.max(0, pixelColor.b - 6)
                    );
                }
                
                // THROAT CONTOURS
                // Central throat groove
                if (x >= 7 && x <= 8 && y >= 8 && y <= 14) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 15),
                        Math.max(0, pixelColor.g - 12),
                        Math.max(0, pixelColor.b - 8)
                    );
                }
                
                // Throat side contours
                if ((x >= 4 && x <= 5 && y >= 10 && y <= 13) || 
                    (x >= 10 && x <= 11 && y >= 10 && y <= 13)) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 10),
                        Math.max(0, pixelColor.g - 8),
                        Math.max(0, pixelColor.b - 5)
                    );
                }
                
                // DEWLAP AREA (cattle have dewlaps)
                if (y >= 13) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 18),
                        Math.max(0, pixelColor.g - 14),
                        Math.max(0, pixelColor.b - 10)
                    );
                    
                    // Dewlap fold detail
                    if (x >= 6 && x <= 9) {
                        pixelColor = new Color(
                            Math.max(0, pixelColor.r - 8),
                            Math.max(0, pixelColor.g - 6),
                            Math.max(0, pixelColor.b - 4)
                        );
                    }
                }
                
                // SUBTLE SKIN TEXTURE
                // Natural skin fold patterns
                int skinPattern = (x * 3 + y * 2) % 11;
                if (skinPattern == 0) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 8),
                        Math.max(0, pixelColor.g - 6),
                        Math.max(0, pixelColor.b - 4)
                    );
                } else if (skinPattern == 5) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 6),
                        Math.min(255, pixelColor.g + 4),
                        Math.min(255, pixelColor.b + 2)
                    );
                }
                
                // Soft lighting on throat
                if (y >= 6 && y <= 10 && x >= 6 && x <= 9) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 8),
                        Math.min(255, pixelColor.g + 6),
                        Math.min(255, pixelColor.b + 3)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowBodyFront(int tileX, int tileY, Color baseColor, Color spotColor, Color darkColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = baseColor;
                
                // ULTRA-REALISTIC HOLSTEIN SPOT PATTERN - CHEST
                // Large central white patch on chest
                if (x >= 4 && x <= 11 && y >= 2 && y <= 13) {
                    pixelColor = spotColor;
                }
                
                // Irregular spot edges for realism
                if ((x == 3 && y >= 4 && y <= 10) || (x == 12 && y >= 5 && y <= 9)) {
                    pixelColor = spotColor;
                }
                if ((y == 1 && x >= 6 && x <= 9) || (y == 14 && x >= 5 && x <= 10)) {
                    pixelColor = spotColor;
                }
                
                // CHEST MUSCLE DEFINITION
                // Pectoral muscle separation
                if (x >= 7 && x <= 8 && y >= 6 && y <= 10) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 12),
                        Math.max(0, pixelColor.g - 9),
                        Math.max(0, pixelColor.b - 6)
                    );
                }
                
                // DETAILED FUR TEXTURE
                // Natural fur direction and depth
                int furDepth = (x * 2 + y) % 8;
                if (furDepth == 0) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 10),
                        Math.max(0, pixelColor.g - 8),
                        Math.max(0, pixelColor.b - 5)
                    );
                } else if (furDepth == 4) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 8),
                        Math.min(255, pixelColor.g + 6),
                        Math.min(255, pixelColor.b + 3)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowBodyBack(int tileX, int tileY, Color baseColor, Color spotColor, Color darkColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = baseColor;
                
                // ULTRA-REALISTIC BACK PATTERN
                // Holstein back spotting pattern
                if ((x >= 1 && x <= 6 && y >= 3 && y <= 9) || 
                    (x >= 9 && x <= 14 && y >= 1 && y <= 7)) {
                    pixelColor = spotColor;
                }
                
                // Small accent spots
                if ((x >= 3 && x <= 4 && y >= 11 && y <= 13) || 
                    (x >= 11 && x <= 12 && y >= 9 && y <= 11)) {
                    pixelColor = spotColor;
                }
                
                // SPINE AND BACK DEFINITION
                // Spinal ridge
                if (x >= 7 && x <= 8 && y >= 2 && y <= 12) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 15),
                        Math.max(0, pixelColor.g - 12),
                        Math.max(0, pixelColor.b - 8)
                    );
                }
                
                // Shoulder blade definition
                if ((x >= 4 && x <= 6 && y >= 4 && y <= 8) || 
                    (x >= 9 && x <= 11 && y >= 4 && y <= 8)) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 8),
                        Math.max(0, pixelColor.g - 6),
                        Math.max(0, pixelColor.b - 4)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowBodySide(int tileX, int tileY, Color baseColor, Color spotColor, Color darkColor, boolean isLeft) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = baseColor;
                
                // ULTRA-DETAILED SIDE PATTERN
                // Large Holstein side patches
                if (isLeft) {
                    // Left side pattern
                    if ((x >= 2 && x <= 8 && y >= 2 && y <= 8) || 
                        (x >= 10 && x <= 14 && y >= 6 && y <= 12)) {
                        pixelColor = spotColor;
                    }
                } else {
                    // Right side pattern (mirrored)
                    if ((x >= 1 && x <= 5 && y >= 6 && y <= 12) || 
                        (x >= 7 && x <= 13 && y >= 2 && y <= 8)) {
                        pixelColor = spotColor;
                    }
                }
                
                // RIB CAGE DEFINITION
                // Subtle rib lines
                if (y >= 6 && y <= 12 && (x + y * 2) % 6 == 0) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 8),
                        Math.max(0, pixelColor.g - 6),
                        Math.max(0, pixelColor.b - 4)
                    );
                }
                
                // BELLY CURVE SHADOW
                if (y >= 11) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 12),
                        Math.max(0, pixelColor.g - 9),
                        Math.max(0, pixelColor.b - 6)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowBodyTop(int tileX, int tileY, Color baseColor, Color spotColor, Color darkColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = baseColor;
                
                // ULTRA-DETAILED BACK/TOP VIEW
                // Classic Holstein back pattern
                if ((x >= 2 && x <= 6 && y >= 3 && y <= 10) || 
                    (x >= 9 && x <= 13 && y >= 5 && y <= 12)) {
                    pixelColor = spotColor;
                }
                
                // Hip bone prominence
                if ((x >= 3 && x <= 4 && y >= 8 && y <= 10) || 
                    (x >= 11 && x <= 12 && y >= 8 && y <= 10)) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 10),
                        Math.min(255, pixelColor.g + 8),
                        Math.min(255, pixelColor.b + 5)
                    );
                }
                
                // Spinal column
                if (x >= 7 && x <= 8) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 12),
                        Math.max(0, pixelColor.g - 9),
                        Math.max(0, pixelColor.b - 6)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowBodyBottom(int tileX, int tileY, Color bellyColor, Color spotColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = bellyColor;
                
                // ULTRA-REALISTIC BELLY
                // Large white belly patch (Holstein characteristic)
                if (x >= 3 && x <= 12 && y >= 2 && y <= 13) {
                    pixelColor = spotColor;
                }
                
                // UDDER ATTACHMENT AREA
                if (y >= 11 && x >= 6 && x <= 9) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 15),
                        Math.max(0, pixelColor.g - 12),
                        Math.max(0, pixelColor.b - 8)
                    );
                }
                
                // Belly center line
                if (x >= 7 && x <= 8 && y >= 4 && y <= 10) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 8),
                        Math.max(0, pixelColor.g - 6),
                        Math.max(0, pixelColor.b - 4)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowLegFront(int tileX, int tileY, Color legColor, Color lightColor, Color hoofColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = legColor;
                
                // ULTRA-DETAILED LEG FRONT VIEW
                // White sock marking (common in Holstein)
                if (y >= 10 && x >= 4 && x <= 11) {
                    pixelColor = new Color(248, 245, 240); // Cream white sock
                }
                
                // HOOF AREA
                if (y >= 13) {
                    if (x >= 5 && x <= 10) {
                        pixelColor = hoofColor; // Main hoof
                    } else if (x >= 4 && x <= 11) {
                        pixelColor = new Color(60, 50, 40); // Hoof edge/coronet
                    }
                }
                
                // MUSCLE AND BONE DEFINITION
                // Cannon bone (lower leg)
                if (y >= 8 && y <= 12 && x >= 6 && x <= 9) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 8),
                        Math.min(255, pixelColor.g + 6),
                        Math.min(255, pixelColor.b + 3)
                    ); // Bone prominence
                }
                
                // Knee/fetlock joint
                if (y >= 6 && y <= 8 && x >= 5 && x <= 10) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 12),
                        Math.max(0, pixelColor.g - 9),
                        Math.max(0, pixelColor.b - 6)
                    ); // Joint depression
                }
                
                // FUR TEXTURE AND DIRECTION
                // Hair growth direction
                int furFlow = (y * 2 + x) % 7;
                if (furFlow == 0) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 8),
                        Math.max(0, pixelColor.g - 6),
                        Math.max(0, pixelColor.b - 4)
                    );
                } else if (furFlow == 3) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 6),
                        Math.min(255, pixelColor.g + 4),
                        Math.min(255, pixelColor.b + 2)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowLegBack(int tileX, int tileY, Color legColor, Color lightColor, Color hoofColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = legColor;
                
                // ULTRA-DETAILED LEG BACK VIEW
                // White sock marking (back view)
                if (y >= 10 && x >= 4 && x <= 11) {
                    pixelColor = new Color(248, 245, 240);
                }
                
                // HOOF BACK VIEW
                if (y >= 13) {
                    if (x >= 5 && x <= 10) {
                        pixelColor = hoofColor;
                    } else if (x >= 4 && x <= 11) {
                        pixelColor = new Color(60, 50, 40);
                    }
                }
                
                // ACHILLES TENDON AREA
                if (y >= 6 && y <= 12 && x >= 7 && x <= 8) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 15),
                        Math.max(0, pixelColor.g - 12),
                        Math.max(0, pixelColor.b - 8)
                    ); // Tendon definition
                }
                
                // Calf muscle definition
                if (y >= 2 && y <= 6 && x >= 5 && x <= 10) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 10),
                        Math.min(255, pixelColor.g + 8),
                        Math.min(255, pixelColor.b + 5)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowLegSide(int tileX, int tileY, Color legColor, Color lightColor, Color hoofColor, boolean isLeft) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = legColor;
                
                // ULTRA-DETAILED LEG SIDE VIEW
                // White sock pattern (side view)
                if (y >= 10) {
                    if (isLeft && x >= 2 && x <= 9) {
                        pixelColor = new Color(248, 245, 240);
                    } else if (!isLeft && x >= 6 && x <= 13) {
                        pixelColor = new Color(248, 245, 240);
                    }
                }
                
                // HOOF SIDE VIEW
                if (y >= 13) {
                    if ((isLeft && x >= 3 && x <= 8) || (!isLeft && x >= 7 && x <= 12)) {
                        pixelColor = hoofColor;
                    }
                }
                
                // LEG PROFILE ANATOMY
                // Shin bone edge
                if (isLeft && x >= 8 && x <= 9 && y >= 7 && y <= 11) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 12),
                        Math.min(255, pixelColor.g + 9),
                        Math.min(255, pixelColor.b + 6)
                    );
                } else if (!isLeft && x >= 6 && x <= 7 && y >= 7 && y <= 11) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 12),
                        Math.min(255, pixelColor.g + 9),
                        Math.min(255, pixelColor.b + 6)
                    );
                }
                
                // Joint articulation
                if (y >= 5 && y <= 7) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 10),
                        Math.max(0, pixelColor.g - 8),
                        Math.max(0, pixelColor.b - 5)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowLegTop(int tileX, int tileY, Color legColor, Color lightColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = legColor;
                
                // ULTRA-DETAILED LEG TOP VIEW
                // Circular cross-section with bone structure
                float centerX = TILE_SIZE * 0.5f;
                float centerY = TILE_SIZE * 0.5f;
                float distance = (float)Math.sqrt(Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2));
                
                // Bone prominence in center
                if (distance <= 3) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 15),
                        Math.min(255, pixelColor.g + 12),
                        Math.min(255, pixelColor.b + 8)
                    );
                }
                
                // Muscle around bone
                if (distance <= 6 && distance > 3) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 8),
                        Math.min(255, pixelColor.g + 6),
                        Math.min(255, pixelColor.b + 3)
                    );
                }
                
                // Edge darkening
                if (distance > 6) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 12),
                        Math.max(0, pixelColor.g - 9),
                        Math.max(0, pixelColor.b - 6)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraHoof(int tileX, int tileY, Color hoofColor, Color grayColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = hoofColor;
                
                // ULTRA-REALISTIC HOOF STRUCTURE
                // Cloven hoof (split in two)
                boolean isLeftCleft = x >= 2 && x <= 6;
                boolean isRightCleft = x >= 9 && x <= 13;
                
                if (isLeftCleft || isRightCleft) {
                    // Main hoof wall
                    if (y <= 10) {
                        pixelColor = hoofColor;
                    }
                    // Sole area
                    else if (y >= 11 && y <= 13) {
                        pixelColor = new Color(
                            Math.max(0, hoofColor.r - 20),
                            Math.max(0, hoofColor.g - 15),
                            Math.max(0, hoofColor.b - 10)
                        );
                    }
                    // Frog (soft center part)
                    else {
                        pixelColor = new Color(80, 70, 60);
                    }
                    
                    // Hoof wall ridges
                    if ((x + y) % 4 == 0 && y <= 8) {
                        pixelColor = new Color(
                            Math.max(0, pixelColor.r - 15),
                            Math.max(0, pixelColor.g - 12),
                            Math.max(0, pixelColor.b - 8)
                        );
                    }
                    
                    // Coronet band (top edge)
                    if (y <= 2) {
                        pixelColor = grayColor;
                    }
                } else {
                    // Central cleft (split between hooves)
                    if (x >= 7 && x <= 8) {
                        pixelColor = new Color(40, 30, 20); // Dark cleft
                    } else {
                        pixelColor = new Color(0, 0, 0, 0); // Transparent
                    }
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowTail(int tileX, int tileY, Color tailColor, Color tuftColor, Color lightColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = new Color(0, 0, 0, 0); // Start transparent
                
                // ULTRA-REALISTIC TAIL STRUCTURE
                // Tail base (thick at top)
                if (y <= 4) {
                    int centerX = TILE_SIZE / 2;
                    int tailWidth = 6; // Thick at base
                    if (Math.abs(x - centerX) <= tailWidth / 2) {
                        pixelColor = tailColor;
                        
                        // Base attachment shading
                        if (y <= 1) {
                            pixelColor = new Color(
                                Math.max(0, tailColor.r - 20),
                                Math.max(0, tailColor.g - 15),
                                Math.max(0, tailColor.b - 10)
                            );
                        }
                    }
                }
                // Mid tail (tapering)
                else if (y <= 10) {
                    int centerX = TILE_SIZE / 2;
                    int tailWidth = 5 - (y - 4); // Gradually thinner
                    if (Math.abs(x - centerX) <= tailWidth / 2) {
                        pixelColor = tailColor;
                    }
                }
                // Tail tuft (bushy end)
                else {
                    int centerX = TILE_SIZE / 2;
                    int tuftWidth = 7; // Wider tuft
                    if (Math.abs(x - centerX) <= tuftWidth / 2) {
                        // Mix of tail color and black tuft
                        if ((x + y) % 3 == 0) {
                            pixelColor = tuftColor; // Black hair
                        } else {
                            pixelColor = new Color(
                                (tailColor.r + tuftColor.r) / 2,
                                (tailColor.g + tuftColor.g) / 2,
                                (tailColor.b + tuftColor.b) / 2
                            );
                        }
                        
                        // Tuft texture variation
                        if ((x * y) % 5 == 0) {
                            pixelColor = tuftColor;
                        }
                    }
                }
                
                // HAIR TEXTURE AND DIRECTION
                if (pixelColor.a > 0) { // Only if not transparent
                    // Natural hair flow lines
                    int hairPattern = (y * 2 + x) % 6;
                    if (hairPattern == 0) {
                        pixelColor = new Color(
                            Math.max(0, pixelColor.r - 12),
                            Math.max(0, pixelColor.g - 9),
                            Math.max(0, pixelColor.b - 6)
                        );
                    } else if (hairPattern == 3) {
                        pixelColor = new Color(
                            Math.min(255, pixelColor.r + 8),
                            Math.min(255, pixelColor.g + 6),
                            Math.min(255, pixelColor.b + 3)
                        );
                    }
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowUdder(int tileX, int tileY, Color udderColor, Color darkPinkColor, Color lightColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = new Color(0, 0, 0, 0); // Start transparent
                
                // ULTRA-REALISTIC UDDER STRUCTURE
                float centerX = TILE_SIZE * 0.5f;
                float centerY = TILE_SIZE * 0.6f; // Slightly lower
                float udderRadius = TILE_SIZE * 0.4f;
                
                float distance = (float)Math.sqrt(Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2));
                
                // Main udder body
                if (distance <= udderRadius) {
                    pixelColor = udderColor;
                    
                    // REALISTIC SHADING AND FORM
                    // Top highlight (light source from above)
                    if (y <= centerY - udderRadius * 0.3f) {
                        pixelColor = new Color(
                            Math.min(255, udderColor.r + 20),
                            Math.min(255, udderColor.g + 15),
                            Math.min(255, udderColor.b + 10)
                        );
                    }
                    // Bottom shadow
                    else if (y >= centerY + udderRadius * 0.5f) {
                        pixelColor = new Color(
                            Math.max(0, udderColor.r - 25),
                            Math.max(0, udderColor.g - 20),
                            Math.max(0, udderColor.b - 15)
                        );
                    }
                    
                    // TEAT POSITIONS (4 teats)
                    // Front left teat
                    if (x >= centerX - 6 && x <= centerX - 3 && y >= centerY + 3 && y <= centerY + 8) {
                        pixelColor = darkPinkColor;
                        // Teat tip
                        if (y >= centerY + 6) {
                            pixelColor = new Color(
                                Math.max(0, darkPinkColor.r - 15),
                                Math.max(0, darkPinkColor.g - 12),
                                Math.max(0, darkPinkColor.b - 8)
                            );
                        }
                    }
                    // Front right teat
                    if (x >= centerX + 3 && x <= centerX + 6 && y >= centerY + 3 && y <= centerY + 8) {
                        pixelColor = darkPinkColor;
                        if (y >= centerY + 6) {
                            pixelColor = new Color(
                                Math.max(0, darkPinkColor.r - 15),
                                Math.max(0, darkPinkColor.g - 12),
                                Math.max(0, darkPinkColor.b - 8)
                            );
                        }
                    }
                    // Back left teat
                    if (x >= centerX - 4 && x <= centerX - 1 && y >= centerY + 1 && y <= centerY + 6) {
                        pixelColor = darkPinkColor;
                    }
                    // Back right teat
                    if (x >= centerX + 1 && x <= centerX + 4 && y >= centerY + 1 && y <= centerY + 6) {
                        pixelColor = darkPinkColor;
                    }
                }
                
                // UDDER ATTACHMENT LINES
                // Connection to belly
                if (y <= 3 && x >= centerX - 3 && x <= centerX + 3) {
                    pixelColor = new Color(
                        (udderColor.r + lightColor.r) / 2,
                        (udderColor.g + lightColor.g) / 2,
                        (udderColor.b + lightColor.b) / 2
                    );
                }
                
                // SKIN TEXTURE
                if (pixelColor.a > 0) {
                    // Natural skin texture
                    int skinTexture = (x * 3 + y * 2) % 8;
                    if (skinTexture == 0) {
                        pixelColor = new Color(
                            Math.max(0, pixelColor.r - 8),
                            Math.max(0, pixelColor.g - 6),
                            Math.max(0, pixelColor.b - 4)
                        );
                    } else if (skinTexture == 4) {
                        pixelColor = new Color(
                            Math.min(255, pixelColor.r + 6),
                            Math.min(255, pixelColor.g + 4),
                            Math.min(255, pixelColor.b + 2)
                        );
                    }
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowHorns(int tileX, int tileY, Color hornColor, Color grayColor, Color blackColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = new Color(0, 0, 0, 0); // Start transparent
                
                // ULTRA-REALISTIC HORN STRUCTURE
                // Left horn
                boolean inLeftHorn = false;
                if (x >= 2 && x <= 6 && y >= 1 && y <= 14) {
                    // Horn shape - wider at base, tapered at tip
                    int hornWidth = 5 - (y / 4); // Tapers from 5 to 2 pixels wide
                    int hornCenter = 4;
                    if (Math.abs(x - hornCenter) <= hornWidth / 2) {
                        inLeftHorn = true;
                        pixelColor = hornColor;
                        
                        // Horn base (darker)
                        if (y >= 12) {
                            pixelColor = grayColor;
                        }
                        // Horn tip (pointed)
                        else if (y <= 3) {
                            pixelColor = new Color(
                                Math.min(255, hornColor.r + 15),
                                Math.min(255, hornColor.g + 12),
                                Math.min(255, hornColor.b + 8)
                            );
                        }
                        
                        // Horn ridges (growth rings)
                        if ((y + 2) % 4 == 0) {
                            pixelColor = new Color(
                                Math.max(0, pixelColor.r - 20),
                                Math.max(0, pixelColor.g - 15),
                                Math.max(0, pixelColor.b - 10)
                            );
                        }
                    }
                }
                
                // Right horn
                boolean inRightHorn = false;
                if (x >= 9 && x <= 13 && y >= 1 && y <= 14) {
                    int hornWidth = 5 - (y / 4);
                    int hornCenter = 11;
                    if (Math.abs(x - hornCenter) <= hornWidth / 2) {
                        inRightHorn = true;
                        pixelColor = hornColor;
                        
                        if (y >= 12) {
                            pixelColor = grayColor;
                        }
                        else if (y <= 3) {
                            pixelColor = new Color(
                                Math.min(255, hornColor.r + 15),
                                Math.min(255, hornColor.g + 12),
                                Math.min(255, hornColor.b + 8)
                            );
                        }
                        
                        if ((y + 2) % 4 == 0) {
                            pixelColor = new Color(
                                Math.max(0, pixelColor.r - 20),
                                Math.max(0, pixelColor.g - 15),
                                Math.max(0, pixelColor.b - 10)
                            );
                        }
                    }
                }
                
                // HORN SHADING AND HIGHLIGHTS
                if (inLeftHorn || inRightHorn) {
                    // Left side lighting
                    if ((inLeftHorn && x <= 3) || (inRightHorn && x <= 10)) {
                        pixelColor = new Color(
                            Math.min(255, pixelColor.r + 12),
                            Math.min(255, pixelColor.g + 9),
                            Math.min(255, pixelColor.b + 6)
                        );
                    }
                    // Right side shadow
                    else if ((inLeftHorn && x >= 5) || (inRightHorn && x >= 12)) {
                        pixelColor = new Color(
                            Math.max(0, pixelColor.r - 15),
                            Math.max(0, pixelColor.g - 12),
                            Math.max(0, pixelColor.b - 8)
                        );
                    }
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    private void generateUltraCowEars(int tileX, int tileY, Color earColor, Color innerEarColor, Color darkColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = new Color(0, 0, 0, 0); // Start transparent
                
                // ULTRA-REALISTIC EAR STRUCTURE
                // Left ear
                boolean inLeftEar = false;
                if (x >= 1 && x <= 7 && y >= 4 && y <= 12) {
                    // Ear shape - oval/teardrop
                    float earCenterX = 4;
                    float earCenterY = 8;
                    float earWidth = 3;
                    float earHeight = 4;
                    
                    float normalizedX = (x - earCenterX) / earWidth;
                    float normalizedY = (y - earCenterY) / earHeight;
                    
                    if (normalizedX * normalizedX + normalizedY * normalizedY <= 1.0f) {
                        inLeftEar = true;
                        pixelColor = earColor;
                        
                        // Inner ear (pink area)
                        if (normalizedX * normalizedX + normalizedY * normalizedY <= 0.4f) {
                            pixelColor = innerEarColor;
                        }
                        
                        // Ear canal shadow
                        if (x >= 3 && x <= 5 && y >= 7 && y <= 9) {
                            pixelColor = new Color(
                                Math.max(0, pixelColor.r - 30),
                                Math.max(0, pixelColor.g - 25),
                                Math.max(0, pixelColor.b - 20)
                            );
                        }
                    }
                }
                
                // Right ear
                boolean inRightEar = false;
                if (x >= 8 && x <= 14 && y >= 4 && y <= 12) {
                    float earCenterX = 11;
                    float earCenterY = 8;
                    float earWidth = 3;
                    float earHeight = 4;
                    
                    float normalizedX = (x - earCenterX) / earWidth;
                    float normalizedY = (y - earCenterY) / earHeight;
                    
                    if (normalizedX * normalizedX + normalizedY * normalizedY <= 1.0f) {
                        inRightEar = true;
                        pixelColor = earColor;
                        
                        if (normalizedX * normalizedX + normalizedY * normalizedY <= 0.4f) {
                            pixelColor = innerEarColor;
                        }
                        
                        if (x >= 10 && x <= 12 && y >= 7 && y <= 9) {
                            pixelColor = new Color(
                                Math.max(0, pixelColor.r - 30),
                                Math.max(0, pixelColor.g - 25),
                                Math.max(0, pixelColor.b - 20)
                            );
                        }
                    }
                }
                
                // EAR DETAILS AND SHADING
                if (inLeftEar || inRightEar) {
                    // Ear fur texture
                    int furTexture = (x * 2 + y) % 7;
                    if (furTexture == 0) {
                        pixelColor = new Color(
                            Math.max(0, pixelColor.r - 10),
                            Math.max(0, pixelColor.g - 8),
                            Math.max(0, pixelColor.b - 5)
                        );
                    } else if (furTexture == 3) {
                        pixelColor = new Color(
                            Math.min(255, pixelColor.r + 8),
                            Math.min(255, pixelColor.g + 6),
                            Math.min(255, pixelColor.b + 3)
                        );
                    }
                    
                    // Ear edge definition
                    if ((inLeftEar && (x == 1 || x == 7 || y == 4 || y == 12)) ||
                        (inRightEar && (x == 8 || x == 14 || y == 4 || y == 12))) {
                        pixelColor = new Color(
                            Math.max(0, pixelColor.r - 15),
                            Math.max(0, pixelColor.g - 12),
                            Math.max(0, pixelColor.b - 8)
                        );
                    }
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    /**
     * BILLIONAIRE BUDGET ULTRA-PREMIUM HORN FACE TEXTURE GENERATOR
     * Creates face-specific horn textures with unparalleled detail and realism.
     */
    private void generateUltraHornFace(int tileX, int tileY, Color hornColor, Color grayColor, Color blackColor, String face) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = hornColor; // Start with base horn color (solid texture)
                
                // Horn structure based on face
                float hornIntensity = 0.0f;
                
                switch (face) {
                    case "front":
                        // Front face - full texture with horn pattern and dramatic lighting
                        hornIntensity = 1.0f;
                        
                        // Create horn shape overlay on full texture
                        int centerX = TILE_SIZE / 2;
                        int hornWidth = Math.max(4, 8 - (y / 2)); // Tapers from 8 to 4 pixels
                        if (Math.abs(x - centerX) <= hornWidth / 2) {
                            // Main horn area - brighter
                            float lightingGradient = 1.0f - (float)y / TILE_SIZE;
                            pixelColor = new Color(
                                Math.min(255, (int)(hornColor.r * (0.9f + lightingGradient * 0.3f))),
                                Math.min(255, (int)(hornColor.g * (0.9f + lightingGradient * 0.3f))),
                                Math.min(255, (int)(hornColor.b * (0.9f + lightingGradient * 0.3f)))
                            );
                        } else {
                            // Edge areas - use base horn color but slightly darker
                            pixelColor = new Color(
                                Math.max(0, hornColor.r - 20),
                                Math.max(0, hornColor.g - 15),
                                Math.max(0, hornColor.b - 10)
                            );
                        }
                        break;
                        
                    case "back":
                        // Back face - full texture with shadowed horn pattern
                        hornIntensity = 0.7f;
                        
                        // Create shadowed horn shape on full texture
                        centerX = TILE_SIZE / 2;
                        int backHornWidth = Math.max(4, 8 - (y / 2));
                        if (Math.abs(x - centerX) <= backHornWidth / 2) {
                            // Main horn area - darker with shadow gradient
                            float shadowGradient = (float)y / TILE_SIZE;
                            pixelColor = new Color(
                                Math.max(0, (int)(hornColor.r * (0.6f + shadowGradient * 0.2f))),
                                Math.max(0, (int)(hornColor.g * (0.6f + shadowGradient * 0.2f))),
                                Math.max(0, (int)(hornColor.b * (0.6f + shadowGradient * 0.2f)))
                            );
                        } else {
                            // Edge areas - even darker
                            pixelColor = new Color(
                                Math.max(0, hornColor.r - 40),
                                Math.max(0, hornColor.g - 30),
                                Math.max(0, hornColor.b - 25)
                            );
                        }
                        break;
                        
                    case "left":
                        // Left face - full texture with side profile curved highlighting
                        hornIntensity = 0.8f;
                        
                        // Create curved side profile on full texture
                        float distanceFromEdge = Math.min(x, TILE_SIZE - 1 - x) / 8.0f;
                        float heightFactor = 1.0f - (float)y / TILE_SIZE;
                        
                        // Curved side lighting across entire texture
                        float curveLight = (float)Math.sin(distanceFromEdge * Math.PI) * 0.4f;
                        pixelColor = new Color(
                            Math.min(255, (int)(hornColor.r * (0.7f + curveLight + heightFactor * 0.2f))),
                            Math.min(255, (int)(hornColor.g * (0.7f + curveLight + heightFactor * 0.2f))),
                            Math.min(255, (int)(hornColor.b * (0.7f + curveLight + heightFactor * 0.2f)))
                        );
                        break;
                        
                    case "right":
                        // Right face - full texture with opposite side profile shading
                        hornIntensity = 0.75f;
                        
                        // Create opposite side shadow profile on full texture
                        distanceFromEdge = Math.min(x, TILE_SIZE - 1 - x) / 8.0f;
                        heightFactor = 1.0f - (float)y / TILE_SIZE;
                        
                        // Right side shadow curve across entire texture
                        float shadowCurve = (float)Math.sin(distanceFromEdge * Math.PI) * 0.3f;
                        pixelColor = new Color(
                            Math.max(0, (int)(hornColor.r * (0.6f + shadowCurve + heightFactor * 0.15f))),
                            Math.max(0, (int)(hornColor.g * (0.6f + shadowCurve + heightFactor * 0.15f))),
                            Math.max(0, (int)(hornColor.b * (0.6f + shadowCurve + heightFactor * 0.15f)))
                        );
                        break;
                }
                
                // ULTRA-PREMIUM HORN DETAILS (applied to all faces - since all are now solid)
                // Growth rings (natural horn texture)
                int ringPattern = (y + 1) / 3;
                if (ringPattern % 2 == 0) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 12),
                        Math.max(0, pixelColor.g - 10),
                        Math.max(0, pixelColor.b - 6)
                    );
                }
                
                // Natural horn striations
                int striationPattern = (x + y * 2) % 5;
                if (striationPattern == 0) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 8),
                        Math.max(0, pixelColor.g - 6),
                        Math.max(0, pixelColor.b - 4)
                    );
                }
                
                // Horn aging spots (random authentic detail)
                if ((x * 7 + y * 11) % 23 == 0 && hornIntensity > 0.5f) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 20),
                        Math.max(0, pixelColor.g - 15),
                        Math.max(0, pixelColor.b - 10)
                    );
                }
                
                // Micro-scratches and wear patterns
                if ((x * 3 + y * 5) % 13 == 0) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 5),
                        Math.min(255, pixelColor.g + 4),
                        Math.min(255, pixelColor.b + 2)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    /**
     * BILLIONAIRE BUDGET ULTRA-PREMIUM HORN TIP TEXTURE GENERATOR
     */
    private void generateUltraHornTip(int tileX, int tileY, Color hornColor, Color grayColor, Color blackColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = hornColor; // Start with solid horn color
                
                // ULTRA-REALISTIC HORN TIP (pointed end) - full texture
                float centerX = TILE_SIZE / 2.0f;
                float centerY = TILE_SIZE / 2.0f;
                float distance = (float)Math.sqrt((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY));
                
                // Create pointed tip pattern on full texture
                float tipRadius = Math.max(2, TILE_SIZE / 2.0f - y * 0.2f);
                if (distance <= tipRadius) {
                    // Central pointed area - brighter
                    float sharpness = 1.0f - distance / tipRadius;
                    pixelColor = new Color(
                        Math.min(255, (int)(hornColor.r * (0.9f + sharpness * 0.3f))),
                        Math.min(255, (int)(hornColor.g * (0.9f + sharpness * 0.3f))),
                        Math.min(255, (int)(hornColor.b * (0.9f + sharpness * 0.3f)))
                    );
                    
                    // Very sharp point highlight
                    if (distance <= 3 && y <= TILE_SIZE / 3) {
                        pixelColor = new Color(
                            Math.min(255, hornColor.r + 30),
                            Math.min(255, hornColor.g + 25),
                            Math.min(255, hornColor.b + 15)
                        );
                    }
                } else {
                    // Outer areas - base horn color with slight darkening
                    pixelColor = new Color(
                        Math.max(0, hornColor.r - 15),
                        Math.max(0, hornColor.g - 12),
                        Math.max(0, hornColor.b - 8)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    /**
     * BILLIONAIRE BUDGET ULTRA-PREMIUM HORN BASE TEXTURE GENERATOR
     */
    private void generateUltraHornBase(int tileX, int tileY, Color baseColor, Color grayColor, Color blackColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = baseColor; // Use cow head color as base
                
                // ULTRA-REALISTIC HORN ATTACHMENT AREA
                float centerX = TILE_SIZE / 2.0f;
                float centerY = TILE_SIZE / 2.0f;
                float distance = (float)Math.sqrt((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY));
                
                // Attachment ring (where horn meets skull)
                if (distance >= 4 && distance <= 7) {
                    pixelColor = new Color(
                        Math.max(0, baseColor.r - 25),
                        Math.max(0, baseColor.g - 20),
                        Math.max(0, baseColor.b - 15)
                    );
                }
                
                // Central attachment point
                if (distance <= 4) {
                    pixelColor = new Color(
                        Math.max(0, baseColor.r - 40),
                        Math.max(0, baseColor.g - 30),
                        Math.max(0, baseColor.b - 20)
                    );
                }
                
                // Bone ridge patterns
                if ((x + y) % 6 == 0 && distance <= 6) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 15),
                        Math.max(0, pixelColor.g - 12),
                        Math.max(0, pixelColor.b - 8)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    /**
     * BILLIONAIRE BUDGET ULTRA-PREMIUM TAIL FACE TEXTURE GENERATOR
     * Creates face-specific tail textures with incredible hair detail and movement.
     */
    private void generateUltraTailFace(int tileX, int tileY, Color tailColor, Color tuftColor, Color lightColor, String face) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = tailColor; // Start with solid tail color
                
                // Tail structure based on face
                float tailIntensity = 0.0f;
                
                switch (face) {
                    case "front":
                        // Front face - full texture with cylindrical tail cross-section pattern
                        tailIntensity = 1.0f;
                        
                        float centerX = TILE_SIZE / 2.0f;
                        float distance = Math.abs(x - centerX);
                        
                        // Cylindrical shading across full texture
                        if (distance <= 6) { // Central cylindrical area
                            float cylinderShade = (float)Math.cos(distance / 6.0f * Math.PI / 2) * 0.4f;
                            pixelColor = new Color(
                                Math.min(255, (int)(tailColor.r * (0.8f + cylinderShade))),
                                Math.min(255, (int)(tailColor.g * (0.8f + cylinderShade))),
                                Math.min(255, (int)(tailColor.b * (0.8f + cylinderShade)))
                            );
                        } else {
                            // Edge areas - darker base tail color
                            pixelColor = new Color(
                                Math.max(0, tailColor.r - 20),
                                Math.max(0, tailColor.g - 15),
                                Math.max(0, tailColor.b - 10)
                            );
                        }
                        break;
                        
                    case "back":
                        // Back face - full texture with opposite side shadowing
                        tailIntensity = 0.8f;
                        
                        centerX = TILE_SIZE / 2.0f;
                        distance = Math.abs(x - centerX);
                        
                        // Back shadow across full texture
                        if (distance <= 6) { // Central area
                            float backShade = (float)Math.cos(distance / 6.0f * Math.PI / 2) * 0.3f;
                            pixelColor = new Color(
                                Math.max(0, (int)(tailColor.r * (0.6f + backShade * 0.2f))),
                                Math.max(0, (int)(tailColor.g * (0.6f + backShade * 0.2f))),
                                Math.max(0, (int)(tailColor.b * (0.6f + backShade * 0.2f)))
                            );
                        } else {
                            // Edge areas - even darker
                            pixelColor = new Color(
                                Math.max(0, tailColor.r - 35),
                                Math.max(0, tailColor.g - 25),
                                Math.max(0, tailColor.b - 20)
                            );
                        }
                        break;
                        
                    case "left":
                        // Left side - full texture with flowing hair pattern
                        tailIntensity = 0.9f;
                        
                        // Flowing hair texture across entire face
                        float hairFlow = (float)Math.sin((y + x * 0.5f) * 0.4f) * 3 + x;
                        
                        // Hair direction lines across full texture
                        if ((y + (int)hairFlow) % 3 == 0) {
                            pixelColor = new Color(
                                Math.max(0, tailColor.r - 15),
                                Math.max(0, tailColor.g - 12),
                                Math.max(0, tailColor.b - 8)
                            );
                        } else {
                            // Base hair color with flow variation
                            float flowVariation = (float)Math.sin(hairFlow * 0.2f) * 0.1f;
                            pixelColor = new Color(
                                Math.min(255, (int)(tailColor.r * (0.9f + flowVariation))),
                                Math.min(255, (int)(tailColor.g * (0.9f + flowVariation))),
                                Math.min(255, (int)(tailColor.b * (0.9f + flowVariation)))
                            );
                        }
                        break;
                        
                    case "right":
                        // Right side - full texture with opposite hair flow
                        tailIntensity = 0.85f;
                        
                        // Opposite hair flow across entire face
                        hairFlow = (float)Math.sin((y - x * 0.5f) * 0.4f) * 3 + (16 - x);
                        
                        // Base color with slight darkening for right side
                        pixelColor = new Color(
                            Math.max(0, tailColor.r - 10),
                            Math.max(0, tailColor.g - 8),
                            Math.max(0, tailColor.b - 5)
                        );
                        
                        // Opposite hair direction lines
                        if ((y - (int)hairFlow) % 3 == 0) {
                            pixelColor = new Color(
                                Math.max(0, pixelColor.r - 12),
                                Math.max(0, pixelColor.g - 10),
                                Math.max(0, pixelColor.b - 6)
                            );
                        } else {
                            // Base hair color with opposite flow variation
                            float flowVariation = (float)Math.sin(hairFlow * 0.15f) * 0.08f;
                            pixelColor = new Color(
                                Math.min(255, (int)(pixelColor.r * (0.95f + flowVariation))),
                                Math.min(255, (int)(pixelColor.g * (0.95f + flowVariation))),
                                Math.min(255, (int)(pixelColor.b * (0.95f + flowVariation)))
                            );
                        }
                        break;
                }
                
                // ULTRA-PREMIUM TAIL HAIR DETAILS (applied to all faces - since all are now solid)
                // Natural hair texture with incredible detail
                int hairPattern = (x * 3 + y * 7) % 11;
                if (hairPattern == 0) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 10),
                        Math.max(0, pixelColor.g - 8),
                        Math.max(0, pixelColor.b - 5)
                    );
                } else if (hairPattern == 5) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 8),
                        Math.min(255, pixelColor.g + 6),
                        Math.min(255, pixelColor.b + 3)
                    );
                }
                
                // Hair follicle detail
                if ((x * 5 + y * 3) % 17 == 0 && tailIntensity > 0.7f) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 20),
                        Math.max(0, pixelColor.g - 15),
                        Math.max(0, pixelColor.b - 10)
                    );
                }
                
                // Hair highlights (natural sheen)
                if ((x * 2 + y) % 9 == 0) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 12),
                        Math.min(255, pixelColor.g + 9),
                        Math.min(255, pixelColor.b + 5)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    /**
     * BILLIONAIRE BUDGET ULTRA-PREMIUM TAIL ATTACHMENT TEXTURE GENERATOR
     */
    private void generateUltraTailAttachment(int tileX, int tileY, Color bodyColor, Color darkColor, Color blackColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = bodyColor; // Use body color as base
                
                // ULTRA-REALISTIC TAIL ATTACHMENT TO BODY
                float centerX = TILE_SIZE / 2.0f;
                float centerY = TILE_SIZE / 2.0f;
                
                // Muscle connection area
                if (y >= TILE_SIZE / 3) {
                    pixelColor = new Color(
                        Math.max(0, bodyColor.r - 15),
                        Math.max(0, bodyColor.g - 12),
                        Math.max(0, bodyColor.b - 8)
                    );
                    
                    // Muscle definition lines
                    if ((x + y * 2) % 7 == 0) {
                        pixelColor = new Color(
                            Math.max(0, pixelColor.r - 20),
                            Math.max(0, pixelColor.g - 15),
                            Math.max(0, pixelColor.b - 10)
                        );
                    }
                }
                
                // Tail emerging point
                if (y <= TILE_SIZE / 3) {
                    float distance = (float)Math.sqrt((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY));
                    if (distance <= 4) {
                        pixelColor = darkColor;
                    }
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    /**
     * BILLIONAIRE BUDGET ULTRA-PREMIUM TAIL TUFT TEXTURE GENERATOR
     */
    private void generateUltraTailTuft(int tileX, int tileY, Color tuftColor, Color darkColor, Color lightColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = tuftColor; // Start with solid tuft color
                
                // ULTRA-REALISTIC BUSHY TAIL TUFT (hair end) - full texture
                float centerX = TILE_SIZE / 2.0f;
                float centerY = TILE_SIZE / 2.0f;
                
                // Bushy tuft pattern across full texture
                float bushyRadius = 8 + (float)Math.sin(x * 0.5f + y * 0.3f) * 3;
                float distance = (float)Math.sqrt((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY));
                
                // Central bushy area
                if (distance <= bushyRadius) {
                    // Hair density variation in central area
                    if ((x + y * 2) % 5 == 0) {
                        pixelColor = darkColor;
                    } else if ((x * 2 + y) % 7 == 0) {
                        pixelColor = new Color(
                            (tuftColor.r + lightColor.r) / 2,
                            (tuftColor.g + lightColor.g) / 2,
                            (tuftColor.b + lightColor.b) / 2
                        );
                    }
                    
                    // Natural hair chaos in center
                    if ((x * 3 + y * 5) % 13 == 0) {
                        pixelColor = new Color(
                            Math.max(0, pixelColor.r - 25),
                            Math.max(0, pixelColor.g - 20),
                            Math.max(0, pixelColor.b - 15)
                        );
                    }
                } else {
                    // Outer areas - lighter tuft color with sparse hair
                    if ((x * 7 + y * 11) % 4 == 0) {
                        pixelColor = new Color(
                            Math.max(0, tuftColor.r - 15),
                            Math.max(0, tuftColor.g - 12),
                            Math.max(0, tuftColor.b - 8)
                        );
                    } else {
                        // Base tuft color for outer areas
                        pixelColor = new Color(
                            Math.max(0, tuftColor.r - 10),
                            Math.max(0, tuftColor.g - 8),
                            Math.max(0, tuftColor.b - 5)
                        );
                    }
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    /**
     * BILLIONAIRE BUDGET ULTRA-PREMIUM UDDER FACE TEXTURE GENERATOR
     * Creates face-specific udder textures with incredible anatomical detail.
     */
    private void generateUltraUdderFace(int tileX, int tileY, Color udderColor, Color darkPinkColor, Color lightColor, String face) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        // Force proper pink colors for realistic udder appearance
        Color basePink = new Color(255, 160, 180);  // Realistic udder pink
        Color darkPink = new Color(220, 120, 140);  // Deeper pink for shadows
        Color lightPink = new Color(255, 200, 210); // Light pink for highlights
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                // Fill entire face with base pink color
                Color pixelColor = basePink;
                
                // ULTRA-REALISTIC UDDER STRUCTURE
                float centerX = TILE_SIZE * 0.5f;
                float centerY = TILE_SIZE * 0.5f; // Center the main udder area
                float distance = (float)Math.sqrt(Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2));
                    
                // Face-specific details with enhanced pink detailing covering full face
                switch (face) {
                    case "front":
                        // Front face shows the main udder curve with teat attachment shadows
                        if (y >= TILE_SIZE * 0.7f) {
                            // Bottom area - darker pink for teat attachment zones
                            if ((x >= TILE_SIZE * 0.2f && x <= TILE_SIZE * 0.35f) || (x >= TILE_SIZE * 0.65f && x <= TILE_SIZE * 0.8f)) {
                                pixelColor = darkPink;
                            }
                        }
                        // Add vertical midline detail
                        if (x >= centerX - 1 && x <= centerX + 1) {
                            pixelColor = lightPink;
                        }
                        // Add curved udder outline
                        if (distance >= TILE_SIZE * 0.3f && distance <= TILE_SIZE * 0.35f) {
                            pixelColor = new Color(
                                Math.max(0, basePink.r - 10),
                                Math.max(0, basePink.g - 8),
                                Math.max(0, basePink.b - 5)
                            );
                        }
                        break;
                        
                    case "back":
                        // Back face shows attachment to belly with pink gradation
                        if (y <= TILE_SIZE * 0.3f) {
                            // Upper attachment area - lighter pink blending to belly
                            pixelColor = lightPink;
                        }
                        // Add horizontal attachment lines across full width
                        if (y >= TILE_SIZE * 0.25f && y <= TILE_SIZE * 0.4f && x % 3 == 0) {
                            pixelColor = darkPink;
                        }
                        // Add full-face texture pattern
                        if ((x + y) % 5 == 0) {
                            pixelColor = new Color(
                                Math.max(0, pixelColor.r - 5),
                                Math.max(0, pixelColor.g - 3),
                                Math.max(0, pixelColor.b - 2)
                            );
                        }
                        break;
                        
                    case "left":
                    case "right":
                        // Side faces show udder profile with enhanced pink curvature
                        float sideOffset = face.equals("left") ? -2.0f : 2.0f;
                        if (Math.abs(x - centerX + sideOffset) <= 3) {
                            // Side curvature emphasis with brighter pink
                            pixelColor = lightPink;
                        }
                        // Add full-face texture lines
                        if ((x + y) % 4 == 0) {
                            pixelColor = new Color(
                                Math.max(0, basePink.r - 8),
                                Math.max(0, basePink.g - 5),
                                Math.max(0, basePink.b - 3)
                            );
                        }
                        // Add side profile curves
                        if (x <= TILE_SIZE * 0.2f || x >= TILE_SIZE * 0.8f) {
                            pixelColor = darkPink;
                        }
                        break;
                }
                    
                // REALISTIC PINK SHADING AND FORM across full face
                // Top highlight (light source from above) - brighter pink
                if (y <= TILE_SIZE * 0.3f) {
                    pixelColor = new Color(
                        Math.min(255, pixelColor.r + 20),
                        Math.min(255, pixelColor.g + 15),
                        Math.min(255, pixelColor.b + 12)
                    );
                }
                // Bottom shadow - deeper pink
                else if (y >= TILE_SIZE * 0.7f) {
                    pixelColor = new Color(
                        Math.max(150, pixelColor.r - 25),
                        Math.max(80, pixelColor.g - 20),
                        Math.max(100, pixelColor.b - 15)
                    );
                }
                
                // PINK SKIN TEXTURE across full face
                if ((x + y * 3) % 6 == 0) {
                    // Enhanced skin texture with pink tones
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 8),
                        Math.max(0, pixelColor.g - 5),
                        Math.max(0, pixelColor.b - 3)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    /**
     * BILLIONAIRE BUDGET ULTRA-PREMIUM UDDER ATTACHMENT TEXTURE GENERATOR
     * Creates the top attachment point where udder connects to belly.
     */
    private void generateUltraUdderAttachment(int tileX, int tileY, Color lightColor, Color udderColor, Color darkPinkColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        // Force proper pink colors for realistic attachment appearance
        Color bellyColor = new Color(240, 220, 200);   // Light belly color
        Color basePink = new Color(255, 160, 180);     // Realistic udder pink
        Color darkPink = new Color(220, 120, 140);     // Deeper pink for details
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                float centerX = TILE_SIZE * 0.5f;
                
                // Fill entire face with gradient from belly to udder color
                float verticalGradient = (float)y / TILE_SIZE; // 0 at top, 1 at bottom
                
                Color pixelColor = new Color(
                    (int)(bellyColor.r * (1 - verticalGradient) + basePink.r * verticalGradient),
                    (int)(bellyColor.g * (1 - verticalGradient) + basePink.g * verticalGradient),
                    (int)(bellyColor.b * (1 - verticalGradient) + basePink.b * verticalGradient)
                );
                    
                // ENHANCED PINK SKIN FOLD DETAILS across full face
                if (y >= TILE_SIZE * 0.4f && y <= TILE_SIZE * 0.6f && Math.abs(x - centerX) <= 6) {
                    // Natural skin fold line with darker pink
                    pixelColor = darkPink;
                }
                
                // Add horizontal attachment lines across full width
                if (y >= TILE_SIZE * 0.3f && y <= TILE_SIZE * 0.7f && (x + y) % 4 == 0) {
                    // Attachment detail lines
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 10),
                        Math.max(0, pixelColor.g - 8),
                        Math.max(0, pixelColor.b - 5)
                    );
                }
                
                // Add edge details
                if (x <= 1 || x >= TILE_SIZE - 2 || y <= 1 || y >= TILE_SIZE - 2) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 8),
                        Math.max(0, pixelColor.g - 6),
                        Math.max(0, pixelColor.b - 4)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
    
    /**
     * BILLIONAIRE BUDGET ULTRA-PREMIUM UDDER TEATS TEXTURE GENERATOR
     * Creates the bottom texture showing realistic teat positioning.
     */
    private void generateUltraUdderTeats(int tileX, int tileY, Color udderColor, Color darkPinkColor, Color lightColor) {
        int startX = tileX * TILE_SIZE;
        int startY = tileY * TILE_SIZE;
        
        // Force proper pink colors for realistic teat appearance
        Color basePink = new Color(255, 160, 180);  // Realistic udder pink
        Color teatPink = new Color(200, 100, 120);  // Darker pink for teats
        Color tipPink = new Color(160, 80, 100);    // Dark pink for teat tips
        
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                Color pixelColor = new Color(0, 0, 0, 0); // Start transparent
                
                // FOUR TEAT POSITIONS
                float[] teatX = {TILE_SIZE * 0.25f, TILE_SIZE * 0.75f, TILE_SIZE * 0.25f, TILE_SIZE * 0.75f};
                float[] teatY = {TILE_SIZE * 0.3f, TILE_SIZE * 0.3f, TILE_SIZE * 0.7f, TILE_SIZE * 0.7f};
                
                for (int i = 0; i < 4; i++) {
                    float teatDistance = (float)Math.sqrt(Math.pow(x - teatX[i], 2) + Math.pow(y - teatY[i], 2));
                    float teatRadius = TILE_SIZE * 0.08f;
                    
                    if (teatDistance <= teatRadius) {
                        // Teat color - use darker pink
                        pixelColor = teatPink;
                        
                        // TEAT TIP DETAIL
                        if (teatDistance <= teatRadius * 0.3f) {
                            // Center tip - darkest pink
                            pixelColor = tipPink;
                        }
                        
                        // TEAT HIGHLIGHT
                        if (teatDistance >= teatRadius * 0.7f && teatDistance <= teatRadius * 0.9f) {
                            // Outer edge highlight - lighter pink
                            pixelColor = new Color(
                                Math.min(255, teatPink.r + 20),
                                Math.min(255, teatPink.g + 15),
                                Math.min(255, teatPink.b + 10)
                            );
                        }
                        break; // Don't overlap teats
                    }
                }
                
                // UDDER BASE - fill entire face if not a teat
                if (pixelColor.a == 0) {
                    pixelColor = basePink;
                    
                    // Enhanced pink shadowing in areas between teats
                    float centerX = TILE_SIZE * 0.5f;
                    float centerY = TILE_SIZE * 0.5f;
                    float baseDistance = (float)Math.sqrt(Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2));
                    
                    if (baseDistance <= TILE_SIZE * 0.2f) {
                        // Center area - darker pink
                        pixelColor = new Color(
                            Math.max(0, basePink.r - 15),
                            Math.max(0, basePink.g - 10),
                            Math.max(0, basePink.b - 8)
                        );
                    }
                }
                
                // Add full-face texture details
                if ((x + y) % 5 == 0) {
                    pixelColor = new Color(
                        Math.max(0, pixelColor.r - 5),
                        Math.max(0, pixelColor.g - 3),
                        Math.max(0, pixelColor.b - 2)
                    );
                }
                
                setPixel(startX + x, startY + y, pixelColor);
            }
        }
    }
}
