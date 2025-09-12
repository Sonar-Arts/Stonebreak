package com.stonebreak.world.save;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Binary chunk header structure for the new save system.
 * Exactly 32 bytes per chunk header, stored at the beginning of each chunk's data.
 */
public class ChunkHeader {
    
    /** Header size in bytes */
    public static final int HEADER_SIZE = 32;
    
    /** Compression type constants */
    public static final byte COMPRESSION_NONE = 0;
    public static final byte COMPRESSION_LZ4 = 1;
    
    /** Chunk flag bits */
    public static final byte FLAG_DIRTY = 1;
    public static final byte FLAG_PLAYER_MODIFIED = 2;
    public static final byte FLAG_FEATURES_POPULATED = 4;
    
    private int chunkX;
    private int chunkZ;
    private int version;
    private int uncompressedSize;
    private long lastModified; // Unix timestamp in milliseconds
    private int paletteSize;
    private byte bitsPerBlock;
    private byte compressionType;
    private byte flags;
    private byte reserved; // Padding for alignment
    
    /**
     * Default constructor.
     */
    public ChunkHeader() {
        this.version = 1; // Current save format version
        this.compressionType = COMPRESSION_LZ4;
        this.lastModified = System.currentTimeMillis();
    }
    
    /**
     * Create a chunk header with basic information.
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param paletteSize Number of unique blocks in palette
     * @param bitsPerBlock Bits per block in the palette system
     * @param uncompressedSize Uncompressed data size
     */
    public ChunkHeader(int chunkX, int chunkZ, int paletteSize, byte bitsPerBlock, int uncompressedSize) {
        this();
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.paletteSize = paletteSize;
        this.bitsPerBlock = bitsPerBlock;
        this.uncompressedSize = uncompressedSize;
    }
    
    /**
     * Serialize this header to binary format.
     * @return 32-byte binary representation
     */
    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        
        buffer.putInt(chunkX);              // 4 bytes
        buffer.putInt(chunkZ);              // 4 bytes
        buffer.putInt(version);             // 4 bytes
        buffer.putInt(uncompressedSize);    // 4 bytes
        buffer.putLong(lastModified);       // 8 bytes
        buffer.putInt(paletteSize);         // 4 bytes
        buffer.put(bitsPerBlock);           // 1 byte
        buffer.put(compressionType);        // 1 byte
        buffer.put(flags);                  // 1 byte
        buffer.put(reserved);               // 1 byte
        
        return buffer.array();
    }
    
    /**
     * Deserialize a header from binary format.
     * @param data 32-byte binary data
     * @return Deserialized chunk header
     */
    public static ChunkHeader deserialize(byte[] data) {
        if (data.length != HEADER_SIZE) {
            throw new IllegalArgumentException("Chunk header must be exactly " + HEADER_SIZE + " bytes");
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(data);
        ChunkHeader header = new ChunkHeader();
        
        header.chunkX = buffer.getInt();
        header.chunkZ = buffer.getInt();
        header.version = buffer.getInt();
        header.uncompressedSize = buffer.getInt();
        header.lastModified = buffer.getLong();
        header.paletteSize = buffer.getInt();
        header.bitsPerBlock = buffer.get();
        header.compressionType = buffer.get();
        header.flags = buffer.get();
        header.reserved = buffer.get();
        
        return header;
    }
    
    // Getters and setters
    
    public int getChunkX() {
        return chunkX;
    }
    
    public void setChunkX(int chunkX) {
        this.chunkX = chunkX;
    }
    
    public int getChunkZ() {
        return chunkZ;
    }
    
    public void setChunkZ(int chunkZ) {
        this.chunkZ = chunkZ;
    }
    
    public int getVersion() {
        return version;
    }
    
    public void setVersion(int version) {
        this.version = version;
    }
    
    public int getUncompressedSize() {
        return uncompressedSize;
    }
    
    public void setUncompressedSize(int uncompressedSize) {
        this.uncompressedSize = uncompressedSize;
    }
    
    public long getLastModified() {
        return lastModified;
    }
    
    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }
    
    /**
     * Set last modified from LocalDateTime.
     * @param dateTime LocalDateTime to convert
     */
    public void setLastModified(LocalDateTime dateTime) {
        this.lastModified = dateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
    
    /**
     * Get last modified as LocalDateTime.
     * @return LocalDateTime representation
     */
    public LocalDateTime getLastModifiedAsDateTime() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(lastModified), ZoneOffset.UTC);
    }
    
    public int getPaletteSize() {
        return paletteSize;
    }
    
    public void setPaletteSize(int paletteSize) {
        this.paletteSize = paletteSize;
    }
    
    public byte getBitsPerBlock() {
        return bitsPerBlock;
    }
    
    public void setBitsPerBlock(byte bitsPerBlock) {
        this.bitsPerBlock = bitsPerBlock;
    }
    
    public byte getCompressionType() {
        return compressionType;
    }
    
    public void setCompressionType(byte compressionType) {
        this.compressionType = compressionType;
    }
    
    public boolean isCompressed() {
        return compressionType != COMPRESSION_NONE;
    }
    
    public byte getFlags() {
        return flags;
    }
    
    public void setFlags(byte flags) {
        this.flags = flags;
    }
    
    // Flag utility methods
    
    public boolean isDirty() {
        return (flags & FLAG_DIRTY) != 0;
    }
    
    public void setDirty(boolean dirty) {
        if (dirty) {
            flags |= FLAG_DIRTY;
        } else {
            flags &= ~FLAG_DIRTY;
        }
    }
    
    public boolean isPlayerModified() {
        return (flags & FLAG_PLAYER_MODIFIED) != 0;
    }
    
    public void setPlayerModified(boolean playerModified) {
        if (playerModified) {
            flags |= FLAG_PLAYER_MODIFIED;
        } else {
            flags &= ~FLAG_PLAYER_MODIFIED;
        }
    }
    
    public boolean isFeaturesPopulated() {
        return (flags & FLAG_FEATURES_POPULATED) != 0;
    }
    
    public void setFeaturesPopulated(boolean featuresPopulated) {
        if (featuresPopulated) {
            flags |= FLAG_FEATURES_POPULATED;
        } else {
            flags &= ~FLAG_FEATURES_POPULATED;
        }
    }
    
    @Override
    public String toString() {
        return String.format("ChunkHeader[x=%d, z=%d, v=%d, size=%d, palette=%d:%d, flags=0x%02X]",
                chunkX, chunkZ, version, uncompressedSize, paletteSize, bitsPerBlock, flags);
    }
}