package com.stonebreak.rendering.core.API.commonBlockResources.meshing;

import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinition;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * CBR Mesh Manager - Pre-builds and manages common block geometries.
 * 
 * Follows SOLID principles:
 * - Single Responsibility: Manages mesh geometry and VBO resources
 * - Open/Closed: Extensible through MeshTemplate system
 * - Interface Segregation: Focused on mesh operations only
 * - Dependency Inversion: Uses abstractions for geometry definition
 * 
 * Implements RAII for automatic GPU resource cleanup.
 */
public class MeshManager implements AutoCloseable {
    
    private final Map<MeshType, MeshResource> prebuiltMeshes;
    private final Map<String, MeshResource> customMeshes;
    private boolean disposed = false;
    
    /**
     * Creates a mesh manager and pre-builds common geometries.
     */
    public MeshManager() {
        this.prebuiltMeshes = new ConcurrentHashMap<>();
        this.customMeshes = new ConcurrentHashMap<>();
        initializeCommonMeshes();
    }
    
    /**
     * Gets a pre-built mesh for the specified type.
     * 
     * @param meshType The type of mesh to retrieve
     * @return The mesh resource, or null if not found
     */
    public MeshResource getMesh(MeshType meshType) {
        if (disposed) {
            throw new IllegalStateException("MeshManager has been disposed");
        }
        return prebuiltMeshes.get(meshType);
    }
    
    /**
     * Gets a custom mesh by name.
     * 
     * @param name The custom mesh name
     * @return The mesh resource, or null if not found
     */
    public MeshResource getCustomMesh(String name) {
        if (disposed) {
            throw new IllegalStateException("MeshManager has been disposed");
        }
        return customMeshes.get(name);
    }
    
    /**
     * Creates and registers a custom mesh from vertex data.
     * 
     * @param name The name for the custom mesh
     * @param vertices Vertex data (position + texture coords: x,y,z,u,v per vertex)
     * @param indices Triangle indices
     * @return The created mesh resource
     */
    public MeshResource createCustomMesh(String name, float[] vertices, int[] indices) {
        if (disposed) {
            throw new IllegalStateException("MeshManager has been disposed");
        }
        
        MeshResource existingMesh = customMeshes.get(name);
        if (existingMesh != null) {
            existingMesh.cleanup();
        }
        
        MeshResource mesh = createMeshFromData(vertices, indices, name);
        customMeshes.put(name, mesh);
        return mesh;
    }
    
