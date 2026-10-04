# FetchContent PATCH_COMMAND for Lua's lvm.c: inserts the deadline poll (see
# cenda_lua_interrupt.h). Pure CMake so it runs anywhere CMake does; fails the
# configure loudly if Lua's source drifts so a silent no-op patch is impossible.
set(_lvm "${LUA_SRC_DIR}/src/lvm.c")
file(READ "${_lvm}" _src)
if(_src MATCHES "cenda_lua_interrupt.h")
    return()  # already patched
endif()

function(_cenda_replace from to)
    string(FIND "${_src}" "${from}" _at)
    if(_at EQUAL -1)
        message(FATAL_ERROR "Lua interrupt patch: anchor not found in lvm.c:\n${from}")
    endif()
    string(REPLACE "${from}" "${to}" _out "${_src}")
    set(_src "${_out}" PARENT_SCOPE)
endfunction()

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
file(WRITE "${_lvm}" "${_src}")
