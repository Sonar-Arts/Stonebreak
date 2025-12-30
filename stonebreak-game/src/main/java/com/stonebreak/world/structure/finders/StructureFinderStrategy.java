package com.stonebreak.world.structure.finders;

import com.stonebreak.world.structure.StructureSearchConfig;
import com.stonebreak.world.structure.StructureSearchResult;
import org.joml.Vector3f;

import java.util.Optional;

/**
 * Strategy interface for finding specific types of structures in the world.
 *
 * <p>Implementations of this interface contain the logic for detecting and
 * locating specific structure types (lakes, villages, temples, etc.).</p>
 *
 * <p>The strategy pattern allows the {@link com.stonebreak.world.structure.StructureFinder}
 * service to delegate structure-specific search logic to specialized implementations
 * without tight coupling.</p>
 *
 * <p>Example implementation:
 * <pre>
 * public class LakeFinderStrategy implements StructureFinderStrategy {
 *     &#64;Override
 *     public Optional&lt;StructureSearchResult&gt; findNearest(
 *         Vector3f origin,
 *         StructureSearchConfig config
 *     ) {
 *         // Lake-specific detection logic here
 *         // Check terrain height, humidity, temperature, basin depth, etc.
 *         return Optional.of(result);
 *     }
 * }
 * </pre>
 * </p>
 *
 * <p><strong>Design Principles:</strong></p>
 * <ul>
 *     <li><strong>Single Responsibility:</strong> Each strategy handles one structure type</li>
 *     <li><strong>Open/Closed:</strong> New structure types added without modifying existing code</li>
 *     <li><strong>Dependency Inversion:</strong> High-level code depends on this interface, not concrete implementations</li>
 * </ul>
 *
 * @see com.stonebreak.world.structure.StructureFinder
 * @see LakeFinderStrategy
 */
public interface StructureFinderStrategy {

    /**
     * Finds the nearest structure of this type from the origin point.
     *
     * <p>Searches within the radius specified in the config. Returns an empty
     * Optional if no structure is found within the search area.</p>
     *
     * <p><strong>Performance Considerations:</strong></p>
     * <ul>
     *     <li>Implementations should use expanding search patterns (spiral, rings, etc.)</li>
     *     <li>Early exit when a structure is found and verified to be closest</li>
     *     <li>Cache terrain/noise data when possible to avoid redundant sampling</li>
     *     <li>For large search radii, consider approximate distance checks before precise validation</li>
     * </ul>
     *
     * @param origin The starting point for the search (usually player position)
     * @param config Search configuration (radius, timeout, etc.)
     * @return Optional containing the nearest structure, or empty if none found
     * @throws IllegalArgumentException if origin or config is null
     */
    Optional<StructureSearchResult> findNearest(Vector3f origin, StructureSearchConfig config);
}
