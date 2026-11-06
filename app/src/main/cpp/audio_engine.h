#pragma once
#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>
#include "chord_synth.h"
#include "dsp.h"
#include "spsc_queue.h"
#include "synth_kit.h"

// Evento de la UI hacia el hilo de audio.
// 0 golpe de pad (a=pad, f=vel), 1 choke (a=pad),
// 10 noteOn(a=slot, f=midi), 11 glideTo(a, f), 12 spawn(a=slot, b=desde, f),
// 13 glideRelease(a, f), 14 release(a), 15 releaseAll.
struct PadEvent {
    int32_t type = 0;
    int32_t a = 0;
    int32_t b = 0;
    float f = 0;
    int64_t eventNs = 0;  // CLOCK_MONOTONIC del toque
};

enum Param {
    kSnareTune = 0,  // semitonos
    kTomTune = 1,    // semitonos
    kRoom = 2,       // 0..1
    kComp = 3,       // 0..1
    kMaster = 4,     // 0..1
    kPadVol = 8,     // +pad (13 pads), 0..1.5
    kChordVol = 21,
    kSoloVol = 22,
    kDrumVol = 23,
    kSynthRoom = 24,
    kChorusMix = 25,
    kCutoff = 26,
    kResonance = 27,
    kInstrument = 28,
    kGlideMs = 29,
    kGlideCurve = 30,
    kParamCount = 40
};

class AudioEngine final : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
public:
    static constexpr int kStatCount = 13;

    AudioEngine();

    bool start();
    void stop();

    // Hilo de UI (único productor). No bloquea ni asigna memoria.
    void trigger(int pad, float velocity, int64_t eventNs);
    void choke(int pad, int64_t eventNs);
    void setParam(int id, float value);
    void synthCmd(int type, int a, int b, float f);

    void setBursts(int bursts);
    void autoTune();
    void resetStats();
    void getStats(double* out) const;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    struct Voice {
        const float* data = nullptr;
        int32_t length = 0;
        double pos = 0;
        float rate = 1.0f;
        float gain = 0;
        float env = 1.0f;
        float envStep = 0;  // >0 cuando está en fade-out
        int32_t pad = -1;
        int32_t group = 0;
        bool active = false;
    };
    static constexpr int kMaxVoices = 48;
    static constexpr int kQueueSize = 512;

    bool openAndStart();
    void applyBursts();
    void handleEvent(const PadEvent& ev, int64_t nowNs);
    void startVoice(const PadEvent& ev, int64_t nowNs);
    SynthParams synthParams() const;
    void chokeVoices(int pad, int group, float frames);
    float param(int id) const { return params_[id].load(std::memory_order_relaxed); }
    uint32_t rnd();

    mutable std::mutex mutex_;  // solo ciclo de vida/estadísticas, nunca en el callback
    std::shared_ptr<oboe::AudioStream> stream_;
    std::vector<Sample> kit_;
    int kitRate_ = 0;
    int sr_ = 48000;
    SpscQueue<PadEvent, kQueueSize> queue_;
    Voice voices_[kMaxVoices];
    std::atomic<float> params_[kParamCount];
    int lastVariant_[kPadCount] = {};
    uint32_t rng_ = 0x9E3779B9u;

    Reverb drumRoom_;
    SynthEngine synth_;
    float compEnv_ = 0;
    float synthL_[SynthEngine::kMaxBlock];
    float synthR_[SynthEngine::kMaxBlock];

    std::atomic<bool> running_{false};
    std::atomic<int> bursts_{1};
    int lastXruns_ = 0;
    bool mmap_ = false;

    std::atomic<double> lastMs_{0};
    std::atomic<double> avgMs_{0};
    std::atomic<double> maxMs_{0};
    std::atomic<int64_t> count_{0};
};
