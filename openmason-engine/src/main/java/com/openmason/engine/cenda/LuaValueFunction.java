package com.openmason.engine.cenda;

/**
 * A value-typed Java function callable from Lua (ABI 2). Runs synchronously on the thread that
 * called into the state. Read every argument from {@code args} first, then do the work (which
 * may call back into the same state), then write the results to {@code results} and return how
 * many were written. A thrown exception becomes a Lua error carrying its message; it never
 * crosses the FFM upcall.
 */
@FunctionalInterface
public interface LuaValueFunction {

    int invoke(LuaValueReader args, LuaValueWriter results);
}
