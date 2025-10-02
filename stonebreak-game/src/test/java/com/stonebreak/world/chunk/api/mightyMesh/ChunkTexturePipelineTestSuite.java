package com.stonebreak.world.chunk.api.mightyMesh;

import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshBuilderTest;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshPipelineTest;
import com.stonebreak.world.chunk.api.mightyMesh.mmsTexturing.MmsAtlasTextureMapperTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * Test suite for the complete Chunk Texture Pipeline.
 *
 * Runs all texture pipeline tests in a coordinated manner:
 * - Component-level tests (texture mapper, mesh builder, pipeline)
 * - Integration tests (complete workflow)
 *
 * Run this suite to validate the entire chunk texture system.
 */
@Suite
@SelectClasses({
    MmsAtlasTextureMapperTest.class,
    MmsMeshBuilderTest.class,
    MmsMeshPipelineTest.class,
    ChunkTexturePipelineIntegrationTest.class
})
public class ChunkTexturePipelineTestSuite {
    // Test suite configuration
}
