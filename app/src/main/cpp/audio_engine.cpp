#include "audio_engine.h"
#include <oboe/OboeExtensions.h>
#include <algorithm>
#include <climits>
#include <cmath>
#include <chrono>
#include <ctime>

namespace {
inline int64_t nowNs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

// Saturación suave sin clics (limitador de salida).
inline float softClip(float x) {
    const float a = std::fabs(x);
    if (a < 0.8f) return x;
    const float s = x < 0 ? -1.0f : 1.0f;
    return s * (0.8f + 0.2f * std::tanh((a - 0.8f) * 5.0f));
}

// Grupo de choke: 1 = hi-hat (cerrado, abierto, pedal).
constexpr int kGroupOf[kPadCount] = {0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 2, 2, 0, 0, 0, 0, 0, 0};  // 2: güiro (corta al anterior)
inline bool isCymbal(int pad) { return pad >= 9 && pad <= 12; }
inline int padVolParam(int pad) { return pad < 13 ? kPadVol + pad : kPadVol2 + (pad - 13); }

}  // namespace

AudioEngine::AudioEngine() {
    for (auto& p : params_) p.store(0.0f);
    params_[kRoom].store(0.25f);
    params_[kComp].store(0.3f);
    params_[kMaster].store(0.9f);
    for (int i = 0; i < kPadCount; ++i) params_[padVolParam(i)].store(1.0f);
    params_[kBassVol].store(0.8f);
    params_[kBassTone].store(0.5f);
    params_[kChordVol].store(0.8f);
    params_[kSoloVol].store(0.8f);
    params_[kDrumVol].store(1.0f);
    params_[kSynthRoom].store(0.3f);
    params_[kChorusMix].store(0.35f);
    params_[kCutoff].store(0.8f);
    params_[kResonance].store(0.15f);
    params_[kInstrument].store(0.0f);
    params_[kGlideMs].store(250.0f);
    params_[kGlideCurve].store(1.0f);
    params_[kSoloInstrument].store(4.0f);
    params_[kSoloGlide].store(20.0f);
    params_[kMetroVol].store(0.6f);
    params_[kBpm].store(100.0f);
    params_[kBeats].store(4.0f);
    params_[kBeatUnit].store(4.0f);
    recBuf_.assign(1 << 19, 0.0f);
}

bool AudioEngine::start() {
    wantRunning_.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> life(lifeMutex_);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (stream_) return true;
    }
    return openAndStart(false);
}

// Se llama con lifeMutex_ tomado. mutex_ solo se toma en tramos cortos: la generación del kit sintético
// (lenta) y la apertura del stream ocurren sin él, así que las estadísticas y los ajustes no se bloquean.
bool AudioEngine::openAndStart(bool reopen) {
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Game)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    oboe::OboeExtensions::setMMapEnabled(true);

    std::shared_ptr<oboe::AudioStream> s;
    if (b.openStream(s) != oboe::Result::OK || !s) return false;

    const int rate = s->getSampleRate();
    bool needKit;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        needKit = synthKit_ == nullptr || kitRate_ != rate;
    }
    std::unique_ptr<Kit> newKit;
    if (needKit) {  // fuera de mutex_; el stream aún no está en marcha
        newKit = std::make_unique<Kit>();
        buildSynthKit(rate, newKit->samples);
        for (auto& smp : newKit->samples) smp.rate = rate;
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        sr_ = rate;
        if (newKit) {
            const bool wasActiveSynth = active_.load() == nullptr || active_.load() == synthKit_;
            synthKit_ = newKit.get();
            kits_.push_back(std::move(newKit));
            kitRate_ = rate;
            if (wasActiveSynth) active_.store(synthKit_, std::memory_order_release);
        }
        drumRoom_.init(sr_);
        synth_.init(sr_);
        bass_.init(sr_);
        seq_.init(sr_);
        {  // clics del metrónomo
            auto make = [&](Sample& smp, float f, float dur) {
                const int n = static_cast<int>(sr_ * dur);
                smp.data.resize(n);
                for (int i = 0; i < n; ++i) {
                    const float t = static_cast<float>(i) / sr_;
                    smp.data[i] = std::sin(6.2831853f * f * t) * std::exp(-t * 180.0f) * 0.8f;
                }
                smp.channels = 1;
                smp.rate = sr_;
            };
            make(clickAcc_, 1700.0f, 0.04f);
            make(clickNorm_, 1000.0f, 0.03f);
        }
        compEnv_ = 0;
        for (auto& v : voices_) v = Voice();
        for (auto& cv : clicks_) cv = ClickVoice();
        stream_ = s;
        mmap_ = oboe::OboeExtensions::isMMapUsed(s.get());
        lastXruns_ = 0;
        quietTicks_ = 0;
        if (reopen) bursts_.store(2);
        applyBursts();
        running_.store(true, std::memory_order_release);
    }
    // Los xruns de los primeros ~3 s (arranque del stream) no cuentan para el ajuste automático.
    warmupUntilNs_.store(nowNs() + 3000000000LL, std::memory_order_relaxed);
    if (s->requestStart() != oboe::Result::OK) {
        std::lock_guard<std::mutex> lock(mutex_);
        running_.store(false);
        stream_.reset();
        s->close();
        return false;
    }
    return true;
}

