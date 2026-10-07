# Patches the vendored Lua for the deadline watchdog (see cenda_lua_interrupt.h). Runs as the
# FetchContent PATCH_COMMAND and again at every configure (native/kernels/CMakeLists.txt), so a
# shared, already-downloaded lua-src picks up new patches without a fresh download. Each file is
# patched once (its include of cenda_lua_interrupt.h is the marker). Pure CMake so it runs anywhere
# CMake does; fails the configure loudly if Lua's source drifts so a silent no-op patch is impossible.
#
#   lvm.c     cenda_poll at loop back-jumps and calls: arms the interrupt hook on the VM thread.
#   lstrlib.c cenda_check in the pattern matcher and the plain-find loop: a pathological pattern
#             or search runs entirely in C, where no hook can fire, so it raises the deadline itself.
#   ltablib.c cenda_check in table.move's loops (table.move({}, 1, math.maxinteger - 1, 2) loops in C).

function(_cenda_replace from to)
    string(FIND "${_src}" "${from}" _at)
    if(_at EQUAL -1)
        message(FATAL_ERROR "Lua interrupt patch: anchor not found in ${_file}:\n${from}")
    endif()
    string(REPLACE "${from}" "${to}" _out "${_src}")
    set(_src "${_out}" PARENT_SCOPE)
endfunction()

# ── lvm.c ──
set(_file "${LUA_SRC_DIR}/src/lvm.c")
file(READ "${_file}" _src)
if(NOT _src MATCHES "cenda_lua_interrupt.h")
    _cenda_replace("#include \"lvm.h\"\n"
        "#include \"lvm.h\"\n#include \"cenda_lua_interrupt.h\"\n")
    # while/repeat/goto loops and conditional jumps
    _cenda_replace("#define dojump(ci,i,e)\t{ pc += GETARG_sJ(i) + e; updatetrap(ci); }"
        "#define dojump(ci,i,e)\t{ pc += GETARG_sJ(i) + e; cenda_poll(L); updatetrap(ci); }")
    # numeric for (both integer and float paths end here)
    _cenda_replace("        updatetrap(ci);  /* allows a signal to break the loop */"
        "        cenda_poll(L);\n        updatetrap(ci);  /* allows a signal to break the loop */")
    # generic for
    _cenda_replace("        if (!ttisnil(s2v(ra + 3)))  /* continue loop? */\n          pc -= GETARG_Bx(i);  /* jump back */"
        "        if (!ttisnil(s2v(ra + 3))) {  /* continue loop? */\n          pc -= GETARG_Bx(i);  /* jump back */\n          cenda_poll(L);\n          updatetrap(ci);\n        }")
    # recursion and tail-call loops
    _cenda_replace("      vmcase(OP_CALL) {\n"
        "      vmcase(OP_CALL) {\n        cenda_poll(L);\n")
    _cenda_replace("      vmcase(OP_TAILCALL) {\n"
        "      vmcase(OP_TAILCALL) {\n        cenda_poll(L);\n")
    file(WRITE "${_file}" "${_src}")
endif()

# ── lstrlib.c ──
set(_file "${LUA_SRC_DIR}/src/lstrlib.c")
file(READ "${_file}" _src)
if(NOT _src MATCHES "cenda_lua_interrupt.h")
    _cenda_replace("#include \"llimits.h\"\n"
        "#include \"llimits.h\"\n#include \"cenda_lua_interrupt.h\"\n")
    # every backtracking step of the matcher enters match()
    _cenda_replace("static const char *match (MatchState *ms, const char *s, const char *p) {\n"
        "static const char *match (MatchState *ms, const char *s, const char *p) {\n  cenda_check(ms->L);\n")
    # plain string.find: O(subject x needle) memcmp loop
    _cenda_replace("static const char *lmemfind (const char *s1, size_t l1,"
        "static const char *lmemfind (lua_State *L, const char *s1, size_t l1,")
    _cenda_replace("      init++;   /* 1st char is already checked */"
        "      cenda_check(L);\n      init++;   /* 1st char is already checked */")
    _cenda_replace("lmemfind(s + init, ls - init, p, lp);"
        "lmemfind(L, s + init, ls - init, p, lp);")
    file(WRITE "${_file}" "${_src}")
endif()

# ── ltablib.c ──
set(_file "${LUA_SRC_DIR}/src/ltablib.c")
file(READ "${_file}" _src)
if(NOT _src MATCHES "cenda_lua_interrupt.h")
    _cenda_replace("#include \"llimits.h\"\n"
        "#include \"llimits.h\"\n#include \"cenda_lua_interrupt.h\"\n")
    _cenda_replace("      for (i = 0; i < n; i++) {\n        lua_geti(L, 1, f + i);"
        "      for (i = 0; i < n; i++) {\n        cenda_check(L);\n        lua_geti(L, 1, f + i);")
    _cenda_replace("      for (i = n - 1; i >= 0; i--) {\n        lua_geti(L, 1, f + i);"
        "      for (i = n - 1; i >= 0; i--) {\n        cenda_check(L);\n        lua_geti(L, 1, f + i);")
    file(WRITE "${_file}" "${_src}")
endif()
