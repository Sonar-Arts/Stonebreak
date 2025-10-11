# Comprehensive Save/Load Test Suite

## Overview

This test suite addresses critical gaps in the original `RaceConditionSaveLoadTest.java` by testing with **realistic data** that matches actual gameplay scenarios.

## The Problem with Original Tests

The original tests **only verified minimal data**:
- ❌ Single marker block per chunk (1 out of 65,536 blocks)
- ❌ No water metadata
- ❌ No entities
- ❌ Tiny palettes (2 block types)
- ❌ No verification of serialization byte-accuracy

**This allowed corruption to pass undetected** because the complex serialization paths were never exercised.

## New Test Suite Components

### 1. RealisticChunkGenerator.java
**Utility class that creates chunks matching real gameplay:**
- ✅ Full terrain (stone, dirt, grass, ores - 15+ block types)
- ✅ 100+ water metadata entries with varying levels
- ✅ 10-20 entities with positions, velocities, and state
- ✅ Large palettes (stresses multi-bit encoding)
- ✅ Edge cases (empty chunks, full chunks)

### 2. SerializationIntegrityTest.java
**Tests byte-perfect serialization with realistic data:**

| Test | What It Verifies | Why It Matters |
|------|------------------|----------------|
| **Test 1: Byte-Perfect Serialization** | serialize → deserialize → re-serialize produces identical bytes | Detects data loss or corruption in serialization pipeline |
| **Test 2: Water Metadata Preservation** | 100+ water entries preserved exactly | Your worlds have flowing water - this must work |
| **Test 3: Entity Data Preservation** | Entity positions, velocities, types preserved | Cows and other mobs must save/load correctly |
| **Test 4: Header Field Integrity** | All header fields (version, palette size, etc.) correct | **Targets your "version: 2560" error directly** |
| **Test 5: Large Palette Stress** | 15+ block types serialize correctly | Stresses palette encoding system |
| **Test 6-7: Edge Cases** | Empty and full chunks work | Boundary condition testing |
| **Test 8: Concurrent Realistic Data** | No corruption under concurrent load | Real gameplay has concurrent access |
| **Test 9: Rapid Save-Reload Cycles** | Data survives 10 save/reload cycles | World restarts must preserve data |
| **Test 10: Version Field Corruption** | **Specifically hunts for version != 1** | **DIRECTLY TARGETS YOUR BUG** |

### 3. RegionFileHeaderIntegrityTest.java
**Reads raw bytes from region files to verify low-level integrity:**

| Test | What It Verifies | Why It Matters |
|------|------------------|----------------|
| **Test 1: Offset/Length Consistency** | Header entries match actual data positions | Prevents "offset 13013" errors |
| **Test 2: No Overlapping Data** | Chunks don't overwrite each other | Prevents data corruption |
| **Test 3: Atomic Header Updates** | Offset and length updated together | **Root cause of torn reads** |
| **Test 4: Version Field Verification** | Reads raw bytes, verifies version == 1 | **DIRECTLY DETECTS YOUR CORRUPTION** |
| **Test 5: Concurrent R/W Integrity** | Readers never see torn headers | Stress test under concurrent access |

## Root Cause Identified

### The Critical Bug: Non-Atomic Header Updates

In `RegionFile.java:276-283`:

```java
private void updateHeaderEntry(int chunkIndex) throws IOException {
    // Update offset entry
    file.seek(chunkIndex * 4);
    file.writeInt(chunkOffsets[chunkIndex]);  // ← Write 1

    // Update length entry
    file.seek(4096 + chunkIndex * 4);
    file.writeInt(chunkLengths[chunkIndex]);  // ← Write 2
}
```

**Problem**: These two writes are NOT atomic!

A concurrent reader can see:
- ✅ New offset + ❌ Old length → reads wrong amount of data
- ❌ Old offset + ✅ New length → reads past chunk boundary
- Either way: **Corrupted data or misaligned reads**

Combined with **batched fsync** (line 112-115), data may not even be on disk when reads occur.

### Why This Causes Your Errors

1. **"Unsupported chunk format version: 2560"**
   - Reader sees torn header: wrong offset
   - Seeks to wrong position in file
   - Reads garbage instead of version field (1)
   - Gets 2560 (0x0A00) instead