void AudioEngine::stop() {
    wantRunning_.store(false, std::memory_order_release);
    std::lock_guard<std::mutex> life(lifeMutex_);
    std::shared_ptr<oboe::AudioStream> s;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        running_.store(false, std::memory_order_release);
        s = std::move(stream_);
    }
    if (s) {
        s->stop();
        s->close();
    }
    // Sin hilo de audio: descartar lo que quedó en la cola para que no suene al reabrir.
    PadEvent ev;
    while (queue_.pop(ev)) {}
}

void AudioEngine::reopenAsync() {
    if (reopening_.exchange(true)) return;
    try {
        std::thread([this] {
            static const int delaysMs[5] = {0, 100, 250, 500, 1000};
            for (int i = 0; i < 5; ++i) {
                if (delaysMs[i] > 0) std::this_thread::sleep_for(std::chrono::milliseconds(delaysMs[i]));
                if (!wantRunning_.load(std::memory_order_acquire)) break;
                std::lock_guard<std::mutex> life(lifeMutex_);
                if (!wantRunning_.load(std::memory_order_acquire)) break;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (stream_) break;  // alguien (start()) ya lo reabrió
                }
                if (openAndStart(true)) break;
            }
            reopening_.store(false);
        }).detach();
    } catch (...) {
        reopening_.store(false);
    }
}

void AudioEngine::applyBursts() {
    if (!stream_) return;
    const int32_t burst = stream_->getFramesPerBurst();
    const int32_t cap = stream_->getBufferCapacityInFrames();
    int32_t target = burst * bursts_.load();
    if (cap > 0) target = std::min(target, cap);
    stream_->setBufferSizeInFrames(target);
}

void AudioEngine::setBursts(int bursts) {
    std::lock_guard<std::mutex> lock(mutex_);
    bursts_.store(std::max(1, std::min(bursts, 6)));
    applyBursts();
}

void AudioEngine::autoTune() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!stream_) return;
    auto r = stream_->getXRunCount();
    if (!r) return;
    const int64_t now = nowNs();
    const int x = r.value();
    // Arranque del stream o carga del kit (hasta 20 s): los xruns no son representativos.
    const bool loading = kitLoading_.load(std::memory_order_relaxed) &&
                         now - kitLoadingSinceNs_.load(std::memory_order_relaxed) < 20000000000LL;
    if (now < warmupUntilNs_.load(std::memory_order_relaxed) || loading) {
        lastXruns_ = x;
        quietTicks_ = 0;
        return;
    }
    const int minBursts = mmap_ ? 1 : 2;
    if (x > lastXruns_) {
        bursts_.store(std::min(bursts_.load() + 1, 4));
        applyBursts();
        quietTicks_ = 0;
    } else if (++quietTicks_ >= 120) {  // ~30 s sin xruns (sondeo cada 250 ms)
        quietTicks_ = 0;
        if (bursts_.load() > minBursts) {
            bursts_.store(bursts_.load() - 1);
            applyBursts();
        }
    }
    lastXruns_ = x;
}

