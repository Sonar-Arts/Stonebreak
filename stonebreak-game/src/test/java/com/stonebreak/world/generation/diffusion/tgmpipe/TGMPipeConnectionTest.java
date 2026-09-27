package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.TGMPipeException;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TGMPipeConnection} against a fake service on in-JVM pipes: the handshake, tiles
 * answered out of order, errors, cancellation and the service going away. The frames the fake
 * writes are built by hand, independently of {@link TGMPipeProtocol}'s encoders, so a codec bug
 * cannot cancel itself out.
 */
class TGMPipeConnectionTest {

    private static final int N = 4;

    /** The service's end: reads the game's frames, writes its own. */
    private final PipedInputStream fromGame = new PipedInputStream(1 << 20);
    private final PipedOutputStream toGame = new PipedOutputStream();
    private final BlockingQueue<List<TGMPipeConnection.TileRequest>> closures = new LinkedBlockingQueue<>();
    private TGMPipeConnection connection;

    private TGMPipeConnection open() throws IOException {
        PipedInputStream gameIn = new PipedInputStream(toGame, 1 << 20);
        PipedOutputStream gameOut = new PipedOutputStream(fromGame);
        connection = new TGMPipeConnection(gameIn, gameOut, (c, reason, unfinished) -> closures.add(unfinished));
        connection.open("{\"world\":{}}");
        return connection;
    }

    @AfterEach
    void close() throws IOException {
        if (connection != null) connection.close();
        toGame.close();
    }

    private TGMPipeProtocol.Frame nextFromGame() throws IOException {
        TGMPipeProtocol.Frame frame = TGMPipeProtocol.read(fromGame);
        assertNotNull(frame, "the game closed its end");
        return frame;
    }

    private void send(int type, byte[] body) throws IOException {
        ByteBuffer frame = ByteBuffer.allocate(5 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(1 + body.length).put((byte) type).put(body);
        toGame.write(frame.array());
        toGame.flush();
    }

    private void sendTile(int id, boolean cached, int i1, int j1, short base) throws IOException {
        ByteBuffer body = ByteBuffer.allocate(17 + TGMPipeProtocol.PLANES * N * N * 2).order(ByteOrder.LITTLE_ENDIAN);
        body.putInt(id).put((byte) (cached ? 1 : 0)).putShort((short) N).putShort((short) N).putInt(i1).putInt(j1);
        for (int plane = 0; plane < TGMPipeProtocol.PLANES; plane++) {
            for (int k = 0; k < N * N; k++) {
                // Tunnel planes stay -1 (none) except plane 5, the flow, so null-plane folding is visible.
                body.putShort(plane == 3 || plane == 4 ? -1 : (short) (base + plane * 100 + k));
            }
        }
        send(TGMPipeProtocol.TILE_DATA, body.array());
    }

    private static byte[] idAndText(int id, String text) {
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + t.length).order(ByteOrder.LITTLE_ENDIAN).putInt(id).put(t).array();
    }

    private static TGMPipeConnection.TileRequest request(long seed, int x, int z) {
        return new TGMPipeConnection.TileRequest(seed, x, z, 1, 0, new CompletableFuture<>());
    }

