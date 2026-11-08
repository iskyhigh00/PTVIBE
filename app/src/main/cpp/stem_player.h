#pragma once
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

// Reproductor de pistas (stems) sincronizadas: hasta kMax pistas PCM 16 bit con volumen, paneo, mudo/solo,
// bucle A-B y velocidad. render() solo desde el hilo de audio (sin asignar ni bloquear); los demás métodos desde la UI.
class StemPlayer {
public:
    static constexpr int kMax = 8;

    struct Buf {
        std::vector<int16_t> d;  // intercalado
        int ch = 2;
        int rate = 44100;
        int64_t frames = 0;
        double seconds() const { return rate > 0 ? static_cast<double>(frames) / rate : 0.0; }
    };

    StemPlayer() {
        for (int i = 0; i < kMax; ++i) {
            buf_[i].store(nullptr);
            vol_[i].store(1.0f);
            pan_[i].store(0.0f);
            mute_[i].store(false);
            solo_[i].store(false);
        }
    }

    // ---- hilo de carga / UI
    void load(int idx, const int16_t* data, int64_t frames, int channels, int rate) {
        if (idx < 0 || idx >= kMax || channels < 1 || channels > 2 || rate < 8000 || frames <= 0) return;
        auto b = std::make_unique<Buf>();
        b->d.assign(data, data + frames * channels);
        b->ch = channels;
        b->rate = rate;
        b->frames = frames;
        std::lock_guard<std::mutex> lock(mutex_);
        const Buf* old = buf_[idx].exchange(b.get(), std::memory_order_acq_rel);
        live_.push_back(std::move(b));
        if (old) retire(old);
        recomputeDuration();
    }

    void clear(int idx) {
        if (idx < 0 || idx >= kMax) return;
        std::lock_guard<std::mutex> lock(mutex_);
        const Buf* old = buf_[idx].exchange(nullptr, std::memory_order_acq_rel);
        if (old) retire(old);
        recomputeDuration();
    }

    void clearAll() { for (int i = 0; i < kMax; ++i) clear(i); playing_.store(false); }

    /** Libera los buffers retirados hace más de un segundo (el hilo de audio ya no los puede estar leyendo). */
    void freeRetired() {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto now = std::chrono::steady_clock::now();
        for (size_t i = 0; i < retired_.size();) {
            if (now - retired_[i].at > std::chrono::seconds(1)) {
                const Buf* p = retired_[i].p;
                live_.erase(std::remove_if(live_.begin(), live_.end(), [&](const std::unique_ptr<Buf>& u) { return u.get() == p; }), live_.end());
                retired_.erase(retired_.begin() + static_cast<long>(i));
            } else {
                ++i;
            }
        }
    }

    void setTrack(int idx, float vol, float pan, bool mute, bool solo) {
        if (idx < 0 || idx >= kMax) return;
        vol_[idx].store(std::max(0.0f, std::min(vol, 2.0f)), std::memory_order_relaxed);
        pan_[idx].store(std::max(-1.0f, std::min(pan, 1.0f)), std::memory_order_relaxed);
        mute_[idx].store(mute, std::memory_order_relaxed);
        solo_[idx].store(solo, std::memory_order_relaxed);
    }
    void setMaster(float master, float speed) {
        master_.store(std::max(0.0f, std::min(master, 1.5f)), std::memory_order_relaxed);
        speed_.store(std::max(0.25f, std::min(speed, 2.0f)), std::memory_order_relaxed);
    }
    void play(bool on) { playing_.store(on, std::memory_order_release); }
    void seek(double sec) { seekTo_.store(sec, std::memory_order_release); }
    void setLoop(double a, double b) { loopA_.store(a, std::memory_order_relaxed); loopB_.store(b, std::memory_order_relaxed); }

    bool playing() const { return playing_.load(std::memory_order_acquire); }
    // Solo hilo de audio: posición exacta al empezar el bloque, velocidad y si hubo un salto (búsqueda, bucle o fin).
    double audioPos() const { return pos_d_; }
    float speed() const { return speed_.load(std::memory_order_relaxed); }
    bool takeJump() { const bool j = jumped_; jumped_ = false; return j; }
    double position() const { return pos_.load(std::memory_order_relaxed); }
    double duration() const { return duration_.load(std::memory_order_relaxed); }
    bool hasTracks() const { return duration() > 0; }

