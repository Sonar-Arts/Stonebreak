# Water Metadata Save/Load Test

## Purpose

This test validates that water flow states (flowing water levels and falling water) are correctly preserved when chunks are saved and loaded.

## What It Tests

1. **Water State Tracking**: Verifies WaterSystem correctly tracks water block states (source, flowing, falling)
2. **Metadata Extraction**: Verifies `Chunk.createSnapshot()` correctly extracts water metadata from WaterSystem
3. **Metadata Serialization**: Verifies water metadata is included in ChunkData and snapshots
4. **Metadata Loading**: Verifies `Chunk.loadFromSnapshot()` correctly restores water metadata
5. **State Preservation**: Verifies water block states are identical after save/load cycle

## Test Scenario

The test creates a chunk with multiple water configurations:

1. **Falling Water Cascade** (y=97-100)
   - Source block at y=100
   - Falling water at y=99 (level 1)
   - Falling water at y=98 (level 2)
   - Falling water at y=97 (level 3)

2. **Water Pool** (y=64, x=7-9, z=7-8)
   - 6 source blocks forming a pool
   - 2 flowing blocks at edges (levels 6 and 7)

## Expected Results

- **5 metadata entries saved** (3 falling + 2 flowing)
- **Source blocks NOT saved** (they're the default state)
- **All water states preserved** after load

## Running the Test

### Option 1: Direct Execution
```bash
cd stonebreak-game
mvn test -Dtest=WaterMetadataSaveLoadTest
```

### Option 2: Run from IDE
1. Open `WaterMetadataSaveLoadTest.java` in your IDE
2. Right-click on the class or `main()` method
3. Select "Run" or "Debug"

### Option 3: Run main() method
```bash
cd stonebreak-game/src/test/java
javac com/stonebreak/world/save/WaterMetadataSaveLoadTest.java
java com.stonebreak.world.save.WaterMetadataSaveLoadTest
```

## Expected Output

```
=== Water Metadata Save/Load Test ===

Phase 1: Setup Water Test Environment
--------------------------------------
✓ Test World and WaterSystem initialized

Phase 2: Create Chunk with Water Blocks
----------------------------------------
✓ Created chunk at position (5, 7)
✓ Placed water blocks in chunk

Phase 3: Simulate WaterSystem States
-------------------------------------
✓ Water states loaded into WaterSystem
  Total tracked water blocks: [number]
  Expected: source blocks + 5 flowing/falling blocks
  Expected to save: 5 metadata entries (non-source only)

Phase 4: Create Snapshot (Save Operation)
------------------------------------------
✓ Snapshot created
✓ Water metadata extracted: 5 entries
✓ Correct number of metadata entries saved
  ✓ Falling water at y=99 metadata verified: level=1, falling=true
  ✓ Falling water at y=98 metadata verified: level=2, falling=true
  ✓ Falling water at y=97 metadata verified: level=3, falling=true
  ✓ Flowing water at edge (10,64,7) metadata verified: level=6, falling=false
  ✓ Flowing water at edge (10,64,8) metadata verified: level=7, falling=false

Phase 5: Simulate Chunk Unload
-------------------------------
✓ WaterSystem cleared for chunk
  Remaining tracked water: 0 (should be 0 or from other chunks)

Phase 6: Load from Snapshot
----------------------------
✓ Chunk loaded from snapshot
✓ Water metadata loaded into WaterSystem
  Loaded water blocks: 5 (should be 5)

Phase 7: Verify Water States Preserved
---------------------------------------
  ✓ Falling y=99 state verified: level=1, falling=true
  ✓ Falling y=98 state verified: level=2, falling=true
  ✓ Falling y=97 state verified: level=3, falling=true
  ✓ Flowing edge (10,64,7) state verified: level=6, falling=false
  ✓ Flowing edge (10,64,8) state verified: level=7, falling=false
✓ All water states correctly preserved!

Phase 8: Verify Block Types Preserved
--------------------------------------
✓ All water block types preserved

Phase 9: Performance Statistics
--------------------------------
✓ Total water blocks in chunk: [number]
✓ Metadata entries saved: 5
✓ Metadata overhead: 5/[total] ([percentage]%)

✅ ALL WATER METADATA TESTS PASSED!
```

## What This Proves

✅ **Water metadata extraction works** - `Chunk.createSnapshot()` correctly extracts flowing water states
✅ **Water metadata serialization works** - Metadata is preserved in ChunkData snapshots
✅ **Water metadata loading works** - `Chunk.loadFromSnapshot()` correctly restores water states
✅ **Water state preservation works** - All water levels and falling states are identical after load

## Troubleshooting

### If Test Fails at Phase 4 (Snapshot Creation)
- Check if `Chunk.createSnapshot()` is correctly iterating through water blocks
- Verify `world.getWaterSystem()` is not null
- Check diagnostic logs: `[WATER-SAVE] Chunk (x,z): ... missing from WaterSystem`

### If Test Fails at Phase 6 (Loading)
- Check if `Chunk.loadFromSnapshot()` is calling `loadWaterMetadata()`
- Verify metadata is not empty in snapshot
- Check diagnostic logs: `[WATER-LOAD] Chunk (x,z): Loaded N flowing water blocks`

### If Test Fails at Phase 7 (Verification)
- Water states don't match after load
- This indicates `loadWaterMetadata()` is not correctly restoring states
- Check if `onChunkLoaded()` is overwriting loaded metadata

## Related Files

- `Chunk.java:316-378` - Water metadata extraction in `createSnapshot()`
- `Chunk.java:387-438` - Water metadata loading in `loadFromSnapshot()`
- `WaterSystem.java:651-674` - Water metadata loading method
- `WaterSystem.java:109-163` - Chunk loaded handler
- `CcoSerializableSnapshot.java` - Snapshot data structure with water metadata
- `StateConverter.java:110-137` - Chunk snapshot conversion
