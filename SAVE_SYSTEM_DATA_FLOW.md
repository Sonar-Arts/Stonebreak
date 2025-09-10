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
│   ├── world.json                   ← WorldSaveMetadata (JSON)
│   ├── player.json                  ← PlayerData (JSON) 
│   ├── entities.json               ← EntityData[] (JSON)
│   ├── chunks/                     ← Chunk files directory
│   │   ├── chunk_0_0.json          ← ChunkData (JSON w/ RLE compression)
│   │   ├── chunk_0_1.json          ← ChunkData (JSON w/ RLE compression)
│   │   ├── chunk_1_0.json          ← ChunkData (JSON w/ RLE compression)
│   │   └── ...                     ← More chunk files
│   └── backups/                    ← Automatic backup directory
│       ├── world_backup_TIMESTAMP.json
│       ├── player_backup_TIMESTAMP.json
│       └── entities_backup_TIMESTAMP.json
│
├── [worldName2]/                    ← Another world
│   ├── world.json
│   ├── player.json
│   └── ...
└── ...
```

## Data Models & Contents

### WorldSaveMetadata.java (world.json)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        WorldSaveMetadata.java                          │
│                         (world.json)                                   │
├─────────────────────────────────────────────────────────────────────────┤
│ • worldName          : String    (World display name)                  │
│ • seed               : long      (World generation seed)               │
│ • spawnX, spawnY, spawnZ : float (Spawn coordinates)                   │
│ • creationTime       : LocalDateTime (When world was created)          │
│ • lastPlayed         : LocalDateTime (Last play session)               │
│ • totalPlayTimeMillis: long      (Total play time)                     │
│ • gameVersion        : String    (Game version compatibility)          │
│ • difficulty         : String    (Game difficulty setting)             │
│ • gameMode           : String    (SURVIVAL/CREATIVE)                   │
│ • worldType          : String    (DEFAULT/FLAT/etc)                    │
│ • preGeneratedChunks : int       (Chunk generation counter)            │
│ • schemaVersion      : Integer   (Save format version)                 │
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

### ChunkData.java (chunks/chunk_X_Z.json)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           ChunkData.java                               │
│                      (chunks/chunk_X_Z.json)                           │
├─────────────────────────────────────────────────────────────────────────┤
│ CHUNK IDENTIFICATION:                                                   │
│ • chunkX, chunkZ     : int       (Chunk coordinates)                   │
│                                                                         │
│ COMPRESSED BLOCK DATA (Run-Length Encoding):                           │
│ • blocks[]           : BlockEntry[] (RLE compressed block data)        │
│   └─ BlockEntry:                                                       │
│      • blockId       : int       (Block type ID)                      │
│      • runLength     : int       (Consecutive block count)             │
│                                                                         │
│ MODIFICATION TRACKING:                                                  │
│ • isDirty            : boolean   (Has unsaved changes)                 │
│ • lastModified       : LocalDateTime (Last modification time)          │
│ • generatedByPlayer  : boolean   (Player vs world generation)          │
│ • featuresPopulated  : boolean   (Trees/ores/structures added)         │
│                                                                         │
│ METADATA:                                                               │
│ • version            : int       (Chunk format version)                │
│ • compressionType    : String    ("RLE" - Run-Length Encoding)         │
│                                                                         │
│ COMPRESSION STATS:                                                      │
│ • Achieves 80%+ compression on sparse chunks (mostly air)              │
│ • 16×256×16 = 65,536 blocks compressed to ~hundreds of entries         │
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

- **WorldManager**: Main coordination point for all save/load operations
- **WorldSaver**: Handles asynchronous world saving with auto-save and backup creation
- **WorldLoader**: Loads world data with granular progress tracking
- **ChunkFileManager**: Manages chunk serialization with compression and validation
- **SaveFileValidator**: Comprehensive validation with checksum verification
- **CorruptionRecoveryManager**: Multi-strategy corruption recovery with backup management

### Data Serialization

All data is serialized using **Jackson JSON** with:
- **JavaTimeModule** for LocalDateTime support
- **Custom annotations** for field mapping
- **Schema versioning** for backward compatibility
- **Compression** for large data structures

### Thread Safety

- **Concurrent file operations** with proper locking
- **Atomic writes** to prevent corruption
- **Separate thread pools** for different operations
- **Thread-safe data structures** throughout