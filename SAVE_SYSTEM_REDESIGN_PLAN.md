# Stonebreak Save System Redesign Plan

## Overview

This document outlines a complete redesign of Stonebreak's save system, optimized for performance and following industry best practices. We're abandoning backward compatibility to implement the most efficient architecture possible.

## Current Problems

### File System Issues
- **File Handle Explosion**: 1000+ individual chunk files for moderate worlds
- **Poor I/O Performance**: Each chunk = separate file operation
- **Disk Fragmentation**: Scattered file access patterns
- **Metadata Overhead**: JSON headers repeated in every file

### Storage Inefficiencies  
- **JSON Overhead**: Text format with significant size overhead
- **No Compression**: Raw JSON storage without optimization
- **Individual File Overhead**: File system metadata per chunk

## New Save Architecture

### Region-Based Binary Storage

#### File Structure
```
worlds/
├── [worldName]/
│   ├── world.dat                 # World metadata (binary)
│   ├── player.dat                # Player data (binary) 
│   ├── regions/
│   │   ├── r.0.0.mcr            # Region files (32x32 chunks each)
│   │   ├── r.0.1.mcr
│   │   ├── r.1.0.mcr
│   │   └── ...
│   └── backups/                  # Automatic backups
│       ├── world_backup_1.zip
│       └── world_backup_2.zip
```

#### Region File Format (.mcr)
```
Region File Structure (Binary):
┌─────────────────────────────────────────────────────────────┐
│ Header (8KB)                                                │
├─────────────────────────────────────────────────────────────┤
│ Chunk Offset Table (1024 * 4 bytes = 4KB)                  │
│ - offset[0][0], offset[0][1], ..., offset[31][31]          │
├─────────────────────────────────────────────────────────────┤
│ Chunk Length Table (1024 * 4 bytes = 4KB)                  │
│ - length[0][0], length[0][1], ..., length[31][31]          │
├─────────────────────────────────────────────────────────────┤
│ Chunk Data Section (Variable size)                          │
│ ├─ Chunk 0,0 (LZ4 compressed)                              │
│ ├─ Chunk 0,1 (LZ4 compressed)                              │
│ └─ ...                                                      │
└─────────────────────────────────────────────────────────────┘
```

### Binary Chunk Format

#### Chunk Data Structure
```
Individual Chunk Format (Before Compression):
┌─────────────────────────────────────────┐
│ Chunk Header (32 bytes)                 │
├─────────────────────────────────────────┤
│ Block Palette (Variable)                │
├─────────────────────────────────────────┤
│ Block Data (Palette indexes)            │
├─────────────────────────────────────────┤
│ Height Map (512 bytes)                  │
├─────────────────────────────────────────┤
│ Biome Data (256 bytes)                  │
├─────────────────────────────────────────┤
│ Modification Flags (Optional)           │
└─────────────────────────────────────────┘
```

#### Chunk Header (32 bytes)
```java
struct ChunkHeader {
    int32  chunkX;              // 4 bytes
    int32  chunkZ;              // 4 bytes  
    int32  version;             // 4 bytes
    int32  dataSize;            // 4 bytes (uncompressed)
    int64  lastModified;        // 8 bytes (Unix timestamp)
    int32  paletteSize;         // 4 bytes
    int8   bitsPerBlock;        // 1 byte
    int8   compressionType;     // 1 byte (0=none, 1=LZ4)
    int8   flags;               // 1 byte (dirty, player-modified, etc.)
    int8   reserved;            // 1 byte padding
}
```

### Palette-Based Block Storage

#### Block Palette
Instead of storing full BlockType enums, use a palette system:

```java
// Palette maps block IDs to actual block types
struct BlockPalette {
    int32    paletteSize;       // Number of unique blocks
    BlockType palette[];        // Array of unique block types in chunk
}

// Block data uses palette indexes instead of full enums
struct BlockData {
    int32    dataLength;        // Length of packed data array
    int64    packedData[];      // Bit-packed palette indexes
}
```

#### Bit Packing Examples
- **1 bit/block**: Air-only chunks (2 states: air/solid)
- **2 bits/block**: Simple chunks (4 block types)
- **4 bits/block**: Typical chunks (16 block types)
- **8 bits/block**: Complex chunks (256 block types)

#### Memory Savings
```
Current System:
- 16×256×16 blocks × 4 bytes (enum) = 262,144 bytes per chunk

New Palette System:
- Air-only: 1 bit/block = 8,192 bytes (97% reduction)
- Simple: 2 bits/block = 16,384 bytes (94% reduction)  
- Typical: 4 bits/block = 32,768 bytes (87% reduction)
- Complex: 8 bits/block = 65,536 bytes (75% reduction)
```

