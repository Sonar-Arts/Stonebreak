package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.CanonicalJson;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.openmason.engine.format.sbui.SbuiExportTest.entries;
import static com.openmason.engine.format.sbui.SbuiExportTest.write;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hostile SBUI input: only {@link UiFormatException}s with structured codes may come out. */
@Tag("regression")
class SbuiHardeningTest {

    static byte[] sample() throws Exception {
        var options = new SbuiExporter.Options(null, SbuiExportTest.collectedShared(), true, Map.of(),
                List.of(SbuiExportTest.graphLua("1")));
        return SbuiWriter.write(SbuiExporter.export(UiSamples.pauseMenu(), options).archive());
    }

    @Test
    void truncationAndCorruptionFailCleanly() throws Exception {
        byte[] good = sample();
        for (int len = 0; len < good.length; len += Math.max(1, good.length / 97)) {
            byte[] cut = Arrays.copyOf(good, len);
            assertThrows(UiFormatException.class, () -> SbuiReader.read(cut, SbuiReader.Options.RUNTIME), "len " + len);
        }
        Random random = new Random(2840);
        for (int i = 0; i < 300; i++) {
            byte[] bad = good.clone();
            for (int k = 0; k < 4; k++) {
                bad[random.nextInt(bad.length)] ^= (byte) (1 + random.nextInt(255));
            }
            try {
                SbuiReader.read(bad, SbuiReader.Options.EDITOR);
            } catch (UiFormatException expected) {
                assertTrue(expected.diagnostics().stream().anyMatch(UiDiagnostic::isError));
            }
        }
    }

    @Test
    void componentRowWithoutEntryIsADiagnosticNotACrash() throws Exception {
        Map<String, byte[]> e = entries(sample());
        e.put("manifest.json", editRow(e.get("manifest.json"), UiSamples.BUTTON_ID, row -> row.remove("entry")));
        UiFormatException ex = assertThrows(UiFormatException.class,
                () -> SbuiReader.read(write(e), SbuiReader.Options.RUNTIME));
        assertTrue(ex.has(Code.INCONSISTENT_MANIFEST), ex.getMessage());
    }

    @Test
    void duplicateAndDisagreeingRowsAreRejected() throws Exception {
        Map<String, byte[]> dup = entries(sample());
        UiValue.Obj m = obj(dup.get("manifest.json"));
        List<UiValue> rows = new ArrayList<>(((UiValue.Arr) m.get("dependencies")).items());
        rows.add(rows.getFirst());
        dup.put("manifest.json", put(m, "dependencies", new UiValue.Arr(rows)));
        assertTrue(assertThrows(UiFormatException.class, () -> SbuiReader.read(write(dup), SbuiReader.Options.RUNTIME))
                .has(Code.DUPLICATE_ID));

        Map<String, byte[]> optional = entries(sample());
        optional.put("manifest.json", editRow(optional.get("manifest.json"), UiSamples.THEME_ID,
                row -> row.put("optional", UiValue.TRUE)));
        assertTrue(assertThrows(UiFormatException.class,
                () -> SbuiReader.read(write(optional), SbuiReader.Options.RUNTIME)).has(Code.INCONSISTENT_MANIFEST));
    }

    // ── helpers ──

    static UiValue.Obj obj(byte[] json) {
        return (UiValue.Obj) CanonicalJson.parse(json, "m", new UiDiagnostics());
    }

    static byte[] put(UiValue.Obj o, String key, UiValue value) {
        Map<String, UiValue> f = new LinkedHashMap<>(o.fields());
        f.put(key, value);
        return CanonicalJson.write(new UiValue.Obj(f));
    }

    static byte[] editRow(byte[] manifest, String id, java.util.function.Consumer<Map<String, UiValue>> edit) {
        UiValue.Obj m = obj(manifest);
        List<UiValue> rows = new ArrayList<>();
        for (UiValue row : ((UiValue.Arr) m.get("dependencies")).items()) {
            Map<String, UiValue> f = new LinkedHashMap<>(((UiValue.Obj) row).fields());
            if (f.get("id").equals(UiValue.of(id))) {
                edit.accept(f);
            }
            rows.add(new UiValue.Obj(f));
        }
        return put(m, "dependencies", new UiValue.Arr(rows));
    }
}
