/* Deadline interrupt for the vendored Lua VM (#283).
 *
 * Lua 5.5 routes EVERY instruction through luaG_traceexec while a count hook
 * is set, which doubles the cost of VM-bound scripts. Instead, the VM polls a
 * cheap per-state cell at loop back-jumps and calls (patch_lvm.cmake inserts
 * cenda_poll), and only when the host has flagged the current call does it arm
 * the count hook — on the VM's own thread, so no lua_sethook ever races the
 * interpreter.
 *
 * The state's extra space (LUA_EXTRASPACE, copied into every coroutine) holds
 * a pointer to a cenda_vm_cell. A call is interrupted when interrupt_gen ==
 * call_gen; a stale request for an earlier call can never match.
 */
#ifndef CENDA_LUA_INTERRUPT_H
#define CENDA_LUA_INTERRUPT_H

typedef struct cenda_vm_cell {
    volatile unsigned long long call_gen;      /* bumped by the host at every top-level call */
    volatile unsigned long long interrupt_gen; /* set by the watchdog to the call it wants stopped */
    void* state;                     /* owning cl_state */
} cenda_vm_cell;

#define cenda_cell(L) (*(cenda_vm_cell* const*)lua_getextraspace(L))
#define cenda_pending(L) (cenda_cell(L)->interrupt_gen == cenda_cell(L)->call_gen)

/* Arms the interrupt hook on L. Defined in lua_host.cpp; VM thread only. */
void cenda_lua_interrupt(lua_State* L);

#define cenda_poll(L) { if (l_unlikely(cenda_pending(L))) cenda_lua_interrupt(L); }

#endif
