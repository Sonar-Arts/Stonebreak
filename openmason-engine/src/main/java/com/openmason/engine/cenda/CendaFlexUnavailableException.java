package com.openmason.engine.cenda;

/**
 * The Cenda library that hosts Yoga is missing, incomplete, or speaks a different flex ABI.
 * Migrated UI has no Java layout fallback (#283), so this is a hard failure that names what
 * was searched and what was found.
 */
public final class CendaFlexUnavailableException extends RuntimeException {

    public CendaFlexUnavailableException(String message) {
        super(message);
    }

    public CendaFlexUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
