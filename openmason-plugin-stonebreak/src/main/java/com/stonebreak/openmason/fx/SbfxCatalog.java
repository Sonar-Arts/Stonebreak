package com.stonebreak.openmason.fx;

import com.openmason.api.fx.FxCatalogSource;
import com.openmason.api.target.ResourceRoot;
import com.stonebreak.fx.sbfx.SbfxCodec;
import com.stonebreak.fx.sbfx.SbfxDocument;
import com.stonebreak.fx.sbfx.ShippedFx;
import com.stonebreak.openmason.StonebreakGameTarget;
import com.stonebreak.openmason.StonebreakResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The FX Stonebreak ships, for the SBO/SBE editors' FX bindings check: every {@code sbfx/*.sbfx} by its object id
 * ({@code stonebreak:torch_fire}) with its parameters. Read from the checkout the Stonebreak target uses
 * (Preferences > Stonebreak, or the launch property), else from the copy bundled with the plugin — the same rule
 * as the numeric ids. Listed only while the Stonebreak target is active.
 */
public final class SbfxCatalog {

    private static final Logger logger = LoggerFactory.getLogger(SbfxCatalog.class);

    /** Catalog source id. */
    public static final String ID = "stonebreak.sbfx";

    private SbfxCatalog() {
    }

    /** The source over the checkout the Stonebreak target resolves to. */
    public static FxCatalogSource source() {
        return source(() -> new ResourceRoot(List.of(StonebreakGameTarget.SOURCE_TREE), StonebreakResources.PROPERTY,
                null).locate());
    }

    /** The source over the resource tree {@code tree} supplies (null = the bundled copy). */
    static FxCatalogSource source(Supplier<Path> tree) {
        return new FxCatalogSource(ID, "Stonebreak", StonebreakGameTarget.ID, () -> list(tree.get()));
    }

    /** The shipped FX under {@code root}'s {@code sbfx/} folder, or the bundled ones when {@code root} is null. */
    static List<FxCatalogSource.Entry> list(Path root) throws IOException {
        return root == null ? bundled() : folder(root.resolve(SbfxExport.FOLDER), "");
    }

    /** Every readable {@code .sbfx} in {@code folder} (any file system), origins {@code <prefix>sbfx/<name>}. */
    private static List<FxCatalogSource.Entry> folder(Path folder, String originPrefix) throws IOException {
        List<FxCatalogSource.Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return entries;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(folder)) {
            for (Path p : dir) {
                if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT)
                        .endsWith(SbfxCodec.FILE_EXTENSION)) {
                    files.add(p);
                }
            }
        }
        files.sort(null);
        for (Path file : files) {
            String origin = originPrefix + SbfxExport.FOLDER + "/" + file.getFileName();
            try {
                entries.add(entry(SbfxCodec.read(file), origin));
            } catch (IOException | RuntimeException e) {
                // One unreadable file must not hide the rest; the game's ShippedFxTest fails on it anyway.
                logger.warn("[stonebreak] skipping unreadable FX {}: {}", origin, e.getMessage());
            }
        }
        return entries;
    }

    /**
     * The copy bundled with the plugin: the {@code sbfx/} folder next to the game classes (a jar or a classes
     * folder). Located through the game classes' code source rather than a class-loader directory lookup, which
     * finds nothing when the game is a named module.
     */
    private static List<FxCatalogSource.Entry> bundled() throws IOException {
        Path location = codeSource();
        if (location != null && Files.isDirectory(location)) {
            return folder(location.resolve(SbfxExport.FOLDER), "bundled ");
        }
        if (location != null && Files.isRegularFile(location)) {
            try (FileSystem jar = FileSystems.newFileSystem(location)) {
                return folder(jar.getPath(SbfxExport.FOLDER), "bundled ");
            }
        }
        return discovered();
    }

    private static Path codeSource() {
        try {
            var source = ShippedFx.class.getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null ? null : Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** Last resort: the game's own discovery (class-path resource lookup). */
    private static List<FxCatalogSource.Entry> discovered() {
        List<FxCatalogSource.Entry> entries = new ArrayList<>();
        for (String path : ShippedFx.discover()) {
            try (InputStream in = ShippedFx.class.getResourceAsStream(path)) {
                if (in == null) {
                    continue;
                }
                entries.add(entry(SbfxCodec.read(in), "bundled " + path.substring(1)));
            } catch (IOException | RuntimeException e) {
                logger.warn("[stonebreak] skipping unreadable bundled FX {}: {}", path, e.getMessage());
            }
        }
        return entries;
    }

    private static FxCatalogSource.Entry entry(SbfxDocument doc, String origin) {
        return new FxCatalogSource.Entry(doc.objectId(), origin, doc.system().parameters());
    }
}
