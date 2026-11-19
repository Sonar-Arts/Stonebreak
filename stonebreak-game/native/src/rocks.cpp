/* Rock shapes: procedural source, SBRB bank parser / baker and the process-wide bank registry (rocks.hpp). */
#include "rocks.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <map>
#include <mutex>

namespace sbw {

namespace {

constexpr double TAU = 6.283185307179586;

uint64_t splitmix(uint64_t& s) {
    s += UINT64_C(0x9E3779B97F4A7C15);
    uint64_t z = s;
    z = (z ^ (z >> 30)) * UINT64_C(0xBF58476D1CE4E5B9);
    z = (z ^ (z >> 27)) * UINT64_C(0x94D049BB133111EB);
    return z ^ (z >> 31);
}

struct Rng {
    uint64_t s;
    uint64_t next() { return splitmix(s); }
    double uni() { return static_cast<double>(next() >> 11) * 0x1.0p-53; }
    double range(double a, double b) { return a + (b - a) * uni(); }
};

uint64_t hash4(uint64_t seed, int x, int y, int z) {
    uint64_t s = seed ^ (static_cast<uint64_t>(static_cast<uint32_t>(x)) * UINT64_C(0x9E3779B97F4A7C15))
                 ^ (static_cast<uint64_t>(static_cast<uint32_t>(y)) * UINT64_C(0xC2B2AE3D27D4EB4F))
                 ^ (static_cast<uint64_t>(static_cast<uint32_t>(z)) * UINT64_C(0x165667B19E3779F9));
    return splitmix(s);
}

double unit(uint64_t h) { return static_cast<double>(h >> 11) * 0x1.0p-53; }

/* largest 6-connected component, trimmed to its bounding box (bottom row = lowest occupied row) */
RockShape keepLargestAndTrim(const RockShape& in) {
    const int n = in.dx * in.dy * in.dz;
    std::vector<int> comp(static_cast<size_t>(n), -1);
    std::vector<int> sizes;
    std::vector<int> stack;
    for (int i = 0; i < n; i++) {
        if (in.occ[static_cast<size_t>(i)] == 0 || comp[static_cast<size_t>(i)] >= 0) continue;
        const int id = static_cast<int>(sizes.size());
        int size = 0;
        stack.push_back(i);
        comp[static_cast<size_t>(i)] = id;
        while (!stack.empty()) {
            const int c = stack.back();
            stack.pop_back();
            size++;
            const int x = c % in.dx, z = (c / in.dx) % in.dz, y = c / (in.dx * in.dz);
            const int nb[6][3] = {{x + 1, y, z}, {x - 1, y, z}, {x, y + 1, z}, {x, y - 1, z}, {x, y, z + 1}, {x, y, z - 1}};
            for (const auto& p : nb) {
                if (p[0] < 0 || p[0] >= in.dx || p[1] < 0 || p[1] >= in.dy || p[2] < 0 || p[2] >= in.dz) continue;
                const int j = in.index(p[0], p[1], p[2]);
                if (in.occ[static_cast<size_t>(j)] == 0 || comp[static_cast<size_t>(j)] >= 0) continue;
                comp[static_cast<size_t>(j)] = id;
                stack.push_back(j);
            }
        }
        sizes.push_back(size);
    }
    RockShape out;
    out.cls = in.cls;
    out.flags = in.flags;
    if (sizes.empty()) {
        out.occ = {1};
        if (!in.hint.empty()) out.hint = {HINT_BODY};
        return out;
    }
    const int best = static_cast<int>(std::max_element(sizes.begin(), sizes.end()) - sizes.begin());
    int x0 = in.dx, x1 = -1, y0 = in.dy, y1 = -1, z0 = in.dz, z1 = -1;
    for (int i = 0; i < n; i++) {
        if (comp[static_cast<size_t>(i)] != best) continue;
        const int x = i % in.dx, z = (i / in.dx) % in.dz, y = i / (in.dx * in.dz);
        x0 = std::min(x0, x); x1 = std::max(x1, x);
        y0 = std::min(y0, y); y1 = std::max(y1, y);
        z0 = std::min(z0, z); z1 = std::max(z1, z);
    }
    out.dx = static_cast<uint8_t>(x1 - x0 + 1);
    out.dy = static_cast<uint8_t>(y1 - y0 + 1);
    out.dz = static_cast<uint8_t>(z1 - z0 + 1);
    const size_t m = static_cast<size_t>(out.dx) * out.dy * out.dz;
    out.occ.assign(m, 0);
    if (!in.hint.empty()) out.hint.assign(m, HINT_BODY);
    for (int y = y0; y <= y1; y++)
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                const int i = in.index(x, y, z);
                if (comp[static_cast<size_t>(i)] != best) continue;
                const auto j = static_cast<size_t>(out.index(x - x0, y - y0, z - z0));
                out.occ[j] = 1;
                if (!in.hint.empty()) out.hint[j] = in.hint[static_cast<size_t>(i)];
            }
    out.sink = static_cast<uint8_t>(std::min<int>(in.sink, out.dy - 1));
    return out;
}

RockShape pebble(Rng& r) {
    RockShape s;
    s.cls = ROCK_PEBBLE;
    switch (r.next() % 4) {
        case 0:
            s.occ = {1};
            break;
        case 1:
            s.dx = 2;
            s.occ = {1, 1};
            break;
        case 2:
            s.dx = 2;
            s.dz = 2;
            s.occ = {1, 1, 1, 1};
            s.occ[static_cast<size_t>(r.next() % 4)] = 0;
            break;
        default:
            s.dx = 2;
            s.dy = 2;
            s.dz = 2;
            s.occ = {1, 1, 1, 1, 0, 0, 0, 0};
            s.occ[static_cast<size_t>(4 + r.next() % 4)] = 1;
            s.sink = 1;
            break;
    }
    return s;
}

void putU16(std::vector<uint8_t>& o, uint16_t v) {
    o.push_back(static_cast<uint8_t>(v & 0xFF));
    o.push_back(static_cast<uint8_t>(v >> 8));
}
void putU32(std::vector<uint8_t>& o, uint32_t v) {
    for (int i = 0; i < 4; i++) o.push_back(static_cast<uint8_t>((v >> (8 * i)) & 0xFF));
}
void putU64(std::vector<uint8_t>& o, uint64_t v) {
    for (int i = 0; i < 8; i++) o.push_back(static_cast<uint8_t>((v >> (8 * i)) & 0xFF));
}
uint32_t getU32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | static_cast<uint32_t>(p[1]) << 8 | static_cast<uint32_t>(p[2]) << 16
           | static_cast<uint32_t>(p[3]) << 24;
}
uint16_t getU16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | p[1] << 8); }
uint64_t getU64(const uint8_t* p) {
    return static_cast<uint64_t>(getU32(p)) | static_cast<uint64_t>(getU32(p + 4)) << 32;
}

