#pragma once
#include <cstdint>
#include "dsp.h"

struct SynthParams {
    int instrument = 0;     // 0 EP, 1 órgano, 2 pad, 3 cuerdas, 4 lead
    float glideMs = 250;
    int curve = 1;          // 0 lineal, 1 S suave, 2 lenta al inicio
    float volume = 0.8f;
    float cutoff = 0.8f;    // 0..1
    float resonance = 0.15f;
    float chorus = 0.35f;
    float room = 0.3f;
};

// Sintetizador polifónico (16 voces) con glide continuo por voz.
// Todas las llamadas excepto init() son seguras para el hilo de audio.
class SynthEngine {
public:
    static constexpr int kVoices = 16;
    static constexpr int kMaxBlock = 1024;

    void init(int sampleRate);
    void reset();

    void noteOn(int slot, float midi, const SynthParams& p);
    void glideTo(int slot, float midi, const SynthParams& p, bool releaseAfter);
    void spawn(int slot, int fromSlot, float midi, const SynthParams& p);
    void release(int slot);
    void releaseAll();

    // Suma estéreo en outL/outR (n <= kMaxBlock). No asigna memoria.
    void render(float* outL, float* outR, int n, const SynthParams& p);

    float currentMidi(int slot) const { return voices_[slot].midi; }
    bool isActive(int slot) const { return voices_[slot].active; }

private:
    struct Voice {
        bool active = false;
        bool releaseAfterGlide = false;
        int stage = 3;  // 0 A, 1 D, 2 S, 3 R
        float env = 0, relStep = 0;
        float midi = 60, startMidi = 60, targetMidi = 60;
        float prog = 1, progStep = 0;
        int curve = 1;
        float vel = 0.8f;
        float age = 0;
        float ph[9] = {};
        Svf filter;
    };

    void startGlide(Voice& v, float target, const SynthParams& p);

    int sr_ = 48000;
    Voice voices_[kVoices];
    Chorus chorus_;
    Reverb reverb_;
    float mono_[kMaxBlock];
};
