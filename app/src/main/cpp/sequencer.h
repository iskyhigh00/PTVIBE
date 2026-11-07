#pragma once
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>

constexpr int kPPQ = 96;  // ticks por negra
// Eventos de patrón con pad >= kBassPadBase son notas de bajo: midi = pad - kBassPadBase, f = ticks de duración + velocidad (0..1).
constexpr int kBassPadBase = 64;
// Notas de acordes en patrón: pad = kChordPadBase + voz*128 + midi (voz 0..7); f = ticks de duración + velocidad.
constexpr int kChordPadBase = 128;
// Nota del solista en patrón: pad = kSoloPadBase + midi.
constexpr int kSoloPadBase = 2048;

struct LoopEvent {
    int32_t tick = 0;
    int32_t type = 0;  // 0 golpe de batería (a=pad, f=vel); 10..15 órdenes del sintetizador de acordes
    int32_t a = 0;
    int32_t b = 0;
    float f = 0;
    int32_t pass = 0;
};

struct SeqSink {
    virtual ~SeqSink() = default;
    virtual void drumHit(int pad, float vel, int offsetFrames) = 0;
    virtual void synthEvent(const LoopEvent& e) = 0;
    virtual void releaseChords() = 0;
    virtual void click(bool accent, int offsetFrames) = 0;
};

/**
 * Reloj maestro + patrón de batería + looper de batería + looper de acordes.
 * Los tres pueden tener largos independientes (poliritmos). process() y command() solo se llaman
 * desde el hilo de audio; los atómicos públicos y setPattern() pueden usarse desde la UI.
 */
class Sequencer {
public:
    static constexpr int kMaxPattern = 1024;
    static constexpr int kMaxLoop = 2048;
    static constexpr int kMaxDeferred = 64;

    enum Cmd {
        kPlay = 20, kStop = 21, kRecDrum = 22, kRecChord = 23, kDrumLen = 24, kChordLen = 25,
        kDrumClear = 26, kChordClear = 27, kDrumUndo = 28, kChordUndo = 29,
        kDrumPlay = 30, kChordPlay = 31, kPatternPlay = 32, kPatternEnd = 33,
        kPatternQueue = 34,  // a = barTicks del patrón (> 0 arma el cambio al próximo compás; <= 0 lo cancela)
    };

    // ---- UI
    std::atomic<float> bpm{100.0f};
    std::atomic<int> beatsPerBar{4};
    std::atomic<int> beatUnit{4};
    std::atomic<float> swing{0.0f};      // 0..0.5
    std::atomic<bool> metro{false};
    std::atomic<int> recQuant{0};        // rejilla de grabación en ticks; 0 = libre
    std::atomic<int> chordQuant{0};      // 0 apagada, 1 tiempo, 2 compás
    std::atomic<uint32_t> muteMask{0};   // pads silenciados en el patrón
    std::atomic<int64_t> posTick{0};
    std::atomic<bool> playing{false};
    std::atomic<int> drumPassCount{0};   // veces que ha dado la vuelta el patrón
    std::atomic<bool> recDrumOn{false};
    std::atomic<bool> recChordOn{false};
    std::atomic<int64_t> patLocalTick{0};  // posición dentro del patrón actual
    std::atomic<int> drumEventCount{0};
    std::atomic<int> chordEventCount{0};
    std::atomic<int> deferredCount{0};     // acordes en espera de la cuantización
    std::atomic<int> swapCount{0};         // cambios de variación aplicados (para que la UI sepa cuándo)

    void init(int sampleRate) { sr_ = sampleRate; }

    /** Cambia el patrón (triple buffer; llamar desde un único hilo a la vez). restart: reinicia desde el compás 1. oneShot: suena una vez. */
    void setPattern(const int32_t* ticks, const int32_t* pads, const float* vels, int n,
                    int lengthTicks, bool restart, bool oneShot);

    /**
     * Variación en cola: se publica aquí y se activa con kPatternQueue exactamente en el próximo límite de compás del
     * patrón actual (el patrón viejo suena hasta el límite y el nuevo desde el límite, con precisión de muestra).
     * Mismo productor serializado que setPattern; publicar siempre antes de enviar la orden.
     */
    void setQueuedPattern(const int32_t* ticks, const int32_t* pads, const float* vels, int n, int lengthTicks);

