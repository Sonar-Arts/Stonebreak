package com.stonebreak.mobs.cow;

import org.joml.Vector3f;
import org.joml.Vector2f;
import com.stonebreak.MobTextureAtlas;

/**
 * Defines the 3D model structure for cow entities.
 * Uses Minecraft-style cuboid parts for simplicity and consistency with the game's aesthetic.
 * This is a data structure that will be used by EntityRenderer for actual rendering.
 */
public class CowModel {
    // Singleton instance for efficiency
    private static CowModel instance;
    
    // Model parts representing different sections of the cow
    private final ModelPart body;
    private final ModelPart head;
    private final ModelPart[] legs;
    private final ModelPart[] horns;
    private final ModelPart udder;
    private final ModelPart tail;
    
    // Animation states for different cow behaviors
    public enum CowAnimation {
        IDLE(new float[]{0, 0, 0, 0}, 0, 0),           // leg rotations, head pitch, tail sway
        WALKING(new float[]{15, -15, -15, 15}, 0, 5),   // alternating leg swing
        GRAZING(new float[]{0, 0, 0, 0}, -30, 0);       // head down rotation
        
        private final float[] legRotations;
        private final float headPitch;
        private final float tailSway;
        
        CowAnimation(float[] legRotations, float headPitch, float tailSway) {
            this.legRotations = legRotations;
            this.headPitch = headPitch;
            this.tailSway = tailSway;
        }
        
        public float[] getLegRotations() { return legRotations; }
        public float getHeadPitch() { return headPitch; }
        public float getTailSway() { return tailSway; }
    }
    
    /**
     * Private constructor for singleton pattern.
     */
    private CowModel() {
        // Initialize cow body parts based on our design specifications
        
        // Main body - largest part of the cow (larger, more realistic proportions)
        this.body = new ModelPart(
            new Vector3f(0, 0.0f, 0),         // position (center of cow)
            new Vector3f(1.2f, 0.8f, 1.0f),  // size (wider, taller, deeper - less squished)
            new Vector2f(MobTextureAtlas.COW_BODY_ATLAS_X, MobTextureAtlas.COW_BODY_ATLAS_Y) // COW_BODY texture
        );
        
        // Head - positioned in front of body (larger, more proportional)
        this.head = new ModelPart(
            new Vector3f(0, 0.3f, -0.8f),    // position (front of cow, adjusted for larger body)
            new Vector3f(0.7f, 0.6f, 0.6f),  // size (larger head to match bigger body)
            new Vector2f(MobTextureAtlas.COW_HEAD_ATLAS_X, MobTextureAtlas.COW_HEAD_ATLAS_Y) // COW_HEAD texture
        );
        
        // Two horns - positioned on top of head (scaled up for larger head)
        this.horns = new ModelPart[2];
        this.horns[0] = new ModelPart( // Left horn
            new Vector3f(-0.2f, 0.7f, -0.8f), // adjusted position for larger head
            new Vector3f(0.1f, 0.3f, 0.1f),   // larger horns to match bigger head
            new Vector2f(MobTextureAtlas.COW_HORNS_ATLAS_X, MobTextureAtlas.COW_HORNS_ATLAS_Y) // COW_HORNS texture
        );
        this.horns[1] = new ModelPart( // Right horn
            new Vector3f(0.2f, 0.7f, -0.8f),  // adjusted position for larger head
            new Vector3f(0.1f, 0.3f, 0.1f),   // larger horns to match bigger head
            new Vector2f(MobTextureAtlas.COW_HORNS_ATLAS_X, MobTextureAtlas.COW_HORNS_ATLAS_Y) // COW_HORNS texture
        );
        
        // Four legs - positioned at corners of body (thicker, more proportional)
        this.legs = new ModelPart[4];
        this.legs[0] = new ModelPart( // Front left
            new Vector3f(-0.4f, -0.8f, -0.3f), // adjusted position for larger body
            new Vector3f(0.2f, 0.8f, 0.2f),   // thicker legs
            new Vector2f(MobTextureAtlas.COW_LEGS_ATLAS_X, MobTextureAtlas.COW_LEGS_ATLAS_Y) // COW_LEGS texture
        );
        this.legs[1] = new ModelPart( // Front right
            new Vector3f(0.4f, -0.8f, -0.3f), 
            new Vector3f(0.2f, 0.8f, 0.2f),   // thicker legs
            new Vector2f(MobTextureAtlas.COW_LEGS_ATLAS_X, MobTextureAtlas.COW_LEGS_ATLAS_Y) // COW_LEGS texture
        );
        this.legs[2] = new ModelPart( // Back left
            new Vector3f(-0.4f, -0.8f, 0.3f), 
            new Vector3f(0.2f, 0.8f, 0.2f),   // thicker legs
            new Vector2f(MobTextureAtlas.COW_LEGS_ATLAS_X, MobTextureAtlas.COW_LEGS_ATLAS_Y) // COW_LEGS texture
        );
        this.legs[3] = new ModelPart( // Back right
            new Vector3f(0.4f, -0.8f, 0.3f), 
            new Vector3f(0.2f, 0.8f, 0.2f),   // thicker legs
            new Vector2f(MobTextureAtlas.COW_LEGS_ATLAS_X, MobTextureAtlas.COW_LEGS_ATLAS_Y) // COW_LEGS texture
        );
        
        // Udder - under the body (scaled up for larger body)
        this.udder = new ModelPart(
            new Vector3f(0, -0.5f, 0.2f),     // position (under larger body, adjusted)
            new Vector3f(0.4f, 0.3f, 0.6f),   // larger udder to match bigger body
            new Vector2f(MobTextureAtlas.COW_UDDER_ATLAS_X, MobTextureAtlas.COW_UDDER_ATLAS_Y) // COW_UDDER texture
        );
        
        // Tail - at the back of the body (scaled up for larger body)
        this.tail = new ModelPart(
            new Vector3f(0, 0.1f, 0.7f),      // position (back of larger cow)
            new Vector3f(0.15f, 0.6f, 0.15f), // slightly larger tail
            new Vector2f(MobTextureAtlas.COW_TAIL_ATLAS_X, MobTextureAtlas.COW_TAIL_ATLAS_Y) // COW_TAIL texture
        );
    }
    
