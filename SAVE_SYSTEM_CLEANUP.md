# Stonebreak Save System Cleanup Analysis

## Overview
This document analyzes all Java files in the `save/` folder to identify unused functions and unimplemented features that need attention.

**Analysis Date:** January 2025  
**Files Analyzed:** 10 files, 4,810 total lines of code

---

## 1. Unused Public Methods

### ChunkFileManager.java (Lines 22-859)

**Advanced Chunk Discovery Methods:**
- `getAllChunkInfo(String worldName, boolean loadExtendedMetadata)` *(Line 432)*
- `getAllChunkInfo(String worldName)` *(Line 487)*
- `getChunkInfoInRadius(String worldName, int centerX, int centerZ, int radius, boolean loadExtendedMetadata)` *(Line 501)*
- `getChunksNeedingValidation(String worldName, long maxValidationAge)` *(Line 520)*
- `getPlayerModifiedChunks(String worldName)` *(Line 535)*

**World Statistics Methods:**
- `getWorldStatistics(String worldName)` *(Line 550)*
- `WorldStatistics` class *(Lines 580-635)*

**Cache Management:**
- `clearCache(String worldName)` *(Line 676)*
- `clearCache()` *(Line 684)*
- `getCacheStats()` *(Line 692)*

**Backup Operations:**
- `backupChunk(String worldName, int chunkX, int chunkZ)` *(Line 404)*

**Enhanced File Info:**
- `getChunkFileInfo(String worldName, int chunkX, int chunkZ, boolean loadExtendedMetadata)` *(Line 258)*
- `validateChunkFile(ChunkFileInfo info, String worldName, int chunkX, int chunkZ)` *(Line 342)*

### WorldManager.java (Lines 23-553)

**World Management:**
- `deleteWorld(String worldName)` *(Line 292)* - World deletion functionality
- `listWorlds()` *(Line 315)* - May be unused by UI

**Backup Operations:**
- `createBackup(String worldName)` *(Line 421)*
- `listBackups(String worldName)` *(Line 437)*
- `restoreFromBackup(String worldName, String backupName, World world, Player player)` *(Line 454)*

**Advanced Features:**
- `validateWorld(String worldName)` *(Line 405)*
- `updateCurrentWorldPlayTime()` *(Line 360)*

**Component Access:**
- `getValidator()` *(Line 478)*
- `getRecoveryManager()` *(Line 485)*

### CorruptionRecoveryManager.java (Lines 25-707)

**Backup Management:**
- `listAvailableBackups(String worldName)` *(Line 148)*
- `createBackupInfo(Path backupPath, String worldName)` *(Line 470)*

**Recovery Operations:**
- Most recovery strategy methods appear unused:
  - `recoverCorruptedWorld()` *(Line 110)*
  - `restoreFromBackup()` *(Line 187)*
  - `determineRecoveryStrategy()` *(Line 229)*
  - `executeRecoveryStrategy()` *(Line 258)*

**Helper Methods:**
- `attemptBackupRestoration()` *(Line 286)*
- `attemptSeedRegeneration()` *(Line 319)*
- `attemptPartialRecovery()` *(Line 359)*
- `attemptFallbackRecovery()` *(Line 402)*

### SaveFileValidator.java (Lines 22-621)

**Validation Workflow:**
- Most public validation methods appear unused by main game flow
- `validateWorld(String worldName)` *(Line 56)* - May only be used internally

---

## 2. Unimplemented Features (TODOs)

### EntityData.java
**Line 152:**
```java
// TODO: Serialize AI data when AI system is implemented
// For now, initialize empty map
this.aiData = new HashMap<>();
```
**Impact:** AI data is not being persisted across saves/loads

### PlayerData.java
**Line 109:**
```java
this.health = 20.0f; // TODO: Add health system to Player
```
**Impact:** Health value is hardcoded, not integrated with actual player health system

**Line 129:**
```java
this.totalPlayTimeMillis = 0; // TODO: Add play time tracking
```
**Impact:** Play time tracking is not functional

---

## 3. Integration Status

### Actively Used Components
✅ **WorldManager** - Singleton initialized in Game.java (Line 86)  
✅ **Basic Save/Load** - Core functionality integrated  
✅ **ChunkFileManager** - Basic chunk operations used  
✅ **WorldSaver/WorldLoader** - Auto-save and world loading functional  

### Potentially Unused Components
❓ **Advanced Backup System** - Extensive backup/recovery functionality  
❓ **Chunk Statistics** - Detailed chunk analysis and discovery  
❓ **World Management UI** - World deletion, backup management  
❓ **Validation Workflows** - Advanced file validation features  

---

## 4. Recommendations

### Immediate Actions
1. **Remove Unused Methods:** Consider removing unused public methods to reduce code complexity
2. **Complete TODOs:** Implement health system and play time tracking integration
3. **Verify Integration:** Test if advanced backup/recovery features are accessible through UI

### Future Considerations
1. **UI Integration:** Many advanced features seem ready but lack UI integration
2. **Feature Completion:** AI data serialization awaits AI system implementation
3. **Documentation:** Update CLAUDE.md to reflect actual feature status

### Cleanup Priority
**High Priority:**
- Complete PlayerData TODOs (health, play time)
- Remove clearly unused methods in ChunkFileManager

**Medium Priority:**
- Verify backup/recovery system usage
- Clean up unused validation workflows

**Low Priority:**
- Remove advanced statistics methods if truly unused
- Clean up cache management if not needed

---

## 5. Detailed Method Analysis

### ChunkFileManager Unused Methods

| Method | Line | Usage | Recommendation |
|--------|------|-------|----------------|
| `getAllChunkInfo(boolean)` | 432 | Not found in codebase | Remove if UI doesn't use |
| `getChunkInfoInRadius()` | 501 | Not found in codebase | Remove or integrate |
| `getWorldStatistics()` | 550 | Not found in codebase | Remove if UI doesn't need |
| `clearCache()` | 676/684 | Not found in codebase | Keep for maintenance |

### WorldManager Unused Methods

| Method | Line | Usage | Recommendation |
|--------|------|-------|----------------|
| `deleteWorld()` | 292 | Not used by UI | Integrate or remove |
| `createBackup()` | 421 | Not used by UI | Integrate or remove |
| `listBackups()` | 437 | Not used by UI | Integrate or remove |

---

**Total Potentially Unused Methods:** 25+  
**Total Unimplemented Features:** 3  
**Estimated Cleanup Impact:** Moderate - could reduce codebase by ~500-800 lines if unused methods removed