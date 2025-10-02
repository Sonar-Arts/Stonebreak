/**
 * CCO Core Interfaces - SOLID-compliant abstractions
 *
 * <p>Provides interface-based contracts for chunk operations following SOLID principles:
 *
 * <h2>Interface Segregation</h2>
 * <ul>
 *   <li>{@link CcoChunkData} - Read-only data access</li>
 *   <li>{@link CcoBlockOperations} - Block manipulation (extends CcoChunkData)</li>
 *   <li>{@link CcoDirtyFlags} - Modification tracking</li>
 *   <li>{@link CcoSerializable} - Save system integration</li>
 *   <li>{@link CcoRenderable} - Rendering system integration</li>
 * </ul>
 *
 * <h2>State Management</h2>
 * <p>Lifecycle state management is handled by {@link com.stonebreak.world.chunk.api.commonChunkOperations.state.CcoAtomicStateManager}
 * which provides lock-free multi-state tracking with integrated dirty flag management.
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
 *   <li>CcoDirtyFlags - Dirty tracking only</li>
 *   <li>CcoSerializable - Save system integration only</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Depend on interfaces, not implementations
 * void processChunk(CcoBlockOperations chunk, CcoAtomicStateManager stateManager) {
 *     if (!stateManager.isRenderable()) return;
 *
 *     // Modify chunk
 *     chunk.setBlock(0, 64, 0, BlockType.STONE);
 *
 *     // Check dirty state via integrated tracker
 *     if (stateManager.needsSave()) {
 *         // Save chunk
 *     }
 * }
 * }</pre>
 *
 * @see com.stonebreak.world.chunk.api.commonChunkOperations.CcoFactory
 * @see com.stonebreak.world.chunk.api.commonChunkOperations.state.CcoAtomicStateManager
 * @since 1.0
 */
package com.stonebreak.world.chunk.api.commonChunkOperations.core;
