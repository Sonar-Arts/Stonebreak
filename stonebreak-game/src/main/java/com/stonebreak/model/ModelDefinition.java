package com.stonebreak.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import java.util.List;
import java.util.Map;

/**
 * JSON model definition classes for cow models.
 * Provides a direct translation of the hardcoded CowModel system to JSON format.
 */
public class ModelDefinition {
    
    // Thread-local flag to control vertex generation mode for backward compatibility
    private static final ThreadLocal<Boolean> useBakedVertices = ThreadLocal.withInitial(() -> false);
    
    /**
     * Sets whether to use baked vertices (position included in vertex data)
     * or origin vertices (position applied via transformation matrix).
     */
    public static void setUseBakedVertices(boolean useBaked) {
        useBakedVertices.set(useBaked);
    }
    
    /**
     * Gets whether baked vertices are currently being used.
     */
    public static boolean isUsingBakedVertices() {
        return useBakedVertices.get();
    }
    
    /**
     * Main cow model definition containing all parts and animations.
     */
    public static class CowModelDefinition {
        @JsonProperty("modelName")
        private String modelName;
        
        @JsonProperty("displayName")
        private String displayName;
        
        @JsonProperty("parts")
        private ModelParts parts;
        
        @JsonProperty("animations")
        private Map<String, ModelAnimation> animations;
        
        // Getters and setters
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        
        public ModelParts getParts() { return parts; }
        public void setParts(ModelParts parts) { this.parts = parts; }
        
        public Map<String, ModelAnimation> getAnimations() { return animations; }
        public void setAnimations(Map<String, ModelAnimation> animations) { this.animations = animations; }
    }
    
    /**
     * Container for all model parts.
     */
    public static class ModelParts {
        @JsonProperty("body")
        private ModelPart body;
        
        @JsonProperty("head")
        private ModelPart head;
        
        @JsonProperty("legs")
        private List<ModelPart> legs;
        
        @JsonProperty("horns")
        private List<ModelPart> horns;
        
        @JsonProperty("udder")
        private ModelPart udder;
        
        @JsonProperty("tail")
        private ModelPart tail;
        
        // Getters and setters
        public ModelPart getBody() { return body; }
        public void setBody(ModelPart body) { this.body = body; }
        
        public ModelPart getHead() { return head; }
        public void setHead(ModelPart head) { this.head = head; }
        
        public List<ModelPart> getLegs() { return legs; }
        public void setLegs(List<ModelPart> legs) { this.legs = legs; }
        
        public List<ModelPart> getHorns() { return horns; }
        public void setHorns(List<ModelPart> horns) { this.horns = horns; }
        
        public ModelPart getUdder() { return udder; }
        public void setUdder(ModelPart udder) { this.udder = udder; }
        
        public ModelPart getTail() { return tail; }
        public void setTail(ModelPart tail) { this.tail = tail; }
    }
    
    /**
     * Individual model part definition.
     */
    public static class ModelPart {
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("position")
        private Position position;
        
        @JsonProperty("size")
        private Size size;
        
        @JsonProperty("texture")
        private String texture;
        
        // Runtime fields for animation (not serialized to JSON)
        @JsonIgnore
        private Vector3f rotation = new Vector3f(0, 0, 0);
        @JsonIgnore
        private Vector3f scale = new Vector3f(1, 1, 1);
        
        // Matrix-based transformation support (always enabled) - not serialized to JSON
        @JsonIgnore
        private Matrix4f transformationMatrix;
        
        // Constructors
        public ModelPart() {}
        
        public ModelPart(String name, Position position, Size size, String texture) {
            this.name = name;
            this.position = position;
            this.size = size;
            this.texture = texture;
            // Initialize scale from size values after construction
            initializeScaleFromSize();
        }
        
        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public Position getPosition() { return position; }
        public void setPosition(Position position) { 
            this.position = position; 
            // Invalidate cached transformation matrix
            transformationMatrix = null;
        }
        
        public Size getSize() { return size; }
        public void setSize(Size size) { 
            this.size = size;
            // Update scale when size changes
            initializeScaleFromSize();
        }
        
        public String getTexture() { return texture; }
        public void setTexture(String texture) { this.texture = texture; }
        
        public Vector3f getRotation() { return new Vector3f(rotation); }
        public void setRotation(Vector3f rotation) { 
            this.rotation.set(rotation); 
            // Invalidate cached transformation matrix
            transformationMatrix = null;
        }
        
        public Vector3f getScale() { return new Vector3f(scale); }
        public void setScale(Vector3f scale) { 
            this.scale.set(scale); 
            // Invalidate cached transformation matrix
            transformationMatrix = null;
        }
        
