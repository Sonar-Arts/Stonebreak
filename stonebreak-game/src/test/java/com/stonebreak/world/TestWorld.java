package com.stonebreak.world;

import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Test helper class that exposes the protected World constructor for testing.
 * This class exists solely to provide test access to World's test-mode constructor.
 */
public class TestWorld extends World {

    /**
     * Creates a test world with the specified configuration, seed, and test mode.
     *
     * @param config World configuration
     * @param seed World generation seed
     * @param testMode If true, skips MmsAPI/rendering initialization (for tests only)
     */
    public TestWorld(WorldConfiguration config, long seed, boolean testMode) {
        super(config, seed, testMode);
    }

    /**
     * Creates a test world with the specified configuration and seed.
     * Test mode is automatically enabled.
     *
     * @param config World configuration
     * @param seed World generation seed
     */
    public TestWorld(WorldConfiguration config, long seed) {
        super(config, seed, true);
    }
}
