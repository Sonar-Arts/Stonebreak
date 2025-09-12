package com.stonebreak.rendering.core.API.commonBlockResources.models;

import java.util.Collection;
import java.util.Optional;

/**
 * Registry interface for block definitions following Dependency Inversion Principle.
 * 
 * This interface abstracts the storage and retrieval of block definitions,
 * allowing different implementations (JSON-based, database, etc.) while
 * maintaining the same contract.
 * 
 * Follows SOLID principles:
 * - Single Responsibility: Only manages block definition lookup
 * - Open/Closed: Extensible through different implementations
 * - Liskov Substitution: All implementations must honor the contract
 * - Interface Segregation: Minimal, focused interface
 * - Dependency Inversion: Depends on abstraction, not concrete classes
 */
public interface BlockDefinitionRegistry extends AutoCloseable {
    
    /**
     * Retrieves a block definition by its resource ID.
     * 
     * @param resourceId The resource identifier (e.g., "stonebreak:grass")
     * @return Optional containing the definition if found, empty otherwise
     */
    Optional<BlockDefinition> getDefinition(String resourceId);
    
    /**
     * Retrieves a block definition by its numeric ID.
     * 
     * @param numericId The numeric block ID
     * @return Optional containing the definition if found, empty otherwise
     */
    Optional<BlockDefinition> getDefinition(int numericId);
    
    /**
     * Checks if a block definition exists for the given resource ID.
     * 
     * @param resourceId The resource identifier
     * @return true if the definition exists, false otherwise
     */
    boolean hasDefinition(String resourceId);
    
    /**
     * Checks if a block definition exists for the given numeric ID.
     * 
     * @param numericId The numeric block ID
     * @return true if the definition exists, false otherwise
     */
    boolean hasDefinition(int numericId);
    
    /**
     * Gets all registered block definitions.
     * 
     * @return Immutable collection of all block definitions
     */
    Collection<BlockDefinition> getAllDefinitions();
    
    /**
     * Gets all definitions for a specific namespace.
     * 
     * @param namespace The namespace to filter by (e.g., "stonebreak")
     * @return Immutable collection of definitions in the namespace
     */
    Collection<BlockDefinition> getDefinitionsByNamespace(String namespace);
    
    /**
     * Gets the total number of registered definitions.
     * 
     * @return The count of registered definitions
     */
    int getDefinitionCount();
    
    /**
     * Registers a new block definition.
     * 
     * @param definition The block definition to register
     * @throws IllegalArgumentException if definition conflicts with existing registration
     * @throws UnsupportedOperationException if registry is read-only
     */
    void registerDefinition(BlockDefinition definition);
    
    /**
     * Checks if the registry supports modification operations.
     * 
     * @return true if definitions can be registered/modified, false for read-only registries
     */
    boolean isModifiable();
    
    /**
     * Gets the schema version of the registry data format.
     * 
     * @return The schema version string
     */
    String getSchemaVersion();
    
    /**
     * RAII cleanup - releases any resources held by the registry.
     */
    @Override
    void close();
}