        /**
         * Initialize scale to unity values.
         * The vertices are already sized correctly from JSON size values,
         * so scale should remain (1,1,1) to avoid double-scaling.
         */
        private void initializeScaleFromSize() {
            // Vertices are already sized correctly from getSizeVector() in getVerticesAtOrigin()
            // Scale should remain (1,1,1) to avoid double-scaling
            this.scale.set(1.0f, 1.0f, 1.0f);
            // Invalidate cached transformation matrix
            transformationMatrix = null;
        }
        
        /**
         * Called after JSON deserialization to ensure scale is properly initialized.
         * This should be called by the ModelLoader after loading from JSON.
         */
        public void postLoadInitialization() {
            initializeScaleFromSize();
        }
        
        /**
         * Matrix transformations are always enabled in this system.
         * @return Always true
         */
        public boolean isUseMatrixTransform() { return true; }
        
        // Helper methods to convert to Vector3f (for compatibility with existing code)
        public Vector3f getPositionVector() {
            return new Vector3f(position.getX(), position.getY(), position.getZ());
        }
        
        public Vector3f getSizeVector() {
            return new Vector3f(size.getX(), size.getY(), size.getZ());
        }
        
        /**
         * Get the base position from JSON (immutable reference).
         * Use this for animations to ensure they start from the correct base position.
         */
        public Vector3f getBasePositionVector() {
            return new Vector3f(position.getX(), position.getY(), position.getZ());
        }
        
        /**
         * Get the base scale from JSON size (immutable reference).
         * Use this for animations to ensure they start from the correct base scale.
         */
        public Vector3f getBaseScaleVector() {
            return new Vector3f(size.getX(), size.getY(), size.getZ());
        }
        
        /**
         * Creates a copy of this model part for animation purposes.
         */
        public ModelPart copy() {
            ModelPart copy = new ModelPart(name, position, size, texture);
            copy.rotation.set(this.rotation);
            copy.scale.set(this.scale);
            return copy;
        }
        
        /**
         * Gets the transformation matrix for this model part.
         * The matrix includes position, rotation, and scale transformations.
         * 
         * @return Transformation matrix for this part
         */
        public Matrix4f getTransformationMatrix() {
            if (transformationMatrix == null) {
                transformationMatrix = new Matrix4f();
            }
            
            // Reset matrix
            transformationMatrix.identity();
            
            // Apply transformations in order: translate, rotate, scale
            Vector3f pos = getPositionVector();
            transformationMatrix.translate(pos);
            
            // Apply rotation (in radians)
            if (rotation.lengthSquared() > 0.0001f) {
                transformationMatrix.rotateXYZ(rotation.x, rotation.y, rotation.z);
            }
            
            // Apply scale
            if (!scale.equals(1.0f, 1.0f, 1.0f)) {
                transformationMatrix.scale(scale);
            }
            
            return transformationMatrix;
        }
        
        /**
         * Gets the vertices for this model part as a cuboid.
         * Returns vertex data in the format expected by OpenGL (24 vertices for 6 faces).
         * 
         * For baked models (identified by having non-zero positions), vertices include 
         * the position for backward compatibility with Stonebreak's EntityRenderer.
         * For non-baked models, vertices are at origin with transformations applied via matrix.
         * 
         * @return Vertex array for OpenGL rendering
         */
        public float[] getVertices() {
            // Use a thread-local flag to determine vertex generation mode
            // This allows the ModelLoader to specify whether to use baked vertices
            // for backward compatibility with Stonebreak's EntityRenderer
            if (useBakedVertices.get()) {
                return getVerticesBaked();
            } else {
                return getVerticesAtOrigin();
            }
        }
        
