# Entity Save/Load Test Documentation

## Overview
`EntitySaveLoadTest.java` is a comprehensive unit test suite that verifies entity serialization and deserialization for block drops and cow entities. This test ensures that all critical entity properties are properly preserved when saving and loading game state.

## Test Coverage

### BlockDrop Entity Tests

#### 1. Basic Serialization (`testBlockDropBasicSerialization`)
Verifies that core block drop properties are preserved:
- **Position** (Vector3f) - Exact 3D coordinates
- **Velocity** (Vector3f) - Movement vector
- **Block Type** (BlockType enum) - Type of block being dropped
- **Despawn Timer** (float) - Time remaining before despawn
- **Stack Count** (int) - Number of items in the stack
- **Age** (float) - Time since entity creation
- **Alive State** (boolean) - Entity lifecycle state

#### 2. Stacked Drops (`testBlockDropStackedSerialization`)
Tests serialization of block drops with multiple items:
- Stack counts up to 64 items
- Ensures stack count is preserved exactly
- Verifies position and block type remain intact

#### 3. Different Block Types (`testBlockDropDifferentBlockTypes`)
Tests all common block types:
- GRASS, DIRT, STONE, WOOD, LEAVES, SAND
- Ensures block type enum serialization works for all types

#### 4. Near Expiration (`testBlockDropNearExpiration`)
Tests edge case of nearly expired block drops:
- Despawn timer close to 0
- High age values (295+ seconds)
- Ensures timer precision is maintained

### Cow Entity Tests

#### 1. Basic Serialization (`testCowBasicSerialization`)
Verifies that core cow properties are preserved:
- **Position, Velocity, Rotation** (Vector3f) - Transform data
- **Texture Variant** (String) - Visual variant ("default", "angus", "highland", "jersey")
- **Health** (float) - Current and maximum health
- **Milk State** (boolean) - Whether cow can be milked
- **Milk Regen Timer** (float) - Time until milk regenerates
- **AI State** (String) - Current behavior state ("IDLE", "WANDERING", "GRAZING")
- **Age** (float) - Time since cow spawned

#### 2. Texture Variants (`testCowTextureVariants`)
Tests all 4 cow texture variants:
- default: Cross-eyed expression with gentle eyebrows
- angus: Derpy cute with white blaze stripe
- highland: Surprised curious with fluffy fur
- jersey: Goofy derpy with asymmetric eyes

#### 3. AI States (`testCowAIStates`)
Tests all cow behavior states:
- IDLE: Standing still
- WANDERING: Moving around randomly
- GRAZING: Eating grass

#### 4. Milk Regeneration (`testCowMilkRegeneration`)
Tests milk system state:
- Recently milked cows (canBeMilked = false)
- Regeneration timer tracking (0-300 seconds)
- Ensures regeneration state persists across saves

#### 5. Damaged Cow (`testCowDamaged`)
Tests health state preservation:
- Partial health values (e.g., 5.5/10)
- Ensures health is restored exactly
- Verifies alive state is maintained

### Multi-Entity Tests

#### 1. Mixed Entity Types (`testMixedEntityTypes`)
Verifies multiple entity types can coexist:
- Block drops and cows in same save data
- Correct entity type identification
- Proper deserialization to specific types

#### 2. Immutability Test (`testEntityDataImmutability`)
Ensures EntityData is truly immutable:
- Modifying returned values doesn't affect stored data
- Defensive copying works correctly
- Thread-safe by design

## Entity Data Model

### EntityData Class
Immutable data model following SOLID principles:

```java
EntityData.builder()
    .entityType(EntityType.BLOCK_DROP | EntityType.COW)
    .position(Vector3f)
    .velocity(Vector3f)
    .rotation(Vector3f)
    .health(float)
    .maxHealth(float)
    .age(float)
    .alive(boolean)
    .customData(Map<String, Object>)
    .build()
```