    // ---- hilo de audio: suma en out (estéreo intercalado)
    void render(float* out, int n, int sr) {
        double seek = seekTo_.load(std::memory_order_acquire);
        if (seek >= 0) {
            pos_d_ = std::min(seek, duration());
            jumped_ = true;
            seekTo_.store(-1.0, std::memory_order_release);
        }
        if (!playing_.load(std::memory_order_acquire)) { for (int i = 0; i < kMax; ++i) cur_[i][0] = cur_[i][1] = 0; pos_.store(pos_d_, std::memory_order_relaxed); return; }
        const double dur = duration();
        if (dur <= 0) return;
        const double step = static_cast<double>(speed_.load(std::memory_order_relaxed)) / sr;
        const float master = master_.load(std::memory_order_relaxed);
        const double la = loopA_.load(std::memory_order_relaxed), lb = loopB_.load(std::memory_order_relaxed);
        const bool loop = la >= 0 && lb > la + 0.05;
        bool anySolo = false;
        for (int k = 0; k < kMax; ++k) anySolo = anySolo || (buf_[k].load(std::memory_order_acquire) && solo_[k].load(std::memory_order_relaxed));

        const Buf* b[kMax];
        float tl[kMax], tr[kMax];
        for (int k = 0; k < kMax; ++k) {
            b[k] = buf_[k].load(std::memory_order_acquire);
            const bool audible = b[k] && !mute_[k].load(std::memory_order_relaxed) && (!anySolo || solo_[k].load(std::memory_order_relaxed));
            const float v = audible ? vol_[k].load(std::memory_order_relaxed) * master : 0.0f;
            const float p = pan_[k].load(std::memory_order_relaxed);
            tl[k] = v * std::min(1.0f, 1.0f - p);
            tr[k] = v * std::min(1.0f, 1.0f + p);
        }
        float gl[kMax], gr[kMax], dl[kMax], dr[kMax];
        for (int k = 0; k < kMax; ++k) {  // rampa lineal en el bloque: sin clics al cambiar volumen o mudo
            gl[k] = cur_[k][0]; gr[k] = cur_[k][1];
            dl[k] = (tl[k] - gl[k]) / static_cast<float>(n);
            dr[k] = (tr[k] - gr[k]) / static_cast<float>(n);
        }
        for (int i = 0; i < n; ++i) {
            if (loop && pos_d_ >= lb) { pos_d_ = la + (pos_d_ - lb); jumped_ = true; }
            if (pos_d_ >= dur) { playing_.store(false, std::memory_order_release); pos_d_ = 0; jumped_ = true; break; }
            for (int k = 0; k < kMax; ++k) {
                gl[k] += dl[k]; gr[k] += dr[k];
                const Buf* s = b[k];
                if (!s || (gl[k] == 0 && gr[k] == 0 && tl[k] == 0 && tr[k] == 0)) continue;
                const double fp = pos_d_ * s->rate;
                const int64_t i0 = static_cast<int64_t>(fp);
                if (i0 + 1 >= s->frames) continue;
                const float fr = static_cast<float>(fp - i0);
                const int16_t* p = s->d.data();
                float l, r;
                if (s->ch == 2) {
                    l = (p[2 * i0] + (p[2 * i0 + 2] - p[2 * i0]) * fr) * (1.0f / 32768.0f);
                    r = (p[2 * i0 + 1] + (p[2 * i0 + 3] - p[2 * i0 + 1]) * fr) * (1.0f / 32768.0f);
                } else {
                    l = r = (p[i0] + (p[i0 + 1] - p[i0]) * fr) * (1.0f / 32768.0f);
                }
                out[2 * i] += l * gl[k];
                out[2 * i + 1] += r * gr[k];
            }
            pos_d_ += step;
        }
        for (int k = 0; k < kMax; ++k) { cur_[k][0] = tl[k]; cur_[k][1] = tr[k]; }
        pos_.store(pos_d_, std::memory_order_relaxed);
    }

private:
    struct Retired { const Buf* p; std::chrono::steady_clock::time_point at; };

    void retire(const Buf* old) { retired_.push_back({old, std::chrono::steady_clock::now()}); }
    void recomputeDuration() {
        double d = 0;
        for (int i = 0; i < kMax; ++i) if (const Buf* p = buf_[i].load()) d = std::max(d, p->seconds());
        duration_.store(d, std::memory_order_relaxed);
    }

    std::atomic<const Buf*> buf_[kMax];
    std::atomic<float> vol_[kMax], pan_[kMax];
    std::atomic<bool> mute_[kMax], solo_[kMax];
    std::atomic<float> master_{0.9f}, speed_{1.0f};
    std::atomic<bool> playing_{false};
    std::atomic<double> seekTo_{-1.0}, pos_{0.0}, duration_{0.0}, loopA_{-1.0}, loopB_{-1.0};
    double pos_d_ = 0;            // solo hilo de audio
    bool jumped_ = false;         // solo hilo de audio
    float cur_[kMax][2] = {};     // ganancias del bloque anterior (hilo de audio)
    std::mutex mutex_;
    std::vector<std::unique_ptr<Buf>> live_;
    std::vector<Retired> retired_;
};
