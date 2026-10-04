package com.openmason.engine.format.omui;

import java.util.ArrayList;
import java.util.List;

/**
 * An enum with a stable lowercase wire name. Wire names are part of the format contract;
 * Java constant names are not and may be renamed freely.
 */
public interface WireEnum {

    String wire();

    static <E extends Enum<E> & WireEnum> List<String> names(Class<E> type) {
        List<String> out = new ArrayList<>();
        for (E e : type.getEnumConstants()) {
            out.add(e.wire());
        }
        return out;
    }
}
