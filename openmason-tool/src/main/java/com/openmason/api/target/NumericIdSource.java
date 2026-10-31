package com.openmason.api.target;

import java.util.List;
import java.util.Optional;

/**
 * The numeric ids a {@link GameTarget} has already handed out (#382): which ids the game's block
 * and item registries hold, so the editor can refuse a colliding SBO {@code numericId} and suggest
 * a free one. Domains are SBO object types ({@code "block"}, {@code "item"}); a domain the game
 * does not number has no ids and is never validated.
 */
public interface NumericIdSource {

    /** One id the game holds. */
    record TakenId(int numericId, String objectId, String displayName) {
    }

    /** True when the game numbers objects of {@code domain}. */
    boolean numbers(String domain);

    /** Every id held in {@code domain}, ascending; empty for a domain the game does not number. */
    List<TakenId> taken(String domain);

    /** The id {@code numericId} in {@code domain} belongs to, if any. */
    default Optional<TakenId> holder(String domain, int numericId) {
        for (TakenId t : taken(domain)) {
            if (t.numericId() == numericId) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** The lowest id a new object of {@code domain} may take. */
    default int firstId(String domain) {
        return 1;
    }
}