void AudioEngine::kitBegin() {
    std::lock_guard<std::mutex> lock(mutex_);
    pending_ = std::make_unique<Kit>();
    pending_->samples.resize(kPadCount * kLayers * kVariants);
    pending_->sampled = true;
    kitLoadingSinceNs_.store(nowNs(), std::memory_order_relaxed);
    kitLoading_.store(true, std::memory_order_relaxed);
}

void AudioEngine::kitSet(int pad, int layer, int variant, const float* data, int frames,
                         int channels, int rate) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!pending_ || pad < 0 || pad >= kPadCount || layer < 0 || layer >= kLayers || variant < 0 ||
        variant >= kVariants || channels < 1 || channels > 2)
        return;
    Sample& s = pending_->samples[sampleIndex(pad, layer, variant)];
    s.data.assign(data, data + static_cast<size_t>(frames) * channels);
    s.channels = channels;
    s.rate = rate;
}

void AudioEngine::kitCommit(int slot) {
    std::lock_guard<std::mutex> lock(mutex_);
    kitLoading_.store(false, std::memory_order_relaxed);
    if (!pending_ || slot < 0 || slot > 1) return;
    sampledKits_[slot] = pending_.get();
    kits_.push_back(std::move(pending_));
    if (slot == kitSlot_ && useSampled_) active_.store(sampledKits_[slot], std::memory_order_release);
}

bool AudioEngine::kitSelect(int slot) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (slot < 0 || slot > 1) return false;
    kitSlot_ = slot;
    if (!sampledKits_[slot]) return false;
    if (useSampled_) active_.store(sampledKits_[slot], std::memory_order_release);
    return true;
}

void AudioEngine::kitUseSampled(bool sampled) {
    std::lock_guard<std::mutex> lock(mutex_);
    useSampled_ = sampled;
    const Kit* k = sampled && sampledKits_[kitSlot_] ? sampledKits_[kitSlot_] : synthKit_;
    if (k) active_.store(k, std::memory_order_release);
}

void AudioEngine::setParam(int id, float value) {
    if (id < 0 || id >= kParamCount) return;
    params_[id].store(value, std::memory_order_relaxed);
    switch (id) {
        case kBpm: seq_.bpm.store(std::max(20.0f, std::min(value, 300.0f))); break;
        case kBeats: seq_.beatsPerBar.store(std::max(1, static_cast<int>(value))); break;
        case kBeatUnit: seq_.beatUnit.store(std::max(1, static_cast<int>(value))); break;
        case kSwing: seq_.swing.store(std::max(0.0f, std::min(value, 0.5f))); break;
        case kMetro: seq_.metro.store(value > 0.5f); break;
        case kRecQuant: seq_.recQuant.store(static_cast<int>(value)); break;
        case kChordQuant: seq_.chordQuant.store(static_cast<int>(value)); break;
        case kMuteMask: seq_.muteMask.store(static_cast<uint32_t>(value)); break;
        default: break;
    }
}

void AudioEngine::trigger(int pad, float velocity, int64_t eventNs) {
    if (!running_.load(std::memory_order_acquire)) { dropped_.fetch_add(1, std::memory_order_relaxed); return; }
    PadEvent ev;
    ev.type = 0;
    ev.a = pad;
    ev.f = velocity;
    ev.eventNs = eventNs > 0 ? eventNs : nowNs();
    if (!queue_.push(ev)) dropped_.fetch_add(1, std::memory_order_relaxed);
}

void AudioEngine::choke(int pad, int64_t eventNs) {
    if (!running_.load(std::memory_order_acquire)) { dropped_.fetch_add(1, std::memory_order_relaxed); return; }
    PadEvent ev;
    ev.type = 1;
    ev.a = pad;
    ev.eventNs = eventNs > 0 ? eventNs : nowNs();
    if (!queue_.push(ev)) dropped_.fetch_add(1, std::memory_order_relaxed);
}

void AudioEngine::synthCmd(int type, int a, int b, float f) {
    if (!running_.load(std::memory_order_acquire)) { dropped_.fetch_add(1, std::memory_order_relaxed); return; }
    PadEvent ev;
    ev.type = type;
    ev.a = a;
    ev.b = b;
    ev.f = f;
    if (!queue_.push(ev)) dropped_.fetch_add(1, std::memory_order_relaxed);
}

