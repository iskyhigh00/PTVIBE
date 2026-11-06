#pragma once
#include <vector>

constexpr int kPadCount = 13;
constexpr int kLayers = 3;    // capas de velocidad
constexpr int kVariants = 3;  // round-robin

// Pads: 0 bombo, 1 caja, 2 rim/cross-stick, 3 hh cerrado, 4 hh abierto, 5 hh pedal,
// 6 tom1, 7 tom2, 8 floor, 9 crash, 10 ride, 11 campana, 12 splash/china.
struct Sample {
    std::vector<float> data;  // PCM float, intercalado si channels == 2
    int channels = 1;
    int rate = 0;             // 0 = frecuencia del dispositivo
    int frames() const { return channels > 0 ? static_cast<int>(data.size()) / channels : 0; }
};

struct Kit {
    std::vector<Sample> samples;  // kPadCount * kLayers * kVariants
    bool sampled = false;         // true = muestras reales; false = kit sintetizado
};

inline int sampleIndex(int pad, int layer, int variant) {
    return (pad * kLayers + layer) * kVariants + variant;
}

// Kit sintetizado de respaldo (osciladores + ruido filtrado con envolvente).
void buildSynthKit(int sampleRate, std::vector<Sample>& out);
