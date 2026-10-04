/* Cenda Lua host — sandboxed Lua 5.5 states for UI scripting, consumed by
 * Java via FFM (com.openmason.engine.cenda.CendaLua).
 *
 * #283 feasibility spike: the smallest ABI that lets us measure state
 * lifecycle, crossing costs, sandbox enforcement and cancellation. #292 owns
 * the production surface (typed push/pull, the `ui` API).
 *
 * Contract notes:
 *  - Lives in the same shared library as the kernels but has its OWN ABI
 *    handshake (cl_abi_version). The kernels are optional; UI scripting is
 *    not, so a mismatch here must fail loudly on the Java side.
 *  - A state is single-threaded. Every call on one state (and the host
 *    callbacks it triggers) must come from the same thread.
 *  - Sandbox: text chunks only (binary chunks are rejected), and only the
 *    base (minus dofile/loadfile/collectgarbage), coroutine, math, string
 *    (minus dump), table and utf8 libraries exist. io/os/debug/package are not
 *    compiled into the library at all. `load` is text-only.
 *  - Memory: every allocation goes through a per-state capped allocator; an
 *    over-cap allocation fails with a Lua memory error, never a crash.
 *  - Instructions: a count hook enforces the budget set by cl_set_budget for
 *    each top-level call (cl_run / cl_call / cl_resume). 0 disables it.
 *  - References (functions, environments, threads) are registry refs owned
 *    by the caller; release them with cl_unref.
 *  - Error text returned by cl_last_error stays valid until the next call on
 *    the same state.
 */
#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CL_ABI_VERSION 1

/* Status codes. Values >= 0 mirror Lua's own; CL_ERR_BUDGET is ours. */
#define CL_OK         0
#define CL_YIELD      1
#define CL_ERR_RUN    2
#define CL_ERR_SYNTAX 3
#define CL_ERR_MEM    4
#define CL_ERR_ERR    5
#define CL_ERR_BUDGET 16
#define CL_ERR_ARG    17
#define CL_ERR_HOST   18
#define CL_ERR_DEADLINE 19

typedef struct cl_state cl_state;

/* A Java upcall. Reads up to nargs doubles, writes up to max_results doubles
 * and returns how many it wrote, or a negative value to raise a Lua error
 * (the host must never let an exception escape the upcall). */
typedef int32_t (*cl_host_fn)(int64_t user, const double* args, int32_t nargs,
                              double* results, int32_t max_results);

int32_t cl_abi_version(void);
/* "Lua 5.5.1" — static string, never freed. */
const char* cl_lua_release(void);

/* New sandboxed state. mem_limit_bytes == 0 means uncapped. NULL on failure. */
cl_state* cl_state_new(size_t mem_limit_bytes);
void cl_state_close(cl_state* s);

size_t cl_mem_used(const cl_state* s);
size_t cl_mem_peak(const cl_state* s);
/* Cumulative bytes the state has ever requested (growth only). The delta
 * across a frame is that frame's Lua-heap garbage. */
size_t cl_mem_allocated(const cl_state* s);
void cl_set_mem_limit(cl_state* s, size_t mem_limit_bytes);

/* Instruction budget per top-level call; 0 disables the count hook. */
void cl_set_budget(cl_state* s, int64_t instructions);
/* Instructions charged to the most recent top-level call (hook granularity). */
int64_t cl_last_instructions(const cl_state* s);

const char* cl_last_error(const cl_state* s);

/* Fresh sandbox environment: an empty table whose __index is the curated
 * globals, so one state can host many isolated document instances. */
int32_t cl_env_new(cl_state* s);
void cl_unref(cl_state* s, int32_t ref);

/* Compile + run a text chunk. env_ref <= 0 runs it in the shared globals. */
int32_t cl_run(cl_state* s, const char* src, size_t len, const char* chunk_name, int32_t env_ref);

/* Registry ref to field `name` of env_ref (or of the globals); <= 0 if the
 * field is not a function. */
int32_t cl_ref_function(cl_state* s, int32_t env_ref, const char* name);

/* Protected call with number args/results. Missing results are NaN. */
int32_t cl_call(cl_state* s, int32_t fn_ref, const double* args, int32_t nargs,
                double* results, int32_t nresults);

/* Expose a Java upcall as global/env function `name`. */
int32_t cl_register_host(cl_state* s, int32_t env_ref, const char* name, cl_host_fn fn, int64_t user);

/* Expose a caller-owned float buffer as global/env table `name` with
 * emit(a, b, ...) (append at the cursor), put(i, v), get(i) and len() —
 * Lua→host bulk data with no JVM crossing per element (e.g. a minigame's
 * per-frame draw list). The memory must outlive the state. Returns a buffer
 * handle >= 0, or a negative status. */
int32_t cl_bind_buffer(cl_state* s, int32_t env_ref, const char* name, float* data, int32_t capacity);
/* Floats appended by emit since the last reset. */
int32_t cl_buffer_cursor(const cl_state* s, int32_t buffer);
void cl_buffer_reset(cl_state* s, int32_t buffer);

/* Coroutines: a thread running fn_ref. resume returns CL_YIELD, CL_OK (dead)
 * or an error; *nresults receives how many values were written. */
int32_t cl_thread_new(cl_state* s, int32_t fn_ref);
int32_t cl_resume(cl_state* s, int32_t thread_ref, const double* args, int32_t nargs,
                  double* results, int32_t max_results, int32_t* nresults);
/* Cancel: closes pending to-be-closed variables and kills the thread. */
int32_t cl_thread_close(cl_state* s, int32_t thread_ref);
/* 0 = suspended, 1 = running, 2 = normal, 3 = dead. */
int32_t cl_thread_status(cl_state* s, int32_t thread_ref);

/* Deadline watchdog — the ONLY two functions another thread may call.
 * cl_watch_token returns a non-zero token identifying the top-level call in
 * progress (0 when idle). A watchdog that sees the same token for longer than
 * its deadline calls cl_interrupt(token): the VM notices at its next loop
 * back-jump or call, arms the count hook on its own thread, and the call ends
 * with CL_ERR_DEADLINE (pcall cannot swallow it). A token for a call that has
 * already finished is ignored, so the race is harmless. Steady-state cost is
 * one compare per back-jump/call — no hook, unlike cl_set_budget. */
uint64_t cl_watch_token(const cl_state* s);
void cl_interrupt(cl_state* s, uint64_t token);

/* Full GC cycle; returns bytes in use afterwards. */
size_t cl_gc_collect(cl_state* s);

#ifdef __cplusplus
}
#endif
