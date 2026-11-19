#include "stages/cells/hydro_host.hpp"

#include "stages/cells/np_tanh.hpp"
#include "stages/common/util.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <string>

// kernels/csrc/hydro.cpp (compiled into this library; the functions kernels/hydro.py calls).
extern "C" {
void tgm_route(const double* z, const uint8_t* inv, int64_t h, int64_t w, const double* eps_field, double eps,
               double* filled, int64_t* recv);
int64_t tgm_flow_acc(const int64_t* recv, const uint8_t* valid, int64_t n, double* acc);
void tgm_d8_class(const int64_t* recv, int64_t h, int64_t w, int64_t* d8c);
void tgm_break_seam_cycles(int64_t* recv, const int64_t* owner, int64_t n);
}

namespace sbtg::cells {

namespace {
// np.maximum / np.minimum (NaN-propagating; the first operand on ties)
inline double np_max(double a, double b) { return std::isnan(a) ? a : std::isnan(b) ? b : (a >= b ? a : b); }
inline double np_min(double a, double b) { return std::isnan(a) ? a : std::isnan(b) ? b : (a <= b ? a : b); }
} // namespace

Route route_window(const float* height, const float* precip, const float* flat, int64_t h, int64_t w,
                   double default_epsilon) {
    const int64_t n = h * w;
    Route r;
    r.h = h;
    r.w = w;
    std::vector<double> z(static_cast<size_t>(n)), eps(static_cast<size_t>(n)), filled(static_cast<size_t>(n));
    r.invalid.resize(static_cast<size_t>(n));
    r.weights.resize(static_cast<size_t>(n));
    r.recv.resize(static_cast<size_t>(n));
    for (int64_t k = 0; k < n; ++k) {
        const size_t s = static_cast<size_t>(k);
        const double zk = static_cast<double>(height[s]);
        z[s] = zk;
        r.invalid[s] = (std::isnan(zk) || zk <= 0.0) ? 1 : 0;            // fill.ocean_mask
        // DEFAULT_EPSILON * (1.0 + 0.9 * np.tanh(flat)), then np.maximum(., 1e-9) (kernels/hydro._eps_field)
        const double u = np::tanh(static_cast<double>(flat[s]));
        eps[s] = np_max(default_epsilon * (1.0 + 0.9 * u), 1e-9);
        // np.clip(precip, 0.0, None) / 1000.0
        r.weights[s] = np_max(static_cast<double>(precip[s]), 0.0) / 1000.0;
    }
    tgm_route(z.data(), r.invalid.data(), h, w, eps.data(), default_epsilon, filled.data(), r.recv.data());
    return r;
}

Route stitch_routes(int64_t wi, int64_t wj, int64_t ext, int64_t core, int64_t apron,
                    const std::function<const Route&(int64_t, int64_t)>& fetch) {
    const int64_t n = ext;
    const int64_t ci0 = wi * core - apron, cj0 = wj * core - apron;
    Route out;
    out.h = out.w = n;
    out.recv.assign(static_cast<size_t>(n * n), -1);
    out.invalid.assign(static_cast<size_t>(n * n), 0);
    out.weights.assign(static_cast<size_t>(n * n), 0.0);
    std::vector<int64_t> owner(static_cast<size_t>(n * n), 0);
    for (int64_t oi = floordiv(ci0, core); oi <= floordiv(ci0 + n - 1, core); ++oi) {
        for (int64_t oj = floordiv(cj0, core); oj <= floordiv(cj0 + n - 1, core); ++oj) {
            const int64_t r0 = std::max(ci0, oi * core), r1 = std::min(ci0 + n, (oi + 1) * core);
            const int64_t s0 = std::max(cj0, oj * core), s1 = std::min(cj0 + n, (oj + 1) * core);
            const Route& o = fetch(oi, oj);
            if (o.h != n || o.w != n) throw std::invalid_argument("stitch_routes: owner window has the wrong size");
            const int64_t oa = oi * core - apron, ob = oj * core - apron;   // owner window's extent origin
            const int64_t own = oi * 1000003 + oj;
            for (int64_t r = r0; r < r1; ++r) {
                for (int64_t s = s0; s < s1; ++s) {
                    const size_t src = static_cast<size_t>((r - oa) * n + (s - ob));
                    const size_t dst = static_cast<size_t>((r - ci0) * n + (s - cj0));
                    const int64_t rr = o.recv[src];
                    int64_t flat = -1;
                    if (rr >= 0) {
                        const int64_t ta = rr / n + oa - ci0, tb = rr % n + ob - cj0;   // receiver in this extent
                        if (ta >= 0 && ta < n && tb >= 0 && tb < n) flat = ta * n + tb;
                    }
                    out.recv[dst] = flat;
                    out.invalid[dst] = o.invalid[src];
                    out.weights[dst] = o.weights[src];
                    owner[dst] = own;
                }
            }
        }
    }
    tgm_break_seam_cycles(out.recv.data(), owner.data(), n * n);
    return out;
}

void accumulate_core(const Route& r, const float* inflow, double* acc, int64_t* d8) {
    const int64_t h = r.h, w = r.w, n = h * w;
    // flow_accumulation: acc = np.where(valid, weights, 0.0) with valid = ~invalid; the inflow raises inward-draining
    // ring cells to np.maximum(weight, expm1(clip(inflow, 0, 30))) first.
    for (int64_t k = 0; k < n; ++k) {
        const size_t s = static_cast<size_t>(k);
        double wv = r.weights[s];
        if (inflow != nullptr) {
            const int64_t i = k / w, j = k % w;
            const bool ring = i == 0 || i == h - 1 || j == 0 || j == w - 1;
            if (ring && r.recv[s] >= 0) {
                const double extra = std::expm1(np_min(np_max(static_cast<double>(inflow[s]), 0.0), 30.0));
                wv = np_max(wv, extra);
            }
        }
        acc[s] = r.invalid[s] ? 0.0 : wv;
    }
    std::vector<uint8_t> valid(static_cast<size_t>(n));
    int64_t n_land = 0;
    for (int64_t k = 0; k < n; ++k) {
        valid[static_cast<size_t>(k)] = r.invalid[static_cast<size_t>(k)] ? 0 : 1;
        n_land += valid[static_cast<size_t>(k)];
    }
    const int64_t processed = tgm_flow_acc(r.recv.data(), valid.data(), n, acc);
    if (processed != n_land)
        throw std::runtime_error("flow graph is not acyclic: " + std::to_string(n_land - processed) + " of " +
                                 std::to_string(n_land) + " land cells were never drained. The depression fill did "
                                 "not produce a strictly descending surface.");
    tgm_d8_class(r.recv.data(), h, w, d8);
}

HostPool& HostPool::get() {
    static HostPool pool;
    return pool;
}

HostPool::HostPool() {
    const unsigned cpus = std::max(1u, std::thread::hardware_concurrency());
    const unsigned n = std::max(2u, std::min(8u, cpus / 2));
    for (unsigned k = 0; k < n; ++k) {
        threads_.emplace_back([this] {
            for (;;) {
                std::packaged_task<void()> job;
                {
                    std::unique_lock<std::mutex> lock(mu_);
                    cv_.wait(lock, [this] { return stop_ || !q_.empty(); });
                    if (q_.empty()) return;          // stop_ and drained
                    job = std::move(q_.front());
                    q_.pop_front();
                }
                job();
            }
        });
    }
}

HostPool::~HostPool() {
    {
        std::lock_guard<std::mutex> lock(mu_);
        stop_ = true;
    }
    cv_.notify_all();
    for (auto& t : threads_)
        if (t.joinable()) t.join();
}

std::future<void> HostPool::submit(std::function<void()> job) {
    std::packaged_task<void()> task(std::move(job));
    std::future<void> f = task.get_future();
    {
        std::lock_guard<std::mutex> lock(mu_);
        q_.push_back(std::move(task));
    }
    cv_.notify_one();
    return f;
}

} // namespace sbtg::cells
