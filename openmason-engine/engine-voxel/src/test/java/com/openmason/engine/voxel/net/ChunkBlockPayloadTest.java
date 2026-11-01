package com.openmason.engine.voxel.net;

import com.openmason.engine.net.protocol.codec.WireCompression;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #346: one compressed chunk payload per codec, shared by every connection (moved out of
 *  WireCompressionTest with ChunkBlockPayload when the engine split into modules, #387). */
class ChunkBlockPayloadTest {

    /** Layered, repetitive bytes — compresses like a chunk payload (as in WireCompressionTest). */
    private static byte[] terrainLike(int size) {
        byte[] b = new byte[size];
        Random r = new Random(7);
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ((i / 256) % 4 + (r.nextInt(50) == 0 ? 9 : 0));
        }
        return b;
    }

    @Test
    void chunkPayloadCompressesOncePerCodec() {
        ChunkBlockPayload payload = ChunkBlockPayload.ofEncoded(terrainLike(8192));
        byte[] first = payload.compressed(WireCompression.DEFLATE);
        assertTrue(first != null && first.length < 8192);
        assertSame(first, payload.compressed(WireCompression.DEFLATE), "shared by every connection");
        assertNull(payload.compressed(WireCompression.NONE));

        byte[] noise = new byte[4096];
        new Random(2).nextBytes(noise);
        assertNull(ChunkBlockPayload.ofEncoded(noise).compressed(WireCompression.DEFLATE));
    }
}
