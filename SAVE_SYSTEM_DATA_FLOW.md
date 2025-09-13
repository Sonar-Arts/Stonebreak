# Stonebreak Save System Data Flow

This document provides a comprehensive overview of all data saved by the Stonebreak save system and where it is stored.

## System Architecture

```
STONEBREAK SAVE SYSTEM DATA FLOW
═══════════════════════════════════════════════════════════════════════════

                        ┌─────────────────────────┐
                        │       GAME WORLD        │
                        │    (Runtime State)      │
                        └─────────────┬───────────┘
                                      │
                        ╔═════════════▼═════════════╗
                        ║      WORLD MANAGER       ║
                        ║   (Main Coordinator)     ║
                        ╚═════════════╤═════════════╝
                                      │
        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
┌───────▼────────┐         ┌─────────▼─────────┐         ┌─────────▼─────────┐
│  WORLD SAVER   │         │   WORLD LOADER    │         │ CHUNK FILE MGR    │
│ (Auto-save &   │         │ (Loading system)  │         │ (File operations) │
│  Manual save)  │         │                   │         │                   │
└───────┬────────┘         └─────────┬─────────┘         └─────────┬─────────┘
        │                             │                             │
        │                   ┌─────────▼─────────┐                   │
        │                   │ SAVE FILE VALIDATOR│                   │
        │                   │ (Integrity check)  │                   │
        │                   └─────────┬─────────┘                   │
        │                             │                             │
        │                   ┌─────────▼─────────┐                   │
        │                   │ CORRUPTION RECOV. │                   │
        │                   │    MANAGER        │                   │
        │                   └─────────┬─────────┘                   │
        └─────────────────────────────┼─────────────────────────────┘
                                      │
                        ╔═════════════▼═════════════╗
                        ║      FILE SYSTEM         ║
                        ║     STORAGE LAYER        ║
                        ╚══════════════════════════╝
```

## Directory Structure

```
worlds/
├── [worldName1]/                    ← World-specific directory
│   ├── world.dat                    ← BinaryWorldMetadata (Binary format)
│   ├── player.dat                   ← BinaryPlayerData (Binary format) 
│   ├── entities.dat                ← EntityData[] (Binary format)
│   ├── regions/                    ← Region files directory
│   │   ├── r.0.0.mcr               ← RegionFile (32×32 chunks, binary compressed)
│   │   ├── r.0.1.mcr               ← RegionFile (32×32 chunks, binary compressed)
│   │   ├── r.1.0.mcr               ← RegionFile (32×32 chunks, binary compressed)
│   │   └── ...                     ← More region files
│   └── backups/                    ← Automatic backup directory
│       ├── world_backup_TIMESTAMP.dat
│       ├── player_backup_TIMESTAMP.dat
│       └── entities_backup_TIMESTAMP.dat
│
├── [worldName2]/                    ← Another world
│   ├── world.dat
│   ├── player.dat
│   └── ...
└── ...
```

## Data Models & Contents

### BinaryWorldMetadata.java (world.dat)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        BinaryWorldMetadata.java                        │
│                         (world.dat - Binary Format)                    │
├─────────────────────────────────────────────────────────────────────────┤
│ HEADER (32 bytes):                                                      │
│ • magicNumber        : int       (0x53544F4E - "STON" validation)       │
│ • formatVersion      : int       (Binary format version)                │
│ • createdTime        : long      (Creation timestamp)                   │
│ • lastPlayed         : long      (Last played timestamp)                │
│ • totalPlayTime      : long      (Total play time milliseconds)         │
│ • dataSize          : int       (Size of variable data section)         │
│                                                                         │
│ CORE DATA (variable size):                                              │
│ • seed               : long      (World generation seed)                │
│ • worldName          : String    (Length-prefixed UTF-8)                │
│ • spawnPosition      : Vector3f  (12 bytes: x, y, z floats)            │
│ • gameMode           : int       (0=Survival, 1=Creative)               │
│ • cheatsEnabled      : byte      (Boolean flag)                        │
│                                                                         │
│ CUSTOM PROPERTIES (variable):                                           │
│ • propertyCount      : int       (Number of key-value pairs)            │
│ • properties[]       : String    (Key-value pairs for extensibility)   │
└─────────────────────────────────────────────────────────────────────────┘
```

### PlayerData.java (player.json)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           PlayerData.java                              │
│                           (player.json)                                │
├─────────────────────────────────────────────────────────────────────────┤
│ POSITION & PHYSICS:                                                     │
│ • positionX, Y, Z    : float     (Player world position)               │
│ • velocityX, Y, Z    : float     (Player movement velocity)            │
│                                                                         │
│ CAMERA ORIENTATION:                                                     │
│ • cameraYaw          : float     (Horizontal look direction)           │
│ • cameraPitch        : float     (Vertical look direction)             │
│                                                                         │
│ PLAYER STATE:                                                           │
│ • onGround           : boolean   (Physics ground contact)              │
│ • flightEnabled      : boolean   (Creative flight capability)          │
│ • isFlying           : boolean   (Currently flying)                    │
│ • health             : float     (Player health points)                │
│ • gameMode           : String    (SURVIVAL/CREATIVE)                   │
│ • totalPlayTimeMillis: long      (Session time tracking)               │
│                                                                         │
│ INVENTORY SYSTEM:                                                       │
│ • hotbarItems[]      : SerializableItemStack (9-slot hotbar)          │
│ • mainInventoryItems[]: SerializableItemStack (27-slot inventory)      │
│ • selectedHotbarSlot : int       (Currently selected hotbar slot)      │
│                                                                         │
│ NESTED: SerializableItemStack                                           │
│ • itemId             : int       (Block/item type ID)                  │
│ • count              : int       (Stack quantity)                      │
└─────────────────────────────────────────────────────────────────────────┘
```