    /**
     * Creates a cube mesh with specific texture coordinates from texture atlas.
     * 
     * @param name The name for the custom mesh
     * @param u1 Left U coordinate in atlas
     * @param v1 Top V coordinate in atlas
     * @param u2 Right U coordinate in atlas
     * @param v2 Bottom V coordinate in atlas
     * @return The created mesh resource with correct atlas coordinates
     */
    public MeshResource createCubeMeshWithTextureCoordinates(String name, float u1, float v1, float u2, float v2) {
        if (disposed) {
            throw new IllegalStateException("MeshManager has been disposed");
        }
        
        // Vertex format: x, y, z, u, v (position + texture coordinates)
        // Fix texture flipping by swapping v1 and v2 mapping
        float[] vertices = {
            // Front face (z = 0.5)
            -0.5f, -0.5f,  0.5f,  u1, v1,  // bottom-left -> top-left of texture
             0.5f, -0.5f,  0.5f,  u2, v1,  // bottom-right -> top-right of texture  
             0.5f,  0.5f,  0.5f,  u2, v2,  // top-right -> bottom-right of texture
            -0.5f,  0.5f,  0.5f,  u1, v2,  // top-left -> bottom-left of texture
            
            // Back face (z = -0.5)
             0.5f, -0.5f, -0.5f,  u1, v1,  // bottom-left -> top-left of texture
            -0.5f, -0.5f, -0.5f,  u2, v1,  // bottom-right -> top-right of texture
            -0.5f,  0.5f, -0.5f,  u2, v2,  // top-right -> bottom-right of texture
             0.5f,  0.5f, -0.5f,  u1, v2,  // top-left -> bottom-left of texture
            
            // Left face (x = -0.5)
            -0.5f, -0.5f, -0.5f,  u1, v1,  // bottom-left -> top-left of texture
            -0.5f, -0.5f,  0.5f,  u2, v1,  // bottom-right -> top-right of texture
            -0.5f,  0.5f,  0.5f,  u2, v2,  // top-right -> bottom-right of texture
            -0.5f,  0.5f, -0.5f,  u1, v2,  // top-left -> bottom-left of texture
            
            // Right face (x = 0.5)
             0.5f, -0.5f,  0.5f,  u1, v1,  // bottom-left -> top-left of texture
             0.5f, -0.5f, -0.5f,  u2, v1,  // bottom-right -> top-right of texture
             0.5f,  0.5f, -0.5f,  u2, v2,  // top-right -> bottom-right of texture
             0.5f,  0.5f,  0.5f,  u1, v2,  // top-left -> bottom-left of texture
            
            // Top face (y = 0.5)
            -0.5f,  0.5f,  0.5f,  u1, v1,  // bottom-left -> top-left of texture
             0.5f,  0.5f,  0.5f,  u2, v1,  // bottom-right -> top-right of texture
             0.5f,  0.5f, -0.5f,  u2, v2,  // top-right -> bottom-right of texture
            -0.5f,  0.5f, -0.5f,  u1, v2,  // top-left -> bottom-left of texture
            
            // Bottom face (y = -0.5)
            -0.5f, -0.5f, -0.5f,  u1, v1,  // bottom-left -> top-left of texture
             0.5f, -0.5f, -0.5f,  u2, v1,  // bottom-right -> top-right of texture
             0.5f, -0.5f,  0.5f,  u2, v2,  // top-right -> bottom-right of texture
            -0.5f, -0.5f,  0.5f,  u1, v2   // top-left -> bottom-left of texture
        };
        
        // Face indices (2 triangles per face)
        int[] indices = {
            // Front face
            0,  1,  2,   2,  3,  0,
            // Back face  
            4,  5,  6,   6,  7,  4,
            // Left face
            8,  9,  10,  10, 11, 8,
            // Right face
            12, 13, 14,  14, 15, 12,
            // Top face
            16, 17, 18,  18, 19, 16,
            // Bottom face
            20, 21, 22,  22, 23, 20
        };
        
        return createCustomMesh(name, vertices, indices);
    }
    
    /**
     * Creates a cross mesh with specific texture coordinates from texture atlas.
     * Supports the cube cross format where texture has pattern:
     * xoxx
     * oooo  
     * xoxx
     * (x = transparent, o = textured)
     * 
     * @param name The name for the custom mesh
     * @param u1 Left U coordinate in atlas
     * @param v1 Top V coordinate in atlas
     * @param u2 Right U coordinate in atlas
     * @param v2 Bottom V coordinate in atlas
     * @return The created cross mesh with correct atlas coordinates
     */
    public MeshResource createCrossMeshWithTextureCoordinates(String name, float u1, float v1, float u2, float v2) {
        if (disposed) {
            throw new IllegalStateException("MeshManager has been disposed");
        }
        
        // For cube cross texture format (xoxx/oooo/xoxx), map the middle row (oooo) to the cross geometry
        // Calculate the UV coordinates for the middle horizontal strip (the 'oooo' part)
        float du = u2 - u1;  // Width of texture region
        float dv = v2 - v1;  // Height of texture region
        
        // The middle row represents 1/4 to 3/4 of the texture height (the 'oooo' strip)
        float crossV1 = v1 + (dv * 0.25f);  // Start of middle row
        float crossV2 = v1 + (dv * 0.75f);  // End of middle row
        
        // Two intersecting quads forming a cross, using only the middle texture strip
        float[] vertices = {
            // First quad (diagonal X from top-left to bottom-right)
            -0.5f, -0.5f,  0.0f,  u1, crossV2,  // bottom-left -> middle row left
             0.5f, -0.5f,  0.0f,  u2, crossV2,  // bottom-right -> middle row right
             0.5f,  0.5f,  0.0f,  u2, crossV1,  // top-right -> middle row right
            -0.5f,  0.5f,  0.0f,  u1, crossV1,  // top-left -> middle row left
            
            // Second quad (diagonal X from top-right to bottom-left)
             0.0f, -0.5f, -0.5f,  u1, crossV2,  // bottom-left -> middle row left
             0.0f, -0.5f,  0.5f,  u2, crossV2,  // bottom-right -> middle row right
             0.0f,  0.5f,  0.5f,  u2, crossV1,  // top-right -> middle row right
             0.0f,  0.5f, -0.5f,  u1, crossV1   // top-left -> middle row left
        };
        
        int[] indices = {
            // First quad (both sides for proper transparency)
            0, 1, 2,  2, 3, 0,  // front
            0, 3, 2,  2, 1, 0,  // back
            // Second quad (both sides)
            4, 5, 6,  6, 7, 4,  // front
            4, 7, 6,  6, 5, 4   // back
        };
        
        return createCustomMesh(name, vertices, indices);
    }
    