uint64_t fnv1a(const uint8_t* p, size_t n) {
    uint64_t h = UINT64_C(0xcbf29ce484222325);
    for (size_t i = 0; i < n; i++) {
        h ^= p[i];
        h *= UINT64_C(0x100000001b3);
    }
    return h;
}

bool connectedOneComponent(const RockShape& s) {
    const RockShape t = keepLargestAndTrim(s);
    return t.volume() == s.volume();
}

} // namespace

int RockShape::volume() const {
    int v = 0;
    for (uint8_t o : occ) v += o != 0;
    return v;
}

size_t RockBank::count() const {
    size_t n = 0;
    for (const auto& v : byClass) n += v.size();
    return n;
}

RockShape proceduralRock(int cls, uint64_t seed) {
    Rng r{seed ^ (static_cast<uint64_t>(cls + 1) * UINT64_C(0xD1B54A32D192ED03))};
    if (cls <= ROCK_PEBBLE) return pebble(r);
    double rad;
    double flatLo = 0.5, flatHi = 0.9;
    switch (cls) {
        case ROCK_SMALL: rad = r.range(0.9, 1.45); break;
        case ROCK_MEDIUM: rad = r.range(1.8, 2.5); break;
        case ROCK_LARGE: rad = r.range(2.8, 3.8); break;
        default: rad = r.range(4.2, 5.6); flatLo = 0.6; flatHi = 0.95; break;
    }
    const double ax = std::min(6.0, rad * r.range(0.85, 1.2));
    const double az = std::min(6.0, rad * r.range(0.8, 1.1));
    const double ay = rad * r.range(flatLo, flatHi);
    const double yaw = r.range(0.0, TAU);
    const double cy = std::cos(yaw), sy = std::sin(yaw);
    /* low-frequency lobes: f(dir) = 1 + sum a_k (dir . u_k)^3 */
    constexpr int LOBES = 5;
    std::array<std::array<double, 3>, LOBES> u{};
    std::array<double, LOBES> a{};
    double asum = 0.0;
    for (int k = 0; k < LOBES; k++) {
        const double th = r.range(0.0, TAU), zz = r.range(-1.0, 1.0), rr = std::sqrt(1.0 - zz * zz);
        u[static_cast<size_t>(k)] = {rr * std::cos(th), zz, rr * std::sin(th)};
        a[static_cast<size_t>(k)] = r.range(-0.22, 0.3);
        asum += std::fabs(a[static_cast<size_t>(k)]);
    }
    if (asum > 0.65) for (double& v : a) v *= 0.65 / asum;
    const double rough = cls == ROCK_SMALL ? 0.06 : 0.09;
    const bool flatTop = r.uni() < 0.3;
    const double cut = r.range(0.3, 0.65);
    const int R = std::min(7, static_cast<int>(std::ceil(std::max(ax, az) * 1.25)));
    const int H = std::min(9, static_cast<int>(std::ceil(ay * 1.25)));
    RockShape s;
    s.cls = static_cast<uint8_t>(cls);
    s.dx = static_cast<uint8_t>(2 * R + 1);
    s.dz = static_cast<uint8_t>(2 * R + 1);
    s.dy = static_cast<uint8_t>(2 * H + 1);
    const double ccx = R + r.range(-0.3, 0.3), ccy = H + r.range(-0.3, 0.3), ccz = R + r.range(-0.3, 0.3);
    const size_t n = static_cast<size_t>(s.dx) * s.dy * s.dz;
    s.occ.assign(n, 0);
    s.hint.assign(n, HINT_BODY);
    const uint64_t vseed = r.next();
    for (int y = 0; y < s.dy; y++) {
        for (int z = 0; z < s.dz; z++) {
            for (int x = 0; x < s.dx; x++) {
                const double px = x - ccx, py = y - ccy, pz = z - ccz;
                if (flatTop && py > ay * cut) continue;
                const double qu = (px * cy + pz * sy) / ax, qw = (-px * sy + pz * cy) / az, qv = py / ay;
                const double len = std::sqrt(qu * qu + qv * qv + qw * qw);
                bool in;
                if (len < 1e-9) {
                    in = true;
                } else {
                    /* the lobe directions live in the un-normalised frame (shape-space) */
                    const double du = qu / len, dv = qv / len, dw = qw / len;
                    double f = 1.0;
                    for (int k = 0; k < LOBES; k++) {
                        const auto& uk = u[static_cast<size_t>(k)];
                        const double d = du * uk[0] + dv * uk[1] + dw * uk[2];
                        f += a[static_cast<size_t>(k)] * d * d * d;
                    }
                    const double j = (unit(hash4(vseed, x, y, z)) * 2.0 - 1.0) * rough;
                    in = len <= f + j;
                }
                if (!in) continue;
                const auto i = static_cast<size_t>(s.index(x, y, z));
                s.occ[i] = 1;
                const double hv = unit(hash4(vseed ^ UINT64_C(0x5151), x, y, z));
                if (hv < 0.10) s.hint[i] = HINT_ROUGH;
            }
        }
    }
    /* moss hint on the shaded (north, -z) exposed faces and in the lower crevices */
    for (int y = 0; y < s.dy; y++) {
        for (int z = 0; z < s.dz; z++) {
            for (int x = 0; x < s.dx; x++) {
                const auto i = static_cast<size_t>(s.index(x, y, z));
                if (s.occ[i] == 0 || s.hint[i] != HINT_BODY) continue;
                const bool northOpen = z == 0 || !s.at(x, y, z - 1);
                if (northOpen && unit(hash4(vseed ^ UINT64_C(0x3A3A), x, y, z)) < 0.6) s.hint[i] = HINT_MOSS;
            }
        }
    }
    s.flags = ROCK_FLAG_MATERIAL | (flatTop ? ROCK_FLAG_FLAT_TOP : 0);
    RockShape t = keepLargestAndTrim(s);
    const double bury = cls == ROCK_ERRATIC ? r.range(0.08, 0.22) : r.range(0.15, 0.4);
    t.sink = static_cast<uint8_t>(std::clamp(static_cast<int>(std::lround(t.dy * bury)), 0, t.dy - 1));
    if (t.dy * 2 <= std::max(t.dx, t.dz)) t.flags |= ROCK_FLAG_SLAB;
    /* the class follows the trimmed extent (a lobe may have shrunk it) */
    return t;
}

