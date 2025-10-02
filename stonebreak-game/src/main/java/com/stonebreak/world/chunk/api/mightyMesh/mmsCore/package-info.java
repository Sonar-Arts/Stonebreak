/**
 * Mighty Mesh System - Core API and data structures.
 *
 * <h2>Overview</h2>
 * This package contains the fundamental building blocks of the Mighty Mesh System (MMS),
 * a high-performance, SOLID-principles-based mesh generation and management system for
 * voxel-based chunk rendering.
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li>{@link com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshData} -
 *       Immutable CPU-side mesh data container</li>
 *   <li>{@link com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshBuilder} -
 *       Fluent builder for constructing mesh data</li>
 *   <li>{@link com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsRenderableHandle} -
 *       GPU resource handle with RAII lifecycle management</li>
 *   <li>{@link com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsBufferLayout} -
 *       Vertex buffer layout definitions and constants</li>
 *   <li>{@link com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshValidator} -
 *       Comprehensive mesh validation utilities</li>
 * </ul>
 *
 * <h2>Design Philosophy</h2>
 * <ul>
 *   <li><b>YAGNI</b>: Build only what's needed, no speculative features</li>
 *   <li><b>DRY</b>: Single source of truth for mesh operations</li>
 *   <li><b>SOLID</b>: Interface-based, single responsibility modules</li>
 *   <li><b>KISS</b>: Simple, clear APIs with minimal cognitive load</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Build mesh data
 * MmsMeshData mesh = MmsMeshBuilder.create()
 *     .beginFace()
 *         .addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0)
 *         .addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, 0)
 *         .addVertex(1, 1, 0, 1, 1, 0, 1, 0, 0, 0)
 *         .addVertex(0, 1, 0, 0, 1, 0, 1, 0, 0, 0)
 *     .endFace()
 *     .build();
 *
 * // Upload to GPU and render
 * try (MmsRenderableHandle handle = MmsRenderableHandle.upload(mesh)) {
 *     handle.render();
 * } // Automatically cleaned up
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>{@code MmsMeshData} is immutable and thread-safe</li>
 *   <li>{@code MmsMeshBuilder} is NOT thread-safe (use one per thread)</li>
 *   <li>{@code MmsRenderableHandle} uses atomic state management</li>
 *   <li>GPU operations MUST be called from the OpenGL thread</li>
 * </ul>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li>Memory: ~40 bytes per vertex + object overhead</li>
 *   <li>Construction: O(n) where n = vertex count</li>
 *   <li>GPU Upload: Single VBO/EBO with interleaved layout</li>
 *   <li>Validation: Optional, can be disabled for release builds</li>
 * </ul>
 *
 * @since MMS 1.0
 */
package com.stonebreak.world.chunk.api.mightyMesh.mmsCore;