SynthParams AudioEngine::synthParams() const {
    SynthParams p;
    p.instrument = static_cast<int>(param(kInstrument));
    p.glideMs = param(kGlideMs);
    p.curve = static_cast<int>(param(kGlideCurve));
    p.volume = param(kChordVol);
    p.cutoff = param(kCutoff);
    p.resonance = param(kResonance);
    p.chorus = param(kChorusMix);
    p.room = param(kSynthRoom);
    p.soloInstrument = static_cast<int>(param(kSoloInstrument));
    p.soloVolume = param(kSoloVol);
    p.soloGlideMs = param(kSoloGlide);
    return p;
}

void AudioEngine::resetStats() {
    lastMs_.store(0);
    avgMs_.store(0);
    maxMs_.store(0);
    count_.store(0);
}

void AudioEngine::getStats(double* out) const {
    for (int i = 0; i < kStatCount; ++i) out[i] = 0;
    std::lock_guard<std::mutex> lock(mutex_);
    if (stream_) {
        out[0] = stream_->getSampleRate();
        out[1] = stream_->getFramesPerBurst();
        out[2] = stream_->getBufferSizeInFrames();
        out[3] = bursts_.load();
        out[4] = mmap_ ? 1 : 0;
        out[5] = stream_->getSharingMode() == oboe::SharingMode::Exclusive ? 1 : 0;
        auto lat = stream_->calculateLatencyMillis();
        out[6] = lat ? lat.value() : -1.0;
        auto xr = stream_->getXRunCount();
        out[10] = xr ? xr.value() : 0;
        out[11] = 1;
        out[12] = stream_->getAudioApi() == oboe::AudioApi::AAudio ? 1 : 0;
    }
    out[7] = lastMs_.load();
    out[8] = avgMs_.load();
    out[9] = maxMs_.load();
    out[13] = dropped_.load(std::memory_order_relaxed);
    out[14] = recDropped_.load(std::memory_order_relaxed);
}

uint32_t AudioEngine::rnd() {
    rng_ ^= rng_ << 13;
    rng_ ^= rng_ >> 17;
    rng_ ^= rng_ << 5;
    return rng_;
}

void AudioEngine::chokeVoices(int pad, int group, float frames) {
    for (auto& v : voices_) {
        if (!v.active || v.envStep != 0) continue;
        if ((group != 0 && v.group == group) || (group == 0 && v.pad == pad))
            v.envStep = std::max(v.env, 0.001f) / std::max(frames, 8.0f);
    }
}

void AudioEngine::pushViz(int type, int a, int b, float f, float glideMs) {
    VizEvent v;
    v.timeMs = static_cast<double>(blockNs_) / 1.0e6;
    v.type = type;
    v.a = a;
    v.f = f;
    v.glideMs = glideMs >= 0.0f ? glideMs : param(kGlideMs);
    v.curve = static_cast<int>(param(kGlideCurve)) | (b << 8);  // curva en 8 bits bajos, voz de origen arriba
    viz_.push(v);
}

void AudioEngine::execSynth(const LoopEvent& e) {
    const SynthParams p = synthParams();
    switch (e.type) {
        case 10: synth_.noteOn(e.a, e.f, p); break;
        case 11: synth_.glideTo(e.a, e.f, p, false); break;
        case 12: synth_.spawn(e.a, e.b, e.f, p); break;
        case 13: {
            // b > 0: tiempo de glide propio de esta salida (p. ej. fundir las voces al apagar); 0 = glide global.
            SynthParams q = p;
            if (e.b > 0) q.glideMs = static_cast<float>(e.b);
            synth_.glideTo(e.a, e.f, q, true);
            pushViz(e.type, e.a, 0, e.f, q.glideMs);
            return;
        }
        case 14:
            // b > 0: apagado propio en ms (la salida fundida se desvanece despacio); 0 = release del instrumento.
            synth_.release(e.a, static_cast<float>(e.b));
            pushViz(e.type, e.a, 0, e.f, static_cast<float>(e.b));
            return;
        case 15: synth_.releaseAll(); break;
        default: return;
    }
    pushViz(e.type, e.a, e.b, e.f);
}

