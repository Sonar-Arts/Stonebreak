# Water Flow Save/Load Integration Test

## Overview

`WaterFlowSaveLoadIntegrationTest` is a comprehensive integration test that validates the **entire water save/load pipeline** in Stonebreak. This test exercises the complete game save system, including SaveService, chunk serialization, water metadata persistence, and WaterSystem state restoration.

## What This Test Validates

### 🌊 **Full Water Flow Cycle**
- Source block detection (level 0, not falling)
- Flowing water propagation (levels 1-7)
- Falling water (vertical flow)
- Water pool behavior (multiple adjacent sources)
- Edge flowing water (pool boundaries)

### 💾 **Complete Save/Load Pipeline**
1. **SaveService Integration** - Uses actual game save system
2. **Chunk Serialization** - Full chunk save/load through SaveService
3. **Water Metadata Persistence** - Flowing water states saved/restored
4. **WaterSystem State Management** - Complete water state lifecycle
5. **Source Detection on Load** - Automatic source identification after load

## Test Phases

### Phase 1: Setup Test Environment ✅
- Creates temporary world directory
- Initializes SaveService
- Creates test World (test mode - no rendering)

### Phase 2: Create Chunk with Water ✅
- Creates 15 water blocks:
  - **1 cascade source** at (5,100,5)
  - **3 cascade flowing** at (5,99-97,5)
  - **9 pool sources** at (7-9,64,7-9)
  - **2 pool flowing edges** at (10,64,7-8)

### Phase 3: Simulate Water Flow ✅
- Pre-loads water metadata (simulates water that has flowed)
- Sets up 5 flowing water states:
  - 3 falling blocks (levels 1-3)
  - 2 edge flowing blocks (levels 6-7)
- Calls `onChunkLoaded` to detect 10 source blocks
- Verifies 15 total water blocks tracked (10 sources + 5 flowing)

### Phase 4: Save Chunk (Game Save) ✅
- Marks chunk as dirty
- Saves chunk through SaveService
- Verifies chunk file exists on disk
- **Validates only flowing water metadata is saved** (5 entries, not 15)

### Phase 5: Unload World (Simulate Game Exit) ✅
- Calls `onChunkUnloaded` to clear WaterSystem
- Verifies WaterSystem is empty (0 blocks)
- Closes SaveService (simulates shutdown)

### Phase 6: Load World (Simulate Game Restart) ✅
- Creates fresh World and SaveService instances
- Loads chunk from disk through SaveService
- Verifies 15 water blocks restored
- Calls `onChunkLoaded` to restore water states
- **Validates source blocks auto-detected** (10 sources found)
- Verifies 15 total water blocks tracked again

### Phase 7: Verify Water States ✅
Tests specific water block states:
- ✅ Cascade source: level=0, falling=false
- ✅ Cascade flowing y=99: level=1, falling=true
- ✅ Cascade flowing y=98: level=2, falling=true
- ✅ Cascade flowing y=97: level=3, falling=true
- ✅ Pool sources: level=0, falling=false
- ✅ Pool edges: level=6-7, falling=false

### Phase 8: Verify Block Types ✅
- Verifies all water blocks present as `BlockType.WATER`
- Checks both source and flowing blocks

### Phase 9: Performance Metrics ✅
- Reports metadata storage efficiency
- Shows overhead: 5/15 = 33.3% (only flowing water saved)

## Key Validations

### ✅ **Source Block Detection**
- Sources are NOT saved as metadata (level 0 implicit)
- Sources auto-detected on chunk load via `onChunkLoaded`
- 10 sources correctly identified from block data

### ✅ **Flowing Water Metadata**
- 5 flowing blocks saved with full state (level + falling flag)
- Metadata correctly persisted through save/load cycle
- States accurately restored in WaterSystem

### ✅ **Complete State Restoration**
- Block data preserved (15 water blocks)
- Water metadata preserved (5 flowing states)
- Sources auto-detected (10 source blocks)
- Total: 15 water blocks correctly tracked after load

### ✅ **SaveService Integration**
- Chunk saved through real save system
- Chunk loaded through real save system
- File I/O validated (chunk exists on disk)

## What Makes This an Integration Test

Unlike the unit test (`WaterMetadataSaveLoadTest`), this test:

1. **Uses SaveService** - Full game save system, not just snapshots
2. **Tests Disk I/O** - Actual file writes/reads
3. **Tests Complete Lifecycle** - World create → save → destroy → load → verify
4. **Tests Source Detection** - Validates `onChunkLoaded` functionality
5. **Tests State Restoration** - Full WaterSystem lifecycle management

## Expected Results

When run successfully, the test will:

```
╔════════════════════════════════════════════════════════════════════╗
║       WATER FLOW SAVE/LOAD INTEGRATION TEST                        ║
╚════════════════════════════════════════════════════════════════════╝

... [9 phases of testing] ...

╔════════════════════════════════════════════════════════════════════╗
║                  ✅ ALL TESTS PASSED!                               ║
╚════════════════════════════════════════════════════════════════════╝
```

## Performance Expectations

- **Metadata overhead**: 33.3% (5/15 blocks saved as metadata)
- **Save time**: < 100ms (single chunk)
- **Load time**: < 100ms (single chunk)
- **Memory**: Minimal (15 water blocks tracked)

## Running the Test

### From IDE
Run `WaterFlowSaveLoadIntegrationTest.main()` directly

### From Command Line
```bash
mvn test -Dtest=WaterFlowSaveLoadIntegrationTest
```

## Test Mode Details

This test uses `World(config, seed, true)` constructor:
- ✅ No MmsAPI initialization required
- ✅ No OpenGL context required
- ✅ No rendering systems initialized
- ✅ WaterSystem fully functional
- ✅ Save/load systems fully functional

## What This Test Proves

✅ Water metadata save/load **works correctly**
✅ Source blocks **auto-detect on chunk load**
✅ Flowing water states **persist through save/load**
✅ SaveService integration **functions properly**
✅ Water simulation **survives game restart**

## Common Issues

### ❌ "Expected 15 tracked water blocks, got X"
- Source detection failed in `onChunkLoaded`
- Check water metadata loading in Chunk.java

### ❌ "Water block count mismatch"
- Block data not persisted correctly
- Check chunk serialization in StateConverter

### ❌ "Water count mismatch after load"
- WaterSystem state not restored
- Check `loadWaterMetadata` and `onChunkLoaded`

### ❌ "Missing water block for X"
- WaterSystem lost track of specific block
- Check water tracking in WaterSystem

## Related Tests

- **WaterMetadataSaveLoadTest** - Unit test for snapshot system only
- **ChunkSaveLoadTest** - Basic chunk save/load without water
- **SaveSystemTest** - SaveService unit tests

## Future Enhancements

Potential additions to this test:
- [ ] Test multiple chunks with shared water sources
- [ ] Test water flowing across chunk boundaries
- [ ] Test water with different flow patterns
- [ ] Test edge cases (underwater sources, etc.)
- [ ] Performance benchmarks for large water bodies
- [ ] Stress test with 1000+ water blocks
