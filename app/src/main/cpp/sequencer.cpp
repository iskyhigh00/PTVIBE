#include "sequencer.h"

namespace {
constexpr double kEps = 1e-6;  // evita que un evento justo en el borde caiga un bloque antes
}

void Sequencer::fillBuf(PatBuf& b, const int32_t* ticks, const int32_t* pads, const float* vels, int n,
                        int lengthTicks) {
    b.n = std::max(0, std::min(n, kMaxPattern));
    for (int i = 0; i < b.n; ++i) {
        b.ev[i].tick = ticks[i];
        b.ev[i].type = 0;
        b.ev[i].a = pads[i];
        b.ev[i].f = vels[i];
    }
    b.length = std::max(lengthTicks, 1);
}

void Sequencer::setPattern(const int32_t* ticks, const int32_t* pads, const float* vels, int n,
                           int lengthTicks, bool restart, bool oneShot) {
    const int next = pat_.pickFree();
    PatBuf& b = pat_.buf[next];
    fillBuf(b, ticks, pads, vels, n, lengthTicks);
    b.oneShot = oneShot;
    if (restart) patRestart_.store(true, std::memory_order_release);
    pat_.publish(next);
}

void Sequencer::setQueuedPattern(const int32_t* ticks, const int32_t* pads, const float* vels, int n,
                                 int lengthTicks) {
    const int next = swap_.pickFree();
    PatBuf& b = swap_.buf[next];
    fillBuf(b, ticks, pads, vels, n, lengthTicks);
    b.oneShot = false;
    swap_.publish(next);
}

void Sequencer::setEndPattern(const int32_t* ticks, const int32_t* pads, const float* vels, int n,
                              int lengthTicks) {
    const int next = end_.pickFree();
    PatBuf& b = end_.buf[next];
    fillBuf(b, ticks, pads, vels, n, lengthTicks);
    b.oneShot = true;
    end_.publish(next);
}

const Sequencer::PatBuf& Sequencer::applySwap(const PatBuf& cur, double at) {
    const PatBuf& q = swap_.acquire();
    const bool sameLen = q.length == cur.length;
    live_.n = q.n;
    for (int i = 0; i < q.n; ++i) live_.ev[i] = q.ev[i];
    live_.length = q.length;
    live_.oneShot = q.oneShot;
    liveActive_ = true;
    liveSeenCur_ = pat_.cur.load(std::memory_order_seq_cst);
    swapArmed_ = false;
    if (!sameLen) patOrigin_ = at;  // largo distinto: la nueva variación arranca desde su compás 1
    swapCount.fetch_add(1, std::memory_order_relaxed);
    return live_;
}

int Sequencer::shiftFor(int tick) const {
    const float s = swing.load(std::memory_order_relaxed);
    if (s <= 0.0f) return 0;
    if (tick % kPPQ == kPPQ / 2) return static_cast<int>(std::lround(s * kPPQ));       // corchea a contratiempo
    if (tick % (kPPQ / 2) == kPPQ / 4) return static_cast<int>(std::lround(s * kPPQ / 2));  // semicorchea
    return 0;
}

// Dispara los eventos cuyo tick local cae en [t0, t1) tomando origin como inicio del bucle.
template <class F>
void Sequencer::scan(const LoopEvent* ev, int n, int len, double origin, double t0, double t1,
                     bool wrap, int skipPass, F&& fire) {
    if (n == 0 || len <= 0) return;
    double ls = t0 - origin;
    if (wrap) {
        ls = std::fmod(ls, static_cast<double>(len));
        if (ls < 0) ls += len;
    }
    const double le = ls + (t1 - t0);
    for (int i = 0; i < n; ++i) {
        if (ev[i].pass == skipPass && skipPass != 0) continue;
        const double e = ev[i].tick;
        if (e >= ls - kEps && e < le - kEps) fire(ev[i], std::max(0.0, e - ls));
        else if (wrap && e + len >= ls - kEps && e + len < le - kEps) fire(ev[i], std::max(0.0, e + len - ls));
    }
}

int Sequencer::quantizeTick(double local, int len) const {
    const int q = recQuant.load(std::memory_order_relaxed);
    int t = static_cast<int>(std::lround(local));
    if (q > 0) {
        t = static_cast<int>(std::lround(local / q)) * q;
        if (t >= len) t -= len;
    }
    return std::max(0, std::min(t, len - 1));
}