## Implementation Plan

### Phase 1: Binary Serialization Framework

#### Create Binary I/O System
```java
// New file: BinaryChunkCodec.java
public class BinaryChunkCodec {
    
    public byte[] encodeChunk(Chunk chunk) {
        ByteBuffer buffer = ByteBuffer.allocate(estimateChunkSize(chunk));
        
        // Write chunk header
        writeChunkHeader(buffer, chunk);
        
        // Build and write palette
        BlockPalette palette = buildPalette(chunk);
        writePalette(buffer, palette);
        
        // Write bit-packed block data
        writeBlockData(buffer, chunk, palette);
        
        // Write height map and biome data
        writeHeightMap(buffer, chunk);
        writeBiomeData(buffer, chunk);
        
        return buffer.array();
    }
    
    public Chunk decodeChunk(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        
        // Read header
        ChunkHeader header = readChunkHeader(buffer);
        
        // Read palette
        BlockPalette palette = readPalette(buffer);
        
        // Read block data
        Chunk chunk = new Chunk(header.chunkX, header.chunkZ);
        readBlockData(buffer, chunk, palette);
        
        // Read additional data
        readHeightMap(buffer, chunk);
        readBiomeData(buffer, chunk);
        
        return chunk;
    }
}
```

### Phase 2: Region File Manager

#### Create Region System
```java
// New file: RegionFileManager.java
public class RegionFileManager {
    private final Map<RegionCoord, RegionFile> openRegions = new ConcurrentHashMap<>();
    private final String worldPath;
    
    public RegionFileManager(String worldPath) {
        this.worldPath = worldPath;
    }
    
    public CompletableFuture<Chunk> loadChunk(int chunkX, int chunkZ) {
        RegionCoord regionCoord = getRegionCoord(chunkX, chunkZ);
        RegionFile regionFile = getOrOpenRegion(regionCoord);
        
        return CompletableFuture.supplyAsync(() -> {
            byte[] chunkData = regionFile.readChunk(chunkX & 31, chunkZ & 31);
            if (chunkData == null) return null;
            
            // Decompress if needed
            if (isCompressed(chunkData)) {
                chunkData = LZ4Factory.fastestInstance().fastDecompressor()
                    .decompress(chunkData);
            }
            
            return binaryCodec.decodeChunk(chunkData);
        });
    }
    
    public CompletableFuture<Void> saveChunk(Chunk chunk) {
        RegionCoord regionCoord = getRegionCoord(chunk.getX(), chunk.getZ());
        RegionFile regionFile = getOrCreateRegion(regionCoord);
        
        return CompletableFuture.runAsync(() -> {
            byte[] chunkData = binaryCodec.encodeChunk(chunk);
            
            // Compress if beneficial
            byte[] compressed = LZ4Factory.fastestInstance().fastCompressor()
                .compress(chunkData);
            if (compressed.length < chunkData.length * 0.9) {
                chunkData = compressed;
            }
            
            regionFile.writeChunk(chunk.getX() & 31, chunk.getZ() & 31, chunkData);
        });
    }
}
```

#### Individual Region File Handler
```java
// New file: RegionFile.java  
public class RegionFile implements AutoCloseable {
    private final RandomAccessFile file;
    private final int[] chunkOffsets = new int[1024];    // 32×32 = 1024 chunks
    private final int[] chunkLengths = new int[1024];
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    public RegionFile(Path regionPath) throws IOException {
        this.file = new RandomAccessFile(regionPath.toFile(), "rw");
        loadHeader();
    }
    
    public byte[] readChunk(int localX, int localZ) throws IOException {
        int index = localX + localZ * 32;
        
        lock.readLock().lock();
        try {
            int offset = chunkOffsets[index];
            int length = chunkLengths[index];
            
            if (offset == 0 || length == 0) {
                return null; // Chunk not saved
            }
            
            file.seek(offset);
            byte[] data = new byte[length];
            file.readFully(data);
            return data;
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public void writeChunk(int localX, int localZ, byte[] data) throws IOException {
        int index = localX + localZ * 32;
        
        lock.writeLock().lock();
        try {
            // Find space at end of file
            long offset = file.length();
            
            // Write chunk data
            file.seek(offset);
            file.write(data);
            
            // Update header
            chunkOffsets[index] = (int) offset;
            chunkLengths[index] = data.length;
            
            // Write updated header
            updateHeader(index);
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private void loadHeader() throws IOException {
        if (file.length() < 8192) { // New file
            file.setLength(8192); // Pre-allocate header space
            return;
        }
        
        file.seek(0);
        for (int i = 0; i < 1024; i++) {
            chunkOffsets[i] = file.readInt();
        }
        for (int i = 0; i < 1024; i++) {
            chunkLengths[i] = file.readInt();
        }
    }
    
    private void updateHeader(int chunkIndex) throws IOException {
        // Update just the changed offset/length entries
        file.seek(chunkIndex * 4);
        file.writeInt(chunkOffsets[chunkIndex]);
        
        file.seek(4096 + chunkIndex * 4);
        file.writeInt(chunkLengths[chunkIndex]);
    }
}
```

