package com.stonebreak.openmason;

import com.openmason.api.target.NumericIdSource;
import com.openmason.api.target.ResourceRoot;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.format.sbo.SBOParser;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.gameplay.SboGameplay;
import com.stonebreak.items.registry.ItemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The numeric ids Stonebreak's blocks and items hold (#382; was the tool's
 * {@code NumericIdValidator}), read from the Stonebreak checkout the target uses
 * ({@link StonebreakResources}) so they match what an export is written next to. Without a
 * checkout they come from the game's {@link BlockRegistry} / {@link ItemRegistry}, i.e. the copy
 * bundled with the plugin. Either way the rules are the registries': every {@code sbo/blocks/*.sbo}
 * with game properties, every texture-only {@code item} in {@code sbo/items/}.
 *
 * <p>Block ids also count the engine sentinel {@link BlockType}s that have no SBO ({@code AIR} 0,
 * {@code WATER} 8): without them the editor would suggest 8 for a new block and the game would
 * collide with water in chunk saves. New items start at 1000, the convention {@link ItemRegistry}
 * documents.
 */
public final class StonebreakNumericIds implements NumericIdSource {

    private static final Logger logger = LoggerFactory.getLogger(StonebreakNumericIds.class);

    public static final String BLOCK = "block";
    public static final String ITEM = "item";

    /** Lowest id a new item takes. */
    public static final int FIRST_ITEM_ID = 1000;

    static final String BLOCKS_FOLDER = "sbo/blocks";
    static final String ITEMS_FOLDER = "sbo/items";

    /** How often a checkout's folders are re-checked for changes (taken ids are read per frame). */
    private static final long PROBE_NANOS = 1_000_000_000L;

    private final Supplier<Path> tree;
    private final long probeNanos;
    private TreeIds cached;
    private long lastProbe;

    /** Ids from the checkout the Stonebreak target resolves to (property, then the working folder). */
    public StonebreakNumericIds() {
        this(() -> new ResourceRoot(List.of(StonebreakGameTarget.SOURCE_TREE), StonebreakResources.PROPERTY, null)
                .locate());
    }

    /** Ids from the resource tree {@code tree} supplies (null = none: the game's registries). */
    StonebreakNumericIds(Supplier<Path> tree) {
        this(tree, PROBE_NANOS);
    }

    /** As above, re-checking the tree for changes at most every {@code probeNanos} (tests: 0). */
    StonebreakNumericIds(Supplier<Path> tree, long probeNanos) {
        this.tree = Objects.requireNonNull(tree, "tree");
        this.probeNanos = probeNanos;
    }

