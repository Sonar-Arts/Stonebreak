package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.ArchiveLimits;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.WriteLimits;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.io.ArchiveIO;
import com.openmason.engine.format.omui.io.AtomicFiles;
import com.openmason.engine.format.omui.io.CanonicalJson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic, validated, atomic {@code .sbui} writing. An export that writes always reads:
 * the finished entries go through {@link SbuiReader}'s own decoding under the default limits
 * (including the shared budget for the embedded OMUI and component documents) before they
 * are returned.
 */
public final class SbuiWriter {

    private SbuiWriter() {
    }

    public static Map<String, UiBytes> entries(SbuiArchive a) throws UiFormatException {
        UiDiagnostics d = new UiDiagnostics();
        SbuiValidator.validate(a, SbuiValidator.entries(a.sourceBytes(), d), SbuiReader.Options.RUNTIME, d);
        d.throwIfErrors("Refusing to write an invalid SBUI export");
        Map<String, UiBytes> out = new LinkedHashMap<>();
        out.put(SbuiFormat.MANIFEST, UiBytes.copyOf(CanonicalJson.write(SbuiManifestCodec.write(a.manifest()))));
        out.put(a.manifest().source().entry(), a.sourceBytes());
        out.putAll(a.assets());
        out.putAll(a.derived());
        a.extraEntries().forEach(out::putIfAbsent);
        Map<String, byte[]> raw = new LinkedHashMap<>();
        out.forEach((k, v) -> raw.put(k, v.toArray()));
        try {
            SbuiReader.fromEntries(raw, SbuiReader.Options.RUNTIME);
        } catch (UiFormatException e) {
            throw new UiFormatException("Refusing to write an SBUI export the reader could not reopen", e.diagnostics());
        }
        return out;
    }

    public static byte[] write(SbuiArchive a) throws UiFormatException {
        byte[] bytes = ArchiveIO.write(entries(a));
        WriteLimits.checkArchiveSize(bytes, ArchiveLimits.DEFAULT, "SBUI");
        return bytes;
    }

    public static void save(SbuiArchive a, Path target) throws IOException {
        AtomicFiles.write(target, write(a));
    }
}
