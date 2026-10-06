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
#include <vector>

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


// ── typed values (ABI 2) ──────────────────────────────────────────────────

uint8_t host_buffer[4096];

// Echoes its args back: the encoded arg bytes are exactly the encoded results.
int32_t host_echo(int64_t, const uint8_t* args, int32_t len, int32_t nargs) {
    std::memcpy(host_buffer, args, static_cast<size_t>(len));
    return nargs;
}

int32_t host_refuse(int64_t, const uint8_t*, int32_t, int32_t) {
    std::strcpy(reinterpret_cast<char*>(host_buffer), "refused by host");
    return -1;
}

struct Enc {
    std::vector<uint8_t> b;
    void tag(uint8_t t) { b.push_back(t); }
    void u32(uint32_t v) { b.insert(b.end(), reinterpret_cast<uint8_t*>(&v), reinterpret_cast<uint8_t*>(&v) + 4); }
    void i64(int64_t v) {
        tag(CL_TAG_INTEGER);
        b.insert(b.end(), reinterpret_cast<uint8_t*>(&v), reinterpret_cast<uint8_t*>(&v) + 8);
    }
    void str(const char* v) {
        tag(CL_TAG_STRING);
        u32(static_cast<uint32_t>(std::strlen(v)));
        b.insert(b.end(), v, v + std::strlen(v));
    }
    void ref(int32_t r) {
        tag(CL_TAG_REF);
        b.insert(b.end(), reinterpret_cast<uint8_t*>(&r), reinterpret_cast<uint8_t*>(&r) + 4);
    }
};

void test_values() {
    cl_state* s = cl_state_new(0);
    cl_set_host_buffer(s, host_buffer, sizeof host_buffer);
    check(cl_register_host_v(s, 0, "echo", host_echo, 0) == CL_OK, "register value host", s);
    check(cl_register_host_v(s, 0, "refuse", host_refuse, 0) == CL_OK, "register refusing host", s);
    check(eval(s,
               "(function() local a, b, c, d, e, f, g, h = echo(nil, true, 3, 2.5, 'hi', {1, 2, {3}}, "
               "{x = 1, y = {z = 'q'}}, {})\n"
               "return a == nil and b == true and math.type(c) == 'integer' and c == 3 and d == 2.5 and e == 'hi'"
               " and #f == 3 and f[3][1] == 3 and g.x == 1 and g.y.z == 'q' and next(h) == nil end)() and 1 or 0") == 1.0,
          "values round-trip through a host function", s);
    check(eval(s, "select('#', echo(1, nil, nil)) == 3 and 1 or 0") == 1.0, "trailing nils keep their count", s);
    check(eval(s, "echo('a\\0b') == 'a\\0b' and 1 or 0") == 1.0, "strings are byte-exact", s);
    check(run(s, "echo(function() end)") == CL_ERR_RUN && std::strstr(cl_last_error(s), "function"),
          "functions cannot cross to the host", s);
    check(run(s, "echo({1, x = 2})") == CL_ERR_RUN && std::strstr(cl_last_error(s), "1..n"),
          "mixed tables are refused", s);
    check(eval(s, "(function() local t = echo({[2] = 'hole', [3] = 'x'}) return (t[1] == nil and t[2] == 'hole' "
                  "and t[3] == 'x') and 1 or 0 end)()") == 1.0,
          "dense integer keys with holes are arrays", s);
    check(run(s, "echo({[100] = 1})") == CL_ERR_RUN, "sparse integer keys are refused", s);
    check(run(s, "local t = {} t.self = t echo(t)") == CL_ERR_RUN && std::strstr(cl_last_error(s), "cyclic"),
          "cycles are refused", s);
    check(run(s, "refuse()") == CL_ERR_RUN && std::strstr(cl_last_error(s), "refused by host"),
          "host errors carry the host's message", s);

    run(s, "function sum(t, k) local n = 0 for _, v in ipairs(t) do n = n + v end return n, k, {ok = true} end\n"
           "function apply(f, x) return f(x) end\n"
           "function twice(x) return x * 2 end");
    Enc in;
    in.tag(CL_TAG_ARRAY);
    in.u32(3);
    in.i64(1);
    in.i64(2);
    in.i64(3);
    in.str("key");
    const int32_t sum = cl_ref_function(s, 0, "sum");
    const uint8_t* out = nullptr;
    int32_t out_len = 0;
    int32_t count = 0;
    check(cl_call_v(s, sum, in.b.data(), static_cast<int32_t>(in.b.size()), 2, 8, &out, &out_len, &count) == CL_OK,
          "value call", s);
    Enc expect;
    expect.i64(6);
    expect.str("key");
    expect.tag(CL_TAG_MAP);
    expect.u32(1);
    expect.str("ok");
    expect.tag(CL_TAG_TRUE);
    check(count == 3 && out_len == static_cast<int32_t>(expect.b.size()) &&
              std::memcmp(out, expect.b.data(), expect.b.size()) == 0,
          "value call results are encoded");
    check(cl_call_v(s, sum, in.b.data(), static_cast<int32_t>(in.b.size()), 2, 1, &out, &out_len, &count) == CL_OK &&
              count == 1,
          "max_results truncates", s);

    const int32_t twice = cl_ref_function(s, 0, "twice");
    const int32_t apply = cl_ref_function(s, 0, "apply");
    Enc refs;
    refs.ref(twice);
    refs.i64(21);
    check(cl_call_v(s, apply, refs.b.data(), static_cast<int32_t>(refs.b.size()), 2, 1, &out, &out_len, &count) ==
                  CL_OK &&
              count == 1 && out[0] == CL_TAG_INTEGER,
          "refs pass registry values", s);

    const uint8_t bad[] = {0x42};
    check(cl_call_v(s, sum, bad, 1, 1, 1, &out, &out_len, &count) == CL_ERR_RUN &&
              std::strstr(cl_last_error(s), "malformed"),
          "malformed args are an error, not a crash", s);
    const uint8_t truncated[] = {CL_TAG_STRING, 0xff, 0xff, 0xff, 0x7f, 'x'};
    check(cl_call_v(s, sum, truncated, sizeof truncated, 1, 1, &out, &out_len, &count) == CL_ERR_RUN,
          "a length beyond the buffer is malformed", s);
    run(s, "function give_fn() return print end");
    const int32_t give = cl_ref_function(s, 0, "give_fn");
    check(cl_call_v(s, give, nullptr, 0, 0, 1, &out, &out_len, &count) == CL_ERR_RUN,
          "unencodable results are an error", s);

    float data[4] = {};
    cl_bind_buffer(s, 0, "draw", data, 4);
    check(eval(s, "(function() draw.emit(1, 2) local c = draw.cursor() draw.reset() return c * 10 + draw.cursor() end)()")
              == 20.0,
          "buffer cursor/reset from Lua", s);
    for (int32_t r : {sum, twice, apply, give}) {
        cl_unref(s, r);
    }
    cl_state_close(s);

    cl_state* capped = cl_state_new(256 * 1024);
    run(capped, "function id(x) return #x end");
    std::vector<uint8_t> big;
    big.push_back(CL_TAG_STRING);
    const uint32_t n = 1024 * 1024;
    big.insert(big.end(), reinterpret_cast<const uint8_t*>(&n), reinterpret_cast<const uint8_t*>(&n) + 4);
    big.resize(big.size() + n, 'x');
    const int32_t id = cl_ref_function(capped, 0, "id");
    check(cl_call_v(capped, id, big.data(), static_cast<int32_t>(big.size()), 1, 1, &out, &out_len, &count) ==
              CL_ERR_MEM,
          "an over-cap argument is a memory error", capped);
    check(eval(capped, "1 + 1") == 2.0, "state usable after an over-cap argument", capped);
    cl_unref(capped, id);
    cl_state_close(capped);
}

