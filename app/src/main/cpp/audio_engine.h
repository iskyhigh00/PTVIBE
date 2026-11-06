#pragma once
#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>
#include "spsc_queue.h"
#include "synth_kit.h"

struct PadEvent {
    int32_t type = 0;  // 0 golpe, 1 choke
    int32_t pad = 0;
    float velocity = 1.0f;
    int64_t eventNs = 0;  // CLOCK_MONOTONIC del toque
};

enum Param {
    kSnareTune = 0,  // semitonos
    kTomTune = 1,    // semitonos
    kRoom = 2,       // 0..1
    kComp = 3,       // 0..1
    kMaster = 4,     // 0..1
    kPadVol = 8,     // +pad, 0..1.5
    kParamCount = 32
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
    struct Comb {
        std::vector<float> buf;
        int idx = 0;
        float damp = 0;
    };
    struct Allpass {
        std::vector<float> buf;
        int idx = 0;
    };
    static constexpr int kMaxVoices = 48;
    static constexpr int kQueueSize = 512;

    bool openAndStart();
    void applyBursts();
    void startVoice(const PadEvent& ev, int64_t nowNs);
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

    Comb combs_[4];
    Allpass aps_[2];
    float compEnv_ = 0;

    std::atomic<bool> running_{false};
    std::atomic<int> bursts_{1};
    int lastXruns_ = 0;
    bool mmap_ = false;

    std::atomic<double> lastMs_{0};
    std::atomic<double> avgMs_{0};
    std::atomic<double> maxMs_{0};
    std::atomic<int64_t> count_{0};
};