void Sequencer::recordDrum(int pad, float vel) {
    if (!recDrumOn.load(std::memory_order_relaxed) || drumN_ >= kMaxLoop) return;
    const double local = std::fmod(tickPos_, static_cast<double>(drumLen_));
    LoopEvent e;
    e.tick = quantizeTick(local, drumLen_);
    e.type = 0;
    e.a = pad;
    e.f = vel;
    e.pass = drumPass_;
    drum_[drumN_++] = e;
    drumEventCount.store(drumN_, std::memory_order_relaxed);
}

void Sequencer::recordChord(const LoopEvent& src) {
    if (!recChordOn.load(std::memory_order_relaxed) || chordN_ >= kMaxLoop) return;
    LoopEvent e = src;
    const double local = std::fmod(tickPos_, static_cast<double>(chordLen_));
    // Las órdenes ya cuantizadas (diferidas) traen su tick; las vivas usan la posición actual.
    e.tick = src.tick >= 0 ? static_cast<int>(std::fmod(static_cast<double>(src.tick), static_cast<double>(chordLen_)))
                           : static_cast<int>(local);
    e.pass = chordPass_;
    chord_[chordN_++] = e;
    chordEventCount.store(chordN_, std::memory_order_relaxed);
}

bool Sequencer::scheduleChord(const LoopEvent& e) {
    const int mode = chordQuant.load(std::memory_order_relaxed);
    if (mode == 0 || !playing.load(std::memory_order_relaxed) || deferredN_ >= kMaxDeferred) return true;
    const int grid = mode == 1 ? kPPQ * 4 / std::max(1, beatUnit.load())
                               : kPPQ * 4 / std::max(1, beatUnit.load()) * std::max(1, beatsPerBar.load());
    double target = std::ceil(tickPos_ / grid) * grid;
    if (target - tickPos_ < 1e-6) return true;  // justo en el tiempo
    deferred_[deferredN_].e = e;
    deferred_[deferredN_].tick = target;
    ++deferredN_;
    deferredCount.store(deferredN_, std::memory_order_relaxed);
    return false;
}

void Sequencer::removeLastPass(LoopEvent* ev, int& n) {
    int last = 0;
    for (int i = 0; i < n; ++i) last = std::max(last, ev[i].pass);
    if (last == 0) return;
    int w = 0;
    for (int i = 0; i < n; ++i)
        if (ev[i].pass != last) ev[w++] = ev[i];
    n = w;
}

void Sequencer::command(int type, int a, int b, float f, SeqSink& sink) {
    (void)b; (void)f;
    switch (type) {
        case kPlay:
            if (!playing.load()) tickPos_ = 0;
            playing.store(true);
            break;
        case kStop:
            playing.store(false);
            tickPos_ = 0;
            posTick.store(0);
            deferredN_ = 0;
            deferredCount.store(0, std::memory_order_relaxed);
            if (swapArmed_) applySwap(liveActive_ ? live_ : pat_.buf[pat_.cur.load()], 0);  // sin transporte: aplica ya
            patOrigin_ = 0;  // Stop -> Play: la base vuelve a empezar en el compás 1
            endArmed_ = false;
            endActive_ = false;
            patDone_ = false;
            patLocalTick.store(0, std::memory_order_relaxed);
            recDrumOn.store(false);
            recChordOn.store(false);
            sink.releaseChords();
            break;
        case kRecDrum:
            recDrumOn.store(a != 0);
            if (a != 0) { drumPass_ = 1; for (int i = 0; i < drumN_; ++i) drumPass_ = std::max(drumPass_, drum_[i].pass + 1); }
            break;
        case kRecChord:
            recChordOn.store(a != 0);
            if (a != 0) { chordPass_ = 1; for (int i = 0; i < chordN_; ++i) chordPass_ = std::max(chordPass_, chord_[i].pass + 1); }
            break;
        case kDrumLen: if (a > 0) drumLen_ = a; break;
        case kChordLen: if (a > 0) chordLen_ = a; break;
        case kDrumClear: drumN_ = 0; drumEventCount.store(0); break;
        case kChordClear: chordN_ = 0; chordEventCount.store(0); sink.releaseChords(); break;
        case kDrumUndo: removeLastPass(drum_, drumN_); drumEventCount.store(drumN_); break;
        case kChordUndo: removeLastPass(chord_, chordN_); chordEventCount.store(chordN_); sink.releaseChords(); break;
        case kDrumPlay: drumPlay_ = a != 0; break;
        case kChordPlay: chordPlay_ = a != 0; if (!chordPlay_) sink.releaseChords(); break;
        case kPatternPlay:
            patEnabled_.store(a != 0);
            if (a != 0) {
                if (playing.load()) {  // con el transporte en marcha, la base entra en el siguiente compás
                    const double bt = barTicks();
                    patOrigin_ = std::ceil((tickPos_ - kEps) / bt) * bt;
                } else {
                    patOrigin_ = tickPos_;
                }
                patDone_ = false; endArmed_ = false; endActive_ = false;
                swapArmed_ = false;
            }
            break;
        case kPatternQueue:
            if (a <= 0) { swapArmed_ = false; break; }
            if (!playing.load() || !patEnabled_.load() || patDone_) {
                // Sin base sonando no hay límite que esperar: se aplica de inmediato.
                applySwap(liveActive_ ? live_ : pat_.buf[pat_.cur.load()], tickPos_);
            } else {
                const double bt = a;
                swapAt_ = patOrigin_ + std::ceil((tickPos_ - patOrigin_ - kEps) / bt) * bt;
                swapArmed_ = true;
            }
            break;
        case kPatternEnd:
            if (patEnabled_.load() && !endActive_) endArmed_ = true;
            break;
        default: break;
    }
}

