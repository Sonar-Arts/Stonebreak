# Chunk Texture Pipeline Test Suite

Comprehensive unit and integration tests for the Mighty Mesh System (MMS) chunk texture pipeline.

## Test Coverage

### Component Tests

#### 1. MmsAtlasTextureMapperTest
Tests texture coordinate generation from the texture atlas.

**Coverage:**
- ✅ Constructor validation
- ✅ Face texture coordinate generation (6 cube faces)
- ✅ Cross-section block texture coordinates (flowers)
- ✅ Water animation flags (animated water surface)
- ✅ Alpha testing flags (transparency)
- ✅ Face index validation
- ✅ Texture atlas integration

**Key Test Cases:**
- `shouldGenerateTextureCoordinatesForCubeFace()` - Validates quad UV mapping
- `shouldGenerateCorrectUVsForAllSixCubeFaces()` - Tests all cube faces
- `shouldGenerateTextureCoordinatesForCrossBlocks()` - Cross-section geometry
- `shouldGenerateWaterFlagsForTopFaceWithCornerHeights()` - Water animation
- `shouldGenerateAlphaFlagsForTransparentBlocks()` - Alpha testing

#### 2. MmsMeshBuilderTest
Tests the fluent mesh building API.

**Coverage:**
- ✅ Builder initialization and state management
- ✅ Vertex addition with all attributes (position, texture, normal, flags)
- ✅ Face building (begin/end face workflow)
- ✅ Index generation for quads
- ✅ Builder reset and reuse
- ✅ Validation on build
- ✅ Texture coordinate preservation

**Key Test Cases:**
- `shouldBuildCompleteQuadFace()` - Complete face building workflow
- `shouldGenerateCorrectTriangleIndicesForQuad()` - Index generation
- `shouldPreserveTextureCoordinatesInMesh()` - Data integrity
- `shouldReuseBuilderAfterBuildAndReset()` - Builder reuse
- `shouldThrowWhenFaceHasWrongVertexCount()` - Error handling

#### 3. MmsMeshPipelineTest
Tests the mesh generation and GPU upload pipeline.

**Coverage:**
- ✅ Pipeline initialization
- ✅ Chunk scheduling and queue management
- ✅ GPU cleanup coordination
- ✅ Failed chunk retry logic
- ✅ Thread safety
- ✅ Pipeline shutdown

**Key Test Cases:**
- `shouldScheduleChunkForGPUCleanup()` - Resource cleanup
- `shouldHandleConcurrentChunkOperationsSafely()` - Thread safety
- `shouldShutdownPipelineCleanly()` - Lifecycle management
- `shouldRejectOperationsAfterShutdown()` - State validation

### Integration Tests

#### ChunkTexturePipelineIntegrationTest
End-to-end tests of the complete texture pipeline.

**Coverage:**
- ✅ Complete cube block rendering (6 faces)
- ✅ Cross-section block rendering (flowers)
- ✅ Water blocks with animated surface
- ✅ Leaf blocks with alpha testing
- ✅ Multi-block scenes
- ✅ Builder reuse across multiple meshes
- ✅ Texture coordinate consistency

**Key Test Cases:**
- `shouldBuildCompleteTexturedCubeBlock()` - Full cube workflow
- `shouldBuildTexturedCrossBlock()` - Flower rendering
- `shouldBuildTexturedWaterBlockWithAnimatedSurface()` - Water animation
- `shouldBuildSceneWithMultipleBlockTypes()` - Mixed block types
- `shouldGenerateConsistentTextureCoordinatesAcrossPipeline()` - Data consistency

## Running Tests

### Run All Tests
```bash
mvn test -Dtest=ChunkTexturePipelineTestSuite
```

### Run Individual Test Classes
```bash
# Texture mapper tests
mvn test -Dtest=MmsAtlasTextureMapperTest

# Mesh builder tests
mvn test -Dtest=MmsMeshBuilderTest

# Pipeline tests
mvn test -Dtest=MmsMeshPipelineTest

# Integration tests
mvn test -Dtest=ChunkTexturePipelineIntegrationTest
```

### Run via IDE
- **IntelliJ IDEA**: Right-click on `ChunkTexturePipelineTestSuite` → Run
- Individual tests can be run by right-clicking on the test class or method

## Test Architecture

### Mocking Strategy
- **TextureAtlas**: Mocked using Mockito to isolate texture coordinate logic
- **Chunk/World**: Mocked for pipeline tests to avoid OpenGL context requirements
- **Pure Logic Tests**: Most tests are pure unit tests without external dependencies

### Test Data
- **Texture UVs**: Standard atlas coordinates (u1, v1, u2, v2 format)
- **Water Heights**: Float values 0.0-1.0 for animation testing
- **Alpha Flags**: 0.0 (opaque) or 1.0 (transparent)

### Validation Approach
1. **Component-level**: Each component tested in isolation
2. **Integration-level**: Complete workflows tested end-to-end
3. **Error handling**: Invalid inputs and edge cases covered
4. **Thread safety**: Concurrent operations validated

## Dependencies

The tests require:
- **JUnit 5 (Jupiter)**: Test framework
- **Mockito**: Mocking framework for dependencies
- **JOML**: Math library (indirect dependency)

Dependencies are managed through Maven in the parent POM.

## Test Metrics

### Total Tests: 70+
- MmsAtlasTextureMapperTest: 25 tests
- MmsMeshBuilderTest: 30 tests
- MmsMeshPipelineTest: 15 tests
- ChunkTexturePipelineIntegrationTest: 10 tests

### Coverage Areas
- ✅ Texture coordinate generation
- ✅ Mesh vertex construction
- ✅ Index generation
- ✅ Water animation flags
- ✅ Alpha testing flags
- ✅ Multi-threading safety
- ✅ Resource cleanup
- ✅ Error handling
- ✅ Edge cases

## Adding New Tests

### 1. Component Test
```java
@Test
@DisplayName("Should handle new block type")
void shouldHandleNewBlockType() {
    // Arrange
    when(mockAtlas.getBlockFaceUVs(...)).thenReturn(...);

    // Act
    float[] result = mapper.generateFaceTextureCoordinates(...);

    // Assert
    assertEquals(...);
}
```

### 2. Integration Test
```java
@Test
@DisplayName("Should build new geometry type")
void shouldBuildNewGeometryType() {
    // Generate texture coordinates
    float[] texCoords = textureMapper.generate...();

    // Build mesh
    meshBuilder.beginFace();
    // ... add vertices with texCoords
    meshBuilder.endFace();

    // Verify result
    MmsMeshData mesh = meshBuilder.build();
    assertEquals(...);
}
```

## Common Issues

### Mockito Not Found
**Solution**: Ensure parent POM has Mockito dependencies:
```xml
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <version>${mockito.version}</version>
    <scope>test</scope>
</dependency>
```

### OpenGL Context Required
**Solution**: Pipeline GPU tests are mocked to avoid OpenGL requirement. For actual GPU testing, see visual tests in `CBRVisualTest.java`.

### Thread Safety Issues
**Solution**: Use `shouldHandleConcurrentChunkOperationsSafely()` as template for thread safety tests.

## Future Enhancements

- [ ] Performance benchmarking tests
- [ ] Memory leak detection tests
- [ ] GPU upload verification (requires OpenGL context)
- [ ] Shader integration tests
- [ ] Visual regression tests
- [ ] Stress tests for large chunk meshes

## Related Documentation

- `docs/MMS-Architecture.md` - MMS system architecture
- `docs/Adding-New-Textures-Guide.md` - Texture system guide
- `CLAUDE.md` - Project overview and structure
