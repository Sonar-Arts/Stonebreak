package com.openmason.engine.net.server;

import com.openmason.engine.net.protocol.Packet;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Issue #346: a tick's sends are flushed once per connection, at the batch's flush. */
class OutboundBatchTest {

    private record Ping(int n) implements Packet {}

    @Test
    void sendsInsideTheBatchWaitForItsFlush() {
        OutboundBatch batch = new OutboundBatch();
        EmbeddedChannel channel = new EmbeddedChannel();
        ServerConnection conn = new ServerConnection(channel, 0L, batch);

        batch.begin();
        conn.send(new Ping(1));
        conn.send(new Ping(2));
        assertNull(channel.readOutbound(), "nothing flushed mid-tick");

        batch.flush();
        assertEquals(new Ping(1), channel.readOutbound());
        assertEquals(new Ping(2), channel.readOutbound());

        conn.send(new Ping(3)); // still open after flush()
        assertNull(channel.readOutbound());
        batch.end();
        assertEquals(new Ping(3), channel.readOutbound());

        conn.send(new Ping(4)); // ended: flushed at once again
        assertEquals(new Ping(4), channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void otherThreadsAreNeverDeferred() throws Exception {
        OutboundBatch batch = new OutboundBatch();
        EmbeddedChannel channel = new EmbeddedChannel();
        ServerConnection conn = new ServerConnection(channel, 0L, batch);
        batch.begin();

        Thread other = new Thread(() -> conn.send(new Ping(7)));
        other.start();
        other.join();
        assertEquals(new Ping(7), channel.readOutbound(), "a worker's send is not stranded");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread flusher = new Thread(() -> {
            try {
                batch.end(); // not the owner: neither flushes nor closes the owner's batch
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        conn.send(new Ping(8));
        flusher.start();
        flusher.join();
        assertNull(failure.get());
        assertNull(channel.readOutbound());
        batch.end();
        assertEquals(new Ping(8), channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void eachConnectionIsFlushedOncePerFlush() {
        OutboundBatch batch = new OutboundBatch();
        int[] flushes = new int[1];
        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.channel.ChannelOutboundHandlerAdapter() {
            @Override
            public void flush(io.netty.channel.ChannelHandlerContext ctx) {
                flushes[0]++;
                ctx.flush();
            }
        });
        ServerConnection conn = new ServerConnection(channel, 0L, batch);
        batch.begin();
        for (int i = 0; i < 50; i++) {
            conn.send(new Ping(i));
        }
        batch.end();
        assertEquals(1, flushes[0]);
        Object first = channel.readOutbound();
        assertSame(Ping.class, first.getClass());
        channel.finishAndReleaseAll();
    }
}
