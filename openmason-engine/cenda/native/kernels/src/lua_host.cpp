// Cenda Lua host — see include/cenda/lua_host.h for the contract.
//
// Lua is compiled as C and raises errors with longjmp, so nothing here that
// can raise a Lua error owns a C++ object with a destructor: every operation
// that allocates inside the Lua heap runs as a lua_CFunction under lua_pcall
// (protected()), and the only C++ state lives in cl_state, outside any frame
// a longjmp can cross.

#include "cenda/lua_host.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <atomic>
#include <string>
#include <vector>

extern "C" {
#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"
#include "cenda_lua_interrupt.h"
}

namespace {

// Instructions between count-hook firings. The budget is enforced at this
// granularity (an over-budget chunk runs at most this many extra
// instructions before it is stopped).
constexpr int HOOK_STRIDE = 1000;
constexpr int MAX_HOST_VALUES = 16;
// Fixed string-hash seed: pairs() order is then identical run to run, which
// the deterministic UI fixtures rely on. UI scripts are not an untrusted
// network input, so hash-flooding resistance buys nothing here.
constexpr unsigned STATE_SEED = 0x5eed5eedu;

struct Buffer {
    float* data;
    int32_t capacity;
    int32_t cursor;
};

}  // namespace

struct cl_state {
    // Read by the patched VM through the extra space; the only fields another
    // thread may touch (cl_watch_token / cl_interrupt).
    cenda_vm_cell cell{0, ~0ull, nullptr};
    std::atomic<int> active{0};
    lua_State* L = nullptr;
    size_t used = 0;
    size_t peak = 0;
    size_t limit = 0;
    size_t allocated_total = 0;  // cumulative bytes requested (growth only) — "garbage" meter
    int64_t budget = 0;
    int64_t charged = 0;
    int hook_stride = HOOK_STRIDE;  // instructions per hook firing right now
    bool budget_hit = false;
    std::string error;
    std::vector<Buffer> buffers;
};

