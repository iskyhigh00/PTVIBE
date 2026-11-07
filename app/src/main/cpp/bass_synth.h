#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

// Bajo eléctrico de cuerda pulsada (Karplus-Strong) monofónico con dos voces para cruzar notas sin clic.
// trigger() y render() solo desde el hilo de audio; no asigna memoria.
class BassSynth {
public:
    static constexpr int kBuf = 4096;      // alcanza hasta ~12 Hz a 48 kHz
    static constexpr int kMaxPending = 32;

    void init(int sampleRate) {
        sr_ = sampleRate;
        for (auto& v : v_) v = Voice();
        pendingN_ = 0;
    }

    /** Nota de [durFrames] muestras que empieza [offset] muestras dentro del próximo render(). */
    void trigger(float midi, float vel, int offset, int durFrames) {
        if (pendingN_ >= kMaxPending) return;
        pending_[pendingN_++] = {midi, vel, std::max(0, offset), std::max(1, durFrames)};
    }

    void allOff() {
        for (auto& v : v_) if (v.active) v.left = std::min(v.left, 1);
        pendingN_ = 0;
    }

    bool active() const { return v_[0].active || v_[1].active; }

    /** Suma en out (estéreo intercalado). tone 0..1 (opaco..brillante), vol 0..1.5. */
    void render(float* out, int n, float tone, float vol) {
        if (pendingN_ == 0 && !active()) return;
        const float cut = 260.0f * std::exp2(tone * 4.2f);
        const float g = std::tan(3.14159265f * std::min(cut, 0.45f * sr_) / sr_);
        const float k = 1.25f;
        const float a1 = 1.0f / (1.0f + g * (g + k)), a2 = g * a1, a3 = g * a2;
        const float relStep = 1.0f / (0.035f * sr_);
        const float drive = 1.4f + 1.6f * tone;
        for (int i = 0; i < n; ++i) {
            for (int p = 0; p < pendingN_;) {
                if (pending_[p].offset <= i) {
                    start(pending_[p], tone);
                    pending_[p] = pending_[--pendingN_];
                } else {
                    ++p;
                }
            }
            float s = 0;
            for (auto& v : v_) {
                if (!v.active) continue;
                const float d = v.delay;
                float rp = static_cast<float>(v.w) - d;
                while (rp < 0) rp += kBuf;
                const int i0 = static_cast<int>(rp);
                const float fr = rp - i0;
                const float x0 = v.buf[i0 & (kBuf - 1)], x1 = v.buf[(i0 + 1) & (kBuf - 1)];
                const float y = x0 + (x1 - x0) * fr;
                const float lp = v.damp * y + (1.0f - v.damp) * v.last;
                v.last = y;
                v.buf[v.w] = lp * v.loop;
                v.w = (v.w + 1) & (kBuf - 1);
                v.subPh += v.subInc;
                if (v.subPh >= 1.0f) v.subPh -= 1.0f;
                const float sub = std::sin(6.2831853f * v.subPh) * v.subEnv;
                v.subEnv *= v.subDecay;
                if (v.left > 0) {
                    --v.left;
                } else {
                    v.env -= relStep;
                    if (v.env <= 0) { v.active = false; continue; }
                }
                s += (y + sub) * v.env * v.vel;
            }
            // Cuerpo: pasabajos (SVF) + saturación suave de amplificador.
            const float v3 = s - ic2_;
            const float v1 = a1 * ic1_ + a2 * v3;
            const float v2 = ic2_ + a2 * ic1_ + a3 * v3;
            ic1_ = 2 * v1 - ic1_;
            ic2_ = 2 * v2 - ic2_;
            const float o = std::tanh(v2 * drive) / drive * 0.9f * vol;
            out[2 * i] += o;
            out[2 * i + 1] += o;
        }
        for (int p = 0; p < pendingN_; ++p) pending_[p].offset -= n;
    }

private:
    struct Pending { float midi, vel; int offset, dur; };
    struct Voice {
        bool active = false;
        float buf[kBuf] = {};
        int w = 0;
        float delay = 100, damp = 0.5f, loop = 0.99f, last = 0;
        float env = 0, vel = 0;
        int left = 0;
        float subPh = 0, subInc = 0, subEnv = 0, subDecay = 1;
    };

    void start(const Pending& p, float tone) {
        // La voz que suena se apaga rápido (la otra) y la nueva toma la cuerda.
        Voice* old = v_[cur_].active ? &v_[cur_] : nullptr;
        if (old) old->left = std::min(old->left, static_cast<int>(0.004f * sr_));
        cur_ ^= 1;
        Voice& v = v_[cur_];
        const float f = 440.0f * std::exp2((p.midi - 69.0f) / 12.0f);
        v.delay = std::min(static_cast<float>(kBuf - 4), std::max(2.0f, sr_ / f - 0.5f));
        v.damp = 0.42f + 0.25f * tone;
        const float decayS = 2.2f;
        v.loop = std::exp(-6.9f / (decayS * f));
        const float vel = std::max(0.05f, std::min(p.vel, 1.0f));
        // Excitación: ruido filtrado (dedo) más brillante cuanto más fuerte el golpe.
        const float c = 0.18f + 0.45f * vel * (0.5f + 0.5f * tone);
        float lp = 0;
        const int len = static_cast<int>(v.delay) + 2;
        for (int k = 0; k < kBuf; ++k) v.buf[k] = 0;
        for (int k = 0; k < len; ++k) {
            seed_ = seed_ * 1664525u + 1013904223u;
            const float r = static_cast<float>(seed_ >> 8) / 8388608.0f - 1.0f;
            lp += c * (r - lp);
            v.buf[(v.w - len + k) & (kBuf - 1)] = lp * 2.2f;
        }
        v.last = 0;
        v.env = 1.0f;
        v.vel = 0.35f + 0.65f * vel;
        v.left = p.dur;
        v.subPh = 0;
        v.subInc = f / sr_;
        v.subEnv = 0.35f;
        v.subDecay = std::exp(-1.0f / (1.2f * sr_));
        v.active = true;
    }

    int sr_ = 48000;
    Voice v_[2];
    int cur_ = 0;
    Pending pending_[kMaxPending];
    int pendingN_ = 0;
    uint32_t seed_ = 12345u;
    float ic1_ = 0, ic2_ = 0;
};
