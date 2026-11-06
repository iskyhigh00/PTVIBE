#pragma once
#include <algorithm>
#include <cmath>
#include <vector>

// Reverb tipo Schroeder (4 combs + 2 allpass). init() asigna memoria; process() no.
struct Reverb {
    struct Comb { std::vector<float> buf; int idx = 0; float damp = 0; };
    struct Allpass { std::vector<float> buf; int idx = 0; };
    Comb combs[4];
    Allpass aps[2];
    bool ready = false;

    void init(int sr) {
        static const int cl[4] = {1116, 1188, 1277, 1356};
        static const int al[2] = {556, 441};
        for (int i = 0; i < 4; ++i) {
            combs[i].buf.assign(std::max(8, cl[i] * sr / 44100), 0.0f);
            combs[i].idx = 0;
            combs[i].damp = 0;
        }
        for (int i = 0; i < 2; ++i) {
            aps[i].buf.assign(std::max(8, al[i] * sr / 44100), 0.0f);
            aps[i].idx = 0;
        }
        ready = true;
    }

    inline void process(float in, float& wl, float& wr) {
        float y[4];
        for (int k = 0; k < 4; ++k) {
            Comb& c = combs[k];
            y[k] = c.buf[c.idx];
            c.damp = y[k] * 0.65f + c.damp * 0.35f;
            c.buf[c.idx] = in + c.damp * 0.8f;
            if (++c.idx >= static_cast<int>(c.buf.size())) c.idx = 0;
        }
        float w[2] = {y[0] + y[2], y[1] + y[3]};
        for (int k = 0; k < 2; ++k) {
            Allpass& a = aps[k];
            const float bo = a.buf[a.idx];
            const float o = -w[k] + bo;
            a.buf[a.idx] = w[k] + bo * 0.5f;
            if (++a.idx >= static_cast<int>(a.buf.size())) a.idx = 0;
            w[k] = o;
        }
        wl = w[0];
        wr = w[1];
    }
};

// Chorus estéreo (mono -> L/R) con dos líneas de retardo moduladas.
struct Chorus {
    std::vector<float> buf;
    int w = 0;
    float phase = 0;
    int sr = 48000;

    void init(int rate) {
        sr = rate;
        buf.assign(static_cast<size_t>(rate / 20) + 8, 0.0f);
        w = 0;
        phase = 0;
    }

    inline void process(float in, float& l, float& r) {
        const int n = static_cast<int>(buf.size());
        buf[w] = in;
        phase += 0.6f / sr;
        if (phase > 1.0f) phase -= 1.0f;
        const float a = 6.2831853f * phase;
        const float dl = (0.012f + 0.004f * std::sin(a)) * sr;
        const float dr = (0.012f + 0.004f * std::sin(a + 1.5707963f)) * sr;
        l = read(w - dl, n);
        r = read(w - dr, n);
        if (++w >= n) w = 0;
    }

private:
    inline float read(float pos, int n) const {
        while (pos < 0) pos += n;
        const int i0 = static_cast<int>(pos);
        const float f = pos - i0;
        const float a = buf[i0 % n];
        const float b = buf[(i0 + 1) % n];
        return a + (b - a) * f;
    }
};

// Filtro de estado variable (TPT), salida pasabajos.
struct Svf {
    float ic1 = 0, ic2 = 0, a1 = 0, a2 = 0, a3 = 0;
    void set(float fc, float res, float sr) {
        fc = std::min(fc, 0.45f * sr);
        const float g = std::tan(3.14159265f * fc / sr);
        const float k = 2.0f - 1.9f * std::min(std::max(res, 0.0f), 1.0f);
        a1 = 1.0f / (1.0f + g * (g + k));
        a2 = g * a1;
        a3 = g * a2;
    }
    inline float lp(float x) {
        const float v3 = x - ic2;
        const float v1 = a1 * ic1 + a2 * v3;
        const float v2 = ic2 + a2 * ic1 + a3 * v3;
        ic1 = 2 * v1 - ic1;
        ic2 = 2 * v2 - ic2;
        return v2;
    }
};

inline float polyblep(float t, float dt) {
    if (t < dt) { t /= dt; return t + t - t * t - 1.0f; }
    if (t > 1.0f - dt) { t = (t - 1.0f) / dt; return t * t + t + t + 1.0f; }
    return 0.0f;
}
