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
    int32_t pad = 0;
    float velocity = 1.0f;
    int64_t eventNs = 0;  // CLOCK_MONOTONIC del toque
};

class AudioEngine final : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
public:
    static constexpr int kStatCount = 13;

    bool start();
    void stop();

    // Hilo de UI (único productor). No bloquea ni asigna memoria.
    void trigger(int pad, float velocity, int64_t eventNs);

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
        int32_t pos = 0;
        float gain = 0;
        float env = 1.0f;
        float envStep = 0;  // >0 cuando está en fade-out
        int32_t pad = -1;
        bool active = false;
    };
    static constexpr int kMaxVoices = 32;
    static constexpr int kQueueSize = 256;
    static constexpr float kReleaseFrames = 96.0f;

    bool openAndStart();
    void applyBursts();
    void startVoice(const PadEvent& ev, int64_t nowNs);

    mutable std::mutex mutex_;  // solo ciclo de vida/estadísticas, nunca en el callback
    std::shared_ptr<oboe::AudioStream> stream_;
    std::vector<Sample> kit_;
    int kitRate_ = 0;
    SpscQueue<PadEvent, kQueueSize> queue_;
    Voice voices_[kMaxVoices];

    std::atomic<bool> running_{false};
    std::atomic<int> bursts_{1};
    int lastXruns_ = 0;
    bool mmap_ = false;

    std::atomic<double> lastMs_{0};
    std::atomic<double> avgMs_{0};
    std::atomic<double> maxMs_{0};
    std::atomic<int64_t> count_{0};
};
