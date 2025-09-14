# Stonebreak Save System Redesign Instructions

## Overview
Complete redesign of the save system to focus purely on terrain optimization while following SOLID principles and clean architecture patterns. Remove over-engineered enterprise features and entity complexity to create a lean, high-performance system.

## Current Problems Analysis

### Architecture Issues
- **Violates Single Responsibility**: Classes like `WorldManager` handle coordination, scheduling, validation, recovery, and file I/O
- **Over-Engineered**: Enterprise-level corruption recovery, validation caching, and multi-format support not needed for terrain-only game
- **Entity Complexity**: Complex AI data, physics, and mob systems when only terrain is required
- **Poor Organization**: Flat directory structure with 22 files vs clean hierarchical organization like UI system

### Performance Issues
- **File Handle Explosion**: Individual JSON files for each chunk (1000+ files for moderate worlds)
- **Storage Inefficiency**: JSON format with no compression, 32 bits per block storage
- **Memory Overhead**: Full entity system loaded even when not needed
- **I/O Bottlenecks**: Scattered file access patterns causing disk fragmentation

## New Architecture Design

### Directory Structure (Following UI Pattern)
```
world/save/
├── core/                          # Core interfaces & data models
│   ├── SaveOperations.java        # Interface for save operations
│   ├── LoadOperations.java        # Interface for load operations
│   ├── WorldMetadata.java         # Pure data model (seed, spawn, time)
│   └── PlayerState.java           # Simple player data model
├── storage/                       # Storage layer implementations
│   ├── binary/                    # Binary format handlers
│   │   ├── BinaryChunkCodec.java  # Encodes/decodes chunks with palette
│   │   ├── RegionFileManager.java # Manages region file operations
│   │   ├── RegionFile.java        # Individual .mcr file handler
│   │   └── BlockPalette.java      # Optimized block storage system
│   └── providers/                 # Data access layer
│       ├── TerrainDataProvider.java    # Provides chunk/terrain data
│       └── PlayerDataProvider.java     # Provides basic player state
└── managers/                      # Coordination & scheduling
    ├── WorldSaveManager.java      # Coordinates all save operations
    ├── WorldLoadManager.java      # Coordinates all load operations
    └── AutoSaveScheduler.java     # Handles 30-second auto-save timer
```

### SOLID Principles Application

#### Single Responsibility Principle
- **WorldSaveManager**: Only coordinates save operations
- **WorldLoadManager**: Only coordinates load operations
- **BinaryChunkCodec**: Only handles chunk encoding/decoding
- **RegionFileManager**: Only manages region file I/O
- **AutoSaveScheduler**: Only handles auto-save timing

#### Open/Closed Principle
- **SaveOperations interface**: Extensible for new save formats without modification
- **LoadOperations interface**: Extensible for new load formats without modification
- **TerrainDataProvider interface**: Can add new data sources without changing consumers

#### Liskov Substitution Principle
- All implementations fully substitutable via their interfaces
- Binary providers can be swapped for other formats seamlessly

#### Interface Segregation Principle
- Small, focused interfaces instead of large monolithic ones
- No entity-related methods in terrain-focused interfaces

#### Dependency Inversion Principle
- High-level managers depend on abstractions (interfaces)
- Low-level providers implement abstractions
- No direct dependencies on concrete implementations

## Implementation Plan

### Phase 1: Create Core Structure
1. **Create directory structure** following UI folder pattern
2. **Define core interfaces**:
   ```java
   public interface SaveOperations {
       CompletableFuture<Void> saveWorld(WorldMetadata metadata);
       CompletableFuture<Void> savePlayerState(PlayerState player);
       CompletableFuture<Void> saveChunk(Chunk chunk);
   }

   public interface LoadOperations {
       CompletableFuture<WorldMetadata> loadWorldMetadata();
       CompletableFuture<PlayerState> loadPlayerState();
       CompletableFuture<Chunk> loadChunk(int x, int z);
   }
   ```

### Phase 2: Implement Storage Layer
3. **Create binary storage system**:
   - `BinaryChunkCodec`: Palette-based chunk encoding (1-8 bits per block)
   - `RegionFile`: Handle individual .mcr files with 32×32 chunk capacity
   - `RegionFileManager`: Coordinate multiple region files
   - `BlockPalette`: Optimize block storage with bit-packing

### Phase 3: Build Data Providers
4. **Implement data access layer**:
   - `TerrainDataProvider`: Access chunk data from storage
   - `PlayerDataProvider`: Access basic player state (position, inventory, game mode)

### Phase 4: Create Coordination Managers
5. **Build management layer**:
   - `WorldSaveManager`: Orchestrate save operations using providers
   - `WorldLoadManager`: Orchestrate load operations using providers
   - `AutoSaveScheduler`: Handle 30-second auto-save with dirty chunk detection

