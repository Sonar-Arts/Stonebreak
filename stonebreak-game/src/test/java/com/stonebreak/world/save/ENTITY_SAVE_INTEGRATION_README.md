# Entity Save System Integration - Complete

## Overview

The entity save system is now **fully integrated** with the chunk save/load pipeline. Block drops and cow entities are automatically saved when chunks are saved and restored when chunks are loaded.

## Integration Architecture

### Data Flow: Saving Entities

```
World Save Request
    ↓
Chunk.createSnapshot(World)
    ↓
EntityManager.getEntitiesInChunk(chunkX, chunkZ)
    ↓
EntitySerializer.serialize(Entity) → EntityData
    ↓
CcoSerializableSnapshot (includes EntityData list)
    ↓
ChunkData.toChunkData() (includes entities)
    ↓
BinaryChunkSerializer.serialize(ChunkData)
    ↓
JsonEntitySerializer.serialize(EntityData) → JSON bytes
    ↓
Binary chunk file with embedded entity JSON
```

### Data Flow: Loading Entities

```
World Load Request
    ↓
BinaryChunkSerializer.deserialize(bytes)
    ↓
JsonEntitySerializer.deserialize(bytes) → EntityData
    ↓
ChunkData (includes entities list)
    ↓
CcoSerializableSnapshot.fromChunkData()
    ↓
Chunk.loadFromSnapshot(snapshot, World)
    ↓
EntityManager.loadEntitiesForChunk(entityDataList)
    ↓
EntitySerializer.deserialize(EntityData, World) → Entity
    ↓
Entities spawned in world
```

## Components Modified

### 1. BinaryChunkSerializer.java
**Location**: `com.stonebreak.world.save.serialization.BinaryChunkSerializer`

**Changes**:
- Added `serializeEntityData()` method to embed JSON entity data in binary chunk format
- Added `deserializeEntityData()` method to extract entity data from binary format
- Updated `serialize()` to include entity data after water metadata
- Updated `deserialize()` to restore entities from binary format

**Format**:
```
[Header][Palette][Blocks][WaterMetadata][EntityData]
EntityData format: [count:int][entity1Len:int][entity1JSON][entity2Len:int][entity2JSON]...
```

### 2. JsonEntitySerializer.java (NEW)
**Location**: `com.stonebreak.world.save.serialization.JsonEntitySerializer`

**Purpose**: Serializes EntityData to/from JSON format

**Features**:
- Handles all entity types (BLOCK_DROP, COW)
- Serializes position, velocity, rotation vectors
- Serializes health, age, alive state
- Serializes entity-specific custom data
- Type-safe deserialization with proper type conversion

### 3. JsonParsingUtil.java
**Location**: `com.stonebreak.world.save.util.JsonParsingUtil`

**Additions**:
- `extractBoolean(String, String)` - Extract boolean without default
- `extractDouble(String, String)` - Extract double values
- `extractStringFromObject()` - Extract from nested JSON objects
- `extractIntFromObject()` - Extract int from nested objects
- `extractDoubleFromObject()` - Extract double from nested objects
- `extractBooleanFromObject()` - Extract boolean from nested objects

### 4. EntityData.java
**Location**: `com.stonebreak.world.save.model.EntityData`

**Purpose**: Immutable data model for entity serialization

**Nested Classes**:
- `EntityData.BlockDropData` - Wrapper for block drop specific data
- `EntityData.CowData` - Wrapper for cow specific data

### 5. ChunkData.java
**Location**: `com.stonebreak.world.save.model.ChunkData`

**Changes**:
- Added `List<EntityData> entities` field
- Updated builder to accept entities
- Defensive copying for entity list to maintain immutability

### 6. CcoSerializableSnapshot.java
**Location**: `com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoSerializableSnapshot`

**Changes**:
- Added `List<EntityData> entities` field
- Added constructor accepting entities
- Updated `toChunkData()` to include entities
- Added `getEntities()` method with defensive copy

### 7. Chunk.java
**Location**: `com.stonebreak.world.chunk.Chunk`

**Changes**:
- **createSnapshot(World)**:
  - Calls `EntityManager.getEntitiesInChunk()` to collect entities
  - Includes entities in CcoSerializableSnapshot
  - Logs entity count for debugging
- **loadFromSnapshot()**:
  - Calls `EntityManager.loadEntitiesForChunk()` to restore entities
  - Updates metadata with entity count
  - Logs entity loading for debugging

### 8. EntityManager.java
**Location**: `com.stonebreak.mobs.entities.EntityManager`

**New Methods**:
- **getEntitiesInChunk(chunkX, chunkZ)** → `List<EntityData>`
  - Finds all entities within chunk bounds
  - Serializes each entity to EntityData
  - Returns list ready for saving
- **loadEntitiesForChunk(entityDataList, chunkX, chunkZ)**
  - Deserializes EntityData to Entity objects
  - Adds entities to EntityManager
  - Logs loaded entity count

### 9. EntitySerializer.java
**Location**: `com.stonebreak.world.save.serialization.EntitySerializer`

**Methods**:
- `serialize(Entity)` → `EntityData`
- `deserialize(EntityData, World)` → `Entity`
- `serializeEntities(List<Entity>)` → `List<EntityData>`
- `deserializeEntities(List<EntityData>, World)` → `List<Entity>`

**Implementation**:
- Uses reflection to access private fields (blockType, despawnTimer, etc.)
- Handles BlockDrop and Cow entity types
- Properly restores all entity state including:
  - Position, velocity, rotation
  - Health, age, alive state
  - Block-specific: blockType, despawnTimer, stackCount
  - Cow-specific: textureVariant, canBeMilked, milkRegenTimer, aiState

