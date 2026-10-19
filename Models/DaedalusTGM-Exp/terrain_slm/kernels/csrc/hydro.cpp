// Drainage routing on the CPU: Priority-Flood depression fill, steepest-descent D8, level-synchronous
// flow accumulation and seam-cycle breaking. Bit-identical ports of data/hydrology/fill.py (numba),
// data/hydrology/flow.py (numpy) and generator.break_seam_cycles (numpy) -- every comparison, every
// float64 operation and every summation order is the reference's, so rivers do not move.
// Built with -ffp-contract=off (no FMA contraction) and without fast-math. Loaded by kernels/hydro.py.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <vector>

#define TGM_EXPORT extern "C" __attribute__((visibility("default")))

namespace {

// ------------------------------------------------------------------------------- priority flood
// The reference pops cells in increasing (key, ord) order, ord = insertion counter (unique), so any
// correct priority queue reproduces its pop sequence exactly. Every key pushed after seeding is >= the
// key just popped (elev + eps with eps >= 0, or a neighbour above elev): a monotone queue, served by a
// radix heap whose buckets keep insertion order (FIFO among equal keys = ascending ord). Degenerate
// inputs that break monotonicity (NaN or negative epsilon) use a replica of the reference's binary heap
// (same sift code), which also reproduces its behaviour on NaN keys.

// Order-preserving double -> uint64 (-0.0 folded onto +0.0: they compare equal in the reference).
inline uint64_t okey(double d) {
    if (d == 0.0) d = 0.0;
    uint64_t u;
    std::memcpy(&u, &d, 8);
    return (u >> 63) ? ~u : (u | 0x8000000000000000ULL);
}

inline double okey_inv(uint64_t u) {
    u = (u >> 63) ? (u & 0x7FFFFFFFFFFFFFFFULL) : ~u;
    double d;
    std::memcpy(&d, &u, 8);
    return d;
}

struct RadixHeap {
    struct Item { uint64_t key; int64_t idx; };
    std::vector<Item> b[65];
    size_t head0 = 0;
    uint64_t last = 0;
    uint64_t used = 0;   // bit i - 1 set: bucket i (1..64) non-empty
    size_t n = 0;
    static int bucket(uint64_t k, uint64_t last) { return k == last ? 0 : 64 - __builtin_clzll(k ^ last); }
    void push(double val, int64_t idx) {
        uint64_t k = okey(val);
        int bi = bucket(k, last);
        b[bi].push_back({k, idx});
        if (bi) used |= 1ULL << (bi - 1);
        ++n;
    }
    // Pops the minimum (key, insertion order); returns false when empty. The popped value is the
    // key's double (-0.0 comes back as +0.0, which the flood treats identically).
    bool pop(double& val, int64_t& idx) {
        if (n == 0) return false;
        if (head0 == b[0].size()) {
            b[0].clear();
            head0 = 0;
            int i = __builtin_ctzll(used) + 1;
            uint64_t m = b[i][0].key;
            for (const Item& it : b[i]) m = it.key < m ? it.key : m;
            last = m;
            used &= ~(1ULL << (i - 1));
            for (const Item& it : b[i]) {   // stable: insertion order kept; all land in buckets < i
                int bi = bucket(it.key, last);
                b[bi].push_back(it);
                if (bi) used |= 1ULL << (bi - 1);
            }
            b[i].clear();
        }
        const Item& it = b[0][head0++];
        val = okey_inv(it.key);
        idx = it.idx;
        --n;
        return true;
    }
};

// Replica of fill.py's numba binary heap (_sift_up / _sift_down and the swap-with-last pop).
struct RefHeap {
    std::vector<double> keys;
    std::vector<int64_t> ords, idxs;
    int64_t n = 0;
    explicit RefHeap(size_t cap) : keys(cap), ords(cap), idxs(cap) {}
    void swap(int64_t a, int64_t c) {
        std::swap(keys[a], keys[c]);
        std::swap(ords[a], ords[c]);
        std::swap(idxs[a], idxs[c]);
    }
    void push(double key, int64_t ord, int64_t idx) {
        keys[n] = key; ords[n] = ord; idxs[n] = idx;
        int64_t i = n++;
        while (i > 0) {
            int64_t p = (i - 1) >> 1;
            if (keys[p] > keys[i] || (keys[p] == keys[i] && ords[p] > ords[i])) { swap(p, i); i = p; }
            else break;
        }
    }
    bool pop(double& val, int64_t& idx) {
        if (n == 0) return false;
        val = keys[0];
        idx = idxs[0];
        --n;
        swap(0, n);
        int64_t i = 0;
        for (;;) {
            int64_t l = 2 * i + 1, r = l + 1, m = i;
            if (l < n && (keys[l] < keys[m] || (keys[l] == keys[m] && ords[l] < ords[m]))) m = l;
            if (r < n && (keys[r] < keys[m] || (keys[r] == keys[m] && ords[r] < ords[m]))) m = r;
            if (m == i) break;
            swap(m, i);
            i = m;
        }
        return true;
    }
};

template <class Q, class Push>
void flood(const double* zf, const uint8_t* inv, int64_t h, int64_t w, const double* eps_field, double eps,
           double* ff, Q& q, Push push) {
    const int64_t n = h * w;
    std::vector<uint8_t> taken(inv, inv + n);   // invalid or visited: never pushed (again)
    int64_t counter = 0;
    for (int64_t i = 0; i < h; ++i) {
        for (int64_t j = 0; j < w; ++j) {
            int64_t k = i * w + j;
            if (inv[k]) continue;
            bool outlet = i == 0 || i == h - 1 || j == 0 || j == w - 1;
            if (!outlet) {
                for (int di = -1; di <= 1 && !outlet; ++di)
                    for (int dj = -1; dj <= 1; ++dj) {
                        if (di == 0 && dj == 0) continue;
                        if (inv[(i + di) * w + (j + dj)]) { outlet = true; break; }
                    }
            }
            if (outlet) {
                push(q, zf[k], counter++, k);
                taken[k] = 1;
            }
        }
    }
    double elev;
    int64_t k;
    while (q.pop(elev, k)) {
        const int64_t i = k / w, j = k - (k / w) * w;
        for (int di = -1; di <= 1; ++di) {
            const int64_t ni = i + di;
            if (ni < 0 || ni >= h) continue;
            for (int dj = -1; dj <= 1; ++dj) {
                if (di == 0 && dj == 0) continue;
                const int64_t nj = j + dj;
                if (nj < 0 || nj >= w) continue;
                const int64_t nk = ni * w + nj;
                if (taken[nk]) continue;
                if (zf[nk] <= elev)
                    ff[nk] = elev + (eps_field ? eps_field[nk] : eps);
                else
                    ff[nk] = zf[nk];
                push(q, ff[nk], counter++, nk);
                taken[nk] = 1;
            }
        }
    }
}

// fill_depressions(z, invalid, epsilon | epsilon_field) without max_raise:
//   z_safe = where(isnan(z), 0, z); flood; filled[invalid] = z[invalid].
void priority_flood(const double* z, const uint8_t* inv, int64_t h, int64_t w, const double* eps_field,
                    double eps, double* ff) {
    const int64_t n = h * w;
    std::vector<double> zf(n);
    for (int64_t k = 0; k < n; ++k) {
        double v = z[k];
        zf[k] = std::isnan(v) ? 0.0 : v;
        ff[k] = zf[k];
    }
    bool monotone = eps_field ? true : (eps >= 0.0);
    if (eps_field)
        for (int64_t k = 0; k < n && monotone; ++k) monotone = eps_field[k] >= 0.0;   // false for NaN too
    if (monotone) {
        RadixHeap q;
        flood(zf.data(), inv, h, w, eps_field, eps, ff, q,
              [](RadixHeap& q, double v, int64_t, int64_t idx) { q.push(v, idx); });
    } else {
        RefHeap q(n > 0 ? n : 1);
        flood(zf.data(), inv, h, w, eps_field, eps, ff, q,
              [](RefHeap& q, double v, int64_t ord, int64_t idx) { q.push(v, ord, idx); });
    }
    for (int64_t k = 0; k < n; ++k)
        if (inv[k]) ff[k] = z[k];
}

// ------------------------------------------------------------------------------- D8
// flow.d8_receivers: neighbour order cardinals first, strict `>` keeps the first steepest.
const int DY[8] = {-1, 1, 0, 0, -1, -1, 1, 1};
const int DX[8] = {0, 0, -1, 1, -1, 1, -1, 1};

void d8(const double* z, const uint8_t* inv, int64_t h, int64_t w, int64_t* recv) {
    const double s2 = std::sqrt(2.0);
    const double dist[8] = {1.0, 1.0, 1.0, 1.0, s2, s2, s2, s2};
    const double inf = std::numeric_limits<double>::infinity();
    for (int64_t i = 0; i < h; ++i) {
        for (int64_t j = 0; j < w; ++j) {
            const int64_t k = i * w + j;
            const double zc = z[k];
            double best = -inf;
            int bk = 0;
            bool touches = false;
            for (int d = 0; d < 8; ++d) {
                int64_t ni = i + DY[d], nj = j + DX[d];
                double nz = inf;
                bool ninv = false;
                if (ni >= 0 && ni < h && nj >= 0 && nj < w) {
                    nz = z[ni * w + nj];
                    ninv = inv[ni * w + nj] != 0;
                }
                touches |= ninv;
                double drop = zc - nz;
                double slope = (ninv || drop <= 0.0) ? -inf : drop / dist[d];
                if (slope > best) { best = slope; bk = d; }
            }
            bool terminal = touches || !std::isfinite(best) || inv[k];
            recv[k] = terminal ? -1 : (i + DY[bk]) * w + (j + DX[bk]);
        }
    }
}

}  // namespace

