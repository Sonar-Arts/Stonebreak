package com.stonebreak.blocks.multipart;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.gameplay.SboGameplay;
import com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration.CendaMesher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Every block's compiled {@link MultipartShape}, by block id, read from the {@code multipart}
 * tables in the shipped SBOs. Built on first use and immutable afterwards, so the mesher threads
 * read it without locking.
 *
 * <p>A neighbour is a "full cube" by the chunk mesher's own cube class
 * ({@link CendaMesher#isFullCube}): the same rule that decides which faces it culls.
 */
public final class MultipartRegistry {

    private static final Logger logger = LoggerFactory.getLogger(MultipartRegistry.class);

    private static volatile MultipartShape[] shapes;

    private MultipartRegistry() {
    }

    /** The shape of {@code type}, or null when its look doesn't depend on its neighbours. */
    public static MultipartShape of(BlockType type) {
        if (type == null) return null;
        MultipartShape[] table = table();
        int id = type.getId();
        return id >= 0 && id < table.length ? table[id] : null;
    }

    /** Every compiled shape, in block-id order. */
    public static List<MultipartShape> all() {
        List<MultipartShape> out = new ArrayList<>();
        for (MultipartShape s : table()) {
            if (s != null) out.add(s);
        }
        return Collections.unmodifiableList(out);
    }

    private static MultipartShape[] table() {
        MultipartShape[] table = shapes;
        if (table == null) {
            synchronized (MultipartRegistry.class) {
                table = shapes;
                if (table == null) {
                    table = build(CendaMesher::isFullCube);
                    shapes = table;
                }
            }
        }
        return table;
    }

    private static MultipartShape[] build(Predicate<BlockType> fullCube) {
        int maxId = 0;
        for (BlockType t : BlockType.values()) maxId = Math.max(maxId, t.getId());
        MultipartShape[] table = new MultipartShape[maxId + 1];
        for (BlockType type : BlockType.values()) {
            SboGameplay gameplay = gameplay(type);
            if (!gameplay.hasMultipart() || type.getId() < 0) continue;
            try {
                table[type.getId()] = new MultipartShape(type, gameplay.multipart(), fullCube,
                        t -> gameplay(t).connectTags());
            } catch (IllegalArgumentException ex) {
                logger.error("Ignoring the multipart table of {}: {}", type, ex.getMessage());
            }
        }
        return table;
    }

    private static SboGameplay gameplay(BlockType type) {
        return BlockRegistry.getInstance().getById(type.getId())
                .map(entry -> SboGameplay.of(entry.sboData().manifest()))
                .orElse(SboGameplay.NONE);
    }
}
