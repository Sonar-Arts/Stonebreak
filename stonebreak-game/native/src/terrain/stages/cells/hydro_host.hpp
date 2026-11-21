// Host half of the generator's routed drainage (generator.route_window / _stitch_routes / break_seam_cycles /
// _accumulate_core), over kernels/csrc/hydro.cpp (compiled into this library: the same fill / D8 / accumulation the
// Python generator calls through kernels/hydro.py) with the numpy glue around it ported op for op (float64, numpy's
// own tanh). Pure functions of their inputs: safe on worker threads (HostPool).
#pragma once

#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <future>
#include <mutex>
#include <thread>
#include <vector>

namespace sbtg::cells {

// generator.route_window's result: recv (n*n) flat receiver or -1, invalid (n*n) ocean, weights (n*n) runoff.
struct Route {
    int64_t h = 0, w = 0;
    std::vector<int64_t> recv;
    std::vector<uint8_t> invalid;
    std::vector<double> weights;
};

// generator.route_window(height, precip_mm, flat_noise) from the fp32 fields (host memory, h*w each).
Route route_window(const float* height, const float* precip, const float* flat, int64_t h, int64_t w,
                   double default_epsilon);

// generator._stitch_routes for window (wi, wj): `fetch(oi, oj)` returns the owner window's route.
Route stitch_routes(int64_t wi, int64_t wj, int64_t ext, int64_t core, int64_t apron,
                    const std::function<const Route&(int64_t, int64_t)>& fetch);

// generator._accumulate_core: acc (float64) and the D8 class (int64) of each cell; `inflow` = the hydro sidecar's
// logacc (fp32, h*w) or nullptr. `acc` / `d8` hold h*w values.
void accumulate_core(const Route& r, const float* inflow, double* acc, int64_t* d8);

// Worker threads for the host work of a cold tile's windows (generator._host_pool: max(2, min(8, cpus / 2))).
class HostPool {
public:
    static HostPool& get();
    ~HostPool();
    std::future<void> submit(std::function<void()> job);

private:
    HostPool();
    std::mutex mu_;
    std::condition_variable cv_;
    std::deque<std::packaged_task<void()>> q_;
    std::vector<std::thread> threads_;
    bool stop_ = false;
};

} // namespace sbtg::cells
