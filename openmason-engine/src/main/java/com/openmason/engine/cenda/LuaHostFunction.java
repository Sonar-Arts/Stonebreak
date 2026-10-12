package com.openmason.engine.cenda;

/**
 * A Java function callable from Lua. Runs synchronously on the thread that
 * called into the state. Read arguments and write results through the reused
 * {@link LuaHostCall}; return the number of results written, or a negative
 * value to raise a Lua error. Exceptions are caught at the upcall boundary and
 * become Lua errors (an exception escaping an FFM upcall would kill the JVM).
 */
@FunctionalInterface
public interface LuaHostFunction {

    int invoke(LuaHostCall call);
}
