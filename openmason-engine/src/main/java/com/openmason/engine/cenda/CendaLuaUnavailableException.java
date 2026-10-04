package com.openmason.engine.cenda;

/**
 * The Cenda library that hosts Lua is missing, incomplete, or speaks a different
 * Lua-host ABI. UI scripting has no Java fallback, so this is a hard failure
 * that names what was searched and what was found.
 */
public final class CendaLuaUnavailableException extends RuntimeException {

    public CendaLuaUnavailableException(String message) {
        super(message);
    }

    public CendaLuaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