### BlockDropData
Specialized wrapper for block drop entities:
- blockType: BlockType enum
- despawnTimer: float (seconds remaining)
- stackCount: int (1-64 items)

### CowData
Specialized wrapper for cow entities:
- textureVariant: String ("default", "angus", "highland", "jersey")
- canBeMilked: boolean
- milkRegenTimer: float (0-300 seconds)
- aiState: String ("IDLE", "WANDERING", "GRAZING")

## Test Precision

All floating-point comparisons use `FLOAT_EPSILON = 0.0001f` for numerical stability.

## Running the Tests

### Using IntelliJ IDEA:
1. Open `EntitySaveLoadTest.java`
2. Right-click on the class name
3. Select "Run 'EntitySaveLoadTest'"

### Using Maven:
```bash
mvn test -Dtest=EntitySaveLoadTest
```

### Running Specific Tests:
```bash
mvn test -Dtest=EntitySaveLoadTest#testBlockDropBasicSerialization
mvn test -Dtest=EntitySaveLoadTest#testCowBasicSerialization
```

## Integration with Save System

### Current Status
✅ EntityData model created
✅ Comprehensive test suite written
⏳ Entity serialization methods (pending)
⏳ ChunkData integration (pending)

### Next Steps

1. **Implement Entity Serialization Methods**
   - Create `EntitySerializer.java` class
   - Implement `serialize(Entity)` → `EntityData`
   - Implement `deserialize(EntityData, World)` → `Entity`

2. **Update ChunkData Model**
   - Add `List<EntityData> entities` field
   - Update builder pattern
   - Ensure immutability is maintained

3. **Integrate with Save Pipeline**
   - Update `BinaryChunkSerializer` to include entities
   - Modify chunk loading to restore entities
   - Add entity data to snapshot system

## Design Principles

### SOLID Compliance
- **Single Responsibility**: EntityData only stores data, no logic
- **Open/Closed**: Extensible via customData map
- **Liskov Substitution**: BlockDropData and CowData extend base EntityData
- **Interface Segregation**: Specific data classes for each entity type
- **Dependency Inversion**: Depends on abstractions (EntityType enum)

### Thread Safety
- Immutable data objects
- Defensive copying for all mutable fields
- No shared mutable state

### Testability
- Builder pattern for easy test data creation
- Clear separation between data and behavior
- Comprehensive edge case coverage

## Test Metrics

- **Total Tests**: 11 test methods
- **BlockDrop Tests**: 4 comprehensive tests
- **Cow Tests**: 5 comprehensive tests
- **Integration Tests**: 2 multi-entity tests
- **Code Coverage Target**: 100% of EntityData classes

## Known Limitations

1. **No World Dependency**: Tests don't require a full World instance
2. **Mock-Free**: Uses actual data structures for realistic testing
3. **Future Extension**: Easy to add new entity types by extending EntityData

## Troubleshooting

### Test Failures

If tests fail, check:
1. EntityData.java is compiled correctly
2. No changes to EntityType enum values
3. CustomData types match expected types
4. Vector3f values are within FLOAT_EPSILON tolerance

### Adding New Entity Types

To add a new entity type to tests:
1. Create new specialized data class in EntityData.java
2. Add test methods following existing patterns
3. Test all entity-specific properties
4. Add to mixed entity type test

## References

- EntityData Model: `src/main/java/com/stonebreak/world/save/model/EntityData.java`
- Test Suite: `src/test/java/com/stonebreak/world/save/EntitySaveLoadTest.java`
- Entity Classes: `src/main/java/com/stonebreak/mobs/entities/`
- Cow Implementation: `src/main/java/com/stonebreak/mobs/cow/Cow.java`
- BlockDrop Implementation: `src/main/java/com/stonebreak/mobs/entities/BlockDrop.java`

## Version History

- **v1.0** (Current): Initial implementation with BlockDrop and Cow support
- Future versions will add: Sheep, Pigs, Chickens, and other entity types