    @Test
    void handshakeCarriesVersionAndWorldConfig() throws Exception {
        open();
        TGMPipeProtocol.Frame hello = nextFromGame();
        assertEquals(TGMPipeProtocol.HELLO, hello.type());
        assertEquals(TGMPipeProtocol.VERSION, hello.body().getShort(0));
        send(TGMPipeProtocol.READY, "{\"name\":\"fake\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("{\"name\":\"fake\"}", connection.ready().get(2, TimeUnit.SECONDS));
    }

    @Test
    void tilesAreMatchedByIdAndDecodedRowsAsX() throws Exception {
        open();
        nextFromGame(); // HELLO
        var first = request(-5L, -1, 2);
        var second = request(9L, 3, 3);
        connection.send(first);
        connection.send(second);
        ByteBuffer a = nextFromGame().body();
        ByteBuffer b = nextFromGame().body();
        assertEquals(-5L, a.getLong(4));
        assertEquals(-1, a.getInt(12));
        assertEquals(2, a.getInt(16));

        sendTile(b.getInt(0), true, 12, 12, (short) 7);     // answered out of order
        sendTile(a.getInt(0), false, -N, 2 * N, (short) 1);

        TerrainTile tile = first.future().get(2, TimeUnit.SECONDS);
        assertEquals(-1, tile.tileX());
        assertEquals(2, tile.tileZ());
        assertEquals(-N, tile.worldI1());
        assertEquals(2 * N, tile.worldJ1());
        assertEquals(0, tile.worldI2());
        assertEquals(1 + 0, tile.heightAt(-N, 2 * N));             // plane 0, cell 0
        assertEquals(1 + N, tile.heightAt(-N + 1, 2 * N));         // next ROW is next x
        assertEquals(1 + 100 + 1, tile.biomeIdAt(-N, 2 * N + 1));       // plane 1, next column
        assertNull(tile.riverFloors(), "all-sentinel tunnel planes fold to null");
        assertNotNull(tile.riverFlows());
        assertEquals(7, second.future().get(2, TimeUnit.SECONDS).heightAt(12, 12));
        assertEquals(0, connection.pendingCount());
    }

    @Test
    void serviceErrorsFailOnlyTheirRequest() throws Exception {
        open();
        nextFromGame();
        var bad = request(1L, 0, 0);
        connection.send(bad);
        int id = nextFromGame().body().getInt(0);
        send(TGMPipeProtocol.TILE_ERROR, idAndText(id, "RuntimeError: CUDA out of memory"));

        CompletionException e = assertThrows(CompletionException.class, () -> bad.future().join());
        assertInstanceOf(TGMPipeException.class, e.getCause());
        assertTrue(e.getCause().getMessage().contains("CUDA out of memory"));
        assertTrue(closures.isEmpty(), "one failed tile must not end the connection");
    }

    @Test
    void aRequestCompletedElsewhereIsCancelledAtTheService() throws Exception {
        open();
        nextFromGame();
        var withdrawn = request(1L, 0, 0);
        connection.send(withdrawn);
        int id = nextFromGame().body().getInt(0);

        withdrawn.future().cancel(false);

        TGMPipeProtocol.Frame cancel = nextFromGame();
        assertEquals(TGMPipeProtocol.CANCEL, cancel.type());
        assertEquals(id, cancel.body().getInt(0));
        sendTile(id, false, 0, 0, (short) 0);                  // raced the cancel: must be ignored
        assertEquals(0, connection.pendingCount());
    }

    @Test
    void theServiceGoingAwayHandsOverEveryUnfinishedRequest() throws Exception {
        open();
        nextFromGame();
        var a = request(1L, 0, 0);
        var b = request(1L, 1, 0);
        connection.send(a);
        connection.send(b);
        nextFromGame();
        nextFromGame();
        send(TGMPipeProtocol.FATAL, "CUDA error: device lost".getBytes(StandardCharsets.UTF_8));
        toGame.close();

        List<TGMPipeConnection.TileRequest> unfinished = closures.poll(2, TimeUnit.SECONDS);
        assertNotNull(unfinished);
        assertEquals(2, unfinished.size());
        assertTrue(!a.future().isDone() && !b.future().isDone(), "left for the owner to re-send or fail");
        assertTrue(connection.isClosed() && !connection.closedByClient());
        assertThrows(TGMPipeException.class, () -> connection.send(request(1L, 2, 0)));
    }

    @Test
    void aBodyOfTheWrongSizeIsRejectedNotSlicedIntoTerrain() {
        ByteBuffer body = ByteBuffer.allocate(17 + 10).order(ByteOrder.LITTLE_ENDIAN);
        body.putInt(1).put((byte) 0).putShort((short) N).putShort((short) N).putInt(0).putInt(0);
        var frame = new TGMPipeProtocol.Frame(TGMPipeProtocol.TILE_DATA, body.flip());
        assertThrows(IllegalStateException.class, () -> TGMPipeProtocol.decodeTile(frame, 0, 0));
    }

    @Test
    void encodersWriteTheDocumentedLayout() throws IOException {
        byte[] tile = TGMPipeProtocol.tile(3, -2L, -7, 8, 8, 1);
        ByteBuffer buf = ByteBuffer.wrap(tile).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(tile.length - 4, buf.getInt());
        assertEquals(TGMPipeProtocol.TILE, buf.get());
        assertEquals(3, buf.getInt());
        assertEquals(-2L, buf.getLong());
        assertEquals(-7, buf.getInt());
        assertEquals(8, buf.getInt());
        assertEquals(8, buf.get());
        assertEquals(1, buf.get());
        assertEquals(0, buf.remaining());

        TGMPipeProtocol.Frame read = TGMPipeProtocol.read(new ByteArrayInputStream(TGMPipeProtocol.cancel(77)));
        assertEquals(TGMPipeProtocol.CANCEL, read.type());
        assertEquals(77, TGMPipeProtocol.requestId(read));
        assertNull(TGMPipeProtocol.read(new ByteArrayInputStream(new byte[0])), "clean end of stream");
        assertThrows(IOException.class, () -> TGMPipeProtocol.read(new ByteArrayInputStream(new byte[] {9, 0, 0, 0, 1})));
        assertArrayEquals(new byte[] {5, 0, 0, 0, TGMPipeProtocol.STATUS, 1, 0, 0, 0}, TGMPipeProtocol.status(1));
    }
}
