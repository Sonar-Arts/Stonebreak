package com.openmason.deprecated;

import org.joml.Vector3f;

/**
 * Model Coordinate System - Phase 7 Open Mason Implementation
 *
 * Provides exact mathematical replication of Stonebreak's 3D model coordinate system
 * for 1:1 rendering parity. This system handles the conversion between position/size
 * definitions and vertex generation with perfect mathematical precision.
 *
 * Key Features:
 * - Right-handed Y-up coordinate system (matches Stonebreak exactly)
 * - Position = center point, Size = full dimensions
 * - Vertex generation: center ± half-size for each axis
 * - Generates exactly 24 vertices per part (4 vertices × 6 faces)
 * - Output: 72 float values per part (24 vertices × 3 components each)
 * - OpenGL-compatible vertex and index generation
 *
 * Mathematical Precision:
 * - Center-based positioning with exact half-size calculations
 * - Face normal consistency for proper lighting
 * - Vertex ordering for correct triangle winding
 * - Index generation for efficient rendering
 *
 * @deprecated This coordinate system is only used by {@link com.openmason.deprecated.LegacyCowCoordinateSystemIntegration}
 *             for legacy cow model rendering. Block rendering uses the CBR API from stonebreak-game
 *             ({@link com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager})
 *             which has its own built-in mesh generation and coordinate management. This class was
 *             created for "Phase 7 Open Mason Implementation" but the integration never happened for
 *             blocks. Consider migrating cow rendering to use stonebreak's model systems directly
 *             ({@link com.stonebreak.model.ModelLoader}).
 */
@Deprecated
public class LegacyCowModelCoordinateSystem {
    
    // Coordinate system constants
    public static final int VERTICES_PER_FACE = 4;
    public static final int FACES_PER_PART = 6;
    public static final int VERTICES_PER_PART = VERTICES_PER_FACE * FACES_PER_PART; // 24
    public static final int COMPONENTS_PER_VERTEX = 3; // X, Y, Z
    public static final int FLOATS_PER_PART = VERTICES_PER_PART * COMPONENTS_PER_VERTEX; // 72
    
    // Triangle indices per face (2 triangles × 3 vertices = 6 indices per face)
    public static final int INDICES_PER_FACE = 6;
    public static final int INDICES_PER_PART = INDICES_PER_FACE * FACES_PER_PART; // 36

    /**
     * 3D position structure for model parts.
     */
    public static class Position {
        private final float x;
        private final float y;
        private final float z;
        
        public Position(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        public float getX() { return x; }
        public float getY() { return y; }
        public float getZ() { return z; }
        
        public Vector3f toVector3f() {
            return new Vector3f(x, y, z);
        }
        
        @Override
        public String toString() {
            return String.format("Position{%.3f,%.3f,%.3f}", x, y, z);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Position position = (Position) obj;
            return Float.compare(position.x, x) == 0 &&
                   Float.compare(position.y, y) == 0 &&
                   Float.compare(position.z, z) == 0;
        }
        
        @Override
        public int hashCode() {
            return Float.floatToIntBits(x) * 31 * 31 + 
                   Float.floatToIntBits(y) * 31 + 
                   Float.floatToIntBits(z);
        }
    }
    
    /**
     * 3D size structure for model parts.
     */
    public static class Size {
        private final float x;
        private final float y;
        private final float z;
        
        public Size(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        public float getX() { return x; }
        public float getY() { return y; }
        public float getZ() { return z; }
        
        public Vector3f toVector3f() {
            return new Vector3f(x, y, z);
        }
        
        @Override
        public String toString() {
            return String.format("Size{%.3f,%.3f,%.3f}", x, y, z);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Size size = (Size) obj;
            return Float.compare(size.x, x) == 0 &&
                   Float.compare(size.y, y) == 0 &&
                   Float.compare(size.z, z) == 0;
        }
        
        @Override
        public int hashCode() {
            return Float.floatToIntBits(x) * 31 * 31 + 
                   Float.floatToIntBits(y) * 31 + 
                   Float.floatToIntBits(z);
        }
    }

    /**
     * Generate vertices for a cuboid model part.
     * 
     * Uses center-based positioning where the position represents the center point
     * and size represents the full dimensions. Vertices are calculated as:
     * vertex = center ± (size / 2) for each axis.
     * 
     * Generates exactly 24 vertices (4 per face × 6 faces) in right-handed Y-up
     * coordinate system matching Stonebreak's mathematical model exactly.
     * 
     * Face order: FRONT, BACK, LEFT, RIGHT, TOP, BOTTOM
     * Vertex order per face: bottom-left, bottom-right, top-right, top-left
     * 
     * @param position Center position of the model part
     * @param size Full dimensions of the model part
     * @return float array with 72 values (24 vertices × 3 components), or null if invalid input
     */
    public static float[] generateVertices(Position position, Size size) {
        if (position == null || size == null) {
            return null;
        }
        
        // Validate size dimensions (must be positive)
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            return null;
        }
        
        // Calculate center position and half-sizes
        float cx = position.getX();
        float cy = position.getY();
        float cz = position.getZ();
        
        float hx = size.getX() / 2.0f; // half-width
        float hy = size.getY() / 2.0f; // half-height
        float hz = size.getZ() / 2.0f; // half-depth
        