    /**
     * Gets the singleton instance of the cow model.
     */
    public static CowModel getInstance() {
        if (instance == null) {
            instance = new CowModel();
        }
        return instance;
    }
    
    /**
     * Gets all the model parts that make up the cow.
     */
    public ModelPart[] getAllParts() {
        ModelPart[] allParts = new ModelPart[10]; // body, head, 4 legs, 2 horns, udder, tail
        allParts[0] = body;
        allParts[1] = head;
        System.arraycopy(legs, 0, allParts, 2, 4);
        System.arraycopy(horns, 0, allParts, 6, 2);
        allParts[8] = udder;
        allParts[9] = tail;
        return allParts;
    }
    
    /**
     * Gets the model parts with animation applied.
     */
    public ModelPart[] getAnimatedParts(CowAnimation animation, float animationTime) {
        ModelPart[] parts = getAllParts();
        
        // Apply animations based on type
        switch (animation) {
            case WALKING:
                applyWalkingAnimation(parts, animationTime);
                break;
            case GRAZING:
                applyGrazingAnimation(parts, animationTime);
                break;
            case IDLE:
                applyIdleAnimation(parts, animationTime);
                break;
        }
        
        return parts;
    }
    
    /**
     * Applies walking animation to model parts.
     */
    private void applyWalkingAnimation(ModelPart[] parts, float time) {
        float walkCycle = time * 3.0f; // Walking speed
        
        // Animate legs with alternating pattern
        float frontLeftLeg = (float)Math.sin(walkCycle) * 25.0f; // Front left
        float frontRightLeg = (float)Math.sin(walkCycle + Math.PI) * 25.0f; // Front right (opposite)
        float backLeftLeg = (float)Math.sin(walkCycle + Math.PI) * 20.0f; // Back left (opposite to front left)
        float backRightLeg = (float)Math.sin(walkCycle) * 20.0f; // Back right (same as front left)
        
        parts[2].setRotation(new Vector3f(frontLeftLeg, 0, 0));  // Front left leg
        parts[3].setRotation(new Vector3f(frontRightLeg, 0, 0)); // Front right leg
        parts[4].setRotation(new Vector3f(backLeftLeg, 0, 0));   // Back left leg
        parts[5].setRotation(new Vector3f(backRightLeg, 0, 0));  // Back right leg
        
        // Subtle head bobbing while walking
        float headBob = (float)Math.sin(walkCycle * 2.0f) * 2.0f;
        parts[1].setRotation(new Vector3f(headBob, 0, 0)); // Head
        
        // Body slightly tilts with walking rhythm
        Vector3f bodyPos = new Vector3f(body.getPosition());
        bodyPos.y += (float)Math.sin(walkCycle * 2.0f) * 0.02f; // Subtle vertical movement
        parts[0].setPosition(bodyPos); // Body
    }
    
