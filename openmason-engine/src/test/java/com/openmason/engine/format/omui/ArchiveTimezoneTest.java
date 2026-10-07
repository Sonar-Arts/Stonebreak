package com.openmason.engine.format.omui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Archive bytes must not depend on the machine's timezone: a UTC CI runner and a workstation must
 * write identical OMUI files, or every pinned golden drifts (cenda-native CI, 2026-10-06).
 */
class ArchiveTimezoneTest {

    @Test
    void bytesAreIdenticalInEveryTimezone() throws Exception {
        OmuiArchive doc = OmuiArchive.of(UiManifest.create("test:ui/tz", UiManifest.DocumentKind.SCREEN, "TZ"),
            new UiDocument(UiNode.of("root", "Box", List.of()), List.of(), null, null, Map.of()));
        TimeZone original = TimeZone.getDefault();
        try {
            byte[] reference = null;
            for (String zone : List.of("UTC", "America/Chicago", "Asia/Kolkata", "Pacific/Kiritimati")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                byte[] bytes = OmuiWriter.write(doc);
                if (reference == null) {
                    reference = bytes;
                }
                assertArrayEquals(reference, bytes, "archive written in " + zone + " differs");
            }
            // no extended-timestamp ("UT", 0x5455) extra field: it carries zone-dependent epoch seconds
            String raw = new String(reference, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(raw.contains("UT\u0005\u0000"), "a UT extended timestamp was written");
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
