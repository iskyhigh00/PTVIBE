#pragma once
#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>
#include "chord_synth.h"
#include "dsp.h"
#include "sequencer.h"
#include "spsc_queue.h"
#include "synth_kit.h"

// Evento de la UI hacia el hilo de audio.
// 0 golpe de pad (a=pad, f=vel), 1 choke (a=pad),
// 10 noteOn(a=slot, f=midi), 11 glideTo(a, f), 12 spawn(a=slot, b=desde, f),
// 13 glideRelease(a, f), 14 release(a), 15 releaseAll.
// Evento hacia la visualización / exportación MIDI (hilo de audio -> UI).
struct VizEvent {
    double timeMs = 0;
    int32_t type = 0;  // 0 golpe (a=pad, f=vel); 10..15 acordes
    int32_t a = 0;
    float f = 0;
    float glideMs = 0;
    int32_t curve = 0;
};

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
    kSoloInstrument = 31,
    kSoloGlide = 32,
    kMetroVol = 33,
    kBpm = 34,
    kBeats = 35,
    kBeatUnit = 36,
    kSwing = 37,
    kMetro = 38,
    kRecQuant = 39,
    kChordQuant = 40,
    kMuteMask = 41,
    kParamCount = 64
};

class AudioEngine final : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
public:
    static constexpr int kStatCount = 15;

    AudioEngine();

    bool start();
    void stop();

    // Hilo de UI (único productor). No bloquea ni asigna memoria.
    void trigger(int pad, float velocity, int64_t eventNs);
    void choke(int pad, int64_t eventNs);
    void setParam(int id, float value);

    // Carga de kits (hilo de fondo): el hilo de audio solo lee un puntero atómico.
    void kitBegin();
    void kitSet(int pad, int layer, int variant, const float* data, int frames, int channels, int rate);
    void kitCommit(int slot);
    bool kitSelect(int slot);
    void kitUseSampled(bool sampled);

    // Secuenciador / patrones / visualización / grabación de audio
    void setPattern(const int32_t* t, const int32_t* p, const float* v, int n, int len, bool restart, bool oneShot) {
        seq_.setPattern(t, p, v, n, len, restart, oneShot);
    }
    void setEndPattern(const int32_t* t, const int32_t* p, const float* v, int n, int len) {
        seq_.setEndPattern(t, p, v, n, len);
    }
    void setQueuedPattern(const int32_t* t, const int32_t* p, const float* v, int n, int len) {
        seq_.setQueuedPattern(t, p, v, n, len);
    }
    void getSeqStatus(double* out) const;
    int pollViz(double* out, int maxEvents);
    void setRecording(bool on);
    int readRecording(float* dst, int maxFloats);
    void synthCmd(int type, int a, int b, float f);
    void soloSet(int idx, float pitch, float filter, float vib, float vol) {
        synth_.setSolo(idx, pitch, filter, vib, vol);
    }

    void setBursts(int bursts);
    void autoTune();
    void resetStats();
    void getStats(double* out) const;
    float level() const { return level_.load(std::memory_order_relaxed); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    struct Voice {
        const float* data = nullptr;
        int32_t length = 0;   // en cuadros
        int32_t ch = 1;
        double pos = 0;
        float rate = 1.0f;
        float gain = 0;
        float env = 1.0f;
        float envStep = 0;  // >0 cuando está en fade-out
        int32_t pad = -1;
        int32_t group = 0;
        int32_t delay = 0;  // retardo en cuadros (secuenciador sample-accurate)
        bool active = false;
    };
    struct ClickVoice {
        const float* d = nullptr;
        int32_t len = 0;
        int32_t pos = 0;
        int32_t delay = 0;
        bool active = false;
    };
    static constexpr int kMaxVoices = 48;
    static constexpr int kQueueSize = 512;

    bool openAndStart(bool reopen);
    void reopenAsync();
    void applyBursts();
    void handleEvent(const PadEvent& ev, int64_t nowNs);
    void startVoice(const PadEvent& ev, int64_t nowNs);
    void triggerDrum(int pad, float vel, int delayFrames);
    void execSynth(const LoopEvent& e);
    void pushViz(int type, int a, int b, float f, float glideMs = -1.0f);
    friend struct EngineSink;
    SynthParams synthParams() const;
    void chokeVoices(int pad, int group, float frames);
    float param(int id) const { return params_[id].load(std::memory_order_relaxed); }
    uint32_t rnd();

    mutable std::mutex mutex_;      // estado compartido (stream_, kits, estadísticas), nunca en el callback
    std::mutex lifeMutex_;          // serializa start/stop/reapertura (la apertura puede tardar)
    std::shared_ptr<oboe::AudioStream> stream_;
    std::vector<std::unique_ptr<Kit>> kits_;  // nunca se liberan mientras vive el motor
    std::unique_ptr<Kit> pending_;
    const Kit* synthKit_ = nullptr;
    const Kit* sampledKits_[2] = {nullptr, nullptr};
    int kitSlot_ = 0;
    bool useSampled_ = true;
    std::atomic<const Kit*> active_{nullptr};
    int kitRate_ = 0;
    int sr_ = 48000;
    SpscQueue<PadEvent, kQueueSize> queue_;
    Voice voices_[kMaxVoices];
    std::atomic<float> params_[kParamCount];
    int lastVariant_[kPadCount] = {};
    uint32_t rng_ = 0x9E3779B9u;

    Sequencer seq_;
    SpscQueue<VizEvent, 1024> viz_;
    int64_t blockNs_ = 0;
    Sample clickAcc_, clickNorm_;
    ClickVoice clicks_[8];
    std::vector<float> recBuf_;
    std::atomic<size_t> recHead_{0};
    std::atomic<size_t> recTail_{0};
    std::atomic<bool> recording_{false};
    Reverb drumRoom_;
    SynthEngine synth_;
    float compEnv_ = 0;
    float synthL_[SynthEngine::kMaxBlock];
    float synthR_[SynthEngine::kMaxBlock];

    std::atomic<float> level_{0};
    std::atomic<bool> running_{false};
    std::atomic<int> bursts_{2};
    int lastXruns_ = 0;
    int quietTicks_ = 0;
    bool mmap_ = false;
    std::atomic<bool> wantRunning_{false};
    std::atomic<bool> reopening_{false};
    std::atomic<bool> kitLoading_{false};
    std::atomic<int64_t> kitLoadingSinceNs_{0};
    std::atomic<int64_t> warmupUntilNs_{0};
    std::atomic<int> recDropped_{0};  // muestras de la grabación perdidas por anillo lleno
    std::atomic<int> dropped_{0};  // eventos descartados (motor parado o cola llena)

    std::atomic<double> lastMs_{0};
    std::atomic<double> avgMs_{0};
    std::atomic<double> maxMs_{0};
    std::atomic<int64_t> count_{0};
};