    /**
     * Applies grazing animation to model parts.
     */
    private void applyGrazingAnimation(ModelPart[] parts, float time) {
        // Head down for grazing
        float grazingCycle = time * 1.5f; // Slower grazing movement
        float headPitch = -35.0f + (float)Math.sin(grazingCycle) * 5.0f; // Head down with slight bobbing
        
        parts[1].setRotation(new Vector3f(headPitch, 0, 0)); // Head
        
        // Legs stable during grazing
        for (int i = 2; i < 6; i++) {
            parts[i].setRotation(new Vector3f(0, 0, 0));
        }
        
        // Body slightly lower when grazing
        Vector3f bodyPos = new Vector3f(body.getPosition());
        bodyPos.y -= 0.05f;
        parts[0].setPosition(bodyPos); // Body
    }
    
    /**
     * Applies idle animation to model parts.
     */
    private void applyIdleAnimation(ModelPart[] parts, float time) {
        float idleCycle = time * 0.8f; // Slow idle movement
        
        // Subtle breathing animation
        float breathingScale = 1.0f + (float)Math.sin(idleCycle) * 0.02f;
        Vector3f bodyScale = new Vector3f(breathingScale, 1.0f, breathingScale);
        parts[0].setScale(bodyScale); // Body breathing
        
        // Occasional head movements
        float headMovement = (float)Math.sin(idleCycle * 0.3f) * 3.0f;
        parts[1].setRotation(new Vector3f(headMovement, (float)Math.sin(idleCycle * 0.2f) * 5.0f, 0)); // Head
        
        // Legs at rest
        for (int i = 2; i < 6; i++) {
            parts[i].setRotation(new Vector3f(0, 0, 0));
        }
        
        // Tail swishing (if tail part exists)
        if (parts.length > 6) {
            float tailSway = (float)Math.sin(idleCycle * 2.0f) * 15.0f;
            parts[6].setRotation(new Vector3f(0, tailSway, 0)); // Tail
        }
    }
    
    // Getters for individual parts
    public ModelPart getBody() { return body; }
    public ModelPart getHead() { return head; }
    public ModelPart[] getLegs() { return legs; }
    public ModelPart[] getHorns() { return horns; }
    public ModelPart getUdder() { return udder; }
    public ModelPart getTail() { return tail; }
    
    /**
     * Represents a single part of the cow model (cuboid).
     */
    public static class ModelPart {
        private Vector3f position;
        private Vector3f size;
        private Vector2f textureUV;
        private Vector3f rotation;
        private Vector3f scale;
        
        public ModelPart(Vector3f position, Vector3f size, Vector2f textureUV) {
            this.position = new Vector3f(position);
            this.size = new Vector3f(size);
            this.textureUV = new Vector2f(textureUV);
            this.rotation = new Vector3f(0, 0, 0);
            this.scale = new Vector3f(1, 1, 1);
        }
        
        // Getters
        public Vector3f getPosition() { return new Vector3f(position); }
        public Vector3f getSize() { return new Vector3f(size); }
        public Vector2f getTextureUV() { return new Vector2f(textureUV); }
        public Vector3f getRotation() { return new Vector3f(rotation); }
        public Vector3f getScale() { return new Vector3f(scale); }
        
        // Setters
        public void setPosition(Vector3f position) { this.position.set(position); }
        public void setSize(Vector3f size) { this.size.set(size); }
        public void setTextureUV(Vector2f textureUV) { this.textureUV.set(textureUV); }
        public void setRotation(Vector3f rotation) { this.rotation.set(rotation); }
        public void setScale(Vector3f scale) { this.scale.set(scale); }
        