cl_state* reenter_state = nullptr;
int32_t reenter_fn = 0;
uint64_t token_before = 0;
uint64_t token_after = 0;

// A host function that calls back into its own state, like a component signal does.
int32_t host_reenter(int64_t, const uint8_t*, int32_t, int32_t) {
    token_before = cl_watch_token(reenter_state);
    const uint8_t* out = nullptr;
    int32_t len = 0;
    int32_t count = 0;
    const int32_t st = cl_call_v(reenter_state, reenter_fn, nullptr, 0, 0, 1, &out, &len, &count);
    token_after = cl_watch_token(reenter_state);
    if (st != CL_OK) {
        std::snprintf(reinterpret_cast<char*>(host_buffer), sizeof host_buffer, "nested status %d", st);
        return -1;
    }
    std::memcpy(host_buffer, out, static_cast<size_t>(len));
    return count;
}

void test_reentrant_calls() {
    cl_state* s = cl_state_new(0);
    reenter_state = s;
    cl_set_host_buffer(s, host_buffer, sizeof host_buffer);
    cl_register_host_v(s, 0, "reenter", host_reenter, 0);
    run(s, "function inner() return 41 end");
    reenter_fn = cl_ref_function(s, 0, "inner");
    check(eval(s, "reenter() + 1") == 42.0, "host function re-enters the state", s);
    check(token_before != 0 && token_before == token_after, "nested calls keep the outer watch token");
    check(cl_watch_token(s) == 0, "idle after the outer call");

    // A runaway nested call stops the whole outer call with the deadline status.
    cl_unref(s, reenter_fn);
    run(s, "function inner() while true do end end");
    reenter_fn = cl_ref_function(s, 0, "inner");
    check(run_with_watchdog(s, "reenter()", std::chrono::milliseconds(20)) == CL_ERR_DEADLINE,
          "a deadline inside a nested call ends the outer call", s);
    check(eval(s, "1 + 1") == 2.0, "usable after a nested deadline", s);
    cl_unref(s, reenter_fn);
    cl_state_close(s);
}

void test_traceback_helper() {
    cl_state* s = cl_state_new(0);
    check(eval(s,
               "(function() local co = coroutine.create(function() local x = nil; return x.y end)\n"
               "local ok, err = coroutine.resume(co)\n"
               "local tb = __cenda_traceback(co, err)\n"
               "return (not ok and tb:find('stack traceback', 1, true) and tb:find('test:1', 1, true)) and 1 or 0 end)()")
              == 1.0,
          "coroutine traceback helper", s);
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
    test_values();
    test_traceback_helper();
    test_reentrant_calls();
    if (failures == 0) {
        std::printf("lua_host: all checks passed (%s)\n", cl_lua_release());
        return EXIT_SUCCESS;
    }
    std::fprintf(stderr, "lua_host: %d failure(s)\n", failures);
    return EXIT_FAILURE;
}
