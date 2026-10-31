package com.stonebreak.openmason;

import com.openmason.api.target.NumericIdSource;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.items.registry.ItemRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The numeric ids Stonebreak's live {@link BlockRegistry} / {@link ItemRegistry} hold (#382; was
 * the tool's {@code NumericIdValidator}). Block ids also count the engine sentinel
 * {@link BlockType}s that have no SBO ({@code AIR} 0, {@code WATER} 8): without them the editor
 * would suggest 8 for a new block and the game would collide with water in chunk saves. New items
 * start at 1000, the convention {@link ItemRegistry} documents.
 */
public final class StonebreakNumericIds implements NumericIdSource {

    public static final String BLOCK = "block";
    public static final String ITEM = "item";

    /** Lowest id a new item takes. */
    public static final int FIRST_ITEM_ID = 1000;

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
                BlockRegistry r = BlockRegistry.getInstance();
                r.ensureLoaded();
                out.addAll(sentinelBlockIds(r));
                for (BlockRegistry.BlockEntry e : r.all()) {
                    if (e.numericId() >= 0) {
                        out.add(new TakenId(e.numericId(), e.objectId(), e.displayName()));
                    }
                }
            }
            case ITEM -> {
                for (ItemRegistry.ItemEntry e : ItemRegistry.getInstance().all()) {
                    if (e.numericId() >= 0) {
                        out.add(new TakenId(e.numericId(), e.objectId(), e.displayName()));
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
        switch (domain(domain)) {
            case BLOCK -> {
                BlockRegistry registry = BlockRegistry.getInstance();
                registry.ensureLoaded();
                for (TakenId sentinel : sentinelBlockIds(registry)) {
                    if (sentinel.numericId() == numericId) {
                        return Optional.of(sentinel);
                    }
                }
                return registry.getById(numericId)
                        .map(e -> new TakenId(numericId, e.objectId(), e.displayName()));
            }
            case ITEM -> {
                return ItemRegistry.getInstance().getById(numericId)
                        .map(e -> new TakenId(numericId, e.objectId(), e.displayName()));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    @Override
    public int firstId(String domain) {
        return domain(domain).equals(ITEM) ? FIRST_ITEM_ID : 1;
    }

    /** Ids held by sentinel {@link BlockType}s that never appear in the registry. */
    private static List<TakenId> sentinelBlockIds(BlockRegistry registry) {
        List<TakenId> out = new ArrayList<>();
        for (BlockType bt : BlockType.values()) {
            if (registry.getById(bt.getId()).isEmpty()) {
                out.add(new TakenId(bt.getId(), "builtin:" + bt.name().toLowerCase(Locale.ROOT),
                        bt.getName() + " (engine sentinel)"));
            }
        }
        return out;
    }
}