std::vector<RockShape> placeholderShapes(int perClass, uint64_t seed) {
    std::vector<RockShape> out;
    for (int cls = ROCK_MEDIUM; cls <= ROCK_ERRATIC; cls++) {
        for (int i = 0; i < perClass; i++) {
            uint64_t s = seed ^ (static_cast<uint64_t>(cls) << 40) ^ static_cast<uint64_t>(i);
            out.push_back(proceduralRock(cls, splitmix(s)));
        }
    }
    return out;
}

std::vector<uint8_t> bakeRockBank(uint32_t bankId, const std::vector<RockShape>& shapes) {
    std::vector<uint8_t> payload;
    std::vector<uint8_t> table;
    const size_t tableEnd = 32 + 16 * shapes.size();
    bool anyMat = false;
    for (const RockShape& s : shapes) {
        const size_t n = s.occ.size();
        const size_t off = tableEnd + payload.size();
        std::vector<uint8_t> bits((n + 7) / 8, 0);
        for (size_t i = 0; i < n; i++) if (s.occ[i] != 0) bits[i >> 3] = static_cast<uint8_t>(bits[i >> 3] | (1u << (i & 7)));
        payload.insert(payload.end(), bits.begin(), bits.end());
        uint8_t flags = static_cast<uint8_t>(s.flags & ~ROCK_FLAG_MATERIAL);
        if (!s.hint.empty()) {
            flags |= ROCK_FLAG_MATERIAL;
            anyMat = true;
            std::vector<uint8_t> mat((n + 3) / 4, 0);
            for (size_t i = 0; i < n; i++) {
                const unsigned v = s.hint[i] & 3u;
                mat[i >> 2] = static_cast<uint8_t>(mat[i >> 2] | (v << (2 * (i & 3))));
            }
            payload.insert(payload.end(), mat.begin(), mat.end());
        }
        table.push_back(s.cls);
        table.push_back(s.dx);
        table.push_back(s.dy);
        table.push_back(s.dz);
        table.push_back(s.sink);
        table.push_back(flags);
        putU16(table, 0);
        putU32(table, static_cast<uint32_t>(off));
        putU32(table, static_cast<uint32_t>(tableEnd + payload.size() - off));
    }
    std::vector<uint8_t> body = table;
    body.insert(body.end(), payload.begin(), payload.end());
    std::vector<uint8_t> out;
    out.insert(out.end(), {'S', 'B', 'R', 'B'});
    putU16(out, 1);
    putU16(out, 32);
    putU32(out, static_cast<uint32_t>(shapes.size()));
    putU32(out, anyMat ? 1u : 0u);
    putU32(out, bankId);
    putU32(out, 0);
    putU64(out, fnv1a(body.data(), body.size()));
    out.insert(out.end(), body.begin(), body.end());
    return out;
}

