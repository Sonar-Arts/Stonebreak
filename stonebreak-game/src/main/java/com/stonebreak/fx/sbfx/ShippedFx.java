package com.stonebreak.fx.sbfx;

import com.stonebreak.fx.FxLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Loads every shipped {@code sbfx/*.sbfx} (Stonebreak#435) into the game's {@link FxLibrary} at start-up, like the
 * SBO and SBE registries do for their folders. A file that fails to load is logged and skipped (with its reason);
 * the rest still load. {@link #library()} is what the FX runtime and the asset bindings resolve against.
 */
public final class ShippedFx {

    private static final Logger logger = LoggerFactory.getLogger(ShippedFx.class);

    /** Resource folder the Open Mason plugin deploys SBFX files into. */
    public static final String RESOURCE_FOLDER = "sbfx";

    private static volatile FxLibrary library = FxLibrary.EMPTY;

    private ShippedFx() {
    }

    /** The loaded library ({@link FxLibrary#EMPTY} until {@link #scanAndLoad()} ran). */
    public static FxLibrary library() {
        return library;
    }

    /** Replaces the library (tests, or a reload). */
    public static void install(FxLibrary next) {
        library = next == null ? FxLibrary.EMPTY : next;
    }

    /** Discovers and loads every shipped SBFX; returns the new library (also installed). */
    public static synchronized FxLibrary scanAndLoad() {
        LoadResult result = load(discover());
        for (String failure : result.failures()) {
            logger.error("SBFX not loaded: {}", failure);
        }
        install(new FxLibrary(result.documents()));
        logger.info("Shipped FX: {} system(s) loaded from {}/", library.size(), RESOURCE_FOLDER);
        return library;
    }

    /** Documents loaded and per-file failures ("name: reason"). */
    public record LoadResult(List<SbfxDocument> documents, List<String> failures) {
    }

    /** Loads the given resource paths ({@code /sbfx/SB_Name.sbfx}); never throws for one bad file. */
    public static LoadResult load(List<String> resourcePaths) {
        List<SbfxDocument> docs = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String path : resourcePaths) {
            try (InputStream in = ShippedFx.class.getResourceAsStream(path)) {
                if (in == null) {
                    failures.add(path + ": resource not found");
                    continue;
                }
                docs.add(SbfxCodec.read(in));
            } catch (IOException | RuntimeException e) {
                failures.add(path + ": " + e.getMessage());
            }
        }
        return new LoadResult(Collections.unmodifiableList(docs), Collections.unmodifiableList(failures));
    }

    /** Resource paths of every shipped {@code .sbfx}, sorted by file name. */
    public static List<String> discover() {
        TreeSet<String> names = new TreeSet<>();
        try {
            Path fsPath = Path.of(RESOURCE_FOLDER);
            if (Files.isDirectory(fsPath)) {
                collect(fsPath, names);
            }
            if (names.isEmpty()) {
                var url = ShippedFx.class.getClassLoader().getResource(RESOURCE_FOLDER);
                if (url != null) {
                    URI uri = url.toURI();
                    if ("jar".equals(uri.getScheme())) {
                        try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
                            collect(fs.getPath(RESOURCE_FOLDER), names);
                        }
                    } else {
                        collect(Path.of(uri), names);
                    }
                }
            }
        } catch (IOException | URISyntaxException | RuntimeException e) {
            logger.error("Error discovering SBFX files", e);
        }
        List<String> paths = new ArrayList<>(names.size());
        for (String name : names) {
            paths.add("/" + RESOURCE_FOLDER + "/" + name);
        }
        return paths;
    }

    private static void collect(Path folder, TreeSet<String> into) throws IOException {
        try (Stream<Path> stream = Files.list(folder)) {
            stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(SbfxCodec.FILE_EXTENSION))
                    .forEach(into::add);
        }
    }
}
