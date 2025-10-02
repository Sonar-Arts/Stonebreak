package com.stonebreak.world.save;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.serialization.BinaryChunkSerializer;
import com.stonebreak.world.save.util.StateConverter;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class BinaryChunkSerializerCompatibilityTest {

    @Test
    void serializeSetsLegacyFeatureFlag() {
        BinaryChunkSerializer serializer = new BinaryChunkSerializer();
        Chunk chunk = new Chunk(0, 0);
        chunk.setFeaturesPopulated(true);

        ChunkData chunkData = StateConverter.toChunkData(chunk);
        byte[] encoded = serializer.serialize(chunkData);

        ByteBuffer header = ByteBuffer.wrap(encoded, 0, 32);
        header.getInt(); // chunkX
        header.getInt(); // chunkZ
        header.getInt(); // version
        header.getInt(); // uncompressed size
        header.getLong(); // lastModified
        header.getInt(); // palette size
        header.get();    // bitsPerBlock
        header.get();    // compressionType
        byte flags = header.get();

        assertEquals(0x03, flags, "Serializer should set both current and legacy feature flags");
    }

    @Test
    void deserializeRecognisesLegacyFeatureFlag() {
        BinaryChunkSerializer serializer = new BinaryChunkSerializer();
        Chunk chunk = new Chunk(0, 0);
        chunk.setFeaturesPopulated(true);
        ChunkData chunkData = StateConverter.toChunkData(chunk);
        byte[] encoded = serializer.serialize(chunkData);

        byte[] legacyEncoded = encoded.clone();
        legacyEncoded[30] = 0x02; // legacy saves used bit 1 for the features flag

        ChunkData decoded = serializer.deserialize(legacyEncoded);
        assertTrue(decoded.isFeaturesPopulated(), "Legacy feature flag should still mark chunk as populated");
    }
}