    private static String domain(String objectType) {
        return objectType == null ? "" : objectType.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean numbers(String domain) {
        String d = domain(domain);
        return d.equals(BLOCK) || d.equals(ITEM);
    }

    @Override
    public List<TakenId> taken(String domain) {
        List<TakenId> out = new ArrayList<>();
        switch (domain(domain)) {
            case BLOCK -> {
                out.addAll(sentinelBlockIds());
                TreeIds t = treeIds();
                if (t != null) {
                    out.addAll(t.blocks());
                } else {
                    BlockRegistry r = BlockRegistry.getInstance();
                    r.ensureLoaded();
                    for (BlockRegistry.BlockEntry e : r.all()) {
                        if (e.numericId() >= 0) {
                            out.add(new TakenId(e.numericId(), e.objectId(), e.displayName()));
                        }
                    }
                }
            }
            case ITEM -> {
                TreeIds t = treeIds();
                if (t != null) {
                    out.addAll(t.items());
                } else {
                    for (ItemRegistry.ItemEntry e : ItemRegistry.getInstance().all()) {
                        if (e.numericId() >= 0) {
                            out.add(new TakenId(e.numericId(), e.objectId(), e.displayName()));
                        }
                    }
                }
            }
            default -> { /* not numbered */ }
        }
        out.sort(Comparator.comparingInt(TakenId::numericId));
        return out;
    }

    @Override
    public Optional<TakenId> holder(String domain, int numericId) {
        if (!numbers(domain)) {
            return Optional.empty();
        }
        return taken(domain).stream().filter(t -> t.numericId() == numericId).findFirst();
    }

    @Override
    public int firstId(String domain) {
        return domain(domain).equals(ITEM) ? FIRST_ITEM_ID : 1;
    }

    /**
     * Ids held by sentinel {@link BlockType}s that never appear in the registry. They are defined in
     * code, so the game classes bundled with the plugin are authoritative for them.
     */
    private static List<TakenId> sentinelBlockIds() {
        BlockRegistry registry = BlockRegistry.getInstance();
        registry.ensureLoaded();
        List<TakenId> out = new ArrayList<>();
        for (BlockType bt : BlockType.values()) {
            if (registry.getById(bt.getId()).isEmpty()) {
                out.add(new TakenId(bt.getId(), "builtin:" + bt.name().toLowerCase(Locale.ROOT),
                        bt.getName() + " (engine sentinel)"));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ checkout ids

    /** One read of a checkout's ids, valid while its folders' fingerprint is unchanged. */
    private record TreeIds(Path tree, long fingerprint, List<TakenId> blocks, List<TakenId> items) {
    }

    /** The current checkout's ids, re-read when the checkout or its SBO files change; null without one. */
    private synchronized TreeIds treeIds() {
        Path root = tree.get();
        if (root == null) {
            cached = null;
            return null;
        }
        long now = System.nanoTime();
        if (cached != null && cached.tree().equals(root) && now - lastProbe < probeNanos) {
            return cached;
        }
        lastProbe = now;
        long fp = fingerprint(root);
        if (cached == null || !cached.tree().equals(root) || cached.fingerprint() != fp) {
            cached = read(root, fp);
        }
        return cached;
    }

    private static TreeIds read(Path root, long fingerprint) {
        SBOParser parser = new SBOParser();
        List<TakenId> blocks = new ArrayList<>();
        for (Path sbo : sboFiles(root.resolve(BLOCKS_FOLDER))) {
            SBOParseResult r = parse(parser, sbo);
            TakenId id = r == null ? null : takenId(r);
            if (id != null) {
                blocks.add(id);
            }
        }
        List<TakenId> items = new ArrayList<>();
        for (Path sbo : sboFiles(root.resolve(ITEMS_FOLDER))) {
            SBOParseResult r = parse(parser, sbo);
            if (r != null && "item".equalsIgnoreCase(r.getObjectType()) && r.isTextureOnly()) {
                TakenId id = takenId(r);
                if (id != null) {
                    items.add(id);
                }
            }
        }
        logger.debug("[stonebreak] numeric ids from {}: {} blocks, {} items", root, blocks.size(), items.size());
        return new TreeIds(root, fingerprint, List.copyOf(blocks), List.copyOf(items));
    }

    private static TakenId takenId(SBOParseResult r) {
        SboGameplay.GameProperties gp = SboGameplay.of(r.manifest()).gameProperties();
        if (gp == null || gp.numericId() < 0) {
            return null;
        }
        return new TakenId(gp.numericId(), r.getObjectId(), r.getObjectName());
    }

    private static SBOParseResult parse(SBOParser parser, Path sbo) {
        try {
            return parser.parse(sbo);
        } catch (IOException | RuntimeException e) {
            logger.warn("[stonebreak] Skipping unreadable SBO {}: {}", sbo, e.getMessage());
            return null;
        }
    }

    /** The {@code .sbo} files directly in {@code dir}, as the game's registries list them. */
    private static List<Path> sboFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sbo"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            logger.warn("[stonebreak] Cannot list {}", dir, e);
            return List.of();
        }
    }

    /** Count, newest mtime and total size of both folders' SBOs: changes when a file is added, edited or removed. */
    private static long fingerprint(Path root) {
        long acc = 17;
        for (String folder : List.of(BLOCKS_FOLDER, ITEMS_FOLDER)) {
            long count = 0;
            long newest = 0;
            long size = 0;
            for (Path p : sboFiles(root.resolve(folder))) {
                try {
                    count++;
                    newest = Math.max(newest, Files.getLastModifiedTime(p).toMillis());
                    size += Files.size(p);
                } catch (IOException ignored) {
                    // vanished between list and stat: the next probe sees it
                }
            }
            acc = acc * 31 + count;
            acc = acc * 31 + newest;
            acc = acc * 31 + size;
        }
        return acc;
    }
}
