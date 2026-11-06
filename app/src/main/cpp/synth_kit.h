#pragma once
#include <vector>

constexpr int kPadCount = 13;
constexpr int kLayers = 3;    // capas de velocidad
constexpr int kVariants = 3;  // round-robin

// Pads: 0 bombo, 1 caja, 2 rim/cross-stick, 3 hh cerrado, 4 hh abierto, 5 hh pedal,
// 6 tom1, 7 tom2, 8 floor, 9 crash, 10 ride, 11 campana, 12 splash/china.
struct Sample {
    std::vector<float> data;  // PCM float mono
};

inline int sampleIndex(int pad, int layer, int variant) {
    return (pad * kLayers + layer) * kVariants + variant;
}

// Kit sintetizado de respaldo (osciladores + ruido filtrado con envolvente).
void buildSynthKit(int sampleRate, std::vector<Sample>& out);