    /** Patrón de cierre: se activa con kPatternEnd y entra justo al terminar el compás/patrón actual. */
    void setEndPattern(const int32_t* ticks, const int32_t* pads, const float* vels, int n, int lengthTicks);

    // ---- hilo de audio
    void command(int type, int a, int b, float f, SeqSink& sink);
    void process(int numFrames, SeqSink& sink);
    void recordDrum(int pad, float vel);
    /** true = ejecutar ya; false = diferido hasta el próximo tiempo/compás. */
    bool scheduleChord(const LoopEvent& e);
    void recordChord(const LoopEvent& e);
    double tickPos() const { return tickPos_; }
    bool patternEnabled() const { return patEnabled_.load(std::memory_order_relaxed); }

    bool hasDeferred() const { return deferredCount.load(std::memory_order_relaxed) > 0; }
    int drumLen() const { return drumLen_; }
    int chordLen() const { return chordLen_; }

private:
    struct PatBuf {
        LoopEvent ev[kMaxPattern];
        int n = 0;
        int length = kPPQ * 4;
        bool oneShot = false;
    };
    /** Triple buffer: el productor (UI, serializado) siempre escribe en un índice libre. */
    struct PatSet {
        PatBuf buf[3];
        std::atomic<int> cur{0};
        std::atomic<int> reading{-1};
        int pickFree() const {
            const int c = cur.load(std::memory_order_seq_cst);
            const int r = reading.load(std::memory_order_seq_cst);
            for (int i = 0; i < 3; ++i)
                if (i != c && i != r) return i;
            return (c + 1) % 3;  // inalcanzable: solo hay 2 índices ocupados como máximo
        }
        void publish(int i) { cur.store(i, std::memory_order_seq_cst); }
        const PatBuf& acquire() {  // hilo de audio
            int c;
            do {
                c = cur.load(std::memory_order_seq_cst);
                reading.store(c, std::memory_order_seq_cst);
            } while (c != cur.load(std::memory_order_seq_cst));
            return buf[c];
        }
    };
    struct Deferred { LoopEvent e; double tick; };

    int barTicks() const {
        return std::max(1, beatsPerBar.load(std::memory_order_relaxed)) * kPPQ * 4 /
               std::max(1, beatUnit.load(std::memory_order_relaxed));
    }

    static void fillBuf(PatBuf& b, const int32_t* ticks, const int32_t* pads, const float* vels, int n, int lengthTicks);
    /** Copia la variación en cola al patrón vivo (hilo de audio). */
    const PatBuf& applySwap(const PatBuf& cur, double at);
    int shiftFor(int tick) const;
    void runPattern(const PatBuf& p, double origin, bool wrap, double ta, double tb, double t0,
                    double spt, int n, SeqSink& sink);
    template <class F>
    void scan(const LoopEvent* ev, int n, int len, double origin, double t0, double t1, bool wrap,
              int skipPass, F&& fire);
    void removeLastPass(LoopEvent* ev, int& n);
    int quantizeTick(double local, int len) const;

    int sr_ = 48000;
    double tickPos_ = 0;
    PatSet pat_;
    PatSet end_;
    PatSet swap_;
    PatBuf live_;            // copia propiedad del hilo de audio de la variación ya activada
    bool liveActive_ = false;
    int liveSeenCur_ = -1;   // pat_.cur cuando se activó: si cambia, el productor publicó un patrón más nuevo
    bool swapArmed_ = false;
    double swapAt_ = 0;
    std::atomic<bool> patRestart_{false};
    std::atomic<bool> patOneShotArm_{false};
    std::atomic<bool> patEnabled_{false};
    bool endArmed_ = false;
    bool endActive_ = false;
    double patOrigin_ = 0;
    bool patDone_ = false;
    bool patWasEnabled_ = false;

    LoopEvent drum_[kMaxLoop];
    int drumN_ = 0;
    int drumLen_ = kPPQ * 4;
    bool drumPlay_ = true;
    int drumPass_ = 1;

    LoopEvent chord_[kMaxLoop];
    int chordN_ = 0;
    int chordLen_ = kPPQ * 4;
    bool chordPlay_ = true;
    int chordPass_ = 1;

    Deferred deferred_[kMaxDeferred];
    int deferredN_ = 0;
};