struct ReplicaEngineSink : ReplicaSink {
    AudioEngine& e;
    explicit ReplicaEngineSink(AudioEngine& eng) : e(eng) {}
    void drum(int pad, float vel, int off) override { e.triggerDrum(pad, vel, off); }
    void bass(int midi, float vel, int off, int dur) override { e.bass_.trigger(static_cast<float>(midi), vel, off, dur); }
    void melody(int midi, float vel, int dur) override {
        const SynthParams p = e.synthParams();
        constexpr int idx = SynthEngine::kSoloVoices - 1;
        e.synth_.setSolo(idx, static_cast<float>(midi), 0.75f, 0.0f, 0.5f + 0.5f * vel);
        e.synth_.soloOn(idx, static_cast<float>(midi), p);
        e.patSynthLeft_[8] = dur;
    }
};

struct EngineSink : SeqSink {
    AudioEngine& e;
    explicit EngineSink(AudioEngine& eng) : e(eng) {}
    void drumHit(int pad, float vel, int off) override {
        if (pad >= kChordPadBase) e.triggerPatternSynth(pad, vel);
        else if (pad >= kBassPadBase) e.triggerBass(pad - kBassPadBase, vel, off);
        else e.triggerDrum(pad, vel, off);
    }
    void synthEvent(const LoopEvent& ev) override { e.execSynth(ev); }
    void releaseChords() override {
        LoopEvent ev;
        ev.type = 15;
        e.execSynth(ev);
    }
    void click(bool accent, int off) override {
        for (auto& c : e.clicks_) {
            if (c.active) continue;
            const Sample& s = accent ? e.clickAcc_ : e.clickNorm_;
            c.d = s.data.data();
            c.len = static_cast<int32_t>(s.data.size());
            c.pos = 0;
            c.delay = off;
            c.active = true;
            return;
        }
    }
};

void AudioEngine::handleEvent(const PadEvent& ev, int64_t now) {
    if (ev.type <= 1) { startVoice(ev, now); return; }
    EngineSink sink(*this);
    if (ev.type >= 10 && ev.type <= 15) {
        LoopEvent le;
        le.type = ev.type;
        le.a = ev.a;
        le.b = ev.b;
        le.f = ev.f;
        le.tick = -1;  // vivo: se graba en la posición actual
        if (seq_.scheduleChord(le)) {
            seq_.recordChord(le);
            execSynth(le);
        }
        return;
    }
    if (ev.type >= 20 && ev.type <= 34) {
        seq_.command(ev.type, ev.a, ev.b, ev.f, sink);
        return;
    }
    const SynthParams p = synthParams();
    switch (ev.type) {
        case 16: synth_.soloOn(ev.a, ev.f, p); break;
        case 17: synth_.soloOff(ev.a); break;
        default: break;
    }
}

void AudioEngine::getSeqStatus(double* out) const {
    out[0] = static_cast<double>(seq_.posTick.load());
    out[1] = seq_.playing.load() ? 1 : 0;
    out[2] = seq_.drumPassCount.load();
    out[3] = seq_.recDrumOn.load() ? 1 : 0;
    out[4] = seq_.recChordOn.load() ? 1 : 0;
    out[5] = seq_.drumEventCount.load();
    out[6] = seq_.chordEventCount.load();
    out[7] = static_cast<double>(seq_.patLocalTick.load());
    out[8] = seq_.patternEnabled() ? 1 : 0;
    out[9] = seq_.hasDeferred() ? 1 : 0;
    out[10] = seq_.swapCount.load(std::memory_order_relaxed);
}

int AudioEngine::pollViz(double* out, int maxEvents) {
    int n = 0;
    VizEvent v;
    while (n < maxEvents && viz_.pop(v)) {
        double* o = out + 6 * n;
        o[0] = v.type; o[1] = v.a; o[2] = v.f; o[3] = v.glideMs; o[4] = v.curve; o[5] = v.timeMs;
        ++n;
    }
    return n;
}

void AudioEngine::setRecording(bool on) {
    if (on) {
        recTail_.store(recHead_.load());
        recDropped_.store(0, std::memory_order_relaxed);
    }
    recording_.store(on, std::memory_order_release);
}