### Binary Chunk Data (regions/r.X.Z.mcr)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          Binary Chunk System                           │
│                       (regions/r.X.Z.mcr files)                        │
├─────────────────────────────────────────────────────────────────────────┤
│ REGION FILE STRUCTURE:                                                  │
│ • Header (8KB):     Chunk offset/length tables for 32×32 chunks        │
│ • Chunk Data:       Variable-size compressed chunk blocks               │
│                                                                         │
│ INDIVIDUAL CHUNK FORMAT:                                                │
│ • ChunkHeader (32 bytes):                                               │
│   - chunkX, chunkZ    : int       (Chunk coordinates)                  │
│   - version           : int       (Binary format version)               │
│   - uncompressedSize  : int       (Size before compression)            │
│   - lastModified      : long      (Unix timestamp)                     │
│   - paletteSize       : int       (Number of unique blocks)            │
│   - bitsPerBlock      : byte      (Palette bit depth: 1-8)             │
│   - compressionType   : byte      (0=None, 1=LZ4)                      │
│   - flags             : byte      (Dirty, PlayerModified, Features)    │
│                                                                         │
│ • BlockPalette (variable):                                              │
│   - Unique block types in chunk mapped to palette indexes              │
│   - Optimal bit depth: 1-8 bits per block (vs 32 bits normally)       │
│                                                                         │
│ • Bit-packed Block Data:                                                │
│   - All chunk blocks stored using palette indexes                      │
│   - Additional LZ4 compression applied to entire payload               │
│                                                                         │
│ COMPRESSION PERFORMANCE:                                                │
│ • Achieves 75-97% size reduction (vs uncompressed)                     │
│ • Air-only chunks: 97% reduction (1 bit/block)                         │
│ • Complex chunks: 50-75% reduction (5-8 bits/block)                    │
│ • 16×256×16 = 65,536 blocks → ~2KB-8KB per chunk                       │
└─────────────────────────────────────────────────────────────────────────┘
```

### EntityData.java (entities.json)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           EntityData.java                              │
│                          (entities.json)                               │
├─────────────────────────────────────────────────────────────────────────┤
│ ENTITY IDENTIFICATION:                                                  │
│ • entityType         : String    (COW, PIG, ZOMBIE, etc.)              │
│ • entityId           : long      (Unique instance identifier)          │
│                                                                         │
│ POSITION & PHYSICS:                                                     │
│ • positionX, Y, Z    : float     (World coordinates)                   │
│ • velocityX, Y, Z    : float     (Movement velocity)                   │
│ • rotationX, Y, Z    : float     (Entity orientation)                  │
│                                                                         │
│ ENTITY STATE:                                                           │
│ • health, maxHealth  : float     (HP system)                           │
│ • onGround           : boolean   (Physics contact)                     │
│ • inWater            : boolean   (Environment state)                   │
│ • alive              : boolean   (Entity status)                       │
│ • age                : float     (Entity age/lifetime)                 │
│                                                                         │
│ LIVING ENTITY DATA:                                                     │
│ • isMoving           : boolean   (Movement state)                      │
│ • invulnerable       : boolean   (Damage immunity)                     │
│ • invulnerabilityTimer: float    (Immunity countdown)                  │
│                                                                         │
│ AI & BEHAVIOR:                                                          │
│ • aiData             : Map<String,Object> (AI state storage)           │
│                                                                         │
│ DIMENSIONS:                                                             │
│ • width, height, length: float   (Entity collision box)                │
│ • legHeight          : float     (Ground clearance)                    │
└─────────────────────────────────────────────────────────────────────────┘
```

