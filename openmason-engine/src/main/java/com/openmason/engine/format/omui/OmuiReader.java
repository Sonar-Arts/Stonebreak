package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.io.AnimationCodec;
import com.openmason.engine.format.omui.io.StateMachineCodec;
import com.openmason.engine.format.omui.io.ArchiveIO;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.omui.io.DependencyCodec;
import com.openmason.engine.format.omui.io.DocumentCodec;
import com.openmason.engine.format.omui.io.GraphCodec;
import com.openmason.engine.format.omui.io.ManifestCodec;
import com.openmason.engine.format.omui.io.StyleCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code .omui} archives headlessly (no GL, no tool, no game classes), strictly: any
 * error fails the whole read with every diagnostic attached, so a caller never receives a
 * silently partial document. Unknown fields and entries are preserved, not dropped. Scripts
 * are carried as source text and never executed.
 *
 * <p>Version negotiation: same major and any minor reads (a newer minor adds a warning);
 * an older major fails with {@link Code#NEEDS_UPGRADE} — upgrading is an explicit
 * {@link OmuiUpgrader} step; a newer major fails with {@link Code#UNSUPPORTED_SCHEMA_VERSION}.
 */
public final class OmuiReader {

    private static final byte[] LUA_BINARY_SIGNATURE = {0x1B, 'L', 'u', 'a'};

    /** A successfully read document plus its non-fatal diagnostics (warnings, info). */
    public record Result(OmuiArchive archive, List<UiDiagnostic> diagnostics) {
    }

    private OmuiReader() {
    }

    public static Result read(Path path) throws IOException {
        long size = Files.size(path);
        if (size > ArchiveLimits.DEFAULT.maxTotalBytes()) {
            UiDiagnostics d = new UiDiagnostics();
            d.error(Code.LIMIT_EXCEEDED, "", "", "Archive is " + size + " bytes");
            throw new UiFormatException("Cannot read " + path, d.list());
        }
        return read(Files.readAllBytes(path), ArchiveLimits.DEFAULT);
    }

    public static Result read(byte[] archive) throws UiFormatException {
        return read(archive, ArchiveLimits.DEFAULT);
    }

    public static Result read(byte[] archive, ArchiveLimits limits) throws UiFormatException {
        UiDiagnostics d = new UiDiagnostics();
        Map<String, byte[]> entries = ArchiveIO.read(archive, limits, d);
        d.throwIfErrors("Cannot open OMUI archive");
        return fromEntries(entries, limits, d);
    }

    /** Decode already-extracted entries (pack/unpack, SBUI's embedded source, upgrades). */
    public static Result fromEntries(Map<String, byte[]> entries, ArchiveLimits limits) throws UiFormatException {
        return fromEntries(entries, limits, new UiDiagnostics());
    }

    private static Result fromEntries(Map<String, byte[]> entries, ArchiveLimits limits, UiDiagnostics d)
            throws UiFormatException {
        UiValue manifestJson = json(entries, OmuiFormat.MANIFEST, true, limits, d);
        d.throwIfErrors("Cannot read OMUI manifest");
        SchemaVersion version = ManifestCodec.peekVersion(manifestJson, OmuiFormat.FORMAT_ID, d);
        d.throwIfErrors("Cannot read OMUI manifest");
        negotiate(version, d);
        d.throwIfErrors("Unsupported OMUI schema");

        UiManifest manifest = ManifestCodec.read(manifestJson, d);
        OmuiValidator.requiredFeatures(manifest.requires(), d);
        d.throwIfErrors("Unsupported OMUI document");

        UiValue documentJson = json(entries, OmuiFormat.DOCUMENT, true, limits, d);
        UiDocument document = documentJson == null ? null : DocumentCodec.read(documentJson, limits, d);
        UiValue depsJson = json(entries, OmuiFormat.DEPENDENCIES, false, limits, d);
        OmuiArchive.UiDependencies deps = depsJson == null ? null : DependencyCodec.read(depsJson, d);

        Map<String, UiStyleSheet> styles = new LinkedHashMap<>();
        Map<String, UiGraph> graphs = new LinkedHashMap<>();
        Map<String, UiAnimationClip> clips = new LinkedHashMap<>();
        Map<String, UiStateMachine> machines = new LinkedHashMap<>();
        Map<String, String> scripts = new LinkedHashMap<>();
        Map<String, UiBytes> assets = new LinkedHashMap<>();
        Map<String, UiBytes> editor = new LinkedHashMap<>();
        Map<String, UiBytes> extra = new LinkedHashMap<>();

        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (name.equals(OmuiFormat.MANIFEST) || name.equals(OmuiFormat.DOCUMENT)
                    || name.equals(OmuiFormat.DEPENDENCIES)) {
                continue;
            }
            String id;
            if ((id = part(name, OmuiFormat.STYLES_DIR, OmuiFormat.STYLE_SUFFIX)) != null) {
                UiValue v = json(entries, name, true, limits, d);
                if (v != null) {
                    styles.put(id, StyleCodec.read(id, name, v, d));
                }
            } else if ((id = part(name, OmuiFormat.GRAPHS_DIR, OmuiFormat.GRAPH_SUFFIX)) != null) {
                UiValue v = json(entries, name, true, limits, d);
                if (v != null) {
                    graphs.put(id, GraphCodec.read(id, name, v, limits, d));
                }
            } else if ((id = part(name, OmuiFormat.ANIMATIONS_DIR, OmuiFormat.ANIMATION_SUFFIX)) != null) {
                UiValue v = json(entries, name, true, limits, d);
                if (v != null) {
                    clips.put(id, AnimationCodec.read(id, name, v, d));
                }
            } else if ((id = part(name, OmuiFormat.ANIMATIONS_DIR, OmuiFormat.STATE_MACHINE_SUFFIX)) != null) {
                UiValue v = json(entries, name, true, limits, d);
                if (v != null) {
                    machines.put(id, StateMachineCodec.read(id, name, v, d));
                }
            } else if ((id = part(name, OmuiFormat.SCRIPTS_DIR, OmuiFormat.SCRIPT_SUFFIX)) != null) {
                String source = script(name, e.getValue(), d);
                if (source != null) {
                    scripts.put(id, source);
                }
            } else if (name.startsWith(OmuiFormat.ASSETS_DIR)) {
                assets.put(name, UiBytes.copyOf(e.getValue()));
            } else if (name.startsWith(OmuiFormat.EDITOR_DIR)) {
                editor.put(name, UiBytes.copyOf(e.getValue()));
            } else {
                extra.put(name, UiBytes.copyOf(e.getValue()));
            }
        }
        d.throwIfErrors("Malformed OMUI document");

        OmuiArchive archive = new OmuiArchive(manifest, document, styles, graphs, clips, machines, scripts, deps, assets,
                editor, extra);
        archive = UiFeatures.withImpliedRetroGated(archive, d);
        OmuiValidator.validate(archive, d);
        d.throwIfErrors("Invalid OMUI document");
        return new Result(archive, d.list());
    }

    /** Applies the negotiation rule; records an error or a newer-minor warning. */
    static void negotiate(SchemaVersion version, UiDiagnostics d) {
        SchemaVersion current = OmuiFormat.SCHEMA_VERSION;
        if (version.major() > current.major()) {
            d.error(Code.UNSUPPORTED_SCHEMA_VERSION, OmuiFormat.MANIFEST, "/schemaVersion",
                    "Schema " + version + " is newer than supported " + current + "; update Open Mason/Stonebreak");
        } else if (version.major() < current.major()) {
            d.error(Code.NEEDS_UPGRADE, OmuiFormat.MANIFEST, "/schemaVersion",
                    "Schema " + version + " must be upgraded to " + current + " (OmuiUpgrader) before use");
        } else if (version.minor() > current.minor()) {
            d.warning(Code.NEWER_MINOR_VERSION, OmuiFormat.MANIFEST, "/schemaVersion",
                    "Schema " + version + " is newer than " + current + "; unknown optional data is preserved");
        }
    }

    /** @return the part id for {@code dir<id>suffix}, or {@code null} when the name is not one */
    static String part(String name, String dir, String suffix) {
        if (name.startsWith(dir) && name.endsWith(suffix) && name.length() > dir.length() + suffix.length()) {
            return name.substring(dir.length(), name.length() - suffix.length());
        }
        return null;
    }

    static UiValue json(Map<String, byte[]> entries, String name, boolean required, ArchiveLimits limits,
                        UiDiagnostics d) {
        byte[] bytes = entries.get(name);
        if (bytes == null) {
            if (required) {
                d.error(Code.MISSING_ENTRY, name, "", "Required entry is missing");
            }
            return null;
        }
        if (bytes.length > limits.maxJsonBytes()) {
            d.error(Code.LIMIT_EXCEEDED, name, "", "JSON entry exceeds " + limits.maxJsonBytes() + " bytes");
            return null;
        }
        return CanonicalJson.parse(bytes, name, d);
    }

    private static String script(String name, byte[] bytes, UiDiagnostics d) {
        if (bytes.length >= 4 && Arrays.equals(bytes, 0, 4, LUA_BINARY_SIGNATURE, 0, 4)) {
            d.error(Code.BINARY_SCRIPT, name, "", "Precompiled Lua bytecode is not allowed; scripts are source only");
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            d.error(Code.INVALID_TEXT, name, "", "Lua source is not valid UTF-8");
            return null;
        }
    }
}
