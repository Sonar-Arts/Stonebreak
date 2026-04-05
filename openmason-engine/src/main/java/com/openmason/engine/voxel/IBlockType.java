package com.openmason.engine.voxel;

/**
 * Engine-level block type contract.
 *
 * <p>Abstracts away game-specific block enumerations so that engine
 * systems (MMS, CCO) can operate on blocks without depending on
 * any particular game's block definitions.
 *
 * <p>Implementations in a game module wrap the game's block type
 * (e.g., an enum) and delegate these methods.
 */
public interface IBlockType {

    /** Unique numeric identifier for this block type. */
    int getId();

    /** Human-readable name (e.g., "Dirt", "Stone"). */
    String getName();

    /** Whether the block is solid for collision purposes. */
    boolean isSolid();

    /** Whether the block can be broken by the player. */
    boolean isBreakable();

    /**
     * Whether the block is transparent for face culling.
     * Transparent blocks (water, leaves, flowers) use special culling rules.
     */
    boolean isTransparent();

    /** Whether this block type represents air (empty space). */
    boolean isAir();
}
