package com.openmason.main.systems.menus.dialogs.validation;

import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.items.registry.ItemRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-logic validator for SBO {@code numericId} uniqueness against the live
 * {@link BlockRegistry} / {@link ItemRegistry}. Rendering layer wraps this with
 * a confirm-and-acknowledge modal — see {@code NumericIdConflictPopup}.
 *
 * <p>"Self-match" is the version-over case: the registry already holds the
 * requested {@code numericId} but maps it to the same {@code objectId} the
 * caller is exporting. Treated as {@link Result.Ok} — re-saving over your own
 * file is the expected flow, not a collision.
 */
public final class NumericIdValidator {

    private NumericIdValidator() {}

    public enum Domain { BLOCK, ITEM, NONE }

    public sealed interface Result {
        record Ok() implements Result {}
        record Conflict(int numericId, String existingObjectId, String existingDisplayName) implements Result {}
    }

    public record TakenId(int numericId, String objectId, String displayName) {}

    /**
     * Map an SBO {@code objectType} string to the registry that governs its
     * numeric IDs. {@code "block"} → {@link Domain#BLOCK}, {@code "item"} →
     * {@link Domain#ITEM}, anything else → {@link Domain#NONE} (no validation
     * — entities/decorations/particles aren't ID-constrained today).
     */
    public static Domain domainFor(String objectType) {
        if (objectType == null) return Domain.NONE;
        String t = objectType.trim().toLowerCase();
        return switch (t) {
            case "block" -> Domain.BLOCK;
            case "item" -> Domain.ITEM;
            default -> Domain.NONE;
        };
    }

    public static Result validate(Domain domain, int numericId, String candidateObjectId) {
        if (domain == Domain.NONE) return new Result.Ok();
        if (numericId < 0) return new Result.Ok();

        return switch (domain) {
            case BLOCK -> validateBlock(numericId, candidateObjectId);
            case ITEM -> validateItem(numericId, candidateObjectId);
            case NONE -> new Result.Ok();
        };
    }

    private static Result validateBlock(int numericId, String candidateObjectId) {
        BlockRegistry registry = BlockRegistry.getInstance();
        registry.ensureLoaded();
        return registry.getById(numericId)
                .<Result>map(entry -> sameObjectId(entry.objectId(), candidateObjectId)
                        ? new Result.Ok()
                        : new Result.Conflict(numericId, entry.objectId(), entry.displayName()))
                .orElseGet(Result.Ok::new);
    }

    private static Result validateItem(int numericId, String candidateObjectId) {
        ItemRegistry registry = ItemRegistry.getInstance();
        return registry.getById(numericId)
                .<Result>map(entry -> sameObjectId(entry.objectId(), candidateObjectId)
                        ? new Result.Ok()
                        : new Result.Conflict(numericId, entry.objectId(), entry.displayName()))
                .orElseGet(Result.Ok::new);
    }

    private static boolean sameObjectId(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /**
     * All currently-taken numeric IDs in the given domain, sorted ascending
     * by numericId.
     */
    public static List<TakenId> listTakenIds(Domain domain) {
        List<TakenId> out = new ArrayList<>();
        switch (domain) {
            case BLOCK -> {
                BlockRegistry r = BlockRegistry.getInstance();
                r.ensureLoaded();
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
            case NONE -> { /* empty */ }
        }
        out.sort(Comparator.comparingInt(TakenId::numericId));
        return out;
    }

    /**
     * First free numeric ID in the domain, starting from a domain-appropriate
     * floor (1 for blocks, 1000 for items — matching the existing convention
     * documented on {@link ItemRegistry}).
     */
    public static int suggestNextFreeId(Domain domain) {
        if (domain == Domain.NONE) return -1;
        Set<Integer> taken = new HashSet<>();
        for (TakenId t : listTakenIds(domain)) taken.add(t.numericId());
        int candidate = (domain == Domain.BLOCK) ? 1 : 1000;
        while (taken.contains(candidate)) candidate++;
        return candidate;
    }
}
