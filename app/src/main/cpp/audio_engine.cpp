#include "audio_engine.h"
#include <oboe/OboeExtensions.h>
#include <algorithm>
#include <climits>
#include <cmath>
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
constexpr int kGroupOf[kPadCount] = {0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
inline bool isCymbal(int pad) { return pad >= 9; }

constexpr int kCombLen[4] = {1116, 1188, 1277, 1356};
constexpr int kApLen[2] = {556, 441};
}  // namespace

AudioEngine::AudioEngine() {
    for (auto& p : params_) p.store(0.0f);
    params_[kRoom].store(0.25f);
    params_[kComp].store(0.3f);
    params_[kMaster].store(0.9f);
    for (int i = 0; i < kPadCount; ++i) params_[kPadVol + i].store(1.0f);
}

bool AudioEngine::start() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stream_) return true;
    return openAndStart();
}

bool AudioEngine::openAndStart() {
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

    sr_ = s->getSampleRate();
    if (kitRate_ != sr_) {
        buildSynthKit(sr_, kit_);  // stream aún parado: seguro
        kitRate_ = sr_;
    }
    for (int i = 0; i < 4; ++i) {
        combs_[i].buf.assign(std::max(8, kCombLen[i] * sr_ / 44100), 0.0f);
        combs_[i].idx = 0;
        combs_[i].damp = 0;
    }
    for (int i = 0; i < 2; ++i) {
        aps_[i].buf.assign(std::max(8, kApLen[i] * sr_ / 44100), 0.0f);
        aps_[i].idx = 0;
    }
    compEnv_ = 0;
    for (auto& v : voices_) v = Voice();
    stream_ = s;
    mmap_ = oboe::OboeExtensions::isMMapUsed(s.get());
    lastXruns_ = 0;
    applyBursts();
    running_.store(true, std::memory_order_release);
    if (s->requestStart() != oboe::Result::OK) {
        running_.store(false);
        s->close();
        stream_.reset();
        return false;
    }
    return true;
}

void AudioEngine::stop() {
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
    if (r.value() > lastXruns_ && bursts_.load() < 4) {
        bursts_.fetch_add(1);
        applyBursts();
    }
    lastXruns_ = r.value();
}

void AudioEngine::setParam(int id, float value) {
    if (id >= 0 && id < kParamCount) params_[id].store(value, std::memory_order_relaxed);
}

void AudioEngine::trigger(int pad, float velocity, int64_t eventNs) {
    if (!running_.load(std::memory_order_acquire)) return;
    PadEvent ev;
    ev.type = 0;
    ev.pad = pad;
    ev.velocity = velocity;
    ev.eventNs = eventNs > 0 ? eventNs : nowNs();
    queue_.push(ev);
}