namespace {

cl_state* state_of(lua_State* L) {
    return static_cast<cl_state*>(cenda_cell(L)->state);
}

void* capped_alloc(void* ud, void* ptr, size_t osize, size_t nsize) {
    auto* s = static_cast<cl_state*>(ud);
    // When ptr is NULL, osize encodes the object type, not a size.
    const size_t old = ptr != nullptr ? osize : 0;
    if (nsize == 0) {
        std::free(ptr);
        s->used -= old;
        return nullptr;
    }
    if (s->limit != 0 && nsize > old && s->used - old + nsize > s->limit) {
        return nullptr;  // Lua runs an emergency GC, then raises LUA_ERRMEM
    }
    void* grown = std::realloc(ptr, nsize);
    if (grown == nullptr) {
        return nullptr;
    }
    s->used = s->used - old + nsize;
    if (nsize > old) {
        s->allocated_total += nsize - old;
    }
    if (s->used > s->peak) {
        s->peak = s->used;
    }
    return grown;
}

void count_hook(lua_State* L, lua_Debug*) {
    cl_state* s = state_of(L);
    if (cenda_pending(L)) {
        // Deadline: stays pending (and the hook stays at stride 1) until the
        // next top-level call, so pcall cannot swallow it either.
        luaL_error(L, "deadline exceeded (interrupted by the host watchdog)");
    }
    s->charged += s->hook_stride;
    if (s->budget > 0 && s->charged > s->budget) {
        if (!s->budget_hit) {
            s->budget_hit = true;
            s->hook_stride = 1;
            // From now on fire on every instruction, so a script that
            // swallows the error with pcall is stopped at its very next
            // instruction instead of looping on caught errors.
            lua_sethook(L, count_hook, LUA_MASKCOUNT, 1);
        }
        luaL_error(L, "instruction budget exceeded (%I instructions)",
                   static_cast<lua_Integer>(s->budget));
    }
}

void begin_call(cl_state* s, lua_State* thread) {
    s->cell.call_gen = s->cell.call_gen + 1;
    s->active.store(1, std::memory_order_release);
    s->charged = 0;
    s->hook_stride = HOOK_STRIDE;
    s->budget_hit = false;
    s->error.clear();
    if (s->budget > 0) {
        lua_sethook(thread, count_hook, LUA_MASKCOUNT, HOOK_STRIDE);
    } else {
        lua_sethook(thread, nullptr, 0, 0);
    }
}

// Every top-level entry point calls this on each exit path.
int32_t end_call(cl_state* s, int32_t status) {
    s->active.store(0, std::memory_order_release);
    return status;
}

int message_handler(lua_State* L) {
    const char* msg = lua_tostring(L, 1);
    if (msg == nullptr) {
        if (luaL_callmeta(L, 1, "__tostring") && lua_type(L, -1) == LUA_TSTRING) {
            return 1;
        }
        msg = lua_pushfstring(L, "(error object is a %s value)", luaL_typename(L, 1));
    }
    luaL_traceback(L, L, msg, 1);
    return 1;
}

int32_t map_status(cl_state* s, int status) {
    if (status == LUA_OK) {
        return CL_OK;
    }
    if (status == LUA_YIELD) {
        return CL_YIELD;
    }
    if (s->cell.interrupt_gen == s->cell.call_gen) {
        return CL_ERR_DEADLINE;
    }
    if (s->budget_hit) {
        return CL_ERR_BUDGET;
    }
    switch (status) {
        case LUA_ERRSYNTAX: return CL_ERR_SYNTAX;
        case LUA_ERRMEM: return CL_ERR_MEM;
        case LUA_ERRERR: return CL_ERR_ERR;
        default: return CL_ERR_RUN;
    }
}

// Records the error value on top of `thread` (if any) and pops it.
void capture_error(cl_state* s, lua_State* thread) {
    const char* msg = lua_tostring(thread, -1);
    s->error = msg != nullptr ? msg : "(non-string error)";
    if (s->budget_hit && s->error.find("instruction budget") == std::string::npos) {
        s->error = "instruction budget exceeded; last error: " + s->error;
    }
    lua_pop(thread, 1);
}

double to_double(lua_State* L, int idx) {
    if (lua_isboolean(L, idx)) {
        return lua_toboolean(L, idx) ? 1.0 : 0.0;
    }
    int isnum = 0;
    const lua_Number n = lua_tonumberx(L, idx, &isnum);
    return isnum ? static_cast<double>(n) : std::nan("");
}

// Runs fn(ctx) in protected mode. Any allocation failure inside becomes a
// status instead of a panic.
int32_t protected_op(cl_state* s, lua_CFunction fn, void* ctx) {
    lua_State* L = s->L;
    s->budget_hit = false;
    lua_pushcfunction(L, fn);
    lua_pushlightuserdata(L, ctx);
    const int status = lua_pcall(L, 1, 0, 0);
    if (status != LUA_OK) {
        capture_error(s, L);
        return map_status(s, status);
    }
    return CL_OK;
}

// ───────────────────────────── sandbox ─────────────────────────────

// Text-only `load`. Strings only (no reader functions); the chunk's _ENV
// defaults to the environment the `load` closure was installed into.
int sandbox_load(lua_State* L) {
    size_t len = 0;
    const char* src = luaL_checklstring(L, 1, &len);
    const char* name = luaL_optstring(L, 2, "=(load)");
    const int status = luaL_loadbufferx(L, src, len, name, "t");
    if (status != LUA_OK) {
        luaL_pushfail(L);
        lua_insert(L, -2);
        return 2;
    }
    if (!lua_isnoneornil(L, 4)) {
        lua_pushvalue(L, 4);
    } else {
        lua_pushvalue(L, lua_upvalueindex(1));
    }
    if (lua_setupvalue(L, -2, 1) == nullptr) {
        lua_pop(L, 1);
    }
    return 1;
}

void install_load(lua_State* L, int env_index) {
    env_index = lua_absindex(L, env_index);
    lua_pushvalue(L, env_index);
    lua_pushcclosure(L, sandbox_load, 1);
    lua_setfield(L, env_index, "load");
}

int open_sandbox(lua_State* L) {
    const luaL_Reg libs[] = {
        {LUA_GNAME, luaopen_base},
        {LUA_COLIBNAME, luaopen_coroutine},
        {LUA_MATHLIBNAME, luaopen_math},
        {LUA_STRLIBNAME, luaopen_string},
        {LUA_TABLIBNAME, luaopen_table},
        {LUA_UTF8LIBNAME, luaopen_utf8},
    };
    for (const luaL_Reg& lib : libs) {
        luaL_requiref(L, lib.name, lib.func, 1);
        lua_pop(L, 1);
    }
    lua_pushglobaltable(L);
    for (const char* banned : {"dofile", "loadfile", "collectgarbage"}) {
        lua_pushnil(L);
        lua_setfield(L, -2, banned);
    }
    install_load(L, -1);
    lua_getfield(L, -1, LUA_STRLIBNAME);
    lua_pushnil(L);
    lua_setfield(L, -2, "dump");
    lua_pop(L, 2);

    // The string metatable is shared by every environment; hide it so one
    // document instance cannot redirect string methods for the others.
    lua_pushliteral(L, "");
    if (lua_getmetatable(L, -1)) {
        lua_pushliteral(L, "string");
        lua_setfield(L, -2, "__metatable");
        lua_pop(L, 1);
    }
    lua_pop(L, 1);
    return 0;
}

// Copy of the curated globals with per-environment library tables, so an
// instance that writes `string.foo` or `math.pi` only changes its own view.
int make_env(lua_State* L) {
    auto* out = static_cast<int32_t*>(lua_touserdata(L, 1));
    lua_newtable(L);  // env
    const int env = lua_gettop(L);
    lua_pushglobaltable(L);
    lua_pushnil(L);
    while (lua_next(L, -2) != 0) {
        // stack: env, G, key, value
        if (lua_istable(L, -1) && !lua_rawequal(L, -1, -3)) {
            lua_newtable(L);
            lua_pushnil(L);
            while (lua_next(L, -3) != 0) {
                lua_pushvalue(L, -2);
                lua_insert(L, -2);
                lua_rawset(L, -4);
            }
            lua_replace(L, -2);
        }
        lua_pushvalue(L, -2);
        lua_insert(L, -2);
        lua_rawset(L, env);
    }
    lua_pop(L, 1);  // G
    lua_pushvalue(L, env);
    lua_setfield(L, env, LUA_GNAME);
    install_load(L, env);
    *out = luaL_ref(L, LUA_REGISTRYINDEX);
    return 0;
}

void push_env(lua_State* L, int32_t env_ref) {
    if (env_ref > 0) {
        lua_rawgeti(L, LUA_REGISTRYINDEX, env_ref);
    } else {
        lua_pushglobaltable(L);
    }
}

// ─────────────────────────── host bridges ───────────────────────────

int host_trampoline(lua_State* L) {
    cl_host_fn fn = nullptr;
    void* raw = lua_touserdata(L, lua_upvalueindex(1));
    std::memcpy(&fn, &raw, sizeof fn);
    const auto user = static_cast<int64_t>(lua_tointeger(L, lua_upvalueindex(2)));
    const int n = lua_gettop(L);
    if (n > MAX_HOST_VALUES) {
        return luaL_error(L, "host functions take at most %d arguments", MAX_HOST_VALUES);
    }
    double args[MAX_HOST_VALUES];
    double results[MAX_HOST_VALUES];
    for (int i = 0; i < n; ++i) {
        args[i] = to_double(L, i + 1);
    }
    const int32_t written = fn(user, args, n, results, MAX_HOST_VALUES);
    if (written < 0) {
        return luaL_error(L, "host function failed (code %d)", written);
    }
    const int count = written > MAX_HOST_VALUES ? MAX_HOST_VALUES : written;
    for (int i = 0; i < count; ++i) {
        lua_pushnumber(L, static_cast<lua_Number>(results[i]));
    }
    return count;
}

Buffer& buffer_of(lua_State* L) {
    const auto idx = static_cast<size_t>(lua_tointeger(L, lua_upvalueindex(1)));
    return state_of(L)->buffers[idx];
}

int buffer_emit(lua_State* L) {
    Buffer& b = buffer_of(L);
    const int n = lua_gettop(L);
    if (b.cursor + n > b.capacity) {
        return luaL_error(L, "buffer overflow (capacity %d floats)", b.capacity);
    }
    for (int i = 1; i <= n; ++i) {
        b.data[b.cursor++] = static_cast<float>(luaL_checknumber(L, i));
    }
    return 0;
}

int buffer_put(lua_State* L) {
    Buffer& b = buffer_of(L);
    const lua_Integer i = luaL_checkinteger(L, 1);
    luaL_argcheck(L, i >= 1 && i <= b.capacity, 1, "index out of range");
    b.data[i - 1] = static_cast<float>(luaL_checknumber(L, 2));
    return 0;
}

int buffer_get(lua_State* L) {
    Buffer& b = buffer_of(L);
    const lua_Integer i = luaL_checkinteger(L, 1);
    luaL_argcheck(L, i >= 1 && i <= b.capacity, 1, "index out of range");
    lua_pushnumber(L, static_cast<lua_Number>(b.data[i - 1]));
    return 1;
}

int buffer_len(lua_State* L) {
    lua_pushinteger(L, buffer_of(L).capacity);
    return 1;
}

// ───────────────────────── protected op bodies ─────────────────────────

struct RefFunctionCtx {
    int32_t env_ref;
    const char* name;
    int32_t ref;
};

int op_ref_function(lua_State* L) {
    auto* c = static_cast<RefFunctionCtx*>(lua_touserdata(L, 1));
    push_env(L, c->env_ref);
    lua_getfield(L, -1, c->name);
    c->ref = lua_isfunction(L, -1) ? luaL_ref(L, LUA_REGISTRYINDEX) : 0;
    return 0;
}

struct RegisterHostCtx {
    int32_t env_ref;
    const char* name;
    cl_host_fn fn;
    int64_t user;
};

int op_register_host(lua_State* L) {
    auto* c = static_cast<RegisterHostCtx*>(lua_touserdata(L, 1));
    push_env(L, c->env_ref);
    void* raw = nullptr;
    std::memcpy(&raw, &c->fn, sizeof raw);
    lua_pushlightuserdata(L, raw);
    lua_pushinteger(L, static_cast<lua_Integer>(c->user));
    lua_pushcclosure(L, host_trampoline, 2);
    lua_setfield(L, -2, c->name);
    return 0;
}

struct BindBufferCtx {
    int32_t env_ref;
    const char* name;
    int32_t index;
};

int op_bind_buffer(lua_State* L) {
    auto* c = static_cast<BindBufferCtx*>(lua_touserdata(L, 1));
    push_env(L, c->env_ref);
    lua_createtable(L, 0, 4);
    const luaL_Reg methods[] = {
        {"emit", buffer_emit}, {"put", buffer_put}, {"get", buffer_get}, {"len", buffer_len}};
    for (const luaL_Reg& m : methods) {
        lua_pushinteger(L, c->index);
        lua_pushcclosure(L, m.func, 1);
        lua_setfield(L, -2, m.name);
    }
    lua_setfield(L, -2, c->name);
    return 0;
}

struct ThreadNewCtx {
    int32_t fn_ref;
    int32_t ref;
};

int op_thread_new(lua_State* L) {
    auto* c = static_cast<ThreadNewCtx*>(lua_touserdata(L, 1));
    lua_State* co = lua_newthread(L);
    lua_rawgeti(L, LUA_REGISTRYINDEX, c->fn_ref);
    if (!lua_isfunction(L, -1)) {
        return luaL_error(L, "not a function reference");
    }
    lua_xmove(L, co, 1);
    c->ref = luaL_ref(L, LUA_REGISTRYINDEX);
    return 0;
}

struct TracebackCtx {
    lua_State* co;
    std::string* out;
};

int op_traceback(lua_State* L) {
    auto* c = static_cast<TracebackCtx*>(lua_touserdata(L, 1));
    luaL_traceback(L, c->co, c->out->c_str(), 0);
    c->out->assign(lua_tostring(L, -1));
    return 0;
}

lua_State* thread_of(cl_state* s, int32_t thread_ref) {
    lua_rawgeti(s->L, LUA_REGISTRYINDEX, thread_ref);
    lua_State* co = lua_tothread(s->L, -1);
    lua_pop(s->L, 1);  // the registry ref keeps it alive
    return co;
}

}  // namespace