    /**
     * Creates a directional cube mesh with different texture coordinates for each face.
     * Used for blocks like grass that have different textures on top, sides, and bottom.
     * 
     * @param name The name for the custom mesh
     * @param topU1 Top face left U coordinate
     * @param topV1 Top face top V coordinate  
     * @param topU2 Top face right U coordinate
     * @param topV2 Top face bottom V coordinate
     * @param sideU1 Side faces left U coordinate
     * @param sideV1 Side faces top V coordinate
     * @param sideU2 Side faces right U coordinate
     * @param sideV2 Side faces bottom V coordinate
     * @param bottomU1 Bottom face left U coordinate
     * @param bottomV1 Bottom face top V coordinate
     * @param bottomU2 Bottom face right U coordinate
     * @param bottomV2 Bottom face bottom V coordinate
     * @return The created directional cube mesh
     */
    public MeshResource createDirectionalCubeMesh(String name, 
                                                 float topU1, float topV1, float topU2, float topV2,
                                                 float sideU1, float sideV1, float sideU2, float sideV2,
                                                 float bottomU1, float bottomV1, float bottomU2, float bottomV2) {
        if (disposed) {
            throw new IllegalStateException("MeshManager has been disposed");
        }
        
        // Vertex format: x, y, z, u, v (position + texture coordinates)
        float[] vertices = {
            // Front face (z = 0.5) - use side texture
            -0.5f, -0.5f,  0.5f,  sideU1, sideV2,  // bottom-left -> bottom-left of side texture
             0.5f, -0.5f,  0.5f,  sideU2, sideV2,  // bottom-right -> bottom-right of side texture  
             0.5f,  0.5f,  0.5f,  sideU2, sideV1,  // top-right -> top-right of side texture
            -0.5f,  0.5f,  0.5f,  sideU1, sideV1,  // top-left -> top-left of side texture
            
            // Back face (z = -0.5) - use side texture
             0.5f, -0.5f, -0.5f,  sideU1, sideV2,  // bottom-left -> bottom-left of side texture
            -0.5f, -0.5f, -0.5f,  sideU2, sideV2,  // bottom-right -> bottom-right of side texture
            -0.5f,  0.5f, -0.5f,  sideU2, sideV1,  // top-right -> top-right of side texture
             0.5f,  0.5f, -0.5f,  sideU1, sideV1,  // top-left -> top-left of side texture
            
            // Left face (x = -0.5) - use side texture
            -0.5f, -0.5f, -0.5f,  sideU1, sideV2,  // bottom-left -> bottom-left of side texture
            -0.5f, -0.5f,  0.5f,  sideU2, sideV2,  // bottom-right -> bottom-right of side texture
            -0.5f,  0.5f,  0.5f,  sideU2, sideV1,  // top-right -> top-right of side texture
            -0.5f,  0.5f, -0.5f,  sideU1, sideV1,  // top-left -> top-left of side texture
            
            // Right face (x = 0.5) - use side texture
             0.5f, -0.5f,  0.5f,  sideU1, sideV2,  // bottom-left -> bottom-left of side texture
             0.5f, -0.5f, -0.5f,  sideU2, sideV2,  // bottom-right -> bottom-right of side texture
             0.5f,  0.5f, -0.5f,  sideU2, sideV1,  // top-right -> top-right of side texture
             0.5f,  0.5f,  0.5f,  sideU1, sideV1,  // top-left -> top-left of side texture
            
            // Top face (y = 0.5) - use top texture (grass)
            -0.5f,  0.5f,  0.5f,  topU1, topV1,
             0.5f,  0.5f,  0.5f,  topU2, topV1,
             0.5f,  0.5f, -0.5f,  topU2, topV2,
            -0.5f,  0.5f, -0.5f,  topU1, topV2,
            
            // Bottom face (y = -0.5) - use bottom texture (dirt)
            -0.5f, -0.5f, -0.5f,  bottomU1, bottomV1,
             0.5f, -0.5f, -0.5f,  bottomU2, bottomV1,
             0.5f, -0.5f,  0.5f,  bottomU2, bottomV2,
            -0.5f, -0.5f,  0.5f,  bottomU1, bottomV2
        };
        
        // Face indices (2 triangles per face)
        int[] indices = {
            // Front face
            0,  1,  2,   2,  3,  0,
            // Back face  
            4,  5,  6,   6,  7,  4,
            // Left face
            8,  9,  10,  10, 11, 8,
            // Right face
            12, 13, 14,  14, 15, 12,
            // Top face
            16, 17, 18,  18, 19, 16,
            // Bottom face
            20, 21, 22,  22, 23, 20
        };
        
        return createCustomMesh(name, vertices, indices);
    }
    