// fill_depressions + d8_receivers in one call (generator.route_window). `filled` is scratch/output.
TGM_EXPORT void tgm_route(const double* z, const uint8_t* inv, int64_t h, int64_t w, const double* eps_field,
                          double eps, double* filled, int64_t* recv) {
    priority_flood(z, inv, h, w, eps_field, eps, filled);
    d8(filled, inv, h, w, recv);
}

TGM_EXPORT void tgm_fill(const double* z, const uint8_t* inv, int64_t h, int64_t w, const double* eps_field,
                         double eps, double* filled) {
    priority_flood(z, inv, h, w, eps_field, eps, filled);
}

TGM_EXPORT void tgm_d8(const double* z, const uint8_t* inv, int64_t h, int64_t w, int64_t* recv) {
    d8(z, inv, h, w, recv);
}

// flow.flow_accumulation (+ topological_levels). `acc` holds the initial per-cell weights on entry
// (np.where(valid, weights, 0) / valid as float, prepared by the caller) and the accumulation on exit.
// Returns the number of drained cells (the caller compares it with the land count, as the reference).
TGM_EXPORT int64_t tgm_flow_acc(const int64_t* recv, const uint8_t* valid, int64_t n, double* acc) {
    std::vector<int64_t> indeg(n, 0);
    for (int64_t s = 0; s < n; ++s)
        if (recv[s] >= 0 && valid[s]) indeg[recv[s]]++;
    std::vector<int64_t> frontier, next, tgt;
    std::vector<double> vals;
    frontier.reserve(1024);
    for (int64_t s = 0; s < n; ++s)
        if (valid[s] && indeg[s] == 0) frontier.push_back(s);
    int64_t processed = 0;
    while (!frontier.empty()) {
        processed += (int64_t)frontier.size();
        // consumer: np.add.at(acc, tgt[live], acc[level[live]]) -- gathered first, added in order
        vals.resize(frontier.size());
        for (size_t q = 0; q < frontier.size(); ++q) vals[q] = acc[frontier[q]];
        for (size_t q = 0; q < frontier.size(); ++q) {
            int64_t t = recv[frontier[q]];
            if (t >= 0) acc[t] += vals[q];
        }
        // producer: np.subtract.at(indeg, tgt, 1); frontier = unique(tgt[indeg[tgt] == 0])
        tgt.clear();
        for (int64_t s : frontier) {
            int64_t t = recv[s];
            if (t >= 0) tgt.push_back(t);
        }
        if (tgt.empty()) break;
        next.clear();
        for (int64_t t : tgt)
            if (--indeg[t] == 0) next.push_back(t);
        size_t m = 0;
        for (size_t q = 0; q < next.size(); ++q)
            if (indeg[next[q]] == 0) next[m++] = next[q];
        next.resize(m);
        std::sort(next.begin(), next.end());
        next.erase(std::unique(next.begin(), next.end()), next.end());
        frontier.swap(next);
    }
    return processed;
}

