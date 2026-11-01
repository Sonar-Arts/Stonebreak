package com.stonebreak.openmason;

import com.openmason.api.target.NumericIdSource;
import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Stonebreak's numeric ids (#382; was the tool's {@code NumericIdValidatorSentinelTest}). WATER (8)
 * and AIR (0) are engine sentinels with no SBO, so they are absent from the BlockRegistry — the
 * exporter used to suggest 8 for every new block. They must read as taken.
 */
class StonebreakNumericIdsTest {

    private final NumericIdSource ids = new StonebreakNumericIds();

    @Test
    void waterIdIsHeld() {
        Optional<NumericIdSource.TakenId> water = ids.holder("block", BlockType.WATER.getId());
        assertTrue(water.isPresent());
        assertNotEquals("stonebreak:new_block", water.get().objectId());
    }

    @Test
    void takenIdsIncludeTheSentinels() {
        assertTrue(ids.taken("block").stream().anyMatch(t -> t.numericId() == BlockType.WATER.getId()));
        assertTrue(ids.taken("block").stream().anyMatch(t -> t.numericId() == BlockType.AIR.getId()));
    }

    @Test
    void registryBlocksAreHeldByTheirObjectId() {
        NumericIdSource.TakenId some = ids.taken("block").stream()
                .filter(t -> !t.objectId().startsWith("builtin:")).findFirst().orElseThrow();
        assertEquals(some.objectId(), ids.holder("block", some.numericId()).orElseThrow().objectId());
    }

    @Test
    void domains() {
        assertTrue(ids.numbers("block"));
        assertTrue(ids.numbers(" Item "));
        assertFalse(ids.numbers("entity"));
        assertTrue(ids.taken("entity").isEmpty());
        assertEquals(1, ids.firstId("block"));
        assertEquals(StonebreakNumericIds.FIRST_ITEM_ID, ids.firstId("item"));
    }

    /** The checkout's own resources (tests run inside it). */
    private static Path checkoutResources() {
        Path tree = StonebreakGameTarget.create().resources().locate();
        assumeTrue(tree != null, "not run inside a Stonebreak checkout");
        return tree;
    }

    private static Map<String, Integer> byObjectId(List<NumericIdSource.TakenId> ids) {
        return ids.stream().filter(t -> !t.objectId().startsWith("builtin:"))
                .collect(Collectors.toMap(NumericIdSource.TakenId::objectId, NumericIdSource.TakenId::numericId,
                        (a, b) -> a));
    }

    @Test
    void idsComeFromTheSelectedCheckoutAndFollowItsFiles(@TempDir Path tmp) throws Exception {
        Path source = checkoutResources();
        Path tree = tmp.resolve("resources");
        Files.createDirectories(tree.resolve(StonebreakNumericIds.BLOCKS_FOLDER));
        Files.createDirectories(tree.resolve(StonebreakNumericIds.ITEMS_FOLDER));
        Files.copy(source.resolve("sbo/blocks/SB_Bedrock.sbo"), tree.resolve("sbo/blocks/SB_Bedrock.sbo"));
        Files.copy(source.resolve("sbo/items/SB_Arrow.sbo"), tree.resolve("sbo/items/SB_Arrow.sbo"));
        NumericIdSource fromTree = new StonebreakNumericIds(() -> tree, 0);
        Map<String, Integer> bundledBlocks = byObjectId(new StonebreakNumericIds(() -> null).taken("block"));
        Map<String, Integer> bundledItems = byObjectId(new StonebreakNumericIds(() -> null).taken("item"));

        Map<String, Integer> blocks = byObjectId(fromTree.taken("block"));
        Map<String, Integer> items = byObjectId(fromTree.taken("item"));
        assertEquals(1, blocks.size(), () -> "only the checkout's blocks: " + blocks);
        assertEquals(1, items.size(), () -> "only the checkout's items: " + items);
        blocks.forEach((id, n) -> assertEquals(bundledBlocks.get(id), n, "same id the game reads for " + id));
        items.forEach((id, n) -> assertEquals(bundledItems.get(id), n, "same id the game reads for " + id));
        assertTrue(fromTree.taken("block").stream().anyMatch(t -> t.numericId() == BlockType.WATER.getId()),
                "sentinels still count");

        // A block added to the checkout is taken at once; removing it frees the id again.
        Files.copy(source.resolve("sbo/blocks/SB_Brick.sbo"), tree.resolve("sbo/blocks/SB_Brick.sbo"));
        Map<String, Integer> after = byObjectId(fromTree.taken("block"));
        assertEquals(2, after.size(), () -> "new block seen: " + after);
        int brick = after.entrySet().stream().filter(e -> !blocks.containsKey(e.getKey()))
                .findFirst().orElseThrow().getValue();
        assertTrue(fromTree.holder("block", brick).isPresent());

        Files.delete(tree.resolve("sbo/blocks/SB_Brick.sbo"));
        assertTrue(fromTree.holder("block", brick).isEmpty(), "freed after removal");
    }

    @Test
    void withoutACheckoutTheBundledRegistriesAreUsed() {
        NumericIdSource bundled = new StonebreakNumericIds(() -> null);
        assertTrue(byObjectId(bundled.taken("block")).size() > 10);
        assertTrue(byObjectId(bundled.taken("item")).size() > 10);
        assertTrue(bundled.holder("block", BlockType.AIR.getId()).isPresent());
    }

    @Test
    void theRealCheckoutMatchesTheGamesRegistries() {
        Path tree = checkoutResources();
        Map<String, Integer> fromTree = byObjectId(new StonebreakNumericIds(() -> tree, 0).taken("item"));
        Map<String, Integer> bundled = byObjectId(new StonebreakNumericIds(() -> null).taken("item"));
        fromTree.forEach((id, n) -> {
            if (bundled.containsKey(id)) {
                assertEquals(bundled.get(id), n, "item " + id);
            }
        });
        assertFalse(fromTree.isEmpty());
    }
}