## Test Coverage

### Unit Tests (EntitySaveLoadTest.java)
11 comprehensive tests covering:
- BlockDrop serialization (4 tests)
- Cow serialization (5 tests)
- Mixed entity types (1 test)
- Immutability verification (1 test)

### Integration Tests (EntitySaveLoadIntegrationTest.java)
6 comprehensive tests covering:
- Save/load chunk with block drops
- Save/load chunk with cows
- Save/load chunk with mixed entities
- Save/load empty chunk
- Entity data integrity verification
- Full binary serialization cycle

## Usage Example

```java
// Saving entities is automatic when chunks are saved
Chunk chunk = world.getChunk(0, 0);
CcoSerializableSnapshot snapshot = chunk.createSnapshot(world);
// snapshot automatically includes all entities in the chunk

// Loading entities is automatic when chunks are loaded
ChunkData chunkData = loadChunkDataFromDisk();
chunk.loadFromSnapshot(chunkData.toSnapshot(), world);
// All entities are automatically restored to EntityManager
```

## Entity-Specific Serialization

### BlockDrop
**Serialized Properties**:
- Position (Vector3f)
- Velocity (Vector3f)
- Block type (BlockType enum)
- Despawn timer (float, 0-300 seconds)
- Stack count (int, 1-64)
- Age (float)
- Alive state (boolean)

**Example JSON**:
```json
{
  "entityType": "BLOCK_DROP",
  "position": {"x": 10.5, "y": 64.0, "z": 20.3},
  "velocity": {"x": 0.5, "y": 2.0, "z": -0.3},
  "rotation": {"x": 0, "y": 0, "z": 0},
  "health": 1.0,
  "maxHealth": 1.0,
  "age": 5.5,
  "alive": true,
  "customData": {
    "blockType": "DIRT",
    "despawnTimer": 250.0,
    "stackCount": 1
  }
}
```

### Cow
**Serialized Properties**:
- Position, velocity, rotation (Vector3f)
- Health (current/max)
- Texture variant (String: "default", "angus", "highland", "jersey")
- Milk state (canBeMilked: boolean, milkRegenTimer: float)
- AI state (String: "IDLE", "WANDERING", "GRAZING")
- Age (float)
- Alive state (boolean)

**Example JSON**:
```json
{
  "entityType": "COW",
  "position": {"x": 50.0, "y": 65.0, "z": 30.0},
  "velocity": {"x": 0.2, "y": 0.0, "z": 0.1},
  "rotation": {"x": 0.0, "y": 45.0, "z": 0.0},
  "health": 8.0,
  "maxHealth": 10.0,
  "age": 120.0,
  "alive": true,
  "customData": {
    "textureVariant": "angus",
    "canBeMilked": true,
    "milkRegenTimer": 0.0,
    "aiState": "WANDERING"
  }
}
```

## Performance Considerations

### Memory
- EntityData objects are immutable (thread-safe, GC-friendly)
- Defensive copying prevents accidental modification
- Entity lists are only created during save/load operations

### Serialization
- JSON used for entity data (human-readable, flexible)
- Binary format used for chunk data (compact, fast)
- LZ4 compression applied to entire chunk (including entities)
- Typical overhead: ~100-200 bytes per entity

### Chunk Loading
- Entities loaded after blocks and water metadata
- Entity spawning is deferred to EntityManager
- No impact on chunk mesh generation
- Entities added to pending list for batch processing

## Compatibility

### Backward Compatibility
- Chunks saved without entity data will load successfully
- Empty entity list returned if no entity section present
- No migration needed for existing saves

### Forward Compatibility
- New entity types can be added by extending EntitySerializer
- Custom data map allows entity-specific properties
- Version-agnostic JSON format

## Debugging

### Logging
Entity save/load operations are logged at `FINE` level:
- `[ENTITY-SAVE] Chunk (X,Z): Saving N entities`
- `[ENTITY-LOAD] Chunk (X,Z): Loading N entities`
- `Loaded N entities for chunk (X, Z)`

### Console Output
EntityManager prints to System.out when loading entities:
```
Loaded 3 entities for chunk (0, 0)
```

## Known Limitations

1. **No Cross-Chunk Entities**: Entities are saved with their containing chunk only
2. **No Entity References**: Entities cannot reference other entities in serialization
3. **Reflection Required**: Some entity fields require reflection access
4. **No Partial Updates**: Entire entity state must be saved/loaded atomically

## Future Enhancements

- Support for additional entity types (sheep, pigs, chickens)
- Entity relationship serialization (followers, targets, etc.)
- Optimized binary entity format (alternative to JSON)
- Entity migration system for format changes
- Cross-chunk entity tracking

## Testing Checklist

✅ BlockDrop basic serialization
✅ BlockDrop stacked items
✅ BlockDrop different block types
✅ BlockDrop near-expiration despawn timer
✅ Cow basic serialization
✅ Cow all texture variants
✅ Cow all AI states
✅ Cow milk regeneration
✅ Cow damaged health
✅ Mixed entity types
✅ Empty chunk (no entities)
✅ Data integrity (precision)
✅ Binary format integration
✅ Full save/load cycle

## Status

**COMPLETE** - Entity save system is fully integrated and tested.

All entities (BlockDrop and Cow) are now automatically saved when chunks are saved and restored when chunks are loaded. The system is production-ready and thoroughly tested.