        /**
         * Gets the vertices for this model part as a cuboid.
         * Returns vertex data in the format expected by OpenGL (24 vertices for 6 faces).
         */
        public float[] getVertices() {
            // Calculate half-sizes for convenience
            float halfWidth = size.x / 2.0f;
            float halfHeight = size.y / 2.0f;
            float halfDepth = size.z / 2.0f;
            
            // Define vertices for each face separately (24 vertices total)
            return new float[] {
                // Front face (4 vertices)
                position.x - halfWidth, position.y - halfHeight, position.z + halfDepth,
                position.x + halfWidth, position.y - halfHeight, position.z + halfDepth,
                position.x + halfWidth, position.y + halfHeight, position.z + halfDepth,
                position.x - halfWidth, position.y + halfHeight, position.z + halfDepth,
                
                // Back face (4 vertices)
                position.x - halfWidth, position.y - halfHeight, position.z - halfDepth,
                position.x + halfWidth, position.y - halfHeight, position.z - halfDepth,
                position.x + halfWidth, position.y + halfHeight, position.z - halfDepth,
                position.x - halfWidth, position.y + halfHeight, position.z - halfDepth,
                
                // Left face (4 vertices)
                position.x - halfWidth, position.y - halfHeight, position.z - halfDepth,
                position.x - halfWidth, position.y - halfHeight, position.z + halfDepth,
                position.x - halfWidth, position.y + halfHeight, position.z + halfDepth,
                position.x - halfWidth, position.y + halfHeight, position.z - halfDepth,
                
                // Right face (4 vertices)
                position.x + halfWidth, position.y - halfHeight, position.z - halfDepth,
                position.x + halfWidth, position.y - halfHeight, position.z + halfDepth,
                position.x + halfWidth, position.y + halfHeight, position.z + halfDepth,
                position.x + halfWidth, position.y + halfHeight, position.z - halfDepth,
                
                // Top face (4 vertices)
                position.x - halfWidth, position.y + halfHeight, position.z - halfDepth,
                position.x + halfWidth, position.y + halfHeight, position.z - halfDepth,
                position.x + halfWidth, position.y + halfHeight, position.z + halfDepth,
                position.x - halfWidth, position.y + halfHeight, position.z + halfDepth,
                
                // Bottom face (4 vertices)
                position.x - halfWidth, position.y - halfHeight, position.z - halfDepth,
                position.x + halfWidth, position.y - halfHeight, position.z - halfDepth,
                position.x + halfWidth, position.y - halfHeight, position.z + halfDepth,
                position.x - halfWidth, position.y - halfHeight, position.z + halfDepth
            };
        }
        
        /**
         * Gets the indices for rendering this cuboid (6 faces with 2 triangles each).
         */
        public int[] getIndices() {
            return new int[] {
                // Front face (vertices 0-3)
                0, 1, 2, 2, 3, 0,
                // Back face (vertices 4-7)
                4, 5, 6, 6, 7, 4,
                // Left face (vertices 8-11)
                8, 9, 10, 10, 11, 8,
                // Right face (vertices 12-15)
                12, 13, 14, 14, 15, 12,
                // Top face (vertices 16-19)
                16, 17, 18, 18, 19, 16,
                // Bottom face (vertices 20-23)
                20, 21, 22, 22, 23, 20
            };
        }
        
        /**
         * Gets texture coordinates for this model part with proper cube face mapping.
         * Returns 48 texture coordinates (2 per vertex, 4 vertices per face, 6 faces).
         * Each face samples the full texture tile for proper appearance.
         */
        public float[] getTextureCoords() {
            // Calculate UV coordinates based on texture atlas position
            float tileSize = 1.0f / 16.0f;  // Size of one tile in UV space (16x16 atlas)
            float u = textureUV.x * tileSize; // Base U coordinate
            float v = textureUV.y * tileSize; // Base V coordinate
            
            // Each face uses the full texture tile for proper cow texture appearance
            float u1 = u;                    // Left edge of tile
            float u2 = u + tileSize;         // Right edge of tile  
            float v1 = v;                    // Top edge of tile
            float v2 = v + tileSize;         // Bottom edge of tile
            
            return new float[] {
                // Front face - full tile mapping
                u1, v2,  // bottom-left
                u2, v2,  // bottom-right  
                u2, v1,  // top-right
                u1, v1,  // top-left
                
                // Back face - full tile mapping
                u1, v2,  // bottom-left
                u2, v2,  // bottom-right
                u2, v1,  // top-right  
                u1, v1,  // top-left
                
                // Left face - full tile mapping
                u1, v2,  // bottom-left
                u2, v2,  // bottom-right
                u2, v1,  // top-right
                u1, v1,  // top-left
                
                // Right face - full tile mapping
                u1, v2,  // bottom-left  
                u2, v2,  // bottom-right
                u2, v1,  // top-right
                u1, v1,  // top-left
                
                // Top face - full tile mapping
                u1, v2,  // bottom-left
                u2, v2,  // bottom-right
                u2, v1,  // top-right
                u1, v1,  // top-left
                
                // Bottom face - full tile mapping
                u1, v2,  // bottom-left
                u2, v2,  // bottom-right
                u2, v1,  // top-right
                u1, v1   // top-left
            };
        }
    }
}