int AudioEngine::readRecording(float* dst, int maxFloats) {
    const size_t cap = recBuf_.size();
    size_t t = recTail_.load(std::memory_order_relaxed);
    const size_t h = recHead_.load(std::memory_order_acquire);
    int n = 0;
    while (t != h && n < maxFloats) {
        dst[n++] = recBuf_[t];
        t = (t + 1) & (cap - 1);
    }
    recTail_.store(t, std::memory_order_release);
    return n;
}

void AudioEngine::startVoice(const PadEvent& ev, int64_t now) {
    const int pad = ev.a;
    if (pad < 0 || pad >= kPadCount || active_.load(std::memory_order_acquire) == nullptr) return;

    if (ev.type == 1) {  // choke manual de platos
        chokeVoices(pad, 0, 0.02f * sr_);
        return;
    }

    // Latencia toque -> callback (cola + despacho de entrada).
    const double ms = static_cast<double>(now - ev.eventNs) / 1.0e6;
    if (ms >= 0 && ms < 1000) {
        lastMs_.store(ms, std::memory_order_relaxed);
        const int64_t c = count_.fetch_add(1, std::memory_order_relaxed);
        const double a = avgMs_.load(std::memory_order_relaxed);
        avgMs_.store(c == 0 ? ms : a + (ms - a) * 0.1, std::memory_order_relaxed);
        if (ms > maxMs_.load(std::memory_order_relaxed)) maxMs_.store(ms, std::memory_order_relaxed);
    }
    seq_.recordDrum(pad, ev.f);
    triggerDrum(pad, ev.f, 0);
}

void AudioEngine::triggerBass(int note, float packed, int delayFrames) {
    const float ticks = std::floor(packed);
    const float vel = packed - ticks;
    const double spt = sr_ * 60.0 / (std::max(20.0f, param(kBpm)) * kPPQ);
    bass_.trigger(static_cast<float>(note), vel, delayFrames, static_cast<int>(std::max(1.0f, ticks) * spt));
}

void AudioEngine::triggerPatternSynth(int pad, float packed) {
    const float ticks = std::floor(packed);
    const double spt = sr_ * 60.0 / (std::max(20.0f, param(kBpm)) * kPPQ);
    const int dur = static_cast<int>(std::max(1.0f, ticks) * spt);
    const SynthParams p = synthParams();
    if (pad >= kSoloPadBase) {
        const float midi = static_cast<float>(pad - kSoloPadBase);
        constexpr int idx = SynthEngine::kSoloVoices - 1;
        synth_.setSolo(idx, midi, 0.75f, 0.0f, 1.0f);
        synth_.soloOn(idx, midi, p);
        patSynthLeft_[8] = dur;
        return;
    }
    const int slot = std::min((pad - kChordPadBase) / 128, 7);
    const float midi = static_cast<float>((pad - kChordPadBase) % 128);
    LoopEvent e;
    e.type = synth_.isHeld(slot) ? 11 : 10;  // si la voz sigue sonando, se desliza a la nota nueva
    e.a = slot;
    e.f = midi;
    execSynth(e);
    patSynthLeft_[slot] = dur;
}