    /**
     * Creates a fully directional cube mesh with different texture coordinates for each individual face.
     * Used for blocks like workbench that have unique textures on all 6 faces.
     * 
     * @param name The name for the custom mesh
     * @param frontU1 Front face left U coordinate
     * @param frontV1 Front face top V coordinate
     * @param frontU2 Front face right U coordinate
     * @param frontV2 Front face bottom V coordinate
     * @param backU1 Back face left U coordinate
     * @param backV1 Back face top V coordinate
     * @param backU2 Back face right U coordinate
     * @param backV2 Back face bottom V coordinate
     * @param leftU1 Left face left U coordinate
     * @param leftV1 Left face top V coordinate
     * @param leftU2 Left face right U coordinate
     * @param leftV2 Left face bottom V coordinate
     * @param rightU1 Right face left U coordinate
     * @param rightV1 Right face top V coordinate
     * @param rightU2 Right face right U coordinate
     * @param rightV2 Right face bottom V coordinate
     * @param topU1 Top face left U coordinate
     * @param topV1 Top face top V coordinate
     * @param topU2 Top face right U coordinate
     * @param topV2 Top face bottom V coordinate
     * @param bottomU1 Bottom face left U coordinate
     * @param bottomV1 Bottom face top V coordinate
     * @param bottomU2 Bottom face right U coordinate
     * @param bottomV2 Bottom face bottom V coordinate
     * @return The created 6-face directional cube mesh
     */
    public MeshResource createSixFaceDirectionalCubeMesh(String name,
                                                        float frontU1, float frontV1, float frontU2, float frontV2,
                                                        float backU1, float backV1, float backU2, float backV2,
                                                        float leftU1, float leftV1, float leftU2, float leftV2,
                                                        float rightU1, float rightV1, float rightU2, float rightV2,
                                                        float topU1, float topV1, float topU2, float topV2,
                                                        float bottomU1, float bottomV1, float bottomU2, float bottomV2) {
        if (disposed) {
            throw new IllegalStateException("MeshManager has been disposed");
        }
        
        // Vertex format: x, y, z, u, v (position + texture coordinates)
        float[] vertices = {
            // Front face (z = 0.5) - use front texture
            -0.5f, -0.5f,  0.5f,  frontU1, frontV2,  // bottom-left
             0.5f, -0.5f,  0.5f,  frontU2, frontV2,  // bottom-right
             0.5f,  0.5f,  0.5f,  frontU2, frontV1,  // top-right
            -0.5f,  0.5f,  0.5f,  frontU1, frontV1,  // top-left
            
            // Back face (z = -0.5) - use back texture
             0.5f, -0.5f, -0.5f,  backU1, backV2,   // bottom-left
            -0.5f, -0.5f, -0.5f,  backU2, backV2,   // bottom-right
            -0.5f,  0.5f, -0.5f,  backU2, backV1,   // top-right
             0.5f,  0.5f, -0.5f,  backU1, backV1,   // top-left
            
            // Left face (x = -0.5) - use left texture
            -0.5f, -0.5f, -0.5f,  leftU1, leftV2,   // bottom-left
            -0.5f, -0.5f,  0.5f,  leftU2, leftV2,   // bottom-right
            -0.5f,  0.5f,  0.5f,  leftU2, leftV1,   // top-right
            -0.5f,  0.5f, -0.5f,  leftU1, leftV1,   // top-left
            
            // Right face (x = 0.5) - use right texture
             0.5f, -0.5f,  0.5f,  rightU1, rightV2, // bottom-left
             0.5f, -0.5f, -0.5f,  rightU2, rightV2, // bottom-right
             0.5f,  0.5f, -0.5f,  rightU2, rightV1, // top-right
             0.5f,  0.5f,  0.5f,  rightU1, rightV1, // top-left
            
            // Top face (y = 0.5) - use top texture
            -0.5f,  0.5f,  0.5f,  topU1, topV1,     // bottom-left
             0.5f,  0.5f,  0.5f,  topU2, topV1,     // bottom-right
             0.5f,  0.5f, -0.5f,  topU2, topV2,     // top-right
            -0.5f,  0.5f, -0.5f,  topU1, topV2,     // top-left
            
            // Bottom face (y = -0.5) - use bottom texture
            -0.5f, -0.5f, -0.5f,  bottomU1, bottomV1, // bottom-left
             0.5f, -0.5f, -0.5f,  bottomU2, bottomV1, // bottom-right
             0.5f, -0.5f,  0.5f,  bottomU2, bottomV2, // top-right
            -0.5f, -0.5f,  0.5f,  bottomU1, bottomV2  // top-left
        };
        
        // Face indices (2 triangles per face)
        int[] indices = {
            // Front face
            0,  1,  2,   2,  3,  0,
            // Back face  
            4,  5,  6,   6,  7,  4,
            // Left face
            8,  9,  10,  10, 11, 8,
            // Right face
            12, 13, 14,  14, 15, 12,
            // Top face
            16, 17, 18,  18, 19, 16,
            // Bottom face
            20, 21, 22,  22, 23, 20
        };
        
        return createCustomMesh(name, vertices, indices);
    }
    
