package com.openmason.main.systems.menus.dialogs.validation;

import com.openmason.api.target.GameTarget;
import com.openmason.api.target.NumericIdSource;
import com.openmason.main.systems.target.GameTargets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Pure-logic validator for SBO {@code numericId} uniqueness against the ids the active game
 * target's registries hold ({@link GameTarget#numericIds()}, #382: Stonebreak's block and item
 * registries live in its plugin). The rendering layer wraps this with a confirm-and-acknowledge
 * modal — see {@code NumericIdConflictPopup}. Without a target, or with one that numbers nothing,
 * every id is accepted.
 *
 * <p>"Self-match" is the version-over case: the game already holds the requested
 * {@code numericId} but maps it to the same {@code objectId} the caller is exporting. Treated as
 * {@link Result.Ok} — re-saving over your own file is the expected flow, not a collision.
 */
public final class NumericIdValidator {

    private static volatile Supplier<Optional<NumericIdSource>> source =
            () -> GameTargets.shared().active().map(GameTarget::numericIds);

    private NumericIdValidator() {}

    public enum Domain {
        BLOCK("block"), ITEM("item"), NONE(null);

        private final String objectType;

        Domain(String objectType) {
            this.objectType = objectType;
        }

        /** The SBO object type this domain numbers, or null for {@link #NONE}. */
        public String objectType() {
            return objectType;
        }
    }

    public sealed interface Result {
        record Ok() implements Result {}
        record Conflict(int numericId, String existingObjectId, String existingDisplayName) implements Result {}
    }

    public record TakenId(int numericId, String objectId, String displayName) {}

    /** Where ids come from (tests); null restores the active game target. */
    public static void setSource(Supplier<Optional<NumericIdSource>> ids) {
        source = ids != null ? ids : () -> GameTargets.shared().active().map(GameTarget::numericIds);
    }

    /**
     * Map an SBO {@code objectType} string to the domain that governs its numeric IDs.
     * {@code "block"} → {@link Domain#BLOCK}, {@code "item"} → {@link Domain#ITEM}, anything else
     * → {@link Domain#NONE} (no validation — entities/decorations/particles aren't ID-constrained).
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

    private static Optional<NumericIdSource> ids(Domain domain) {
        if (domain == null || domain == Domain.NONE) {
            return Optional.empty();
        }
        return source.get().filter(s -> s.numbers(domain.objectType()));
    }

    public static Result validate(Domain domain, int numericId, String candidateObjectId) {
        if (numericId < 0) return new Result.Ok();
        Optional<NumericIdSource> ids = ids(domain);
        if (ids.isEmpty()) return new Result.Ok();
        return ids.get().holder(domain.objectType(), numericId)
                .<Result>map(t -> sameObjectId(t.objectId(), candidateObjectId)
                        ? new Result.Ok()
                        : new Result.Conflict(numericId, t.objectId(), t.displayName()))
                .orElseGet(Result.Ok::new);
    }

    private static boolean sameObjectId(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /** All currently-taken numeric IDs in the given domain, sorted ascending by numericId. */
    public static List<TakenId> listTakenIds(Domain domain) {
        List<TakenId> out = new ArrayList<>();
        ids(domain).ifPresent(s -> {
            for (NumericIdSource.TakenId t : s.taken(domain.objectType())) {
                out.add(new TakenId(t.numericId(), t.objectId(), t.displayName()));
            }
        });
        out.sort(Comparator.comparingInt(TakenId::numericId));
        return out;
    }

    /**
     * First free numeric ID in the domain, from the game's floor for it (Stonebreak: 1 for
     * blocks, 1000 for items); 1 when nothing numbers the domain, -1 for {@link Domain#NONE}.
     */
    public static int suggestNextFreeId(Domain domain) {
        if (domain == null || domain == Domain.NONE) return -1;
        Optional<NumericIdSource> ids = ids(domain);
        Set<Integer> taken = new HashSet<>();
        for (TakenId t : listTakenIds(domain)) taken.add(t.numericId());
        int candidate = ids.map(s -> s.firstId(domain.objectType())).orElse(1);
        while (taken.contains(candidate)) candidate++;
        return candidate;
    }
}