        // Generate vertices for all 6 faces (24 vertices total)
        return new float[] {
            // FRONT face (+Z direction) - vertices 0-3
            cx - hx, cy - hy, cz + hz, // bottom-left
            cx + hx, cy - hy, cz + hz, // bottom-right
            cx + hx, cy + hy, cz + hz, // top-right
            cx - hx, cy + hy, cz + hz, // top-left
            
            // BACK face (-Z direction) - vertices 4-7
            cx + hx, cy - hy, cz - hz, // bottom-left (note: reversed X for correct winding)
            cx - hx, cy - hy, cz - hz, // bottom-right
            cx - hx, cy + hy, cz - hz, // top-right
            cx + hx, cy + hy, cz - hz, // top-left
            
            // LEFT face (-X direction) - vertices 8-11
            cx - hx, cy - hy, cz - hz, // bottom-left
            cx - hx, cy - hy, cz + hz, // bottom-right
            cx - hx, cy + hy, cz + hz, // top-right
            cx - hx, cy + hy, cz - hz, // top-left
            
            // RIGHT face (+X direction) - vertices 12-15
            cx + hx, cy - hy, cz + hz, // bottom-left
            cx + hx, cy - hy, cz - hz, // bottom-right
            cx + hx, cy + hy, cz - hz, // top-right
            cx + hx, cy + hy, cz + hz, // top-left
            
            // TOP face (+Y direction) - vertices 16-19
            cx - hx, cy + hy, cz + hz, // bottom-left (front-left in top view)
            cx + hx, cy + hy, cz + hz, // bottom-right (front-right in top view)
            cx + hx, cy + hy, cz - hz, // top-right (back-right in top view)
            cx - hx, cy + hy, cz - hz, // top-left (back-left in top view)
            
            // BOTTOM face (-Y direction) - vertices 20-23
            cx - hx, cy - hy, cz - hz, // bottom-left (back-left in bottom view)
            cx + hx, cy - hy, cz - hz, // bottom-right (back-right in bottom view)
            cx + hx, cy - hy, cz + hz, // top-right (front-right in bottom view)
            cx - hx, cy - hy, cz + hz  // top-left (front-left in bottom view)
        };
    }
    
    /**
     * Generate indices for rendering a cuboid with proper triangle winding.
     * 
     * Creates 36 indices (6 faces × 2 triangles × 3 vertices) for efficient
     * OpenGL rendering. Uses counter-clockwise winding for front-facing triangles
     * in the right-handed coordinate system.
     * 
     * @return int array with 36 indices for triangle rendering
     */
    public static int[] generateIndices() {
        return new int[] {
            // FRONT face (vertices 0-3) - CCW winding
            0, 1, 2,  2, 3, 0,
            
            // BACK face (vertices 4-7) - CCW winding (already reversed in vertex generation)
            4, 5, 6,  6, 7, 4,
            
            // LEFT face (vertices 8-11) - CCW winding
            8, 9, 10,  10, 11, 8,
            
            // RIGHT face (vertices 12-15) - CCW winding
            12, 13, 14,  14, 15, 12,
            
            // TOP face (vertices 16-19) - CCW winding
            16, 17, 18,  18, 19, 16,
            
            // BOTTOM face (vertices 20-23) - CCW winding
            20, 21, 22,  22, 23, 20
        };
    }
    
    /**
     * Calculate the bounding box for a model part.
     * Returns the minimum and maximum coordinates for each axis.
     * 
     * @param position Center position of the model part
     * @param size Full dimensions of the model part
     * @return float array with [minX, minY, minZ, maxX, maxY, maxZ], or null if invalid
     */
    public static float[] calculateBoundingBox(Position position, Size size) {
        if (position == null || size == null) {
            return null;
        }
        
        float hx = size.getX() / 2.0f;
        float hy = size.getY() / 2.0f;
        float hz = size.getZ() / 2.0f;
        
        return new float[] {
            position.getX() - hx, // minX
            position.getY() - hy, // minY
            position.getZ() - hz, // minZ
            position.getX() + hx, // maxX
            position.getY() + hy, // maxY
            position.getZ() + hz  // maxZ
        };
    }
    
    /**
     * Calculate face normals for a cuboid.
     * Returns the normal vectors for all 6 faces.
     * 
     * @return float array with 18 values (6 faces × 3 components per normal)
     */
    public static float[] generateFaceNormals() {
        return new float[] {
            // FRONT face normal (+Z)
            0.0f, 0.0f, 1.0f,
            // BACK face normal (-Z)
            0.0f, 0.0f, -1.0f,
            // LEFT face normal (-X)
            -1.0f, 0.0f, 0.0f,
            // RIGHT face normal (+X)
            1.0f, 0.0f, 0.0f,
            // TOP face normal (+Y)
            0.0f, 1.0f, 0.0f,
            // BOTTOM face normal (-Y)
            0.0f, -1.0f, 0.0f
        };
    }
    
    /**
     * Generate vertex normals for lighting calculations.
     * Each vertex gets the normal of its corresponding face.
     * 
     * @return float array with 72 values (24 vertices × 3 components per normal)
     */
    public static float[] generateVertexNormals() {
        float[] faceNormals = generateFaceNormals();
        float[] vertexNormals = new float[FLOATS_PER_PART];
        
        int index = 0;
        for (int face = 0; face < FACES_PER_PART; face++) {
            float nx = faceNormals[face * 3];
            float ny = faceNormals[face * 3 + 1];
            float nz = faceNormals[face * 3 + 2];
            
            // Each face has 4 vertices, all with the same face normal
            for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++) {
                vertexNormals[index++] = nx;
                vertexNormals[index++] = ny;
                vertexNormals[index++] = nz;
            }
        }
        
        return vertexNormals;
    }
    
}