    /**
     * Gets mesh resource that matches a block definition's render type.
     * 
     * @param definition The block definition
     * @return Appropriate mesh resource for the render type
     */
    public MeshResource getMeshForDefinition(BlockDefinition definition) {
        if (disposed) {
            throw new IllegalStateException("MeshManager has been disposed");
        }
        
        switch (definition.getRenderType()) {
            case CUBE_ALL:
            case CUBE_DIRECTIONAL:
                return getMesh(MeshType.CUBE);
            case CROSS:
                return getMesh(MeshType.CROSS);
            case SPRITE:
                return getMesh(MeshType.SPRITE);
            default:
                return getMesh(MeshType.CUBE); // fallback
        }
    }
    
    /**
     * Gets statistics about managed meshes.
     * 
     * @return Mesh statistics
     */
    public MeshStatistics getStatistics() {
        int prebuiltCount = prebuiltMeshes.size();
        int customCount = customMeshes.size();
        
        long totalVertices = prebuiltMeshes.values().stream()
                .mapToLong(mesh -> mesh.getVertexCount())
                .sum() +
                customMeshes.values().stream()
                .mapToLong(mesh -> mesh.getVertexCount())
                .sum();
        
        long totalTriangles = prebuiltMeshes.values().stream()
                .mapToLong(mesh -> mesh.getTriangleCount())
                .sum() +
                customMeshes.values().stream()
                .mapToLong(mesh -> mesh.getTriangleCount())
                .sum();
        
        return new MeshStatistics(prebuiltCount, customCount, totalVertices, totalTriangles);
    }
    