std::shared_ptr<const RockBank> parseRockBank(const uint8_t* data, size_t len, std::string& error) {
    if (data == nullptr || len < 32 || std::memcmp(data, "SBRB", 4) != 0) {
        error = "not an SBRB rock bank";
        return nullptr;
    }
    const uint16_t version = getU16(data + 4), headerSize = getU16(data + 6);
    if (version != 1) {
        error = "unsupported SBRB version " + std::to_string(version);
        return nullptr;
    }
    if (headerSize < 32 || headerSize > len) {
        error = "bad SBRB header size";
        return nullptr;
    }
    const uint32_t count = getU32(data + 8);
    auto bank = std::make_shared<RockBank>();
    bank->id = getU32(data + 16);
    bank->hash = getU64(data + 24);
    if (fnv1a(data + headerSize, len - headerSize) != bank->hash) {
        error = "SBRB content hash mismatch";
        return nullptr;
    }
    if (count == 0 || count > 65535 || headerSize + static_cast<size_t>(count) * 16 > len) {
        error = "bad SBRB shape count";
        return nullptr;
    }
    for (uint32_t k = 0; k < count; k++) {
        const uint8_t* e = data + headerSize + static_cast<size_t>(k) * 16;
        RockShape s;
        s.cls = e[0];
        s.dx = e[1];
        s.dy = e[2];
        s.dz = e[3];
        s.sink = e[4];
        s.flags = e[5];
        const uint32_t off = getU32(e + 8), plen = getU32(e + 12);
        const std::string where = "SBRB shape " + std::to_string(k) + ": ";
        if (s.cls >= ROCK_CLASSES || s.dx < 1 || s.dx > ROCK_MAX_DXZ || s.dz < 1 || s.dz > ROCK_MAX_DXZ || s.dy < 1
                || s.dy > ROCK_MAX_DY || s.sink >= s.dy) {
            error = where + "bad class / dims / sink";
            return nullptr;
        }
        const size_t n = static_cast<size_t>(s.dx) * s.dy * s.dz;
        const bool mat = (s.flags & ROCK_FLAG_MATERIAL) != 0;
        const size_t need = (n + 7) / 8 + (mat ? (n + 3) / 4 : 0);
        if (plen < need || static_cast<size_t>(off) + plen > len || off < headerSize + static_cast<size_t>(count) * 16) {
            error = where + "payload out of range";
            return nullptr;
        }
        const uint8_t* p = data + off;
        s.occ.resize(n);
        for (size_t i = 0; i < n; i++) s.occ[i] = static_cast<uint8_t>((p[i >> 3] >> (i & 7)) & 1u);
        if (mat) {
            const uint8_t* m = p + (n + 7) / 8;
            s.hint.resize(n);
            for (size_t i = 0; i < n; i++) {
                const auto v = static_cast<uint8_t>((m[i >> 2] >> (2 * (i & 3))) & 3u);
                s.hint[i] = v == 3 ? static_cast<uint8_t>(HINT_BODY) : v;
            }
        }
        bool bottom = false;
        for (int z = 0; z < s.dz && !bottom; z++)
            for (int x = 0; x < s.dx && !bottom; x++) bottom = s.at(x, 0, z);
        if (!bottom) {
            error = where + "empty bottom row";
            return nullptr;
        }
        if (!connectedOneComponent(s)) {
            error = where + "not one 6-connected component";
            return nullptr;
        }
        bank->byClass[s.cls].push_back(std::move(s));
    }
    return bank;
}

namespace {
std::mutex& registryMutex() {
    static std::mutex m;
    return m;
}
std::map<uint32_t, std::shared_ptr<const RockBank>>& registry() {
    static std::map<uint32_t, std::shared_ptr<const RockBank>> r;
    return r;
}
} // namespace

bool registerRockBank(uint32_t id, const uint8_t* data, size_t len, std::string& error) {
    auto bank = parseRockBank(data, len, error);
    if (!bank) return false;
    if (bank->id != id) {
        error = "SBRB bank id " + std::to_string(bank->id) + " registered as " + std::to_string(id);
        return false;
    }
    std::lock_guard<std::mutex> lock(registryMutex());
    auto it = registry().find(id);
    if (it != registry().end()) {
        if (it->second->hash != bank->hash) {
            error = "a different rock bank is already registered under id " + std::to_string(id);
            return false;
        }
        return true; /* same bank again */
    }
    registry().emplace(id, std::move(bank));
    return true;
}

std::shared_ptr<const RockBank> findRockBank(uint32_t id) {
    std::lock_guard<std::mutex> lock(registryMutex());
    auto it = registry().find(id);
    return it == registry().end() ? nullptr : it->second;
}

} // namespace sbw
