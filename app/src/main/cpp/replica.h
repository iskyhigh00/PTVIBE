#pragma once
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

// Réplica: una línea de tiempo de notas (batería, bajo y melodía transcritas de las pistas) que se dispara desde la
// posición del reproductor de pistas, con precisión de muestra para batería y bajo. process() solo desde el hilo de audio.
struct ReplicaSink {
    virtual ~ReplicaSink() = default;
    virtual void drum(int pad, float vel, int offsetFrames) = 0;
    virtual void bass(int midi, float vel, int offsetFrames, int durFrames) = 0;
    virtual void melody(int midi, float vel, int durFrames) = 0;
};

class Replica {
public:
    enum Kind { kDrum = 0, kBass = 1, kMelody = 2 };
    struct Ev { double t; float vel; float dur; int16_t a; int8_t kind; };
    struct Data { std::vector<Ev> ev; };

    /** Hilo de UI: reemplaza la línea de tiempo (los eventos llegan ordenados por tiempo o se ordenan aquí). */
    void set(std::vector<Ev> events) {
        std::sort(events.begin(), events.end(), [](const Ev& x, const Ev& y) { return x.t < y.t; });
        auto d = std::make_unique<Data>();
        d->ev = std::move(events);
        std::lock_guard<std::mutex> lock(mutex_);
        data_.store(d.get(), std::memory_order_release);
        owned_.push_back(std::move(d));
        if (owned_.size() > 4) owned_.erase(owned_.begin());  // los anteriores ya no los lee el hilo de audio
        reset_.store(true, std::memory_order_release);
    }
    void clear() { set({}); }
    void setMask(int bits) { mask_.store(bits, std::memory_order_relaxed); }
    void setGain(float drum, float bass, float melody) {
        gd_.store(drum, std::memory_order_relaxed); gb_.store(bass, std::memory_order_relaxed); gm_.store(melody, std::memory_order_relaxed);
    }
    int count() const { const Data* d = data_.load(std::memory_order_acquire); return d ? static_cast<int>(d->ev.size()) : 0; }

    /**
     * Dispara los eventos con t en [p0, p1) (posición de la canción en segundos). Si hubo salto (búsqueda, bucle o fin),
     * solo recoloca el cursor en p1.
     */
    void process(double p0, double p1, bool jumped, int sr, float speed, int n, ReplicaSink& sink) {
        const Data* d = data_.load(std::memory_order_acquire);
        if (!d) return;
        if (reset_.exchange(false, std::memory_order_acq_rel) || jumped || d != cur_) {
            cur_ = d;
            cursor_ = lowerBound(*d, p1);
            return;
        }
        const int bits = mask_.load(std::memory_order_relaxed);
        const float gd = gd_.load(std::memory_order_relaxed), gb = gb_.load(std::memory_order_relaxed), gm = gm_.load(std::memory_order_relaxed);
        const double framesPerSec = sr / std::max(0.25f, speed);
        while (cursor_ < d->ev.size() && d->ev[cursor_].t < p1) {
            const Ev& e = d->ev[cursor_++];
            if (e.t < p0) continue;
            if (!(bits & (1 << e.kind))) continue;
            const int off = std::max(0, std::min(n - 1, static_cast<int>((e.t - p0) * framesPerSec)));
            const int dur = std::max(1, static_cast<int>(e.dur * framesPerSec));
            switch (e.kind) {
                case kDrum: sink.drum(e.a, std::min(1.0f, e.vel * gd), off); break;
                case kBass: sink.bass(e.a, std::min(1.0f, e.vel * gb), off, dur); break;
                default: sink.melody(e.a, std::min(1.0f, e.vel * gm), dur); break;
            }
        }
    }

private:
    static size_t lowerBound(const Data& d, double t) {
        size_t lo = 0, hi = d.ev.size();
        while (lo < hi) { const size_t mid = (lo + hi) / 2; if (d.ev[mid].t < t) lo = mid + 1; else hi = mid; }
        return lo;
    }

    std::atomic<const Data*> data_{nullptr};
    std::atomic<bool> reset_{false};
    std::atomic<int> mask_{7};
    std::atomic<float> gd_{1.0f}, gb_{1.0f}, gm_{1.0f};
    std::mutex mutex_;
    std::vector<std::unique_ptr<Data>> owned_;
    const Data* cur_ = nullptr;   // solo hilo de audio
    size_t cursor_ = 0;
};