    // === Private Implementation ===
    
    /**
     * Initialize common mesh geometries on startup.
     */
    private void initializeCommonMeshes() {
        // Create cube mesh (most common)
        prebuiltMeshes.put(MeshType.CUBE, createCubeMesh());
        
        // Create cross mesh for flowers/plants
        prebuiltMeshes.put(MeshType.CROSS, createCrossMesh());
        
        // Create sprite mesh for 2D items
        prebuiltMeshes.put(MeshType.SPRITE, createSpriteMesh());
        
        // Create partial cube meshes for various configurations
        prebuiltMeshes.put(MeshType.SLAB_BOTTOM, createSlabMesh(false));
        prebuiltMeshes.put(MeshType.SLAB_TOP, createSlabMesh(true));
        
        System.out.println("[MeshManager] Initialized " + prebuiltMeshes.size() + " common meshes");
    }
    
    /**
     * Creates a standard cube mesh with all 6 faces.
     */
    private MeshResource createCubeMesh() {
        // Vertex format: x, y, z, u, v (position + texture coordinates)
        float[] vertices = {
            // Front face (z = 0.5)
            -0.5f, -0.5f,  0.5f,  0.0f, 0.0f,  // bottom-left
             0.5f, -0.5f,  0.5f,  1.0f, 0.0f,  // bottom-right
             0.5f,  0.5f,  0.5f,  1.0f, 1.0f,  // top-right
            -0.5f,  0.5f,  0.5f,  0.0f, 1.0f,  // top-left
            
            // Back face (z = -0.5)
             0.5f, -0.5f, -0.5f,  0.0f, 0.0f,  // bottom-left
            -0.5f, -0.5f, -0.5f,  1.0f, 0.0f,  // bottom-right
            -0.5f,  0.5f, -0.5f,  1.0f, 1.0f,  // top-right
             0.5f,  0.5f, -0.5f,  0.0f, 1.0f,  // top-left
            
            // Left face (x = -0.5)
            -0.5f, -0.5f, -0.5f,  0.0f, 0.0f,  // bottom-left
            -0.5f, -0.5f,  0.5f,  1.0f, 0.0f,  // bottom-right
            -0.5f,  0.5f,  0.5f,  1.0f, 1.0f,  // top-right
            -0.5f,  0.5f, -0.5f,  0.0f, 1.0f,  // top-left
            
            // Right face (x = 0.5)
             0.5f, -0.5f,  0.5f,  0.0f, 0.0f,  // bottom-left
             0.5f, -0.5f, -0.5f,  1.0f, 0.0f,  // bottom-right
             0.5f,  0.5f, -0.5f,  1.0f, 1.0f,  // top-right
             0.5f,  0.5f,  0.5f,  0.0f, 1.0f,  // top-left
            
            // Top face (y = 0.5)
            -0.5f,  0.5f,  0.5f,  0.0f, 0.0f,  // bottom-left
             0.5f,  0.5f,  0.5f,  1.0f, 0.0f,  // bottom-right
             0.5f,  0.5f, -0.5f,  1.0f, 1.0f,  // top-right
            -0.5f,  0.5f, -0.5f,  0.0f, 1.0f,  // top-left
            
            // Bottom face (y = -0.5)
            -0.5f, -0.5f, -0.5f,  0.0f, 0.0f,  // bottom-left
             0.5f, -0.5f, -0.5f,  1.0f, 0.0f,  // bottom-right
             0.5f, -0.5f,  0.5f,  1.0f, 1.0f,  // top-right
            -0.5f, -0.5f,  0.5f,  0.0f, 1.0f   // top-left
        };
        
        // Face indices (2 triangles per face)
        int[] indices = {
            // Front face
            0,  1,  2,   2,  3,  0,
            // Back face  
            4,  5,  6,   6,  7,  4,
            // Left face
            8,  9,  10,  10, 11, 8,
            // Right face
            12, 13, 14,  14, 15, 12,
            // Top face
            16, 17, 18,  18, 19, 16,
            // Bottom face
            20, 21, 22,  22, 23, 20
        };
        
        return createMeshFromData(vertices, indices, "cube");
    }
    
