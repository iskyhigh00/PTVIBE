#include "chord_synth.h"
#include <cmath>

namespace {
constexpr float kTwoPi = 6.28318530718f;

struct Inst {
    float a, d, s, r;  // segundos / nivel
    float susK;        // caída lenta en sostenido (1/s)
    float cutMul;
    float gain;
};
const Inst kInst[5] = {
    {0.003f, 1.4f, 0.45f, 0.40f, 0.35f, 4.0f, 0.22f},  // piano eléctrico
    {0.010f, 0.01f, 1.0f, 0.08f, 0.0f, 4.0f, 0.12f},   // órgano
    {0.500f, 0.60f, 0.8f, 1.20f, 0.0f, 0.8f, 0.20f},   // pad
    {0.300f, 0.30f, 0.85f, 0.70f, 0.0f, 1.2f, 0.17f},  // cuerdas
    {0.010f, 0.20f, 0.7f, 0.20f, 0.0f, 1.0f, 0.15f},   // lead
};

inline float shape(float p, int curve) {
    if (curve == 1) return p * p * (3.0f - 2.0f * p);
    if (curve == 2) return p * p;
    return p;
}

inline float saw(float ph, float dt) { return 2.0f * ph - 1.0f - polyblep(ph, dt); }

inline float square(float ph, float dt) {
    float p2 = ph + 0.5f;
    if (p2 >= 1.0f) p2 -= 1.0f;
    return (ph < 0.5f ? 1.0f : -1.0f) + polyblep(ph, dt) - polyblep(p2, dt);
}

inline float adv(float& ph, float inc) {
    ph += inc;
    if (ph >= 1.0f) ph -= 1.0f;
    return ph;
}
}  // namespace

void SynthEngine::init(int sampleRate) {
    sr_ = sampleRate;
    chorus_.init(sampleRate);
    reverb_.init(sampleRate);
    reset();
}

void SynthEngine::reset() {
    for (auto& v : voices_) v = Voice();
}

void SynthEngine::startGlide(Voice& v, float target, const SynthParams& p) {
    v.startMidi = v.midi;
    v.targetMidi = target;
    v.curve = p.curve;
    const float samples = p.glideMs * 0.001f * sr_;
    if (samples < 1.0f) {
        v.midi = target;
        v.prog = 1.0f;
        v.progStep = 0;
    } else {
        v.prog = 0;
        v.progStep = 1.0f / samples;
    }
}

void SynthEngine::noteOn(int slot, float midi, const SynthParams& p) {
    if (slot < 0 || slot >= kVoices) return;
    Voice& v = voices_[slot];
    const bool was = v.active;
    v.midi = v.startMidi = v.targetMidi = midi;
    v.prog = 1;
    v.progStep = 0;
    v.releaseAfterGlide = false;
    v.relStep = 0;
    v.vel = 0.8f;
    v.age = 0;
    if (!was) v.env = 0;  // si seguía sonando, la envolvente continúa (sin clic)
    v.stage = 0;
    v.active = true;
    (void)p;
}

void SynthEngine::glideTo(int slot, float midi, const SynthParams& p, bool releaseAfter) {
    if (slot < 0 || slot >= kVoices) return;
    Voice& v = voices_[slot];
    if (!v.active) { noteOn(slot, midi, p); return; }
    startGlide(v, midi, p);
    v.releaseAfterGlide = releaseAfter;
    if (releaseAfter && v.prog >= 1.0f) release(slot);
}

void SynthEngine::spawn(int slot, int fromSlot, float midi, const SynthParams& p) {
    if (slot < 0 || slot >= kVoices) return;
    if (fromSlot < 0 || fromSlot >= kVoices || !voices_[fromSlot].active) {
        noteOn(slot, midi, p);
        return;
    }
    const float start = voices_[fromSlot].midi;
    noteOn(slot, start, p);  // nace en la altura de la voz de origen
    glideTo(slot, midi, p, false);
}

void SynthEngine::release(int slot) {
    if (slot < 0 || slot >= kVoices) return;
    Voice& v = voices_[slot];
    if (!v.active || v.stage == 3) return;
    v.stage = 3;
    v.relStep = -1.0f;  // se calcula en render con el release del instrumento
    v.releaseAfterGlide = false;
}

void SynthEngine::releaseAll() {
    for (int i = 0; i < kVoices; ++i) release(i);
}

