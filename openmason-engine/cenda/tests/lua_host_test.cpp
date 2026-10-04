// Plain-assert checks for the Lua host's sandbox, memory cap, instruction
// budget and coroutine cancellation (#283). The Java side re-tests the same
// contract through FFM; this one runs without a JVM.

#include "cenda/lua_host.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <atomic>
#include <chrono>
#include <string>
#include <thread>

namespace {

int failures = 0;

void check(bool ok, const char* what, const cl_state* s = nullptr) {
    if (!ok) {
        ++failures;
        std::fprintf(stderr, "FAIL: %s%s%s\n", what, s ? " — " : "", s ? cl_last_error(s) : "");
    }
}

int32_t run(cl_state* s, const char* src, int32_t env = 0) {
    return cl_run(s, src, std::strlen(src), "=test", env);
}

double eval(cl_state* s, const char* expr, int32_t env = 0) {
    std::string src = std::string("function __probe() return ") + expr + " end";
    if (run(s, src.c_str(), env) != CL_OK) {
        return std::nan("");
    }
    const int32_t fn = cl_ref_function(s, env, "__probe");
    double out = std::nan("");
    cl_call(s, fn, nullptr, 0, &out, 1);
    cl_unref(s, fn);
    return out;
}

void test_sandbox() {
    cl_state* s = cl_state_new(0);
    check(s != nullptr, "state creation");
    for (const char* banned : {"io", "os", "debug", "package", "require", "dofile", "loadfile",
                               "collectgarbage", "string.dump"}) {
        std::string expr = std::string(banned) + " == nil";
        check(eval(s, expr.c_str()) == 1.0, (std::string("banned global absent: ") + banned).c_str(), s);
    }
    check(eval(s, "math.sqrt(16) + #string.rep('a', 3) + utf8.len('é')") == 8.0, "curated libs work", s);
    // Binary chunks are refused both at the host boundary and through load().
    const char binary[] = "\x1bLua\x55\x00";
    check(cl_run(s, binary, sizeof binary - 1, "=bin", 0) == CL_ERR_SYNTAX, "binary chunk rejected");
    check(eval(s, "select('#', load('\\27Lua')) == 2 and 1 or 0") == 1.0, "load() refuses binary", s);
    check(eval(s, "getmetatable('') == 'string'") == 1.0, "string metatable hidden", s);
    check(run(s, "error('boom')") == CL_ERR_RUN && std::strstr(cl_last_error(s), "boom") &&
              std::strstr(cl_last_error(s), "traceback"),
          "runtime error carries traceback", s);
    check(run(s, "x = = 1") == CL_ERR_SYNTAX, "syntax error status");
    cl_state_close(s);
}

void test_env_isolation() {
    cl_state* s = cl_state_new(0);
    const int32_t a = cl_env_new(s);
    const int32_t b = cl_env_new(s);
    check(a > 0 && b > 0, "env creation", s);
    run(s, "counter = 1; string.extra = 7; math.pi = 3", a);
    check(eval(s, "counter == nil and string.extra == nil and math.pi > 3.14 and 1 or 0", b) == 1.0,
          "envs do not share globals or library tables", s);
    check(eval(s, "counter == nil and 1 or 0") == 1.0, "env writes do not reach shared globals", s);
    check(eval(s, "load('return counter')()", a) == 1.0, "load() defaults to the calling env", s);
    check(eval(s, "_G == _ENV and 1 or 0", a) == 1.0, "_G points at the env itself", s);
    cl_unref(s, a);
    cl_unref(s, b);
    cl_state_close(s);
}

void test_memory_cap() {
    cl_state* s = cl_state_new(256 * 1024);
    check(s != nullptr, "capped state creation");
    check(run(s, "local t = {} for i = 1, 1e7 do t[i] = string.rep('x', 64) .. i end") == CL_ERR_MEM,
          "over-cap allocation raises a memory error");
    check(cl_mem_peak(s) <= 256 * 1024, "peak stays within the cap");
    cl_gc_collect(s);
    check(eval(s, "1 + 1") == 2.0, "state still usable after a memory error", s);
    cl_state_close(s);
}

void test_budget() {
    cl_state* s = cl_state_new(0);
    cl_set_budget(s, 100000);
    check(run(s, "while true do end") == CL_ERR_BUDGET, "infinite loop stopped", s);
    check(run(s, "while true do pcall(function() while true do end end) end") == CL_ERR_BUDGET,
          "pcall cannot swallow the budget error", s);
    check(cl_last_instructions(s) < 100000 + 2000, "overrun bounded by hook stride");
    check(run(s, "local x = 0 for i = 1, 1000 do x = x + i end") == CL_OK, "budget resets per call", s);
    cl_set_budget(s, 0);
    check(run(s, "local x = 0 for i = 1, 1e6 do x = x + i end") == CL_OK, "budget 0 = unlimited", s);
    cl_state_close(s);
}

void test_coroutine_cancel() {
    cl_state* s = cl_state_new(0);
    run(s,
        "closed = 0\n"
        "function job()\n"
        "  local guard <close> = setmetatable({}, {__close = function() closed = closed + 1 end})\n"
        "  local r = coroutine.yield(1)\n"
        "  return r * 2\n"
        "end");
    const int32_t fn = cl_ref_function(s, 0, "job");
    const int32_t co = cl_thread_new(s, fn);
    check(co > 0, "thread creation", s);
    double out[2];
    int32_t n = 0;
    check(cl_resume(s, co, nullptr, 0, out, 2, &n) == CL_YIELD && n == 1 && out[0] == 1.0, "yield", s);
    check(cl_thread_status(s, co) == 0, "suspended after yield");
    check(cl_thread_close(s, co) == CL_OK, "cancel", s);
    check(eval(s, "closed") == 1.0, "to-be-closed variable ran on cancel", s);
    check(cl_thread_status(s, co) == 3, "dead after cancel");
    const double arg = 21;
    check(cl_resume(s, co, &arg, 1, out, 2, &n) == CL_ERR_ARG, "cancelled thread never resumes");

    // A normal completion for contrast.
    const int32_t co2 = cl_thread_new(s, fn);
    cl_resume(s, co2, nullptr, 0, out, 2, &n);
    check(cl_resume(s, co2, &arg, 1, out, 2, &n) == CL_OK && out[0] == 42.0, "resume to completion", s);
    cl_unref(s, co);
    cl_unref(s, co2);
    cl_unref(s, fn);
    cl_state_close(s);
}

// Runs `src` while a watchdog thread interrupts it after `after`.
int32_t run_with_watchdog(cl_state* s, const char* src, std::chrono::milliseconds after) {
    std::atomic<bool> done{false};
    std::thread dog([&] {
        uint64_t seen = 0;
        auto since = std::chrono::steady_clock::now();
        while (!done.load()) {
            const uint64_t token = cl_watch_token(s);
            if (token != seen) {
                seen = token;
                since = std::chrono::steady_clock::now();
            } else if (token != 0 && std::chrono::steady_clock::now() - since > after) {
                cl_interrupt(s, token);
            }
            std::this_thread::sleep_for(std::chrono::microseconds(200));
        }
    });
    const int32_t status = run(s, src);
    done = true;
    dog.join();
    return status;
}

void test_deadline() {
    cl_state* s = cl_state_new(0);
    const auto deadline = std::chrono::milliseconds(20);
    const char* loops[] = {
        "while true do end",
        "repeat until false",
        "for i = 1, math.maxinteger do end",
        "for i = 1, 1e300, 0.5 do end",
        "for _ in function() return 1 end do end",
        "local function f() return f() end f()",
        "::top:: goto top",
        "while true do pcall(function() while true do end end) end",
        "local co = coroutine.wrap(function() while true do end end) co()",
        "while true do coroutine.resume(coroutine.create(function() while true do end end)) end",
    };
    for (const char* src : loops) {
        const auto t0 = std::chrono::steady_clock::now();
        const int32_t st = run_with_watchdog(s, src, deadline);
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t0).count();
        check(st == CL_ERR_DEADLINE && ms < 500, (std::string("deadline stops: ") + src).c_str(), s);
    }
    check(run(s, "ok = 1") == CL_OK, "state usable after an interrupt", s);
    check(run_with_watchdog(s, "local x = 0 for i = 1, 1000 do x = x + i end", deadline) == CL_OK,
          "a fast call is never interrupted", s);
    // A token from a finished call must not hit the next one.
    run(s, "y = 1");
    const uint64_t stale = 1;  // call generations start at 1; long gone
    cl_interrupt(s, stale);
    check(run(s, "local x = 0 for i = 1, 1e5 do x = x + i end") == CL_OK, "stale token ignored", s);
    cl_state_close(s);
}

