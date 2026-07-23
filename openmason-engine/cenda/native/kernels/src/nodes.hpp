/* Shared FastNoise2 node construction — the single place that encodes the
 * "frequency inside the generator's feature scale + amplitude-sum normalized
 * fbm" contract. kernels.cpp (the FFM noise ABI) and carver.cpp (terrain
 * context) must build IDENTICAL node graphs so channel values agree. */
#pragma once

#include <FastNoise/FastNoise.h>

namespace cenda {

inline FastNoise::SmartNode<> makeSimplexFbm(int32_t octaves, float lacunarity,
                                             float gain, float frequency) {
    if (octaves < 1 || !(frequency > 0.0f)) {
        return {};
    }
    auto simplex = FastNoise::New<FastNoise::Simplex>();
    simplex->SetScale(1.0f / frequency);
    auto fbm = FastNoise::New<FastNoise::FractalFBm>();
    fbm->SetSource(simplex);
    fbm->SetOctaveCount(octaves);
    fbm->SetLacunarity(lacunarity);
    fbm->SetGain(gain);
    float amplitudeSum = 0.0f;
    float amplitude = 1.0f;
    for (int32_t i = 0; i < octaves; ++i) {
        amplitudeSum += amplitude;
        amplitude *= gain;
    }
    auto normalized = FastNoise::New<FastNoise::Multiply>();
    normalized->SetLHS(fbm);
    normalized->SetRHS(1.0f / amplitudeSum);
    return normalized;
}

} // namespace cenda