### Phase 5: Integration & Cleanup
6. **Replace existing system**:
   - Update `World.java` to use new managers via interfaces
   - Remove old save system files (22 files → 12 focused files)
   - Update world selection to use new metadata format

## Technical Specifications

### Binary Region Format (.mcr files)
```
Region File Structure:
┌─────────────────────────────────────────────────────────────┐
│ Header (8KB)                                                │
├─────────────────────────────────────────────────────────────┤
│ Chunk Offset Table (1024 entries × 4 bytes = 4KB)         │
│ Chunk Length Table (1024 entries × 4 bytes = 4KB)         │
├─────────────────────────────────────────────────────────────┤
│ Chunk Data Section (Variable size, LZ4 compressed)         │
│ ├─ Chunk[0,0]: Header + Palette + Bit-packed blocks       │
│ ├─ Chunk[0,1]: Header + Palette + Bit-packed blocks       │
│ └─ ... (up to 1024 chunks per region)                     │
└─────────────────────────────────────────────────────────────┘
```

### Palette Compression System
```
Block Palette Optimization:
- Air-only chunks: 1 bit per block (97% size reduction)
- Simple terrain: 2-4 bits per block (87-94% reduction)
- Complex areas: 5-8 bits per block (75-84% reduction)
- vs Current: 32 bits per block (no compression)

Memory Impact:
- 16×256×16 blocks = 65,536 blocks per chunk
- Current: 262,144 bytes per chunk
- Optimized: 2,048-32,768 bytes per chunk (87-99% reduction)
```

### World Metadata (Terrain-Only)
```java
public class WorldMetadata {
    private long seed;              // World generation seed
    private String worldName;       // Display name
    private Vector3f spawnPosition; // Player spawn point
    private long createdTime;       // Creation timestamp
    private long lastPlayed;        // Last access time
    private long totalPlayTime;     // Total session time
    // NO entity data, AI data, complex properties
}
```

### Player State (Simplified)
```java
public class PlayerState {
    private Vector3f position;      // World position
    private Vector2f rotation;      // Camera yaw/pitch
    private float health;           // Health points
    private boolean isFlying;       // Flight state
    private int gameMode;           // Survival/Creative
    private ItemStack[] inventory;  // Inventory contents
    // NO complex physics, AI state, entity relationships
}
```

## Performance Expectations

### File System Improvements
- **File Count**: 1000+ individual chunk files → ~10-50 region files (95% reduction)
- **File Handles**: Massive reduction in OS resource usage
- **I/O Performance**: Sequential region reads vs random chunk file access

### Storage Efficiency
- **Compression**: 75-97% size reduction with palette + LZ4
- **Binary Format**: 30-50% smaller than JSON before compression
- **Memory Usage**: 87-99% reduction in chunk memory footprint

### Load/Save Performance
- **Save Speed**: 5-10x faster with batched region writes
- **Load Speed**: 3-5x faster with sequential reads and binary parsing
- **Startup Time**: Faster world loading due to fewer file operations
- **Auto-Save**: Non-blocking, selective dirty chunk saving

## Files to Remove (22 → 12 files)
Delete these over-engineered files:
- `BinaryPlayerData.java` (replace with simple PlayerState)
- `BinaryWorldMetadata.java` (replace with simple WorldMetadata)
- `ChunkFileManager.java` (replace with RegionFileManager)
- `ChunkHeader.java` (integrate into BinaryChunkCodec)
- `CorruptionRecoveryManager.java` (unnecessary complexity)
- `EntityData.java` (no entities needed)
- `PlayerData.java` (replace with PlayerState)
- `SaveFileValidator.java` (over-engineered validation)
- `WorldLoader.java` (replace with focused WorldLoadManager)
- `WorldManager.java` (split responsibilities)
- `WorldSaver.java` (replace with focused WorldSaveManager)
- `WorldStatistics.java` (unnecessary tracking)

## Success Metrics
- **Codebase Size**: Reduce from 22 files to 12 focused files
- **Memory Usage**: 87-99% reduction in chunk storage
- **Load Performance**: 3-5x faster world loading
- **Save Performance**: 5-10x faster saves with regions
- **Architecture**: Clean SOLID compliance with single responsibilities
- **Maintainability**: Easy to understand and extend for future features

## Integration Notes
- Update `World.java` to use new interfaces instead of direct class dependencies
- Modify world selection UI to work with new metadata format
- Ensure existing worlds can be migrated or regenerated as needed
- Maintain 30-second auto-save functionality with dirty chunk detection
- Preserve existing player experience (seamless save/load from user perspective)

This redesign will create a lean, high-performance save system optimized for terrain data while following clean architecture principles and being easily extensible for future game features.