int32_t host_add(int64_t user, const double* args, int32_t nargs, double* results, int32_t) {
    double sum = static_cast<double>(user);
    for (int32_t i = 0; i < nargs; ++i) {
        sum += args[i];
    }
    results[0] = sum;
    return 1;
}

int32_t host_fail(int64_t, const double*, int32_t, double*, int32_t) { return -1; }

void test_host_and_buffer() {
    cl_state* s = cl_state_new(0);
    check(cl_register_host(s, 0, "add", host_add, 100) == CL_OK, "register host", s);
    check(cl_register_host(s, 0, "fail", host_fail, 0) == CL_OK, "register failing host", s);
    check(eval(s, "add(1, 2, 3)") == 106.0, "host upcall", s);
    check(run(s, "fail()") == CL_ERR_RUN, "host failure becomes a Lua error", s);
    float data[8] = {};
    const int32_t buf = cl_bind_buffer(s, 0, "out", data, 8);
    check(buf >= 0, "bind buffer", s);
    check(run(s, "out.emit(1, 2, 3) out.emit(4) out.put(8, 9)") == CL_OK, "buffer writes", s);
    check(cl_buffer_cursor(s, buf) == 4 && data[3] == 4.0f && data[7] == 9.0f, "buffer contents");
    check(run(s, "out.emit(1, 2, 3, 4, 5)") == CL_ERR_RUN, "buffer overflow is an error", s);
    cl_buffer_reset(s, buf);
    check(cl_buffer_cursor(s, buf) == 0, "buffer reset");
    cl_state_close(s);
}

}  // namespace

int main() {
    check(cl_abi_version() == CL_ABI_VERSION, "ABI handshake");
    check(std::strncmp(cl_lua_release(), "Lua 5.5", 7) == 0, "Lua 5.5 linked");
    test_sandbox();
    test_env_isolation();
    test_memory_cap();
    test_budget();
    test_coroutine_cancel();
    test_deadline();
    test_host_and_buffer();
    if (failures == 0) {
        std::printf("lua_host: all checks passed (%s)\n", cl_lua_release());
        return EXIT_SUCCESS;
    }
    std::fprintf(stderr, "lua_host: %d failure(s)\n", failures);
    return EXIT_FAILURE;
}