// The D8 class of each cell (generator.accumulate_drainage): index into data.build.D8_OFFSETS of
// (recv row - row, recv col - col), 8 where terminal (or not a neighbour step).
TGM_EXPORT void tgm_d8_class(const int64_t* recv, int64_t h, int64_t w, int64_t* d8c) {
    static const int OFF[8][2] = {{-1, 0}, {-1, 1}, {0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}};
    const int64_t n = h * w;
    for (int64_t s = 0; s < n; ++s) {
        int64_t r = recv[s];
        int64_t c = 8;
        if (r >= 0) {
            int64_t dr = r / w - s / w, dc = r % w - s % w;
            for (int k = 0; k < 8; ++k)
                if (dr == OFF[k][0] && dc == OFF[k][1]) { c = k; break; }
        }
        d8c[s] = c;
    }
}

// generator.break_seam_cycles: up to 4 rounds of Kahn's algorithm over all live edges; cells it cannot
// drain whose edge crosses an owner boundary are cut. The drained set is order-independent. In place.
TGM_EXPORT void tgm_break_seam_cycles(int64_t* recv, const int64_t* owner, int64_t n) {
    std::vector<int64_t> indeg(n), stack;
    std::vector<uint8_t> done(n);
    std::vector<int64_t> cut;
    for (int round = 0; round < 4; ++round) {
        std::fill(indeg.begin(), indeg.end(), 0);
        std::fill(done.begin(), done.end(), 0);
        for (int64_t s = 0; s < n; ++s)
            if (recv[s] >= 0) indeg[recv[s]]++;
        stack.clear();
        for (int64_t s = 0; s < n; ++s)
            if (indeg[s] == 0) stack.push_back(s);
        while (!stack.empty()) {
            int64_t s = stack.back();
            stack.pop_back();
            done[s] = 1;
            int64_t t = recv[s];
            if (t >= 0 && --indeg[t] == 0) stack.push_back(t);
        }
        cut.clear();
        bool stuck = false;
        for (int64_t s = 0; s < n; ++s) {
            if (done[s]) continue;
            stuck = true;
            int64_t t = recv[s];
            if (t >= 0 && owner[s] != owner[t]) cut.push_back(s);
        }
        if (!stuck) return;
        for (int64_t s : cut) recv[s] = -1;
    }
}
