#include "cenda/kernels.h"

#include <FastNoise/FastNoise.h>

namespace {

struct NodeHandle {
    FastNoise::SmartNode<> node;
};

NodeHandle* wrap(FastNoise::SmartNode<> node) {
    if (!node) {
        return nullptr;
    }
    return new NodeHandle{std::move(node)};
}

const FastNoise::Generator* generator(void* handle) {
    if (handle == nullptr) {
        return nullptr;
    }
    return static_cast<NodeHandle*>(handle)->node.get();
}

} // namespace

extern "C" {

int32_t ck_abi_version(void) {
    return CK_ABI_VERSION;
}

const char* ck_simd_level(void) {
    switch (FastSIMD::DetectCpuMaxFeatureSet()) {
        case FastSIMD::FeatureSet::SCALAR: return "Scalar";
        case FastSIMD::FeatureSet::SSE2: return "SSE2";
        case FastSIMD::FeatureSet::SSE41: return "SSE4.1";
        case FastSIMD::FeatureSet::SSE42: return "SSE4.2";
        case FastSIMD::FeatureSet::AVX: return "AVX";
        case FastSIMD::FeatureSet::AVX2: return "AVX2";
        case FastSIMD::FeatureSet::AVX512: return "AVX512";
        case FastSIMD::FeatureSet::NEON: return "NEON";
        case FastSIMD::FeatureSet::AARCH64: return "AARCH64";
        default: return "Other";
    }
}

void* ck_noise_from_encoded_tree(const char* encoded_tree) {
    if (encoded_tree == nullptr) {
        return nullptr;
    }
    return wrap(FastNoise::NewFromEncodedNodeTree(encoded_tree));
}

void* ck_noise_simplex_fbm(int32_t octaves, float lacunarity, float gain, float frequency) {
    if (octaves < 1 || !(frequency > 0.0f)) {
        return nullptr;
    }
    // v1.x generators carry their own feature scale (default 100 world units!);
    // set it from the requested frequency instead of stacking a DomainScale on
    // top, otherwise both scales apply and features become enormous.
    auto simplex = FastNoise::New<FastNoise::Simplex>();
    simplex->SetScale(1.0f / frequency);
    auto fbm = FastNoise::New<FastNoise::FractalFBm>();
    fbm->SetSource(simplex);
    fbm->SetOctaveCount(octaves);
    fbm->SetLacunarity(lacunarity);
    fbm->SetGain(gain);
    // FastNoise2's fbm sums gain-weighted octaves without normalizing, so its
    // range grows with octave count. Divide by the amplitude sum to restore
    // the ~[-1,1] contract the terrain splines / biome thresholds assume.
    float amplitudeSum = 0.0f;
    float amplitude = 1.0f;
    for (int32_t i = 0; i < octaves; ++i) {
        amplitudeSum += amplitude;
        amplitude *= gain;
    }
    auto normalized = FastNoise::New<FastNoise::Multiply>();
    normalized->SetLHS(fbm);
    normalized->SetRHS(1.0f / amplitudeSum);
    return wrap(normalized);
}

void ck_noise_destroy(void* node) {
    delete static_cast<NodeHandle*>(node);
}

int32_t ck_gen_grid_2d(void* node, float* out,
                       float x_offset, float y_offset,
                       int32_t x_count, int32_t y_count,
                       float x_step, float y_step,
                       int32_t seed) {
    const auto* gen = generator(node);
    if (gen == nullptr || out == nullptr || x_count <= 0 || y_count <= 0) {
        return 1;
    }
    gen->GenUniformGrid2D(out, x_offset, y_offset, x_count, y_count, x_step, y_step, seed);
    return 0;
}

int32_t ck_gen_grid_3d(void* node, float* out,
                       float x_offset, float y_offset, float z_offset,
                       int32_t x_count, int32_t y_count, int32_t z_count,
                       float x_step, float y_step, float z_step,
                       int32_t seed) {
    const auto* gen = generator(node);
    if (gen == nullptr || out == nullptr || x_count <= 0 || y_count <= 0 || z_count <= 0) {
        return 1;
    }
    gen->GenUniformGrid3D(out, x_offset, y_offset, z_offset, x_count, y_count, z_count,
                          x_step, y_step, z_step, seed);
    return 0;
}

} // extern "C"
