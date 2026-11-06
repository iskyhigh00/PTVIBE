#include "audio_engine.h"
#include <oboe/OboeExtensions.h>
#include <algorithm>
#include <cmath>
#include <ctime>

namespace {
inline int64_t nowNs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

// Saturación suave sin clics (limitador simple de salida).
inline float softClip(float x) {
    const float a = std::fabs(x);
    if (a < 0.8f) return x;
    const float s = x < 0 ? -1.0f : 1.0f;
    return s * (0.8f + 0.2f * std::tanh((a - 0.8f) * 5.0f));
}
}  // namespace

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

    if (kitRate_ != s->getSampleRate()) {
        buildSynthKit(s->getSampleRate(), kit_);  // stream aún parado: seguro
        kitRate_ = s->getSampleRate();
    }
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
        lastXruns_ = r.value();
        bursts_.fetch_add(1);
        applyBursts();
    } else {
        lastXruns_ = r.value();
    }
}

void AudioEngine::trigger(int pad, float velocity, int64_t eventNs) {
    if (!running_.load(std::memory_order_acquire)) return;
    PadEvent ev;
    ev.pad = pad;
    ev.velocity = velocity;
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
        auto xr = const_cast<oboe::AudioStream*>(stream_.get())->getXRunCount();
        out[10] = xr ? xr.value() : 0;
        out[11] = 1;
        out[12] = stream_->getAudioApi() == oboe::AudioApi::AAudio ? 1 : 0;
    }
    out[7] = lastMs_.load();
    out[8] = avgMs_.load();
    out[9] = maxMs_.load();
}

void AudioEngine::startVoice(const PadEvent& ev, int64_t now) {
    if (ev.pad < 0 || ev.pad >= static_cast<int>(kit_.size())) return;

    // Latencia toque -> callback (la cola + el despacho de entrada).
    const double ms = static_cast<double>(now - ev.eventNs) / 1.0e6;
    if (ms >= 0 && ms < 1000) {
        lastMs_.store(ms, std::memory_order_relaxed);
        const int64_t c = count_.fetch_add(1, std::memory_order_relaxed);
        const double a = avgMs_.load(std::memory_order_relaxed);
        avgMs_.store(c == 0 ? ms : a + (ms - a) * 0.1, std::memory_order_relaxed);
        if (ms > maxMs_.load(std::memory_order_relaxed)) maxMs_.store(ms, std::memory_order_relaxed);
    }

    // Fade-out rápido de la misma pad (re-golpe sin clic).
    for (auto& v : voices_) {
        if (v.active && v.pad == ev.pad && v.envStep == 0) v.envStep = v.env / kReleaseFrames;
    }

    Voice* slot = nullptr;
    for (auto& v : voices_) {
        if (!v.active) { slot = &v; break; }
    }
    if (!slot) {  // robar la voz con menos restante
        int32_t best = INT32_MAX;
        for (auto& v : voices_) {
            const int32_t rem = v.length - v.pos;
            if (rem < best) { best = rem; slot = &v; }
        }
    }
    const Sample& s = kit_[ev.pad];
    slot->data = s.data.data();
    slot->length = static_cast<int32_t>(s.data.size());
    slot->pos = 0;
    slot->gain = std::max(0.0f, std::min(ev.velocity, 1.0f));
    slot->env = 1.0f;
    slot->envStep = 0;
    slot->pad = ev.pad;
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
        const int32_t n = std::min(numFrames, v.length - v.pos);
        for (int32_t i = 0; i < n; ++i) {
            const float s = v.data[v.pos + i] * v.gain * v.env;
            out[2 * i] += s;
            out[2 * i + 1] += s;
            if (v.envStep > 0) {
                v.env -= v.envStep;
                if (v.env <= 0) { v.env = 0; v.pos = v.length; break; }
            }
        }
        v.pos += n;
        if (v.pos >= v.length) v.active = false;
    }

    for (int32_t i = 0; i < numFrames * 2; ++i) out[i] = softClip(out[i]);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result) {
    // Desconexión (p. ej. auriculares): reabrir en otro hilo, fuera del callback.
    std::lock_guard<std::mutex> lock(mutex_);
    stream_.reset();
    running_.store(false);
    openAndStart();
}