## Save Process Flow

### Auto-Save (Every 30 seconds)

```
┌─────────────┐    ┌──────────────┐    ┌─────────────────┐    ┌──────────┐
│ WorldSaver  │───▶│ Create       │───▶│ Selective Save  │───▶│ Backup   │
│ Timer       │    │ Backup       │    │ (Dirty chunks   │    │ Creation │
│ Triggers    │    │ Files        │    │  only)          │    │          │
└─────────────┘    └──────────────┘    └─────────────────┘    └──────────┘
```

### Manual Save

```
┌─────────────┐    ┌──────────────┐    ┌─────────────────┐
│ User Action │───▶│ Force Save   │───▶│ All Modified    │
│ (Save Game) │    │ All Data     │    │ Data Written    │
└─────────────┘    └──────────────┘    └─────────────────┘
```

## Load Process Flow

```
┌────────────┐    ┌──────────────┐    ┌─────────────────┐    ┌──────────┐
│ World      │───▶│ Validation   │───▶│ Corruption      │───▶│ Data     │
│ Selection  │    │ (Checksums,  │    │ Recovery        │    │ Loading  │
│            │    │  Schema)     │    │ (if needed)     │    │          │
└────────────┘    └──────────────┘    └─────────────────┘    └──────────┘
                        │                      │                   │
                        ▼                      ▼                   ▼
                  ┌──────────────┐    ┌─────────────────┐    ┌──────────┐
                  │ Progress     │    │ Backup          │    │ Runtime  │
                  │ Reporting    │    │ Restoration     │    │ State    │
                  │              │    │                 │    │ Applied  │
                  └──────────────┘    └─────────────────┘    └──────────┘
```

## File Operations

### Write Operations

- **Atomic writes** using temporary files (.tmp → .json)
- **Pre-save backup creation** (timestamped files)
- **Compression applied** (RLE for chunks)
- **Checksum generation** for validation
- **Thread-safe file locking**

### Read Operations

- **Schema validation** before loading
- **Checksum verification**
- **Corruption detection** and recovery
- **Progressive loading** with progress callbacks
- **Graceful fallback** for invalid data

## Performance Features

### Selective Saving

- Only **dirty chunks** are saved during auto-save
- **Player modification tracking** separate from world generation
- **Metadata updates** without full chunk rewrites

### Compression

- **Run-Length Encoding** achieves 80%+ compression on sparse chunks
- Large areas of identical blocks (air, stone) compress efficiently
- **65,536 blocks → hundreds of RLE entries**

### Async Operations

- **Background auto-save** every 30 seconds
- **Non-blocking save operations**
- **Separate thread pool** for file I/O
- **Progress reporting** during loads

### Validation & Recovery

- **Comprehensive file integrity checking**
- **Multiple recovery strategies** (backup restore, regeneration)
- **Intelligent error reporting** with severity levels
- **Caching of validation results** for performance

## Technical Implementation

### Key Classes

- **WorldManager**: Main coordination point for all save/load operations (integrates with binary system)
- **RegionFileManager**: Handles binary region file operations and caching
- **BinaryChunkCodec**: Encodes/decodes chunks using palette compression + LZ4
- **BlockPalette**: Optimizes block storage with 1-8 bits per block (vs 32 bits)
- **RegionFile**: Manages individual .mcr files containing 32×32 chunks
- **BinaryWorldMetadata**: Efficient binary world metadata storage
- **ChunkHeader**: 32-byte binary header for each chunk with flags and compression info
- **SaveFileValidator**: Comprehensive validation with checksum verification
- **CorruptionRecoveryManager**: Multi-strategy corruption recovery with backup management

### Data Serialization

All data is serialized using **Binary Format** with:
- **Custom binary protocols** optimized for game data
- **Block palette system** for efficient chunk storage
- **LZ4 compression** for fast compression/decompression
- **Magic numbers and version headers** for validation
- **Bit-packing** to minimize storage requirements

### Thread Safety

- **Concurrent file operations** with proper locking
- **Atomic writes** to prevent corruption
- **Separate thread pools** for different operations
- **Thread-safe data structures** throughout