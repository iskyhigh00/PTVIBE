#include "synth_kit.h"
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace {
constexpr float kPi = 3.14159265358979f;
constexpr float kNominal[kLayers] = {0.35f, 0.65f, 1.0f};

struct Noise {
    uint32_t s;
    explicit Noise(uint32_t seed) : s(seed * 2654435761u + 12345u) {}
    float next() {
        s = s * 1664525u + 1013904223u;
        return (static_cast<int32_t>(s) >> 8) / 8388608.0f;
    }
};

struct OnePole {
    float y = 0;
    float lp(float x, float a) { y += a * (x - y); return y; }
    float hp(float x, float a) { y += a * (x - y); return x - y; }
};

// Seis osciladores cuadrados inarmónicos (base metálica de platos/hi-hat).
struct Metal {
    float ph[6] = {0, 0, 0, 0, 0, 0};
    float next(float scale, int sr) {
        static const float f[6] = {205.3f, 304.4f, 369.6f, 522.7f, 540.0f, 800.0f};
        float s = 0;
        for (int i = 0; i < 6; ++i) {
            ph[i] += f[i] * scale / sr;
            ph[i] -= std::floor(ph[i]);
            s += ph[i] < 0.5f ? 1.0f : -1.0f;
        }
        return s / 6.0f;
    }
};

inline float expo(float t, float k) { return std::exp(-t * k); }

Sample sweep(int sr, float dur, float f0, float f1, float sweepK, float decayK, float amp,
             float clickAmp, float clickK, uint32_t seed) {
    Sample o;
    const int n = static_cast<int>(sr * dur);
    o.data.resize(n);
    Noise nz(seed);
    OnePole lp;
    double ph = 0;
    for (int i = 0; i < n; ++i) {
        const float t = static_cast<float>(i) / sr;
        ph += 2.0 * kPi * (f1 + (f0 - f1) * expo(t, sweepK)) / sr;
        float s = std::sin(static_cast<float>(ph)) * expo(t, decayK);
        s += lp.hp(nz.next(), 0.3f) * clickAmp * expo(t, clickK);
        const float atk = 1.0f - expo(t, 3000.0f);
        o.data[i] = s * amp * atk;
    }
    return o;
}

Sample cymbal(int sr, float dur, float metalK, float noiseK, float noiseAmp, float metalAmp,
              float bright, float scale, float amp, uint32_t seed) {
    Sample o;
    const int n = static_cast<int>(sr * dur);
    o.data.resize(n);
    Noise nz(seed);
    Metal m;
    OnePole hp;
    for (int i = 0; i < n; ++i) {
        const float t = static_cast<float>(i) / sr;
        float x = m.next(scale, sr) * metalAmp * expo(t, metalK) +
                  nz.next() * noiseAmp * expo(t, noiseK);
        x = hp.hp(x, bright);
        const float atk = 1.0f - expo(t, 2500.0f);
        o.data[i] = x * amp * atk;
    }
    return o;
}

Sample make(int pad, int layer, int var, int sr) {
    const float I = kNominal[layer];
    const float vr = 1.0f + 0.03f * (var - 1);  // pequeña variación entre round-robin
    const uint32_t seed = static_cast<uint32_t>(pad * 97 + layer * 13 + var * 7 + 1);
    switch (pad) {
        case 0:  // bombo: punch, poca cola
            return sweep(sr, 0.30f, (125.0f + 30.0f * I) * vr, 46.0f * vr, 45.0f, 13.0f + 4.0f * (1 - I),
                         0.95f * I, 0.55f * I, 350.0f, seed);
        case 1: {  // caja: seca, mucho ataque, afinación media-alta
            Sample o;
            const int n = static_cast<int>(sr * 0.20f);
            o.data.resize(n);
            Noise nz(seed);
            OnePole hp;
            double ph = 0;
            for (int i = 0; i < n; ++i) {
                const float t = static_cast<float>(i) / sr;
                ph += 2.0 * kPi * ((235.0f * vr) + 60.0f * expo(t, 70.0f)) / sr;
                const float body = std::sin(static_cast<float>(ph)) * expo(t, 38.0f);
                const float w = hp.hp(nz.next(), 0.12f + 0.2f * I) * expo(t, 24.0f + 10.0f * (1 - I));
                const float crack = nz.next() * expo(t, 600.0f) * 0.9f * I;
                o.data[i] = (w * 0.85f + body * 0.55f + crack) * I * (1.0f - expo(t, 4000.0f));
            }
            return o;
        }
        case 2:  // rim / cross-stick
            return sweep(sr, 0.10f, 1750.0f * vr, 820.0f * vr, 150.0f, 55.0f, 0.8f * I, 0.9f * I, 140.0f,
                         seed);
        case 3:  // hi-hat cerrado
            return cymbal(sr, 0.12f, 45.0f - 12.0f * I, 60.0f - 20.0f * I, 0.5f, 0.9f,
                          0.25f + 0.35f * I, vr, 0.8f * I, seed);
        case 4:  // hi-hat abierto
            return cymbal(sr, 0.9f, 4.5f, 5.5f, 0.45f, 0.85f, 0.22f + 0.3f * I, vr, 0.75f * I, seed);
        case 5:  // pedal: golpe sordo + chick corto
            return cymbal(sr, 0.07f, 90.0f, 110.0f, 0.4f, 0.5f, 0.15f, 0.7f * vr, 0.7f * I, seed);
        case 6:
        case 7:
        case 8: {  // toms profundos, sustain corto
            const float base[3] = {165.0f, 125.0f, 92.0f};
            const float b = base[pad - 6] * vr;
            return sweep(sr, 0.45f, b * 1.5f, b, 22.0f, 9.5f, 0.9f * I, 0.45f * I, 200.0f, seed);
        }
        case 9:  // crash brillante pero no estridente
            return cymbal(sr, 1.8f, 2.4f, 2.0f, 0.55f, 0.65f, 0.2f + 0.15f * I, 1.0f * vr, 0.7f * I, seed);
        case 10:  // ride definido
            return cymbal(sr, 1.4f, 3.6f, 6.0f, 0.2f, 0.9f, 0.18f + 0.1f * I, 1.45f * vr, 0.65f * I, seed);
        case 11: {  // campana del ride
            Sample o;
            const int n = static_cast<int>(sr * 1.1f);
            o.data.resize(n);
            Noise nz(seed);
            const float fr[4] = {820.0f, 1230.0f, 1710.0f, 2570.0f};
            const float am[4] = {1.0f, 0.7f, 0.45f, 0.3f};
            for (int i = 0; i < n; ++i) {
                const float t = static_cast<float>(i) / sr;
                float s = 0;
                for (int k = 0; k < 4; ++k) s += std::sin(2 * kPi * fr[k] * vr * t) * am[k] * expo(t, 3.5f + k * 1.5f);
                s += nz.next() * 0.15f * expo(t, 60.0f);
                o.data[i] = s * 0.3f * I * (1.0f - expo(t, 3000.0f));
            }
            return o;
        }
        case 12:  // splash / china
            return cymbal(sr, 0.9f, 5.0f, 4.5f, 0.55f, 0.7f, 0.3f + 0.2f * I, 1.25f * vr, 0.7f * I, seed);
        case 13:
        case 14: {  // güiro: raspado de dientes (ruido brillante pulsado)
            Sample o;
            const float dur = pad == 13 ? 0.16f : 0.42f;
            const int n = static_cast<int>(sr * dur);
            o.data.resize(n);
            Noise nz(seed);
            OnePole hp;
            const float rate = (pad == 13 ? 75.0f : 55.0f) * vr;
            for (int i = 0; i < n; ++i) {
                const float t = static_cast<float>(i) / sr;
                const float teeth = 0.5f + 0.5f * std::sin(2 * kPi * rate * t);
                const float env = std::min(1.0f, t * 60.0f) * std::min(1.0f, (dur - t) * 25.0f);
                o.data[i] = hp.hp(nz.next(), 0.35f) * teeth * teeth * env * 0.6f * I;
            }
            return o;
        }
        case 15:  // maracas: ruido agudo muy corto
            return cymbal(sr, 0.09f, 200.0f, 45.0f, 0.9f, 0.0f, 0.55f, vr, 0.6f * I, seed);
        case 16:  // conga
            return sweep(sr, 0.5f, 245.0f * vr, 205.0f * vr, 40.0f, 9.0f, 0.8f * I, 0.25f * I, 400.0f, seed);
        case 17:  // quinto (alegre): más agudo y seco
            return sweep(sr, 0.4f, 330.0f * vr, 290.0f * vr, 40.0f, 12.0f, 0.75f * I, 0.35f * I, 500.0f, seed);
        case 18:  // tumba
            return sweep(sr, 0.6f, 175.0f * vr, 150.0f * vr, 30.0f, 7.0f, 0.85f * I, 0.2f * I, 350.0f, seed);
        case 19:  // bongó
            return sweep(sr, 0.3f, 520.0f * vr, 450.0f * vr, 50.0f, 16.0f, 0.7f * I, 0.4f * I, 600.0f, seed);
        default: {  // campana (cencerro): dos parciales cuadrados
            Sample o;
            const int n = static_cast<int>(sr * 0.35f);
            o.data.resize(n);
            double p1 = 0, p2 = 0;
            OnePole lp;
            for (int i = 0; i < n; ++i) {
                const float t = static_cast<float>(i) / sr;
                p1 += 562.0 * vr / sr; p2 += 845.0 * vr / sr;
                p1 -= std::floor(p1); p2 -= std::floor(p2);
                const float x = (p1 < 0.5 ? 1.0f : -1.0f) + (p2 < 0.5 ? 1.0f : -1.0f);
                o.data[i] = lp.lp(x, 0.35f) * 0.3f * I * expo(t, 14.0f) * (1.0f - expo(t, 3000.0f));
            }
            return o;
        }
    }
}
}  // namespace

void buildSynthKit(int sampleRate, std::vector<Sample>& out) {
    out.clear();
    out.resize(kPadCount * kLayers * kVariants);
    for (int p = 0; p < kPadCount; ++p)
        for (int l = 0; l < kLayers; ++l)
            for (int v = 0; v < kVariants; ++v) out[sampleIndex(p, l, v)] = make(p, l, v, sampleRate);
}