extern "C" void cenda_lua_interrupt(lua_State* L) {
    if (lua_gethook(L) != count_hook || lua_gethookcount(L) != 1) {
        lua_sethook(L, count_hook, LUA_MASKCOUNT, 1);
    }
}

namespace {

int panic_handler(lua_State* L) {
    const char* msg = lua_tostring(L, -1);
    std::fprintf(stderr, "[cenda-lua] PANIC (unprotected error): %s\n", msg != nullptr ? msg : "?");
    return 0;  // Lua aborts after this returns
}

}  // namespace

extern "C" {

int32_t cl_abi_version(void) { return CL_ABI_VERSION; }

const char* cl_lua_release(void) { return LUA_RELEASE; }

cl_state* cl_state_new(size_t mem_limit_bytes) {
    auto* s = new (std::nothrow) cl_state();
    if (s == nullptr) {
        return nullptr;
    }
    s->limit = mem_limit_bytes;
    s->L = lua_newstate(capped_alloc, s, STATE_SEED);
    if (s->L == nullptr) {
        delete s;
        return nullptr;
    }
    s->cell.state = s;
    cenda_vm_cell* cell = &s->cell;
    std::memcpy(lua_getextraspace(s->L), &cell, sizeof cell);
    lua_atpanic(s->L, panic_handler);
    lua_pushcfunction(s->L, open_sandbox);
    if (lua_pcall(s->L, 0, 0, 0) != LUA_OK) {
        lua_close(s->L);
        delete s;
        return nullptr;
    }
    return s;
}

void cl_state_close(cl_state* s) {
    if (s == nullptr) {
        return;
    }
    s->limit = 0;  // closing must always be able to run __close/__gc handlers
    s->budget = 0;
    lua_sethook(s->L, nullptr, 0, 0);
    lua_close(s->L);
    delete s;
}

size_t cl_mem_used(const cl_state* s) { return s->used; }

size_t cl_mem_peak(const cl_state* s) { return s->peak; }

size_t cl_mem_allocated(const cl_state* s) { return s->allocated_total; }

void cl_set_mem_limit(cl_state* s, size_t mem_limit_bytes) { s->limit = mem_limit_bytes; }

void cl_set_budget(cl_state* s, int64_t instructions) { s->budget = instructions > 0 ? instructions : 0; }

int64_t cl_last_instructions(const cl_state* s) { return s->charged; }

const char* cl_last_error(const cl_state* s) { return s->error.c_str(); }

int32_t cl_env_new(cl_state* s) {
    int32_t ref = 0;
    s->error.clear();
    const int32_t st = protected_op(s, make_env, &ref);
    return st == CL_OK ? ref : -st;
}

void cl_unref(cl_state* s, int32_t ref) {
    if (ref > 0) {
        luaL_unref(s->L, LUA_REGISTRYINDEX, ref);
    }
}

int32_t cl_run(cl_state* s, const char* src, size_t len, const char* chunk_name, int32_t env_ref) {
    lua_State* L = s->L;
    begin_call(s, L);
    const int base = lua_gettop(L);
    lua_pushcfunction(L, message_handler);
    const int status = luaL_loadbufferx(L, src, len, chunk_name, "t");
    if (status != LUA_OK) {
        capture_error(s, L);
        lua_settop(L, base);
        return end_call(s, map_status(s, status));
    }
    if (env_ref > 0) {
        lua_rawgeti(L, LUA_REGISTRYINDEX, env_ref);
        if (lua_setupvalue(L, -2, 1) == nullptr) {
            lua_pop(L, 1);
        }
    }
    const int run = lua_pcall(L, 0, 0, base + 1);
    if (run != LUA_OK) {
        capture_error(s, L);
    }
    lua_settop(L, base);
    return end_call(s, map_status(s, run));
}

int32_t cl_ref_function(cl_state* s, int32_t env_ref, const char* name) {
    RefFunctionCtx ctx{env_ref, name, 0};
    s->error.clear();
    const int32_t st = protected_op(s, op_ref_function, &ctx);
    return st == CL_OK ? ctx.ref : -st;
}

int32_t cl_call(cl_state* s, int32_t fn_ref, const double* args, int32_t nargs,
                double* results, int32_t nresults) {
    lua_State* L = s->L;
    if (nargs < 0 || nresults < 0 || !lua_checkstack(L, nargs + 2)) {
        return CL_ERR_ARG;
    }
    begin_call(s, L);
    const int base = lua_gettop(L);
    lua_pushcfunction(L, message_handler);
    lua_rawgeti(L, LUA_REGISTRYINDEX, fn_ref);
    if (!lua_isfunction(L, -1)) {
        lua_settop(L, base);
        s->error = "not a function reference";
        return end_call(s, CL_ERR_ARG);
    }
    for (int32_t i = 0; i < nargs; ++i) {
        lua_pushnumber(L, static_cast<lua_Number>(args[i]));
    }
    const int status = lua_pcall(L, nargs, nresults, base + 1);
    if (status == LUA_OK) {
        for (int32_t i = 0; i < nresults; ++i) {
            results[i] = to_double(L, base + 2 + i);
        }
    } else {
        capture_error(s, L);
    }
    lua_settop(L, base);
    return end_call(s, map_status(s, status));
}

int32_t cl_register_host(cl_state* s, int32_t env_ref, const char* name, cl_host_fn fn, int64_t user) {
    if (fn == nullptr) {
        return CL_ERR_ARG;
    }
    RegisterHostCtx ctx{env_ref, name, fn, user};
    s->error.clear();
    return protected_op(s, op_register_host, &ctx);
}

int32_t cl_bind_buffer(cl_state* s, int32_t env_ref, const char* name, float* data, int32_t capacity) {
    if (data == nullptr || capacity <= 0) {
        return -CL_ERR_ARG;
    }
    s->buffers.push_back(Buffer{data, capacity, 0});
    BindBufferCtx ctx{env_ref, name, static_cast<int32_t>(s->buffers.size() - 1)};
    s->error.clear();
    const int32_t st = protected_op(s, op_bind_buffer, &ctx);
    return st == CL_OK ? ctx.index : -st;
}

int32_t cl_buffer_cursor(const cl_state* s, int32_t buffer) {
    return s->buffers[static_cast<size_t>(buffer)].cursor;
}

void cl_buffer_reset(cl_state* s, int32_t buffer) {
    s->buffers[static_cast<size_t>(buffer)].cursor = 0;
}

int32_t cl_thread_new(cl_state* s, int32_t fn_ref) {
    ThreadNewCtx ctx{fn_ref, 0};
    s->error.clear();
    const int32_t st = protected_op(s, op_thread_new, &ctx);
    return st == CL_OK ? ctx.ref : -st;
}

int32_t cl_resume(cl_state* s, int32_t thread_ref, const double* args, int32_t nargs,
                  double* results, int32_t max_results, int32_t* nresults) {
    *nresults = 0;
    lua_State* co = thread_of(s, thread_ref);
    if (co == nullptr || nargs < 0 || !lua_checkstack(co, nargs + 1)) {
        return CL_ERR_ARG;
    }
    const int pre = lua_status(co);
    if (pre != LUA_YIELD && (pre != LUA_OK || lua_gettop(co) == 0)) {
        s->error = "cannot resume dead coroutine";
        return CL_ERR_ARG;
    }
    begin_call(s, co);
    for (int32_t i = 0; i < nargs; ++i) {
        lua_pushnumber(co, static_cast<lua_Number>(args[i]));
    }
    int nres = 0;
    const int status = lua_resume(co, s->L, nargs, &nres);
    if (status == LUA_OK || status == LUA_YIELD) {
        const int count = nres < max_results ? nres : max_results;
        const int first = lua_gettop(co) - nres + 1;
        for (int i = 0; i < count; ++i) {
            results[i] = to_double(co, first + i);
        }
        *nresults = count;
        lua_pop(co, nres);
        return end_call(s, map_status(s, status));
    }
    const int32_t mapped = map_status(s, status);
    capture_error(s, co);
    std::string message = s->error;
    TracebackCtx ctx{co, &message};
    if (protected_op(s, op_traceback, &ctx) == CL_OK) {
        s->error = message;
    }
    return end_call(s, mapped);
}

int32_t cl_thread_close(cl_state* s, int32_t thread_ref) {
    lua_State* co = thread_of(s, thread_ref);
    if (co == nullptr) {
        return CL_ERR_ARG;
    }
    begin_call(s, co);
    const int status = lua_closethread(co, s->L);
    if (status != LUA_OK) {
        capture_error(s, co);
    }
    return end_call(s, map_status(s, status));
}

int32_t cl_thread_status(cl_state* s, int32_t thread_ref) {
    lua_State* co = thread_of(s, thread_ref);
    if (co == nullptr) {
        return 3;
    }
    const int st = lua_status(co);
    if (st == LUA_YIELD) {
        return 0;
    }
    if (st == LUA_OK) {
        lua_Debug ar;
        if (lua_getstack(co, 0, &ar)) {
            return 2;
        }
        return lua_gettop(co) == 0 ? 3 : 0;
    }
    return 3;
}

uint64_t cl_watch_token(const cl_state* s) {
    return s->active.load(std::memory_order_acquire) ? s->cell.call_gen : 0;
}

void cl_interrupt(cl_state* s, uint64_t token) {
    if (token != 0) {
        s->cell.interrupt_gen = token;
    }
}

size_t cl_gc_collect(cl_state* s) {
    lua_gc(s->L, LUA_GCCOLLECT);
    return s->used;
}

}  // extern "C"