    /**
     * Creates a cross-shaped mesh for flowers and plants.
     */
    private MeshResource createCrossMesh() {
        // Two intersecting quads forming a cross
        float[] vertices = {
            // First quad (diagonal)
            -0.5f, -0.5f,  0.0f,  0.0f, 0.0f,
             0.5f, -0.5f,  0.0f,  1.0f, 0.0f,
             0.5f,  0.5f,  0.0f,  1.0f, 1.0f,
            -0.5f,  0.5f,  0.0f,  0.0f, 1.0f,
            
            // Second quad (perpendicular diagonal)
             0.0f, -0.5f, -0.5f,  0.0f, 0.0f,
             0.0f, -0.5f,  0.5f,  1.0f, 0.0f,
             0.0f,  0.5f,  0.5f,  1.0f, 1.0f,
             0.0f,  0.5f, -0.5f,  0.0f, 1.0f
        };
        
        int[] indices = {
            // First quad (both sides)
            0, 1, 2,  2, 3, 0,  // front
            0, 3, 2,  2, 1, 0,  // back
            // Second quad (both sides) 
            4, 5, 6,  6, 7, 4,  // front
            4, 7, 6,  6, 5, 4   // back
        };
        
        return createMeshFromData(vertices, indices, "cross");
    }
    
    /**
     * Creates a sprite mesh for 2D item rendering.
     */
    private MeshResource createSpriteMesh() {
        // Single quad facing the camera
        float[] vertices = {
            -0.5f, -0.5f,  0.0f,  0.0f, 0.0f,  // bottom-left
             0.5f, -0.5f,  0.0f,  1.0f, 0.0f,  // bottom-right
             0.5f,  0.5f,  0.0f,  1.0f, 1.0f,  // top-right
            -0.5f,  0.5f,  0.0f,  0.0f, 1.0f   // top-left
        };
        
        int[] indices = {
            0, 1, 2,  2, 3, 0
        };
        
        return createMeshFromData(vertices, indices, "sprite");
    }
    
    /**
     * Creates a slab mesh (half-height cube).
     */
    private MeshResource createSlabMesh(boolean topSlab) {
        float yMin = topSlab ? 0.0f : -0.5f;
        float yMax = topSlab ? 0.5f : 0.0f;
        
        // Similar to cube but with adjusted Y coordinates
        float[] vertices = {
            // Front face
            -0.5f, yMin,  0.5f,  0.0f, 0.0f,
             0.5f, yMin,  0.5f,  1.0f, 0.0f,
             0.5f, yMax,  0.5f,  1.0f, 1.0f,
            -0.5f, yMax,  0.5f,  0.0f, 1.0f,
            // ... (other faces similar to cube but with adjusted Y)
        };
        
        int[] indices = {
            0, 1, 2,  2, 3, 0
            // ... (same pattern as cube)
        };
        
        return createMeshFromData(vertices, indices, topSlab ? "slab_top" : "slab_bottom");
    }
    
    /**
     * Creates a mesh resource from vertex and index data.
     */
    private MeshResource createMeshFromData(float[] vertices, int[] indices, String name) {
        // Generate VAO
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        
        // Generate and bind VBO
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        
        // Upload vertex data
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);
        
