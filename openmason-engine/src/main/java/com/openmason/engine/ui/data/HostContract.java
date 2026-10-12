package com.openmason.engine.ui.data;

import java.util.Objects;

/**
 * A versioned host contract (#289): the unit a document's manifest {@code hostApis} names and
 * a host implements. Data roots and actions each belong to one; integer versions are
 * cumulative, so a host at version N serves documents that need 1..N.
 *
 * @param id      logical id ({@code stonebreak:session})
 * @param version newest version implemented, at least 1
 */
public record HostContract(String id, int version) {

    public HostContract {
        Objects.requireNonNull(id, "id");
        if (id.indexOf(':') <= 0) {
            throw new IllegalArgumentException("contract id must be namespaced (ns:name): " + id);
        }
        if (version < 1) {
            throw new IllegalArgumentException("contract version must be >= 1");
        }
    }

    public static HostContract of(String id, int version) {
        return new HostContract(id, version);
    }
}