void SynthEngine::render(float* outL, float* outR, int n, const SynthParams& p) {
    const int ii = std::min(std::max(p.instrument, 0), 4);
    const Inst& in = kInst[ii];
    const float invSr = 1.0f / sr_;
    for (int i = 0; i < n; ++i) mono_[i] = 0;

    const float baseCut = 150.0f * std::exp2(p.cutoff * 7.0f) * in.cutMul;
    const float susMul = std::exp(-in.susK * invSr);

    for (auto& v : voices_) {
        if (!v.active) continue;
        v.filter.set(baseCut, p.resonance, static_cast<float>(sr_));
        for (int i = 0; i < n; ++i) {
            // Glide continuo en el dominio de altura (semitonos).
            if (v.prog < 1.0f) {
                v.prog += v.progStep;
                if (v.prog >= 1.0f) {
                    v.prog = 1.0f;
                    v.midi = v.targetMidi;
                    if (v.releaseAfterGlide) {
                        v.stage = 3;
                        v.relStep = -1.0f;
                        v.releaseAfterGlide = false;
                    }
                } else {
                    v.midi = v.startMidi + (v.targetMidi - v.startMidi) * shape(v.prog, v.curve);
                }
            }

            // Envolvente.
            switch (v.stage) {
                case 0:
                    v.env += 1.0f / (in.a * sr_);
                    if (v.env >= 1.0f) { v.env = 1.0f; v.stage = 1; }
                    break;
                case 1:
                    v.env -= (1.0f - in.s) / (in.d * sr_);
                    if (v.env <= in.s) { v.env = in.s; v.stage = 2; }
                    break;
                case 2:
                    v.env *= susMul;
                    break;
                default:
                    if (v.relStep < 0) v.relStep = std::max(v.env, 0.001f) / (in.r * sr_);
                    v.env -= v.relStep;
                    if (v.env <= 0.0f) { v.env = 0; v.active = false; }
                    break;
            }
            if (!v.active) break;

            const float f = 440.0f * std::exp2((v.midi - 69.0f) / 12.0f);
            const float dt = std::min(f * invSr, 0.45f);
            const float t = v.age;
            v.age += invSr;
            float s = 0;
            switch (ii) {
                case 0: {  // piano eléctrico FM
                    const float pc = adv(v.ph[0], dt);
                    const float pm = adv(v.ph[1], dt);
                    const float pt = adv(v.ph[2], dt * 14.0f);
                    const float idx = 0.4f + 2.2f * v.vel * std::exp(-t * 3.5f);
                    s = std::sin(kTwoPi * pc + idx * std::sin(kTwoPi * pm)) +
                        0.15f * std::sin(kTwoPi * pt) * std::exp(-t * 20.0f);
                    break;
                }
                case 1: {  // órgano (drawbars)
                    static const float ratio[9] = {0.5f, 1.5f, 1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 8.0f};
                    static const float level[9] = {0.5f, 0.3f, 1.0f, 0.8f, 0.5f, 0.4f, 0.3f, 0.25f, 0.2f};
                    for (int k = 0; k < 9; ++k) {
                        if (dt * ratio[k] > 0.45f) continue;
                        s += std::sin(kTwoPi * adv(v.ph[k], dt * ratio[k])) * level[k];
                    }
                    s *= 0.22f;
                    break;
                }
                case 2: {  // pad: tres sierras desafinadas + sub
                    static const float det[3] = {0.99596f, 1.0f, 1.00406f};
                    for (int k = 0; k < 3; ++k) s += saw(adv(v.ph[k], dt * det[k]), dt) * 0.34f;
                    s += std::sin(kTwoPi * adv(v.ph[3], dt * 0.5f)) * 0.3f;
                    break;
                }
                case 3: {  // cuerdas: dos sierras + vibrato leve
                    const float vib = 1.0f + 0.0015f * std::sin(kTwoPi * 5.2f * t);
                    s = saw(adv(v.ph[0], dt * 0.9971f * vib), dt) * 0.5f +
                        saw(adv(v.ph[1], dt * 1.0029f * vib), dt) * 0.5f;
                    break;
                }
                default: {  // lead
                    s = square(adv(v.ph[0], dt * 0.998f), dt) * 0.4f +
                        saw(adv(v.ph[1], dt * 1.002f), dt) * 0.5f;
                    break;
                }
            }
            mono_[i] += v.filter.lp(s) * v.env * v.vel * in.gain;
        }
    }

    const float vol = p.volume;
    for (int i = 0; i < n; ++i) {
        const float x = mono_[i];
        float cl, cr;
        chorus_.process(x, cl, cr);
        const float m = p.chorus * 0.6f;
        float l = x * (1.0f - m * 0.5f) + cl * m;
        float r = x * (1.0f - m * 0.5f) + cr * m;
        if (p.room > 0.001f) {
            float wl, wr;
            reverb_.process((l + r) * 0.5f * 0.12f, wl, wr);
            l += wl * p.room * 1.6f;
            r += wr * p.room * 1.6f;
        }
        outL[i] += l * vol;
        outR[i] += r * vol;
    }
}
