package com.stonebreak.world.structure;

import com.stonebreak.world.World;
import com.stonebreak.world.structure.finders.LakeFinderStrategy;
import com.stonebreak.world.structure.finders.StructureFinderStrategy;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service for finding structures in the world using strategy pattern.
 *
 * <p>This service provides both synchronous and asynchronous APIs for locating
 * structures like lakes, villages, temples, etc. It delegates to structure-specific
 * {@link StructureFinderStrategy} implementations.</p>
 *
 * <p><strong>Architecture:</strong></p>
 * <ul>
 *     <li><strong>Facade Pattern:</strong> Provides unified interface to structure finding subsystem</li>
 *     <li><strong>Strategy Pattern:</strong> Delegates to structure-specific finders</li>
 *     <li><strong>Service Pattern:</strong> World-scoped service (similar to SaveService)</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * // Get from World
 * StructureFinder finder = world.getStructureFinder();
 *
 * // Synchronous search (blocks until complete)
 * Optional&lt;StructureSearchResult&gt; result = finder.findNearest(
 *     StructureType.LAKE,
 *     playerPosition,
 *     new StructureSearchConfig(8192)
 * );
 *
 * // Asynchronous search (non-blocking)
 * finder.findNearestAsync(StructureType.LAKE, playerPosition, config)
 *     .thenAccept(result -> {
 *         if (result.isPresent()) {
 *             System.out.println("Found: " + result.get());
 *         }
 *     });
 * </pre>
 * </p>
 *
 * @see StructureType
 * @see StructureSearchResult
 * @see StructureSearchConfig
 * @see StructureFinderStrategy
 */
public class StructureFinder {

    private final World world;
    private final Map<StructureType, StructureFinderStrategy> strategies;
    private final ExecutorService executorService;

    /**
     * Creates a new structure finder service for the specified world.
     *
     * @param world The world to search in (must not be null)
     * @throws IllegalArgumentException if world is null
     */
    public StructureFinder(World world) {
        if (world == null) {
            throw new IllegalArgumentException("World cannot be null");
        }

        this.world = world;
        this.strategies = new EnumMap<>(StructureType.class);
        this.executorService = Executors.newFixedThreadPool(2,
            r -> {
                Thread t = new Thread(r, "StructureFinder-Worker");
                t.setDaemon(true); // Don't prevent JVM shutdown
                return t;
            }
        );

        registerStrategies();
    }

    /**
     * Registers all available structure finder strategies.
     *
     * <p>To add support for new structure types:
     * <ol>
     *     <li>Add enum value to {@link StructureType}</li>
     *     <li>Create implementation of {@link StructureFinderStrategy}</li>
     *     <li>Register here: {@code strategies.put(StructureType.FOO, new FooFinderStrategy(world));}</li>
     * </ol>
     * </p>
     */
    private void registerStrategies() {
        // Lake finder (implemented)
        strategies.put(StructureType.LAKE, new LakeFinderStrategy(world));

        // Future structure finders
        // strategies.put(StructureType.VILLAGE, new VillageFinderStrategy(world));
        // strategies.put(StructureType.TEMPLE, new TempleFinderStrategy(world));
    }

    /**
     * Finds the nearest structure of the specified type from the origin point.
     *
     * <p><strong>Synchronous operation:</strong> Blocks the calling thread until
     * the search completes or exhausts the search radius.</p>
     *
     * <p>Use this method for small search radii (&lt; 2048 blocks) or when the
     * result is immediately needed. For large searches, prefer {@link #findNearestAsync}
     * to avoid blocking the game thread.</p>
     *
     * @param type The type of structure to find
     * @param origin The starting point for the search (usually player position)
     * @param config Search configuration (radius, etc.)
     * @return Optional containing the nearest structure, or empty if none found
     * @throws IllegalArgumentException if type/origin/config is null, or type is not supported
     */
    public Optional<StructureSearchResult> findNearest(
        StructureType type,
        Vector3f origin,
        StructureSearchConfig config
    ) {
        validateArguments(type, origin, config);

        StructureFinderStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException(
                "No finder strategy registered for structure type: " + type +
                ". This structure type is not yet implemented."
            );
        }

        return strategy.findNearest(origin, config);
    }

    /**
     * Asynchronously finds the nearest structure of the specified type.
     *
     * <p><strong>Asynchronous operation:</strong> Returns immediately with a
     * {@link CompletableFuture} that completes when the search finishes.</p>
     *
     * <p>Use this method for large search radii (&gt;= 2048 blocks) to avoid
     * blocking the game thread. The search executes on a background thread pool.</p>
     *
     * <p>Example:
     * <pre>
     * finder.findNearestAsync(StructureType.LAKE, playerPos, config)
     *     .thenAccept(result -> {
     *         // Runs on background thread when search completes
     *         if (result.isPresent()) {
     *             System.out.println("Found: " + result.get());
     *         }
     *     })
     *     .exceptionally(ex -> {
     *         System.err.println("Search failed: " + ex.getMessage());
     *         return null;
     *     });
     * </pre>
     * </p>
     *
     * @param type The type of structure to find
     * @param origin The starting point for the search (usually player position)
     * @param config Search configuration (radius, etc.)
     * @return CompletableFuture that completes with the search result
     * @throws IllegalArgumentException if type/origin/config is null, or type is not supported
     */
    public CompletableFuture<Optional<StructureSearchResult>> findNearestAsync(
        StructureType type,
        Vector3f origin,
        StructureSearchConfig config
    ) {
        // Validate on calling thread (fail fast)
        validateArguments(type, origin, config);

        return CompletableFuture.supplyAsync(
            () -> findNearest(type, origin, config),
            executorService
        );
    }

    /**
     * Validates method arguments.
     *
     * @param type Structure type (must not be null)
     * @param origin Search origin (must not be null)
     * @param config Search config (must not be null)
     * @throws IllegalArgumentException if any argument is null
     */
    private void validateArguments(StructureType type, Vector3f origin, StructureSearchConfig config) {
        if (type == null) {
            throw new IllegalArgumentException("Structure type cannot be null");
        }
        if (origin == null) {
            throw new IllegalArgumentException("Search origin cannot be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("Search config cannot be null");
        }
    }

    /**
     * Shuts down the background executor service.
     *
     * <p>Should be called when the world is unloaded or the game is shutting down.
     * After calling this method, async searches will fail.</p>
     */
    public void shutdown() {
        executorService.shutdown();
    }

    /**
     * Gets the world this finder is searching in.
     *
     * @return The world instance
     */
    public World getWorld() {
        return world;
    }

    /**
     * Checks if a structure finder strategy is registered for the given type.
     *
     * @param type The structure type to check
     * @return true if a strategy is registered, false otherwise
     */
    public boolean isSupported(StructureType type) {
        return strategies.containsKey(type);
    }
}
