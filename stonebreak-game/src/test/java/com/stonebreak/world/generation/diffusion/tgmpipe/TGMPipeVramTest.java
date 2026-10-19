package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.openmason.engine.ui.masonry.MStatPanel;
import com.stonebreak.world.generation.diffusion.TGMPipeException;
import com.stonebreak.ui.debug.VramPanel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The F3 screen's Daedalus VRAM reading: STATUS parsing, refresh cadence, staleness, a dead service. */
class TGMPipeVramTest {

    private static final String GPU_STATUS = "{\"name\": \"DaedalusTGM-Exp\", \"queue\": 0, \"vram\": {"
            + "\"device\": \"cuda:1\", \"gpu\": \"NVIDIA RTX PRO 6000\", \"allocated_bytes\": 1073741824,"
            + " \"reserved_bytes\": 2147483648, \"peak_bytes\": 1610612736, \"process_bytes\": 3221225472,"
            + " \"device_used_bytes\": 4294967296, \"device_total_bytes\": 102641958912}}";

    @Test
    void parsesTheVramObject() {
        TGMPipeVram.Reading r = TGMPipeVram.parse(GPU_STATUS, 5);
        assertNotNull(r);
        assertEquals("cuda:1", r.device());
        assertTrue(r.onGpu());
        assertEquals(3221225472L, r.processBytes());
        assertEquals(1073741824L, r.allocatedBytes());
        assertEquals(2147483648L, r.reservedBytes());
        assertEquals(1610612736L, r.peakBytes());
        assertEquals(102641958912L, r.deviceTotalBytes());
        assertEquals(5, r.atMillis());
    }

    @Test
    void missingFieldsAreMinusOneAndBadRepliesAreNull() {
        TGMPipeVram.Reading cpu = TGMPipeVram.parse("{\"vram\": {\"device\": \"cpu\"}}", 0);
        assertNotNull(cpu);
        assertFalse(cpu.onGpu());
        assertEquals(-1, cpu.processBytes());
        assertNull(TGMPipeVram.parse("{\"queue\": 0}", 0), "an older service without vram");
        assertNull(TGMPipeVram.parse("not json", 0));
    }

    @Test
    void refreshesAtMostOncePerIntervalAndDropsStaleOrDeadReadings() {
        AtomicLong now = new AtomicLong(10_000);
        AtomicInteger asked = new AtomicInteger();
        CompletableFuture<String>[] reply = new CompletableFuture[1];
        TGMPipeVram vram = new TGMPipeVram(() -> {
            asked.incrementAndGet();
            reply[0] = new CompletableFuture<>();
            return reply[0];
        }, now::get);

        assertNull(vram.latest(), "nothing until the first reply");
        assertEquals(1, asked.get());
        assertNull(vram.latest());
        assertEquals(1, asked.get(), "one request in flight at a time");
        reply[0].complete(GPU_STATUS);
        assertNotNull(vram.latest());

        now.addAndGet(TGMPipeVram.REFRESH_MS / 2);
        vram.latest();
        assertEquals(1, asked.get(), "no refresh inside the interval");
        now.addAndGet(TGMPipeVram.REFRESH_MS);
        assertNotNull(vram.latest(), "the old reading still shows while the refresh runs");
        assertEquals(2, asked.get());

        now.addAndGet(TGMPipeVram.STALE_MS + 1);
        assertNull(vram.latest(), "an unanswered refresh lets the reading go stale");

        reply[0].completeExceptionally(new TGMPipeException("TGMPipe is not running"));
        now.addAndGet(TGMPipeVram.REFRESH_MS);
        assertNull(vram.latest(), "a dead service hides the section");
    }

    @Test
    void panelSectionShowsProcessTensorsAndCardOnlyWithAReading() {
        MStatPanel none = new MStatPanel("VRAM");
        VramPanel.addDaedalus(none, null);
        assertTrue(none.lines().isEmpty());

        MStatPanel gpu = new MStatPanel("VRAM");
        VramPanel.addDaedalus(gpu, TGMPipeVram.parse(GPU_STATUS, 0));
        List<MStatPanel.Line> l = gpu.lines();
        assertEquals("Daedalus (cuda:1)", l.get(0).label());
        assertTrue(l.get(0).section());
        assertEquals("Process", l.get(1).label());
        assertEquals("3.00 GB", l.get(1).value());
        assertEquals("1.00 GB (peak 1.50 GB, pool 2.00 GB)", l.get(2).value());
        assertEquals("4.00 GB / 95.59 GB", l.get(3).value());

        MStatPanel cpu = new MStatPanel("VRAM");
        VramPanel.addDaedalus(cpu, TGMPipeVram.parse("{\"vram\": {\"device\": \"cpu\"}}", 0));
        assertEquals("CPU (no VRAM)", cpu.lines().get(1).value());
    }
}