        /**
         * Gets vertices positioned at origin for matrix-based transformation.
         */
        public float[] getVerticesAtOrigin() {
            Vector3f sz = getSizeVector();
            
            // Calculate half-sizes for convenience
            float halfWidth = sz.x / 2.0f;
            float halfHeight = sz.y / 2.0f;
            float halfDepth = sz.z / 2.0f;
            
            // Define vertices at origin (0,0,0) - transformations applied via matrix
            return new float[] {
                // Front face (4 vertices)
                -halfWidth, -halfHeight, +halfDepth,
                +halfWidth, -halfHeight, +halfDepth,
                +halfWidth, +halfHeight, +halfDepth,
                -halfWidth, +halfHeight, +halfDepth,
                
                // Back face (4 vertices)
                -halfWidth, -halfHeight, -halfDepth,
                +halfWidth, -halfHeight, -halfDepth,
                +halfWidth, +halfHeight, -halfDepth,
                -halfWidth, +halfHeight, -halfDepth,
                
                // Left face (4 vertices)
                -halfWidth, -halfHeight, -halfDepth,
                -halfWidth, -halfHeight, +halfDepth,
                -halfWidth, +halfHeight, +halfDepth,
                -halfWidth, +halfHeight, -halfDepth,
                
                // Right face (4 vertices)
                +halfWidth, -halfHeight, -halfDepth,
                +halfWidth, -halfHeight, +halfDepth,
                +halfWidth, +halfHeight, +halfDepth,
                +halfWidth, +halfHeight, -halfDepth,
                
                // Top face (4 vertices)
                -halfWidth, +halfHeight, -halfDepth,
                +halfWidth, +halfHeight, -halfDepth,
                +halfWidth, +halfHeight, +halfDepth,
                -halfWidth, +halfHeight, +halfDepth,
                
                // Bottom face (4 vertices)
                -halfWidth, -halfHeight, -halfDepth,
                +halfWidth, -halfHeight, -halfDepth,
                +halfWidth, -halfHeight, +halfDepth,
                -halfWidth, -halfHeight, +halfDepth
            };
        }
        
