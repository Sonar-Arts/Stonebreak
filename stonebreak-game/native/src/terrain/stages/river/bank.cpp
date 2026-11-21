// "river.bank_net": river/banks.py BankNet.forward in fp32 (weights from the manifest's "river_bank", base64 fp32).
// Not bit-identical to torch (cuDNN TF32 convolutions); tests/test_native_river.py measures it.
#include "stages/river/river.hpp"

#include <mutex>

namespace sbtg::river {

namespace {
const char kBank[] =
#include "river_bank.cu.inc"
    ;
SBTG_MODULE("river_bank", kBank);

struct ConvArgs {
    const float* in1; const float* in2; const float* w; const float* b; const float* res; float* out;
    int C1, C2, H1, W1, Hin, Win, Co, Ho, Wo, stride, up, silu_in;
};
constexpr int kTH = 16, kCOG = 16, kCIC = 4;   // river_bank.cu TH / COG / CIC

std::vector<uint8_t> b64(const std::string& s) {
    static int8_t T[256];
    static std::once_flag once;
    std::call_once(once, [] {
        for (int i = 0; i < 256; ++i) T[i] = -1;
        const char* a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < 64; ++i) T[static_cast<uint8_t>(a[i])] = static_cast<int8_t>(i);
    });
    std::vector<uint8_t> out;
    out.reserve(s.size() * 3 / 4);
    uint32_t acc = 0;
    int bits = 0;
    for (char ch : s) {
        int8_t v = T[static_cast<uint8_t>(ch)];
        if (v < 0) continue;
        acc = (acc << 6) | static_cast<uint32_t>(v);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<uint8_t>((acc >> bits) & 0xFF));
        }
    }
    return out;
}

// The model's tensors, uploaded once per generator (a one-entry generator cache: freed with the generator).
struct BankNet {
    Fields p;
    std::vector<int> ch;
    int blocks = 2;
};

struct Layer {
    const float* w;
    const float* b;
    int co, ci;
};

Layer layer(const BankNet& n, const std::string& name) {
    const Tensor& w = n.p[name + ".weight"];
    const Tensor& b = n.p[name + ".bias"];
    return {w.f32(), b.f32(), static_cast<int>(w.size(0)), static_cast<int>(w.size(1))};
}

BankNet net(Gen& g) {
    if (!g.manifest.has("river_bank")) throw std::logic_error("river.bank_net: the manifest has no river_bank");
    const Json& m = g.manifest["river_bank"];
    BankNet n;
    for (const Json& c : m["channels"].items()) n.ch.push_back(c.i32());
    n.blocks = m["blocks"].i32();
    n.p = g.cache("river_bank", 1).get_or({0}, [&] {
        Fields f;
        for (const auto& [name, p] : m["params"].members()) {
            std::vector<uint8_t> bytes = b64(p["b64"].str());
            std::vector<int64_t> shape = p["shape"].i64s();
            int64_t numel = 1;
            for (int64_t s : shape) numel *= s;
            if (static_cast<int64_t>(bytes.size()) != numel * 4 || shape.empty() || shape.size() > 4)
                throw std::runtime_error("bank model: bad tensor " + name);
            Tensor t = g.gpu.upload(bytes.data(), DType::F32, {numel});
            t.ndim = static_cast<int>(shape.size());
            for (size_t k = 0; k < shape.size(); ++k) t.shape[k] = shape[k];
            f.set(name, t);
        }
        return f;
    });
    return n;
}

struct Act {
    Tensor t;
    int c = 0, h = 0, w = 0;
};

// One conv layer: out = conv3x3(maybe silu(maybe up2(in1)) ++ in2) + bias (+ res).
Act conv(Gen& g, const Layer l, const Act& in1, const Act* in2, int stride, bool up, bool silu_in,
         const float* res = nullptr) {
    const int Hin = up ? in1.h * 2 : in1.h, Win = up ? in1.w * 2 : in1.w;
    const int Ho = (Hin - 1) / stride + 1, Wo = (Win - 1) / stride + 1;
    const int C2 = in2 ? in2->c : 0;
    if (l.ci != in1.c + C2) throw std::logic_error("bank model: channel mismatch");
    Act o;
    o.c = l.co;
    o.h = Ho;
    o.w = Wo;
    o.t = g.gpu.empty(DType::F32, {l.co, Ho, Wo});
    ConvArgs a{in1.t.f32(), in2 ? in2->t.f32() : nullptr, l.w, l.b, res, o.t.f32(), in1.c, C2, in1.h, in1.w,
               Hin, Win, l.co, Ho, Wo, stride, up ? 1 : 0, silu_in ? 1 : 0};
    const int TW = stride == 1 ? 64 : 32;    // 16 column groups of 4 (stride 1) / 2 (stride 2) pixels
    const int PH = kTH * stride + 2, PW = TW * stride + 2;
    const unsigned smem = static_cast<unsigned>((kCIC * PH * PW + kCOG * kCIC * 9) * sizeof(float));
    const uint32_t tiles = static_cast<uint32_t>(((Ho + kTH - 1) / kTH) * ((Wo + TW - 1) / TW));
    g.gpu.launch_grid(g.gpu.kernel("river_bank", stride == 1 ? "rb_conv3_s1" : "rb_conv3_s2"), tiles,
                      static_cast<uint32_t>((l.co + kCOG - 1) / kCOG), 1, 256, a, smem);
    return o;
}

// _Res: x + b(silu(a(silu(x))))
Act res_block(Gen& g, const BankNet& n, const std::string& p, const Act& x) {
    Act t = conv(g, layer(n, p + ".a"), x, nullptr, 1, false, true);
    return conv(g, layer(n, p + ".b"), t, nullptr, 1, false, true, x.t.f32());
}

Fields bank_net(Gen& g, const Args& a) {
    need(a, 0, 0, 1, "river.bank_net");
    const Tensor& x = a.t[0];
    x.check(DType::F32);
    if (x.ndim != 4 || x.size(0) != 1) throw std::invalid_argument("river.bank_net: x must be (1, C, H, W)");
    const BankNet n = net(g);
    Act in{x, static_cast<int>(x.size(1)), static_cast<int>(x.size(2)), static_cast<int>(x.size(3))};
    Act h = conv(g, layer(n, "stem"), in, nullptr, 1, false, false);
    const size_t lv = n.ch.size() - 1;
    std::vector<Act> skips;
    for (size_t i = 0; i < lv; ++i) {
        for (int b = 0; b < n.blocks; ++b) h = res_block(g, n, "enc." + std::to_string(i) + "." + std::to_string(b), h);
        skips.push_back(h);
        h = conv(g, layer(n, "down." + std::to_string(i)), h, nullptr, 2, false, true);
    }
    for (int b = 0; b < n.blocks; ++b) h = res_block(g, n, "mid." + std::to_string(b), h);
    for (size_t r = lv; r-- > 0;) {
        h = conv(g, layer(n, "up." + std::to_string(r)), h, nullptr, 1, true, true);
        h = conv(g, layer(n, "merge." + std::to_string(r)), h, &skips[r], 1, false, false);
        for (int b = 0; b < n.blocks; ++b) h = res_block(g, n, "dec." + std::to_string(r) + "." + std::to_string(b), h);
    }
    Act o = conv(g, layer(n, "out"), h, nullptr, 1, false, true, x.f32());   // x[:, 0:1] + out(silu(h))
    return Fields::of(o.t.view({1, 1, o.h, o.w}));
}
SBTG_METHOD("river.bank_net", bank_net);

} // namespace
} // namespace sbtg::river
