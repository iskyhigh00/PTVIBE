#include "synth_kit.h"
#include <cmath>
#include <cstdint>

namespace {
constexpr float kPi = 3.14159265358979f;

struct Noise {
    uint32_t s = 22222u;
    float next() {
        s = s * 1664525u + 1013904223u;
        return (static_cast<int32_t>(s) >> 8) / 8388608.0f;
    }
};

Sample kick(int sr) {
    Sample out;
    const int n = static_cast<int>(sr * 0.35f);
    out.data.resize(n);
    double phase = 0;
    Noise nz;
    for (int i = 0; i < n; ++i) {
        const float t = static_cast<float>(i) / sr;
        const float f = 45.0f + 90.0f * std::exp(-t * 38.0f);
        phase += 2.0 * kPi * f / sr;
        float s = std::sin(static_cast<float>(phase)) * std::exp(-t * 11.0f);
        s += nz.next() * std::exp(-t * 400.0f) * 0.35f;  // click de ataque
        out.data[i] = s * 0.95f;
    }
    return out;
}

Sample snare(int sr) {
    Sample out;
    const int n = static_cast<int>(sr * 0.25f);
    out.data.resize(n);
    Noise nz;
    float lp = 0;
    double phase = 0;
    for (int i = 0; i < n; ++i) {
        const float t = static_cast<float>(i) / sr;
        const float w = nz.next();
        lp += 0.35f * (w - lp);
        const float hp = w - lp;  // ruido filtrado pasa-altos
        phase += 2.0 * kPi * (185.0f + 40.0f * std::exp(-t * 60.0f)) / sr;
        const float body = std::sin(static_cast<float>(phase)) * std::exp(-t * 28.0f);
        out.data[i] = (hp * std::exp(-t * 20.0f) * 0.8f + body * 0.6f) * 0.9f;
    }
    return out;
}

Sample hatClosed(int sr) {
    Sample out;
    const int n = static_cast<int>(sr * 0.09f);
    out.data.resize(n);
    Noise nz;
    float lp = 0;
    for (int i = 0; i < n; ++i) {
        const float t = static_cast<float>(i) / sr;
        const float w = nz.next();
        lp += 0.6f * (w - lp);
        out.data[i] = (w - lp) * std::exp(-t * 70.0f) * 0.7f;
    }
    return out;
}

Sample tom(int sr) {
    Sample out;
    const int n = static_cast<int>(sr * 0.4f);
    out.data.resize(n);
    double phase = 0;
    for (int i = 0; i < n; ++i) {
        const float t = static_cast<float>(i) / sr;
        phase += 2.0 * kPi * (95.0f + 70.0f * std::exp(-t * 18.0f)) / sr;
        out.data[i] = std::sin(static_cast<float>(phase)) * std::exp(-t * 9.0f) * 0.9f;
    }
    return out;
}
}  // namespace

void buildSynthKit(int sampleRate, std::vector<Sample>& out) {
    out.clear();
    out.push_back(kick(sampleRate));
    out.push_back(snare(sampleRate));
    out.push_back(hatClosed(sampleRate));
    out.push_back(tom(sampleRate));
}