### Phase 3: World Metadata

#### Binary World Data
```java
// New file: WorldMetadata.java
public class WorldMetadata {
    private long seed;
    private String worldName;
    private Vector3f spawnPosition;
    private long createdTime;
    private long lastPlayed;
    private int version;
    private Map<String, String> customProperties;
    
    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        buffer.putLong(seed);
        writeString(buffer, worldName);
        buffer.putFloat(spawnPosition.x);
        buffer.putFloat(spawnPosition.y); 
        buffer.putFloat(spawnPosition.z);
        buffer.putLong(createdTime);
        buffer.putLong(lastPlayed);
        buffer.putInt(version);
        
        // Custom properties
        buffer.putInt(customProperties.size());
        for (Map.Entry<String, String> entry : customProperties.entrySet()) {
            writeString(buffer, entry.getKey());
            writeString(buffer, entry.getValue());
        }
        
        return Arrays.copyOf(buffer.array(), buffer.position());
    }
    
    public static WorldMetadata deserialize(byte[] data) {
        // Read back from binary format
    }
}
```

### Phase 4: Player Data Optimization

#### Binary Player Data
```java
// New file: PlayerData.java
public class PlayerData {
    private Vector3f position;
    private Vector2f rotation;
    private float health;
    private boolean isFlying;
    private Inventory inventory;
    private long lastSaved;
    
    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        
        // Position and rotation
        buffer.putFloat(position.x);
        buffer.putFloat(position.y);
        buffer.putFloat(position.z);
        buffer.putFloat(rotation.x);
        buffer.putFloat(rotation.y);
        
        // Player state
        buffer.putFloat(health);
        buffer.put((byte) (isFlying ? 1 : 0));
        buffer.putLong(lastSaved);
        
        // Inventory (binary serialized)
        byte[] inventoryData = inventory.serialize();
        buffer.putInt(inventoryData.length);
        buffer.put(inventoryData);
        
        return Arrays.copyOf(buffer.array(), buffer.position());
    }
}
```

## Performance Expectations

### File System Improvements
- **File Count**: 1000+ chunk files → ~10 region files (99% reduction)
- **File Handles**: Massive reduction in OS file handle usage
- **Disk I/O**: Sequential reads instead of random access across many files

### Storage Efficiency
- **Compression**: 70-90% size reduction with LZ4 compression
- **Palette Storage**: 75-97% memory reduction for typical chunks
- **Binary Format**: 30-50% smaller than JSON even before compression

### Performance Gains
- **Save Speed**: 5-10x faster due to batched region writes
- **Load Speed**: 3-5x faster due to sequential reads and binary parsing
- **Memory Usage**: 70-90% reduction in chunk memory footprint
- **Startup Time**: Faster world loading due to fewer file system operations

## Implementation Timeline

### Week 1-2: Binary Serialization
- Implement BinaryChunkCodec
- Create palette-based block storage
- Test compression ratios

### Week 3-4: Region System
- Build RegionFileManager and RegionFile classes
- Implement chunk offset/length tracking
- Add atomic write operations

### Week 5-6: Integration
- Replace existing ChunkFileManager with RegionFileManager  
- Update WorldManager to use binary metadata
- Convert PlayerData to binary format

### Week 7: Optimization & Testing
- Performance benchmarking
- Memory usage validation
- Stress testing with large worlds

## Migration Strategy (If Needed Later)

While we're not maintaining backward compatibility now, if migration becomes necessary:

### One-Time Conversion Tool
```java
public class SaveFormatConverter {
    public void convertWorldToBinary(String worldName) {
        // Read old JSON chunks
        // Convert to new binary region format  
        // Create backup of old format
        // Replace with new format
    }
}
```

This new save system will provide dramatic performance improvements while following modern game development best practices for data storage and retrieval.