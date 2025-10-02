/**
 * CCO Core Interfaces - SOLID-compliant abstractions
 *
 * <p>Provides interface-based contracts for chunk operations following SOLID principles:
 *
 * <h2>Interface Segregation</h2>
 * <ul>
 *   <li>{@link CcoChunkData} - Read-only data access</li>
 *   <li>{@link CcoBlockOperations} - Block manipulation (extends CcoChunkData)</li>
 *   <li>{@link CcoStateManager} - Lifecycle state management</li>
 *   <li>{@link CcoDirtyFlags} - Modification tracking</li>
 *   <li>{@link CcoSerializable} - Save system integration</li>
 *   <li>{@link CcoRenderable} - Rendering system integration</li>
 * </ul>
 *
 * <h2>Dependency Inversion</h2>
 * <p>Consumers depend on interfaces, not concrete implementations. This allows:
 * <ul>
 *   <li>Multiple implementations (e.g., compressed chunks, LOD chunks)</li>
 *   <li>Mock implementations for testing</li>
 *   <li>Runtime swapping of implementations</li>
 * </ul>
 *
 * <h2>Single Responsibility</h2>
 * <p>Each interface has one clear purpose:
 * <ul>
 *   <li>CcoChunkData - Data access only</li>
 *   <li>CcoStateManager - State transitions only</li>
 *   <li>CcoDirtyFlags - Dirty tracking only</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Depend on interfaces, not implementations
 * void processChunk(CcoBlockOperations chunk, CcoStateManager state) {
 *     if (!state.isRenderable()) return;
 *
 *     // Modify chunk
 *     chunk.setBlock(0, 64, 0, BlockType.STONE);
 *
 *     // Check dirty state
 *     if (chunk instanceof CcoDirtyFlags flags) {
 *         if (flags.isMeshDirty()) {
 *             // Regenerate mesh
 *         }
 *     }
 * }
 * }</pre>
 *
 * @see com.stonebreak.world.chunk.api.commonChunkOperations.CcoFactory
 * @since 1.0
 */
package com.stonebreak.world.chunk.api.commonChunkOperations.core;