2. **"Error decoding offset 13013"**
   - Reader gets mismatched offset/length from header
   - ByteBuffer operations use wrong positions
   - Decoding fails at invalid offset

## How to Run These Tests

### Run All Tests
```bash
mvn test -Dtest=SerializationIntegrityTest,RegionFileHeaderIntegrityTest
```

### Run Individual Test Classes
```bash
# Serialization integrity
mvn test -Dtest=SerializationIntegrityTest

# Header integrity (targets your specific bug)
mvn test -Dtest=RegionFileHeaderIntegrityTest
```

### Run Specific Tests
```bash
# Just the version field corruption detection
mvn test -Dtest=SerializationIntegrityTest#testVersionFieldCorruptionDetection

# Just the atomic header test (tests root cause)
mvn test -Dtest=RegionFileHeaderIntegrityTest#testAtomicHeaderUpdates
```

### In IntelliJ IDEA
1. Right-click on `SerializationIntegrityTest.java` → Run
2. Right-click on `RegionFileHeaderIntegrityTest.java` → Run
3. Or run individual tests by clicking the ▶️ icon next to each `@Test`

## Expected Results

### If Tests PASS ✅
- Your save system handles realistic data correctly
- No corruption under normal conditions
- Concurrent access is safe (with locks)

### If Tests FAIL ❌
You'll see **exactly which scenario causes corruption**:

```
❌ FAILURE: VERSION CORRUPTION DETECTED in chunk 5: version=2560 (0x0a00)
```

This tells you:
- Which test failed
- What data was corrupted
- The exact value that was wrong

## What These Tests Will Find

These tests WILL catch:
1. ✅ Torn header writes (offset/length mismatch)
2. ✅ Version field corruption
3. ✅ Buffer alignment issues
4. ✅ Palette size calculation errors
5. ✅ Water metadata serialization bugs
6. ✅ Entity data corruption
7. ✅ Compression/decompression issues
8. ✅ Concurrent access race conditions

These tests will NOT catch:
- ❌ Bugs in your Chunk class itself
- ❌ Issues in StateConverter (conversion logic)
- ❌ Problems in world generation

## Recommended Action Plan

1. **Run RegionFileHeaderIntegrityTest first**
   - This directly tests the region file format
   - Test 3 and 4 will likely fail (atomic header updates)

2. **Run SerializationIntegrityTest**
   - Tests higher-level serialization with realistic data
   - Test 10 will detect version corruption if it occurs

3. **If tests fail, the output will show:**
   - Exact test that failed
   - What data was corrupted
   - Expected vs actual values

4. **Fix the root cause:**
   - Make header updates atomic (use a lock or write buffer)
   - Or implement proper synchronization in RegionFile.java

## Comparison to Original Tests

| Aspect | RaceConditionSaveLoadTest | New Test Suite |
|--------|---------------------------|----------------|
| Data Realism | ❌ Minimal (1 marker block) | ✅ Full terrain, water, entities |
| Block Verification | ❌ 1 block out of 65,536 | ✅ All 65,536 blocks |
| Water Metadata | ❌ None | ✅ 100+ entries |
| Entity Data | ❌ None | ✅ 10-20 entities |
| Palette Stress | ❌ 2 block types | ✅ 15+ block types |
| Byte-Accuracy | ❌ Not verified | ✅ Byte-perfect verification |
| Header Validation | ❌ Not checked | ✅ Raw byte validation |
| Version Field | ❌ Indirect check | ✅ Direct verification |
| Corruption Detection | ❌ Would pass with corruption | ✅ Catches corruption |

## Files Created

```
stonebreak-game/src/test/java/com/stonebreak/world/save/
├── RealisticChunkGenerator.java          # Utility for creating realistic test data
├── SerializationIntegrityTest.java       # High-level serialization tests
├── RegionFileHeaderIntegrityTest.java    # Low-level region file format tests
└── README_COMPREHENSIVE_TESTS.md         # This file
```

## Next Steps

1. **Run the tests** using your IDE
2. **Examine failures** - they will show exactly what's wrong
3. **Fix the root cause** in RegionFile.java (atomic header updates)
4. **Re-run tests** to verify the fix
5. **Test in-game** to confirm worlds load correctly

---

**These tests use REAL DATA and will catch the corruption you're experiencing in production.**

If you see test failures, **that's good** - it means we've reproduced the bug in a controlled environment where we can fix it!