        // Generate and bind EBO
        int ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        
        // Upload index data
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW);
        
        // Setup vertex attributes
        // Position attribute (location 0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        
        // Texture coordinate attribute (location 1)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        
        // Unbind
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        
        return new MeshResource(vao, vbo, ebo, indices.length, vertices.length / 5, name);
    }
    
    @Override
    public void close() {
        if (!disposed) {
            // Cleanup all prebuilt meshes
            prebuiltMeshes.values().forEach(MeshResource::cleanup);
            prebuiltMeshes.clear();
            
            // Cleanup all custom meshes
            customMeshes.values().forEach(MeshResource::cleanup);
            customMeshes.clear();
            
            disposed = true;
            System.out.println("[MeshManager] Disposed and cleaned up all mesh resources");
        }
    }
    
    // === Enums and Data Classes ===
    
    /**
     * Common mesh types for pre-built geometries.
     */
    public enum MeshType {
        CUBE,           // Standard 6-faced cube
        CROSS,          // Cross-shaped for plants
        SPRITE,         // 2D quad for items
        SLAB_BOTTOM,    // Bottom half cube
        SLAB_TOP        // Top half cube
    }
    
    /**
     * Mesh resource containing OpenGL handles and metadata.
     */
    public static class MeshResource implements AutoCloseable {
        private final int vao;
        private final int vbo;
        private final int ebo;
        private final int indexCount;
        private final int vertexCount;
        private final String name;
        private boolean disposed = false;
        
        public MeshResource(int vao, int vbo, int ebo, int indexCount, int vertexCount, String name) {
            this.vao = vao;
            this.vbo = vbo;
            this.ebo = ebo;
            this.indexCount = indexCount;
            this.vertexCount = vertexCount;
            this.name = name;
        }
        
        public void bind() {
            if (disposed) {
                throw new IllegalStateException("MeshResource has been disposed: " + name);
            }
            glBindVertexArray(vao);
        }
        
        public void unbind() {
            glBindVertexArray(0);
        }
        
        public void draw() {
            if (disposed) {
                throw new IllegalStateException("MeshResource has been disposed: " + name);
            }
            glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
        }
        
        public void bindAndDraw() {
            bind();
            draw();
        }
        
        // Getters
        public int getVAO() { return vao; }
        public int getVBO() { return vbo; }
        public int getEBO() { return ebo; }
        public int getIndexCount() { return indexCount; }
        public int getVertexCount() { return vertexCount; }
        public int getTriangleCount() { return indexCount / 3; }
        public String getName() { return name; }
        
        public void cleanup() {
            close();
        }
        
        @Override
        public void close() {
            if (!disposed) {
                glDeleteVertexArrays(vao);
                glDeleteBuffers(vbo);
                glDeleteBuffers(ebo);
                disposed = true;
            }
        }
        
        @Override
        public String toString() {
            return String.format("MeshResource[%s: VAO=%d, vertices=%d, triangles=%d]", 
                               name, vao, vertexCount, getTriangleCount());
        }
    }
    
    /**
     * Statistics about managed meshes.
     */
    public static class MeshStatistics {
        private final int prebuiltMeshCount;
        private final int customMeshCount;
        private final long totalVertices;
        private final long totalTriangles;
        
        public MeshStatistics(int prebuiltMeshCount, int customMeshCount, 
                             long totalVertices, long totalTriangles) {
            this.prebuiltMeshCount = prebuiltMeshCount;
            this.customMeshCount = customMeshCount;
            this.totalVertices = totalVertices;
            this.totalTriangles = totalTriangles;
        }
        
        public int getPrebuiltMeshCount() { return prebuiltMeshCount; }
        public int getCustomMeshCount() { return customMeshCount; }
        public int getTotalMeshCount() { return prebuiltMeshCount + customMeshCount; }
        public long getTotalVertices() { return totalVertices; }
        public long getTotalTriangles() { return totalTriangles; }
        
        @Override
        public String toString() {
            return String.format("MeshStats[meshes=%d (%d prebuilt, %d custom), vertices=%d, triangles=%d]",
                               getTotalMeshCount(), prebuiltMeshCount, customMeshCount, totalVertices, totalTriangles);
        }
    }
}