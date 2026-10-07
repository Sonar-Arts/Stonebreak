package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.format.sbui.SbuiWriter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.openmason.engine.format.omui.OmuiRoundTripTest.rawEntries;
import static com.openmason.engine.format.omui.OmuiRoundTripTest.write;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Structured fuzzing (#284 review): well-formed JSON with type-confused, out-of-range and
 * missing fields fed through every OMUI/SBUI decoder. Byte-level fuzzing ({@code
 * OmuiMalformedTest}) mostly dies in the ZIP or JSON layer; this reaches the record codecs and
 * validators. The only acceptable outcomes are a successful read or a {@link UiFormatException}.
 */
@Tag("regression")
class OmuiStructuredFuzzTest {

    private static final int ROUNDS = 1500;

    @Test
    void typeConfusedOmuiDocumentsFailCleanly() throws Exception {
        Map<String, byte[]> base = rawEntries(OmuiWriter.write(UiSamples.pauseMenu()));
        List<String> jsonEntries = base.keySet().stream().filter(n -> n.endsWith(".json")
                && !n.startsWith(OmuiFormat.EDITOR_DIR)).toList();
        Random random = new Random(0x282_284L);
        for (int round = 0; round < ROUNDS; round++) {
            Map<String, byte[]> entries = new LinkedHashMap<>(base);
            String entry = jsonEntries.get(random.nextInt(jsonEntries.size()));
            UiValue root = CanonicalJson.parse(entries.get(entry), entry, new UiDiagnostics());
            int mutations = 1 + random.nextInt(3);
            for (int m = 0; m < mutations; m++) {
                root = mutate(root, random);
            }
            entries.put(entry, CanonicalJson.write(root));
            byte[] archive = write(entries);
            try {
                OmuiReader.read(archive);
            } catch (UiFormatException expected) {
                // clean refusal
            } catch (RuntimeException | StackOverflowError e) {
                fail("round " + round + " (" + entry + "): " + e + "\n" + new String(CanonicalJson.write(root),
                        java.nio.charset.StandardCharsets.UTF_8), e);
            }
        }
    }

    @Test
    void typeConfusedSbuiManifestsFailCleanly() throws Exception {
        byte[] sbui = SbuiWriter.write(SbuiExporter.export(UiSamples.pauseMenu(), SbuiExporter.Options.shared())
                .archive());
        Map<String, byte[]> base = rawEntries(sbui);
        Random random = new Random(0x285L);
        for (int round = 0; round < ROUNDS / 3; round++) {
            Map<String, byte[]> entries = new LinkedHashMap<>(base);
            UiValue root = CanonicalJson.parse(entries.get("manifest.json"), "manifest.json", new UiDiagnostics());
            root = mutate(root, random);
            entries.put("manifest.json", CanonicalJson.write(root));
            try {
                SbuiReader.read(write(entries), SbuiReader.Options.EDITOR);
            } catch (UiFormatException expected) {
                // clean refusal
            } catch (RuntimeException | StackOverflowError e) {
                fail("round " + round + ": " + e, e);
            }
        }
    }

    /** Replaces, removes or retypes one randomly chosen value somewhere in {@code v}. */
    private static UiValue mutate(UiValue v, Random r) {
        List<List<Object>> paths = new ArrayList<>();
        collect(v, new ArrayList<>(), paths);
        List<Object> path = paths.get(r.nextInt(paths.size()));
        return replace(v, path, 0, r);
    }

    private static void collect(UiValue v, List<Object> path, List<List<Object>> out) {
        out.add(List.copyOf(path));
        if (v instanceof UiValue.Obj o) {
            for (String k : o.fields().keySet()) {
                path.add(k);
                collect(o.fields().get(k), path, out);
                path.removeLast();
            }
        } else if (v instanceof UiValue.Arr a) {
            for (int i = 0; i < a.items().size(); i++) {
                path.add(i);
                collect(a.items().get(i), path, out);
                path.removeLast();
            }
        }
    }

    private static UiValue replace(UiValue v, List<Object> path, int at, Random r) {
        if (at == path.size()) {
            return junk(v, r);
        }
        Object step = path.get(at);
        if (v instanceof UiValue.Obj o && step instanceof String key) {
            Map<String, UiValue> f = new LinkedHashMap<>(o.fields());
            if (at == path.size() - 1 && r.nextInt(5) == 0) {
                f.remove(key);
            } else {
                f.put(key, replace(f.get(key), path, at + 1, r));
            }
            return new UiValue.Obj(f);
        }
        if (v instanceof UiValue.Arr a && step instanceof Integer i) {
            List<UiValue> items = new ArrayList<>(a.items());
            if (at == path.size() - 1 && r.nextInt(5) == 0) {
                items.add(i, items.get(i)); // duplicate: ids, keys and rows collide
            } else {
                items.set(i, replace(items.get(i), path, at + 1, r));
            }
            return new UiValue.Arr(items);
        }
        return v;
    }

    private static UiValue junk(UiValue old, Random r) {
        return switch (r.nextInt(12)) {
            case 0 -> new UiValue.Null();
            case 1 -> UiValue.of(r.nextBoolean());
            case 2 -> UiValue.of(-1);
            case 3 -> UiValue.of(1e300);
            case 4 -> UiValue.of(0.5);
            case 5 -> UiValue.of(9007199254740991.0);
            case 6 -> UiValue.of("");
            case 7 -> UiValue.of("../../etc/passwd");
            case 8 -> UiValue.of("x".repeat(300));
            case 9 -> new UiValue.Arr(List.of(old, old));
            case 10 -> new UiValue.Obj(Map.of("unexpected", old));
            default -> UiValue.of("#zz:ui/\u0000");
        };
    }
}