        /**
         * Gets vertices with baked position transformations (original behavior).
         * Used for backward compatibility with baked models.
         */
        public float[] getVerticesBaked() {
            Vector3f pos = getPositionVector();
            Vector3f sz = getSizeVector();
            
            // Calculate half-sizes for convenience
            float halfWidth = sz.x / 2.0f;
            float halfHeight = sz.y / 2.0f;
            float halfDepth = sz.z / 2.0f;
            
            // Define vertices for each face separately (24 vertices total)
            return new float[] {
                // Front face (4 vertices)
                pos.x - halfWidth, pos.y - halfHeight, pos.z + halfDepth,
                pos.x + halfWidth, pos.y - halfHeight, pos.z + halfDepth,
                pos.x + halfWidth, pos.y + halfHeight, pos.z + halfDepth,
                pos.x - halfWidth, pos.y + halfHeight, pos.z + halfDepth,
                
                // Back face (4 vertices)
                pos.x - halfWidth, pos.y - halfHeight, pos.z - halfDepth,
                pos.x + halfWidth, pos.y - halfHeight, pos.z - halfDepth,
                pos.x + halfWidth, pos.y + halfHeight, pos.z - halfDepth,
                pos.x - halfWidth, pos.y + halfHeight, pos.z - halfDepth,
                
                // Left face (4 vertices)
                pos.x - halfWidth, pos.y - halfHeight, pos.z - halfDepth,
                pos.x - halfWidth, pos.y - halfHeight, pos.z + halfDepth,
                pos.x - halfWidth, pos.y + halfHeight, pos.z + halfDepth,
                pos.x - halfWidth, pos.y + halfHeight, pos.z - halfDepth,
                
                // Right face (4 vertices)
                pos.x + halfWidth, pos.y - halfHeight, pos.z - halfDepth,
                pos.x + halfWidth, pos.y - halfHeight, pos.z + halfDepth,
                pos.x + halfWidth, pos.y + halfHeight, pos.z + halfDepth,
                pos.x + halfWidth, pos.y + halfHeight, pos.z - halfDepth,
                
                // Top face (4 vertices)
                pos.x - halfWidth, pos.y + halfHeight, pos.z - halfDepth,
                pos.x + halfWidth, pos.y + halfHeight, pos.z - halfDepth,
                pos.x + halfWidth, pos.y + halfHeight, pos.z + halfDepth,
                pos.x - halfWidth, pos.y + halfHeight, pos.z + halfDepth,
                
                // Bottom face (4 vertices)
                pos.x - halfWidth, pos.y - halfHeight, pos.z - halfDepth,
                pos.x + halfWidth, pos.y - halfHeight, pos.z - halfDepth,
                pos.x + halfWidth, pos.y - halfHeight, pos.z + halfDepth,
                pos.x - halfWidth, pos.y - halfHeight, pos.z + halfDepth
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
         * Gets texture coordinates for this model part using the texture atlas.
         * Returns 48 texture coordinates (2 per vertex, 4 vertices per face, 6 faces).
         * Uses different textures for different faces to ensure proper appearance.
         */
        public float[] getTextureCoords() {
            return getTextureCoords("default");
        }
        
        public float[] getTextureCoords(String textureVariant) {
            // Map texture part to base name for JSON lookups
            String partType = switch (texture) {
                case "cow_head" -> "HEAD";
                case "cow_body" -> "BODY";
                case "cow_legs" -> "LEG";
                case "cow_horns" -> "HORNS";
                case "cow_udder" -> "UDDER";
                case "cow_tail" -> "TAIL";
                default -> "HEAD"; // fallback
            };
            
            // Face order: front(0), back(1), left(2), right(3), top(4), bottom(5)
            String[] faceNames = {
                partType + "_FRONT",
                partType + "_BACK", 
                partType + "_LEFT",
                partType + "_RIGHT",
                partType + "_TOP",
                partType + "_BOTTOM"
            };
            
            // Build the final texture coordinate array (48 values total)
            float[] result = new float[48];
            int index = 0;
            
            // Process each face (front, back, left, right, top, bottom)
            for (int face = 0; face < 6; face++) {
                float[] coords = null;
                
                try {
                    // Safe texture coordinate lookup - avoid OpenGL calls during model loading
                    coords = getTextureCoordinatesSafe(textureVariant, faceNames[face]);
                } catch (Exception e) {
                    System.err.println("[ModelDefinition] Error getting texture coordinates for " + 
                        textureVariant + ":" + faceNames[face] + " - " + e.getMessage());
                    coords = null;
                }
                
                if (coords == null) {
                    // Use safe fallback coordinates without OpenGL calls
                    float tileSize = 1.0f / 16.0f;
                    coords = new float[]{
                        0, 0,                    // bottom-left
                        tileSize, 0,             // bottom-right
                        tileSize, tileSize,      // top-right
                        0, tileSize              // top-left
                    };
                    System.err.println("[ModelDefinition] Warning: Missing texture coordinates for " + 
                        textureVariant + ":" + faceNames[face] + ", using safe fallback");
                }
                
                // Add UV coordinates for this face (4 vertices × 2 coordinates = 8 values)
                result[index++] = coords[0]; result[index++] = coords[1]; // bottom-left
                result[index++] = coords[2]; result[index++] = coords[3]; // bottom-right
                result[index++] = coords[4]; result[index++] = coords[5]; // top-right
                result[index++] = coords[6]; result[index++] = coords[7]; // top-left
            }
            
            return result;
        }
        
        /**
         * Safe texture coordinate lookup that avoids OpenGL native calls during model loading.
         * This prevents EXCEPTION_ACCESS_VIOLATION crashes when called before OpenGL context is ready.
         */
        private static float[] getTextureCoordinatesSafe(String textureVariant, String faceName) {
            try {
                // Use texture loader directly without triggering OpenGL initialization
                return com.stonebreak.textures.mobs.CowTextureLoader.getQuadUVCoordinates(textureVariant, faceName, 16);
            } catch (Exception e) {
                System.err.println("[ModelDefinition] Safe texture lookup failed for " + textureVariant + ":" + faceName);
                return null;
            }
        }
    }
    
    /**
     * 3D position coordinates.
     */
    public static class Position {
        @JsonProperty("x")
        private float x;
        
        @JsonProperty("y")
        private float y;
        
        @JsonProperty("z")
        private float z;
        
        // Constructors
        public Position() {}
        
        public Position(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        // Getters and setters
        public float getX() { return x; }
        public void setX(float x) { this.x = x; }
        
        public float getY() { return y; }
        public void setY(float y) { this.y = y; }
        
        public float getZ() { return z; }
        public void setZ(float z) { this.z = z; }
    }
    
    /**
     * 3D size dimensions.
     */
    public static class Size {
        @JsonProperty("x")
        private float x;
        
        @JsonProperty("y")
        private float y;
        
        @JsonProperty("z")
        private float z;
        
        // Constructors
        public Size() {}
        
        public Size(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        // Getters and setters
        public float getX() { return x; }
        public void setX(float x) { this.x = x; }
        
        public float getY() { return y; }
        public void setY(float y) { this.y = y; }
        
        public float getZ() { return z; }
        public void setZ(float z) { this.z = z; }
    }
    
    /**
     * Animation definition with leg rotations, head pitch, and tail sway.
     */
    public static class ModelAnimation {
        @JsonProperty("legRotations")
        private float[] legRotations;
        
        @JsonProperty("headPitch")
        private float headPitch;
        
        @JsonProperty("tailSway")
        private float tailSway;
        
        // Constructors
        public ModelAnimation() {}
        
        public ModelAnimation(float[] legRotations, float headPitch, float tailSway) {
            this.legRotations = legRotations;
            this.headPitch = headPitch;
            this.tailSway = tailSway;
        }
        
        // Getters and setters
        public float[] getLegRotations() { return legRotations; }
        public void setLegRotations(float[] legRotations) { this.legRotations = legRotations; }
        
        public float getHeadPitch() { return headPitch; }
        public void setHeadPitch(float headPitch) { this.headPitch = headPitch; }
        
        public float getTailSway() { return tailSway; }
        public void setTailSway(float tailSway) { this.tailSway = tailSway; }
    }
}