void Sequencer::runPattern(const PatBuf& p, double origin, bool wrap, double ta, double tb, double t0,
                           double spt, int n, SeqSink& sink) {
    if (tb - ta <= 0) return;
    auto off = [&](double rel) {
        return std::max(0, std::min(n - 1, static_cast<int>(std::lround(rel * spt))));
    };
    const uint32_t mask = muteMask.load(std::memory_order_relaxed);
    double ls = ta - origin;
    if (wrap) { ls = std::fmod(ls, static_cast<double>(p.length)); if (ls < 0) ls += p.length; }
    const double le = ls + (tb - ta);
    for (int i = 0; i < p.n; ++i) {
        const LoopEvent& ev = p.ev[i];
        if (mask & (1u << ev.a)) continue;
        const double e = ev.tick + shiftFor(ev.tick);
        if (e >= ls - kEps && e < le - kEps) sink.drumHit(ev.a, ev.f, off(ta - t0 + std::max(0.0, e - ls)));
        else if (wrap && e + p.length >= ls - kEps && e + p.length < le - kEps)
            sink.drumHit(ev.a, ev.f, off(ta - t0 + std::max(0.0, e + p.length - ls)));
    }
}

void Sequencer::process(int n, SeqSink& sink) {
    if (!playing.load(std::memory_order_relaxed)) return;
    const double spt = sr_ * 60.0 / (static_cast<double>(bpm.load()) * kPPQ);
    const double t0 = tickPos_;
    const double t1 = t0 + n / spt;
    auto off = [&](double tick) {
        return std::max(0, std::min(n - 1, static_cast<int>(std::lround(tick * spt))));
    };

    // Metrónomo
    if (metro.load(std::memory_order_relaxed)) {
        const int beatTicks = kPPQ * 4 / std::max(1, beatUnit.load());
        const int bpb = std::max(1, beatsPerBar.load());
        long first = static_cast<long>(std::ceil((t0 - kEps) / beatTicks));
        for (long k = first; k * static_cast<double>(beatTicks) < t1 - kEps; ++k)
            sink.click(k % bpb == 0, off(k * beatTicks - t0));
    }

    // Órdenes de acordes diferidas (cuantizadas al tiempo o al compás)
    for (int i = 0; i < deferredN_;) {
        if (deferred_[i].tick < t1 - kEps) {
            LoopEvent e = deferred_[i].e;
            e.tick = static_cast<int32_t>(deferred_[i].tick);
            recordChord(e);
            sink.synthEvent(e);
            deferred_[i] = deferred_[--deferredN_];
            deferredCount.store(deferredN_, std::memory_order_relaxed);
        } else {
            ++i;
        }
    }

    // Patrón de batería (con "final" que entra exactamente al terminar el patrón actual)
    const PatBuf& fresh = pat_.acquire();
    const PatBuf& endp = end_.acquire();
    if (patRestart_.exchange(false)) { patOrigin_ = t0; patDone_ = false; endActive_ = false; endArmed_ = false; }
    // Un patrón publicado después de activar una variación en cola (relleno, edición) manda sobre la copia viva.
    if (liveActive_ && pat_.cur.load(std::memory_order_seq_cst) != liveSeenCur_) liveActive_ = false;
    const PatBuf* pcur = liveActive_ ? &live_ : &fresh;
    if (patEnabled_.load(std::memory_order_relaxed) && !patDone_) {
        if (patOrigin_ >= t1 - kEps && !swapArmed_) {
            patLocalTick.store(0, std::memory_order_relaxed);  // entra en un compás posterior
        } else if (patOrigin_ >= t1 - kEps) {
            pcur = &applySwap(*pcur, patOrigin_);               // la base aún no ha empezado: la variación nueva entra ya
            patLocalTick.store(0, std::memory_order_relaxed);
        } else {
            double ta = std::max(t0, patOrigin_);
            const double ta0 = ta;
            if (endActive_) {
                runPattern(endp, patOrigin_, false, ta, t1, t0, spt, n, sink);
                if (t1 - patOrigin_ >= endp.length - kEps) {
                    patDone_ = true;
                    patEnabled_.store(false);
                    endActive_ = false;
                }
            } else {
                // Cambio de variación en cola: el patrón viejo suena hasta el límite de compás, el nuevo desde ahí.
                if (swapArmed_ && !pcur->oneShot && swapAt_ < t1 - kEps) {
                    const double sw = std::max(swapAt_, ta);
                    if (sw > ta + kEps) runPattern(*pcur, patOrigin_, true, ta, sw, t0, spt, n, sink);
                    pcur = &applySwap(*pcur, sw);
                    ta = sw;
                } else if (swapArmed_ && pcur->oneShot) {
                    swapArmed_ = false;  // un relleno/uno-disparo en curso gana: se descarta el cambio
                }
                const PatBuf& p = *pcur;
                double split = -1;
                if (endArmed_ && !p.oneShot) {
                    const double k = std::floor((t1 - patOrigin_ - kEps) / p.length);
                    const double b = patOrigin_ + k * p.length;
                    if (k >= 1 && b > ta - kEps && b < t1 - kEps) split = b;
                }
                if (split >= 0) {
                    runPattern(p, patOrigin_, true, ta, split, t0, spt, n, sink);
                    endArmed_ = false;
                    endActive_ = true;
                    patOrigin_ = split;
                    runPattern(endp, patOrigin_, false, split, t1, t0, spt, n, sink);
                    if (t1 - patOrigin_ >= endp.length - kEps) {
                        patDone_ = true;
                        patEnabled_.store(false);
                        endActive_ = false;
                    }
                    drumPassCount.fetch_add(1);
                } else {
                    runPattern(p, patOrigin_, !p.oneShot, ta, t1, t0, spt, n, sink);
                    if (!p.oneShot) {
                        if (std::floor((t1 - patOrigin_) / p.length) > std::floor((ta0 - patOrigin_) / p.length))
                            drumPassCount.fetch_add(1);
                    } else if (t1 - patOrigin_ >= p.length) {
                        patDone_ = true;
                        patEnabled_.store(false);
                    }
                }
                double loc = std::fmod(t1 - patOrigin_, static_cast<double>(p.length));
                if (loc < 0) loc += p.length;
                patLocalTick.store(static_cast<int64_t>(loc), std::memory_order_relaxed);
            }
        }
    }

    // Looper de batería (largo independiente)
    if (drumPlay_ && drumN_ > 0) {
        scan(drum_, drumN_, drumLen_, 0.0, t0, t1, true, recDrumOn.load() ? drumPass_ : 0,
             [&](const LoopEvent& ev, double rel) { sink.drumHit(ev.a, ev.f, off(rel)); });
    }
    if (std::floor(t1 / drumLen_) > std::floor(t0 / drumLen_) && recDrumOn.load()) ++drumPass_;

    // Looper de acordes (largo independiente)
    if (std::floor(t1 / chordLen_) > std::floor(t0 / chordLen_)) {
        if (chordPlay_ && chordN_ > 0) sink.releaseChords();  // reinicio limpio del bucle
        if (recChordOn.load()) ++chordPass_;
    }
    if (chordPlay_ && chordN_ > 0) {
        scan(chord_, chordN_, chordLen_, 0.0, t0, t1, true, recChordOn.load() ? chordPass_ : 0,
             [&](const LoopEvent& ev, double) { sink.synthEvent(ev); });
    }

    tickPos_ = t1;
    posTick.store(static_cast<int64_t>(t1), std::memory_order_relaxed);
}