void AudioEngine::choke(int pad, int64_t eventNs) {
    if (!running_.load(std::memory_order_acquire)) return;
    PadEvent ev;
    ev.type = 1;
    ev.pad = pad;
    ev.eventNs = eventNs > 0 ? eventNs : nowNs();
    queue_.push(ev);
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

void AudioEngine::startVoice(const PadEvent& ev, int64_t now) {
    const int pad = ev.pad;
    if (pad < 0 || pad >= kPadCount || kit_.empty()) return;

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

    const int group = kGroupOf[pad];
    if (group != 0) {
        chokeVoices(pad, group, 0.006f * sr_);       // hi-hat: el nuevo corta al anterior
    } else if (!isCymbal(pad)) {
        chokeVoices(pad, 0, 96.0f);                  // re-golpe sin clic
    }

    const float vel = std::max(0.0f, std::min(ev.velocity, 1.0f));
    const int layer = vel < 0.4f ? 0 : (vel < 0.75f ? 1 : 2);
    const int variant = (lastVariant_[pad] + 1 + static_cast<int>(rnd() % (kVariants - 1))) % kVariants;
    lastVariant_[pad] = variant;
    static const float nominal[kLayers] = {0.35f, 0.65f, 1.0f};

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
    const Sample& s = kit_[sampleIndex(pad, layer, variant)];
    slot->data = s.data.data();
    slot->length = static_cast<int32_t>(s.data.size());
    slot->pos = 0;
    slot->rate = tune == 0 ? 1.0f : std::exp2(tune / 12.0f);
    slot->gain = (vel / nominal[layer]) * human * param(kPadVol + pad);
    slot->env = 1.0f;
    slot->envStep = 0;
    slot->pad = pad;
    slot->group = group;
    slot->active = true;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream*, void* audioData,
                                                   int32_t numFrames) {
    float* out = static_cast<float*>(audioData);
    const int64_t now = nowNs();

    PadEvent ev;
    while (queue_.pop(ev)) startVoice(ev, now);

    std::fill(out, out + numFrames * 2, 0.0f);

    for (auto& v : voices_) {
        if (!v.active) continue;
        const float* d = v.data;
        for (int32_t i = 0; i < numFrames; ++i) {
            const int32_t i0 = static_cast<int32_t>(v.pos);
            if (i0 + 1 >= v.length) { v.active = false; break; }
            const float fr = static_cast<float>(v.pos - i0);
            const float s = (d[i0] + (d[i0 + 1] - d[i0]) * fr) * v.gain * v.env;
            out[2 * i] += s;
            out[2 * i + 1] += s;
            v.pos += v.rate;
            if (v.envStep > 0) {
                v.env -= v.envStep;
                if (v.env <= 0) { v.active = false; break; }
            }
        }
    }

    // Sala, compresión y master.
    const float room = param(kRoom);
    const float comp = param(kComp);
    const float master = param(kMaster);
    const float thr = 1.0f - 0.7f * comp;
    const float ratio = 1.0f + 5.0f * comp;
    const float makeup = 1.0f + 0.5f * comp;
    const float atk = 1.0f - std::exp(-1.0f / (0.001f * sr_));
    const float rel = 1.0f - std::exp(-1.0f / (0.12f * sr_));
    const bool useRoom = room > 0.001f && !combs_[0].buf.empty();

    for (int32_t i = 0; i < numFrames; ++i) {
        float l = out[2 * i];
        float r = out[2 * i + 1];

        if (useRoom) {
            const float in = (l + r) * 0.5f * 0.12f;
            float y[4];
            for (int k = 0; k < 4; ++k) {
                Comb& c = combs_[k];
                y[k] = c.buf[c.idx];
                c.damp = y[k] * 0.65f + c.damp * 0.35f;
                c.buf[c.idx] = in + c.damp * 0.8f;
                if (++c.idx >= static_cast<int>(c.buf.size())) c.idx = 0;
            }
            float wl = y[0] + y[2];
            float wr = y[1] + y[3];
            Allpass* ap[2] = {&aps_[0], &aps_[1]};
            float* w[2] = {&wl, &wr};
            for (int k = 0; k < 2; ++k) {
                const float bo = ap[k]->buf[ap[k]->idx];
                const float o = -*w[k] + bo;
                ap[k]->buf[ap[k]->idx] = *w[k] + bo * 0.5f;
                if (++ap[k]->idx >= static_cast<int>(ap[k]->buf.size())) ap[k]->idx = 0;
                *w[k] = o;
            }
            l += wl * room * 1.6f;
            r += wr * room * 1.6f;
        }

        const float lvl = std::max(std::fabs(l), std::fabs(r));
        compEnv_ += (lvl > compEnv_ ? atk : rel) * (lvl - compEnv_);
        float g = 1.0f;
        if (compEnv_ > thr) g = (thr + (compEnv_ - thr) / ratio) / compEnv_;
        g *= makeup * master;

        out[2 * i] = softClip(l * g);
        out[2 * i + 1] = softClip(r * g);
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result) {
    // Desconexión (p. ej. auriculares): reabrir fuera del callback.
    std::lock_guard<std::mutex> lock(mutex_);
    stream_.reset();
    running_.store(false);
    openAndStart();
}