void AudioEngine::triggerDrum(int pad, float velocity, int delayFrames) {
    const Kit* kit = active_.load(std::memory_order_acquire);
    if (pad < 0 || pad >= kPadCount || kit == nullptr) return;
    pushViz(0, pad, 0, velocity);
    const int group = kGroupOf[pad];
    if (group != 0) {
        chokeVoices(pad, group, 0.006f * sr_);       // hi-hat: el nuevo corta al anterior
    } else if (!isCymbal(pad)) {
        chokeVoices(pad, 0, 96.0f);                  // re-golpe sin clic
    }

    const float vel = std::max(0.0f, std::min(velocity, 1.0f));
    const bool real = kit->sampled;
    const int layer = real ? (vel < 0.3f ? 0 : (vel < 0.7f ? 1 : 2)) : (vel < 0.4f ? 0 : (vel < 0.75f ? 1 : 2));
    int variant = (lastVariant_[pad] + 1 + static_cast<int>(rnd() % (kVariants - 1))) % kVariants;
    if (kit->samples[sampleIndex(pad, layer, variant)].data.empty()) {
        variant = -1;
        for (int v = 0; v < kVariants; ++v)
            if (!kit->samples[sampleIndex(pad, layer, v)].data.empty()) { variant = v; break; }
        if (variant < 0) return;
    }
    lastVariant_[pad] = variant;
    static const float nominalSynth[kLayers] = {0.35f, 0.65f, 1.0f};
    static const float centerReal[kLayers] = {0.12f, 0.5f, 0.9f};

    Voice* slot = nullptr;
    for (auto& v : voices_) {
        if (!v.active) { slot = &v; break; }
    }
    if (!slot) {
        double best = 1e18;
        for (auto& v : voices_) {
            const double rem = v.length - v.pos;
            if (rem < best) { best = rem; slot = &v; }
        }
    }

    float tune = 0;
    if (pad == 1 || pad == 2) tune = param(kSnareTune);
    else if (pad >= 6 && pad <= 8) tune = param(kTomTune);

    const float human = 1.0f + (static_cast<int>(rnd() % 1000) - 500) * 0.00005f;
    const Sample& s = kit->samples[sampleIndex(pad, layer, variant)];
    slot->data = s.data.data();
    slot->ch = s.channels;
    slot->length = s.frames();
    slot->pos = 0;
    const float ratio = s.rate > 0 ? static_cast<float>(s.rate) / sr_ : 1.0f;
    slot->rate = (tune == 0 ? 1.0f : std::exp2(tune / 12.0f)) * ratio;
    float g = real ? std::min(1.6f, std::max(0.35f, std::pow(vel / centerReal[layer], 0.6f)))
                   : vel / nominalSynth[layer];
    slot->gain = g * human * param(padVolParam(pad));
    slot->env = 1.0f;
    slot->envStep = 0;
    slot->pad = pad;
    slot->group = group;
    slot->delay = delayFrames;
    slot->active = true;
}


oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream*, void* audioData,
                                                   int32_t numFrames) {
    float* out = static_cast<float*>(audioData);
    const int64_t now = nowNs();
    blockNs_ = now;

    PadEvent ev;
    while (queue_.pop(ev)) handleEvent(ev, now);
    EngineSink sink(*this);
    seq_.process(numFrames, sink);

    std::fill(out, out + numFrames * 2, 0.0f);

    for (auto& v : voices_) {
        if (!v.active) continue;
        const float* d = v.data;
        int32_t first = 0;
        if (v.delay > 0) {
            first = std::min(v.delay, numFrames);
            v.delay -= first;
        }
        for (int32_t i = first; i < numFrames; ++i) {
            const int32_t i0 = static_cast<int32_t>(v.pos);
            if (i0 + 1 >= v.length) { v.active = false; break; }
            const float fr = static_cast<float>(v.pos - i0);
            const float g = v.gain * v.env;
            if (v.ch == 2) {
                const float* a = d + 2 * i0;
                out[2 * i] += (a[0] + (a[2] - a[0]) * fr) * g;
                out[2 * i + 1] += (a[1] + (a[3] - a[1]) * fr) * g;
            } else {
                const float s = (d[i0] + (d[i0 + 1] - d[i0]) * fr) * g;
                out[2 * i] += s;
                out[2 * i + 1] += s;
            }
            v.pos += v.rate;
            if (v.envStep > 0) {
                v.env -= v.envStep;
                if (v.env <= 0) { v.active = false; break; }
            }
        }
    }

    // Bus de batería: sala y compresión.
    const float room = param(kRoom);
    const float comp = param(kComp);
    const float drumVol = param(kDrumVol);
    const float thr = 1.0f - 0.7f * comp;
    const float ratio = 1.0f + 5.0f * comp;
    const float makeup = (1.0f + 0.5f * comp) * drumVol;
    const float atk = 1.0f - std::exp(-1.0f / (0.001f * sr_));
    const float rel = 1.0f - std::exp(-1.0f / (0.12f * sr_));
    const bool useRoom = room > 0.001f && drumRoom_.ready;

    for (int32_t i = 0; i < numFrames; ++i) {
        float l = out[2 * i];
        float r = out[2 * i + 1];
        if (useRoom) {
            float wl, wr;
            drumRoom_.process((l + r) * 0.5f * 0.12f, wl, wr);
            l += wl * room * 1.6f;
            r += wr * room * 1.6f;
        }
        const float lvl = std::max(std::fabs(l), std::fabs(r));
        compEnv_ += (lvl > compEnv_ ? atk : rel) * (lvl - compEnv_);
        float g = 1.0f;
        if (compEnv_ > thr) g = (thr + (compEnv_ - thr) / ratio) / compEnv_;
        g *= makeup;
        out[2 * i] = l * g;
        out[2 * i + 1] = r * g;
    }

    // Bajo (fuera del bus de batería).
    const bool seqPlaying = seq_.playing.load(std::memory_order_relaxed);
    if (bassWasPlaying_ && !seqPlaying) {
        bass_.allOff();
        for (int& left : patSynthLeft_) if (left > 0) left = 1;
    }
    for (int i = 0; i < 9; ++i) {
        if (patSynthLeft_[i] <= 0) continue;
        patSynthLeft_[i] -= numFrames;
        if (patSynthLeft_[i] > 0) continue;
        patSynthLeft_[i] = 0;
        if (i == 8) {
            synth_.soloOff(SynthEngine::kSoloVoices - 1);
        } else {
            LoopEvent e;
            e.type = 14;
            e.a = i;
            execSynth(e);
        }
    }
    bassWasPlaying_ = seqPlaying;
    bass_.render(out, numFrames, param(kBassTone), param(kBassVol));
    const bool stemsWerePlaying = stems_.playing();
    const double stemP0 = stems_.audioPos();
    stems_.render(out, numFrames, sr_);
    if (stemsWerePlaying) {
        ReplicaEngineSink rs(*this);
        replica_.process(stemP0, stems_.audioPos(), stems_.takeJump(), sr_, stems_.speed(), numFrames, rs);
    }

    // Bus de acordes/solista y mezcla final.
    const SynthParams sp = synthParams();
    const float master = param(kMaster);
    const float metroVol = param(kMetroVol);
    for (auto& cv : clicks_) {
        if (!cv.active) continue;
        int32_t first = 0;
        if (cv.delay > 0) { first = std::min(cv.delay, numFrames); cv.delay -= first; }
        for (int32_t i = first; i < numFrames; ++i) {
            if (cv.pos >= cv.len) { cv.active = false; break; }
            const float s = cv.d[cv.pos++] * metroVol;
            out[2 * i] += s;
            out[2 * i + 1] += s;
        }
    }
    float peak = 0;
    for (int32_t off = 0; off < numFrames; off += SynthEngine::kMaxBlock) {
        const int n = std::min<int32_t>(SynthEngine::kMaxBlock, numFrames - off);
        std::fill(synthL_, synthL_ + n, 0.0f);
        std::fill(synthR_, synthR_ + n, 0.0f);
        synth_.render(synthL_, synthR_, n, sp);
        for (int i = 0; i < n; ++i) {
            float* o = out + 2 * (off + i);
            o[0] = softClip((o[0] + synthL_[i]) * master);
            o[1] = softClip((o[1] + synthR_[i]) * master);
            peak = std::max(peak, std::max(std::fabs(o[0]), std::fabs(o[1])));
        }
    }
    if (recording_.load(std::memory_order_relaxed)) {
        const size_t cap = recBuf_.size();
        size_t h = recHead_.load(std::memory_order_relaxed);
        const size_t t = recTail_.load(std::memory_order_acquire);
        for (int32_t i = 0; i < numFrames * 2; ++i) {
            const size_t nh = (h + 1) & (cap - 1);
            if (nh == t) {  // lleno: el lector no da abasto
                recDropped_.fetch_add(numFrames * 2 - i, std::memory_order_relaxed);
                break;
            }
            recBuf_[h] = out[i];
            h = nh;
        }
        recHead_.store(h, std::memory_order_release);
    }
    level_.store(std::max(peak, level_.load(std::memory_order_relaxed) * 0.85f), std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    // Desconexión (p. ej. auriculares): reabrir en un hilo propio, con reintentos, fuera del callback.
    if (!wantRunning_.load(std::memory_order_acquire) || error != oboe::Result::ErrorDisconnected) return;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (stream_.get() == stream) {
            running_.store(false, std::memory_order_release);
            stream_.reset();
        }
    }
    reopenAsync();
}
