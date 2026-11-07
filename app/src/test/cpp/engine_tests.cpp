// Pruebas de host (sin Oboe ni Android) del secuenciador y del sintetizador.
#include <cmath>
#include <atomic>
#include <cstdio>
#include <thread>
#include <vector>
#include "bass_synth.h"
#include "chord_synth.h"
#include "sequencer.h"
#include "synth_kit.h"

namespace {

struct Hit { long sample; int pad; };

struct Sink : SeqSink {
    long base = 0;
    std::vector<Hit> hits;
    std::vector<long> clicks;  // muestra (+1e9 si acentuado)
    std::vector<long> chords;  // muestra (a 250 muestras por tick)
    int released = 0;
    void drumHit(int pad, float, int off) override { hits.push_back({base + off, pad}); }
    void synthEvent(const LoopEvent& e) override { chords.push_back(static_cast<long>(e.tick) * 250); }
    void releaseChords() override { ++released; }
    void click(bool acc, int off) override { clicks.push_back(base + off + (acc ? 1000000000L : 0)); }
};

int fails = 0;
#define CHECK(cond, msg)                                              \
    do {                                                              \
        if (!(cond)) { std::printf("FAIL: %s (linea %d)\n", msg, __LINE__); ++fails; } \
    } while (0)

constexpr int N = 192;  // bloque de 192 muestras; a 120 BPM y 48 kHz hay 250 muestras por tick

struct Rig {
    Sequencer s;
    Sink k;
    long block = 0;
    explicit Rig(float bpm = 120.0f) { s.init(48000); s.bpm.store(bpm); }
    void run(int blocks) {
        for (int i = 0; i < blocks; ++i, ++block) { k.base = block * N; s.process(N, k); }
    }
    void runUntilTick(double tick) {
        while (s.tickPos() < tick) run(1);
    }
    void cmd(int type, int a = 0) { s.command(type, a, 0, 0, k); }
};

void fourKicks(Sequencer& s, bool restart = true) {
    int32_t t[] = {0, 96, 192, 288};
    int32_t p[] = {0, 0, 0, 0};
    float v[] = {1, 1, 1, 1};
    s.setPattern(t, p, v, 4, 384, restart, false);
}

void testPatternSampleAccurate() {
    Rig r;
    fourKicks(r.s);
    r.cmd(Sequencer::kPatternPlay, 1);
    r.cmd(Sequencer::kPlay);
    r.run(1000);
    bool ok = r.k.hits.size() >= 8;
    for (size_t i = 0; ok && i < 8; ++i) ok = r.k.hits[i].sample == static_cast<long>(i) * 96 * 250;
    CHECK(ok, "patron: golpes exactos en muestra tick*250");
}

void testPolyrhythm() {
    // looper de bateria de 7/8 (336 ticks) sobre un patron 4/4 (384 ticks)
    Rig r;
    fourKicks(r.s);
    r.cmd(Sequencer::kDrumLen, 336);
    r.cmd(Sequencer::kPatternPlay, 1);
    r.cmd(Sequencer::kPlay);
    r.cmd(Sequencer::kRecDrum, 1);
    r.runUntilTick(10);
    r.s.recordDrum(5, 0.9f);  // tick ~10 -> libre
    r.cmd(Sequencer::kRecDrum, 0);
    r.k.hits.clear();
    r.run(2000);
    std::vector<long> loop, pat;
    for (auto& h : r.k.hits) (h.pad == 5 ? loop : pat).push_back(h.sample);
    CHECK(loop.size() >= 2, "poliritmo: el loop se repite");
    CHECK(loop.size() >= 2 && std::lround((loop[1] - loop[0]) / 250.0) == 336, "poliritmo: loop de 336 ticks");
    CHECK(pat.size() >= 2 && std::lround((pat[4] - pat[0]) / 250.0) == 384, "poliritmo: patron de 384 ticks");
}

void testRecordQuantizeAndUndo() {
    Rig r;
    r.s.recQuant.store(24);
    r.cmd(Sequencer::kDrumLen, 336);
    r.cmd(Sequencer::kPlay);
    r.cmd(Sequencer::kRecDrum, 1);
    long samples = 0;
    while (samples < 25100) { r.k.base = samples; r.s.process(N, r.k); samples += N; r.block = samples / N; }
    r.s.recordDrum(1, 0.9f);  // tick 100.4 -> 96
    r.k.hits.clear();
    while (samples < 336L * 250 * 3 + 1000) { r.k.base = samples; r.s.process(N, r.k); samples += N; }
    // el pase 1 se graba; los pases siguientes lo reproducen en 96, 96+336...
    CHECK(r.k.hits.size() >= 2, "cuantizacion: se reproduce");
    if (r.k.hits.size() >= 2) {
        CHECK(r.k.hits[0].sample / 250 == 96 + 336, "cuantizacion: 1/16 (tick 96) en la 2a vuelta");
        CHECK(std::lround((r.k.hits[1].sample - r.k.hits[0].sample) / 250.0) == 336, "cuantizacion: periodo 336");
    }
    r.cmd(Sequencer::kDrumUndo);
    CHECK(r.s.drumEventCount.load() == 0, "deshacer quita el ultimo pase");
}

void testDeferredChord() {
    Rig r;
    r.s.chordQuant.store(1);
    r.cmd(Sequencer::kPlay);
    r.run(30);
    LoopEvent e; e.type = 10; e.tick = -1;
    CHECK(!r.s.scheduleChord(e), "acorde diferido");
    CHECK(r.s.hasDeferred(), "hasDeferred true");
    r.run(170);
    CHECK(r.k.chords.size() == 1 && r.k.chords[0] == 96 * 250, "acorde cae en el tick 96");
    CHECK(!r.s.hasDeferred(), "hasDeferred false tras ejecutarse");
}

void testEndPattern() {
    Rig r;
    int32_t t[] = {0, 192}; int32_t p[] = {0, 1}; float v[] = {1, 1};
    r.s.setPattern(t, p, v, 2, 384, true, false);
    int32_t te[] = {0}; int32_t pe[] = {9}; float ve[] = {1};
    r.s.setEndPattern(te, pe, ve, 1, 384);
    r.cmd(Sequencer::kPatternPlay, 1);
    r.cmd(Sequencer::kPlay);
    r.run(200);
    r.cmd(Sequencer::kPatternEnd);
    r.run(1300);
    int crashes = 0; long at = -1;
    for (auto& h : r.k.hits) if (h.pad == 9) { ++crashes; at = h.sample; }
    CHECK(crashes == 1 && at == 384L * 250, "final entra exactamente al terminar el compas");
    int after = 0;
    for (auto& h : r.k.hits) if (h.sample > 2 * 384L * 250) ++after;
    CHECK(after == 0, "la base se detiene tras el final");
    CHECK(!r.s.patternEnabled(), "patron deshabilitado tras el final");
}

void testSwing() {
    Rig r;
    r.s.swing.store(0.33f);
    int32_t t[] = {48}; int32_t p[] = {3}; float v[] = {1};
    r.s.setPattern(t, p, v, 1, 96, true, false);
    r.cmd(Sequencer::kPatternPlay, 1);
    r.cmd(Sequencer::kPlay);
    r.run(200);
    CHECK(!r.k.hits.empty() && std::fabs(r.k.hits[0].sample / 250.0 - (48 + 32)) < 1.5, "swing 33% retrasa ~32 ticks");
}

void testMetronome() {
    Rig r;
    r.s.metro.store(true);
    r.cmd(Sequencer::kPlay);
    r.run(600);
    int acc = 0;
    for (auto c : r.k.clicks) if (c >= 1000000000L) ++acc;
    CHECK(r.k.clicks.size() == 5 && acc == 2, "metronomo: 5 clics con 2 acentos en 460 ticks");
}

// C3(a): dos setPattern seguidos sin process intermedio no corrompen
void testDoubleSetPattern() {
    Rig r;
    int32_t t1[] = {0}; int32_t p1[] = {1}; float v1[] = {1};
    int32_t t2[] = {96}; int32_t p2[] = {2}; float v2[] = {1};
    int32_t t3[] = {192}; int32_t p3[] = {3}; float v3[] = {1};
    r.s.setPattern(t1, p1, v1, 1, 384, true, false);
    r.s.setPattern(t2, p2, v2, 1, 384, false, false);
    r.s.setPattern(t3, p3, v3, 1, 384, false, false);
    int32_t te[] = {0}; int32_t pe[] = {9}; float ve[] = {1};
    r.s.setEndPattern(te, pe, ve, 1, 384);
    r.s.setEndPattern(te, pe, ve, 1, 384);
    r.cmd(Sequencer::kPatternPlay, 1);
    r.cmd(Sequencer::kPlay);
    r.run(1000);
    bool onlyLast = !r.k.hits.empty();
    for (auto& h : r.k.hits) if (h.pad != 3) onlyLast = false;
    CHECK(onlyLast, "dos setPattern seguidos: solo suena el ultimo");
    CHECK(r.k.hits.size() >= 2 && r.k.hits[0].sample == 192L * 250, "setPattern doble: tick 192 exacto");
    // y con process entre medias, alternando muchas veces
    for (int i = 0; i < 50; ++i) {
        r.s.setPattern(t1, p1, v1, 1, 384, false, false);
        r.run(1);
        r.s.setPattern(t2, p2, v2, 1, 384, false, false);
        r.s.setPattern(t3, p3, v3, 1, 384, false, false);
        r.run(1);
    }
    CHECK(true, "alternancia sin fallos");
}

// C3(b): con el transporte en marcha, la base entra en el siguiente compas
void testPatternPlayWaitsForBar() {
    Rig r;
    int32_t t[] = {0}; int32_t p[] = {0}; float v[] = {1};
    r.s.setPattern(t, p, v, 1, 384, false, false);
    r.cmd(Sequencer::kPlay);
    r.runUntilTick(100);
    r.cmd(Sequencer::kPatternPlay, 1);
    r.run(2000);
    CHECK(!r.k.hits.empty() && r.k.hits[0].sample == 384L * 250, "base activada en tick 100 entra en el tick 384");
    CHECK(r.k.hits.size() >= 2 && r.k.hits[1].sample == 768L * 250, "y se repite cada compas");
}

// C3(c): Stop -> Play reinicia la base en el offset 0
void testStopPlayRestarts() {
    Rig r;
    int32_t t[] = {0}; int32_t p[] = {0}; float v[] = {1};
    r.s.setPattern(t, p, v, 1, 384, false, false);
    r.cmd(Sequencer::kPatternPlay, 1);
    r.cmd(Sequencer::kPlay);
    r.runUntilTick(500);   // dentro del segundo compas
    r.cmd(Sequencer::kStop);
    r.k.hits.clear();
    r.cmd(Sequencer::kPlay);
    r.k.base = r.block * N;
    r.s.process(N, r.k);
    CHECK(r.k.hits.size() == 1 && r.k.hits[0].sample == r.block * N, "Stop->Play: primer golpe en offset 0");
}

// El productor cambia el patron sin parar mientras el "hilo de audio" procesa: dentro de un mismo
// bloque todos los golpes deben venir de un unico patron coherente.
void testTripleBufferStress() {
    Rig r;
    r.cmd(Sequencer::kPatternPlay, 1);
    r.cmd(Sequencer::kPlay);
    std::atomic<bool> stop{false};
    std::thread prod([&] {
        int32_t t[8]; int32_t p[8]; float v[8];
        int k = 0;
        while (!stop.load()) {
            for (int i = 0; i < 8; ++i) { t[i] = i * 2; p[i] = k % 12; v[i] = 1.0f; }
            r.s.setPattern(t, p, v, 8, 384, false, false);
            ++k;
        }
    });
    bool coherent = true;
    for (int b = 0; b < 20000 && coherent; ++b) {
        r.k.hits.clear();
        r.k.base = r.block * N;
        r.s.process(N, r.k);
        ++r.block;
        for (auto& h : r.k.hits) if (h.pad != r.k.hits[0].pad) coherent = false;
    }
    stop.store(true);
    prod.join();
    CHECK(coherent, "triple buffer: bloques coherentes con un productor concurrente");
}

void testSynthSmoke() {
    std::vector<Sample> kit;
    buildSynthKit(48000, kit);
    int nonEmpty = 0;
    for (auto& s : kit) if (!s.data.empty()) ++nonEmpty;
    CHECK(nonEmpty > 0, "kit sintetico no vacio");
    static SynthEngine eng;
    eng.init(48000);
    SynthParams sp;
    eng.noteOn(0, 60.0f, sp);
    std::vector<float> l(512, 0.0f), rr(512, 0.0f);
    float peak = 0;
    for (int b = 0; b < 8; ++b) {
        std::fill(l.begin(), l.end(), 0.0f);
        std::fill(rr.begin(), rr.end(), 0.0f);
        eng.render(l.data(), rr.data(), 512, sp);
        for (float x : l) peak = std::fmax(peak, std::fabs(x));
    }
    CHECK(peak > 0.001f, "sintetizador de acordes produce audio");
}

// Un acorde sostenido (piano eléctrico, el instrumento por defecto) no debe irse apagando.
void testHeldChordDoesNotFade() {
    static SynthEngine eng;
    eng.init(48000);
    SynthParams sp;
    sp.instrument = 0;
    sp.room = 0; sp.chorus = 0;
    eng.noteOn(0, 55.0f, sp);
    eng.noteOn(1, 62.0f, sp);
    std::vector<float> l(512), rr(512);
    auto rms = [&](double seconds) {
        double acc = 0; long n = 0;
        const int blocks = static_cast<int>(seconds * 48000 / 512);
        for (int b = 0; b < blocks; ++b) {
            std::fill(l.begin(), l.end(), 0.0f); std::fill(rr.begin(), rr.end(), 0.0f);
            eng.render(l.data(), rr.data(), 512, sp);
            for (float x : l) { acc += static_cast<double>(x) * x; ++n; }
        }
        return std::sqrt(acc / std::max<long>(n, 1));
    };
    rms(2.0);                  // pasa el ataque inicial
    const double early = rms(0.2);
    rms(17.0);
    const double late = rms(0.2);
    CHECK(early > 0.001, "acorde sostenido: suena");
    CHECK(late > early * 0.6, "acorde sostenido: a los 20 s sigue en el mismo nivel (sin caida de piano)");
}

// Cambio de variación en cola: cae exactamente en el límite de compás del patron.
void testQueuedSwapOnBarBoundary() {
    Rig r;
    fourKicks(r.s);  // pad 0 en 0, 96, 192, 288 (largo 384)
    r.cmd(Sequencer::kPatternPlay, 1);
    r.cmd(Sequencer::kPlay);
    r.runUntilTick(100);
    int32_t t[] = {0, 192}; int32_t p[] = {1, 1}; float v[] = {1, 1};
    r.s.setQueuedPattern(t, p, v, 2, 384);
    r.cmd(Sequencer::kPatternQueue, 384);
    CHECK(r.s.swapCount.load() == 0, "cola: aun no se ha aplicado");
    r.run(1000);
    long firstNew = -1; bool earlyNew = false; int oldAfter = 0;
    for (auto& h : r.k.hits) {
        if (h.pad == 1) { if (firstNew < 0) firstNew = h.sample; if (h.sample < 384L * 250) earlyNew = true; }
        if (h.pad == 0 && h.sample >= 384L * 250) ++oldAfter;
    }
    CHECK(firstNew == 384L * 250, "cola: el primer golpe nuevo cae exactamente en el tick 384");
    CHECK(!earlyNew, "cola: nada del patron nuevo antes del limite");
    CHECK(oldAfter == 0, "cola: nada del patron viejo tras el limite");
    long lastOldBefore = -1;
    for (auto& h : r.k.hits) if (h.pad == 0 && h.sample < 384L * 250) lastOldBefore = h.sample;
    CHECK(lastOldBefore == 288L * 250, "cola: el patron viejo sigue sonando hasta el limite");
    CHECK(r.s.swapCount.load() == 1, "cola: contador de cambios = 1");
}

// Limite a mitad de bloque: compas de 100 ticks dentro de un patron de 400.
void testQueuedSwapMidBlock() {
    Rig r;
    int32_t ta[] = {0, 100, 200, 300}; int32_t pa[] = {0, 0, 0, 0}; float va[] = {1, 1, 1, 1};
    r.s.setPattern(ta, pa, va, 4, 400, true, false);
    r.cmd(Sequencer::kPatternPlay, 1);
    r.cmd(Sequencer::kPlay);
    r.runUntilTick(30);
    int32_t tb[] = {200, 300}; int32_t pb[] = {1, 1}; float vb[] = {1, 1};
    r.s.setQueuedPattern(tb, pb, vb, 2, 400);
    r.cmd(Sequencer::kPatternQueue, 100);
    r.run(900);
    bool snareAt = false, kickAt200 = false; int kicks = 0;
    for (auto& h : r.k.hits) {
        if (h.pad == 1 && h.sample == 200L * 250) snareAt = true;
        if (h.pad == 0 && h.sample == 200L * 250) kickAt200 = true;
        if (h.pad == 0 && h.sample < 200L * 250) ++kicks;
    }
    // Límite en el tick 100 (primer múltiplo de 100 tras el tick 30): kick@0 y kick@100 no cambian.
    CHECK(kicks >= 1, "cola mitad de bloque: golpes viejos previos");
    CHECK(!kickAt200, "cola mitad de bloque: sin golpe viejo en el limite siguiente");
    CHECK(snareAt, "cola mitad de bloque: golpe nuevo exacto en el tick 200 con la fase conservada");
    CHECK(r.s.swapCount.load() == 1, "cola mitad de bloque: un solo cambio");
}

// Sin base sonando: se aplica de inmediato; el inmediato no altera el resto.
void testQueuedSwapImmediateWhenIdle() {
    Rig r;
    fourKicks(r.s);
    int32_t t[] = {0}; int32_t p[] = {1}; float v[] = {1};
    r.s.setQueuedPattern(t, p, v, 1, 384);
    r.cmd(Sequencer::kPatternQueue, 384);
    CHECK(r.s.swapCount.load() == 1, "cola sin base: se aplica de inmediato");
    r.cmd(Sequencer::kPatternPlay, 1);
    r.cmd(Sequencer::kPlay);
    r.run(600);
    bool ok = !r.k.hits.empty();
    for (auto& h : r.k.hits) if (h.pad != 1) ok = false;
    CHECK(ok, "cola sin base: suena la variacion aplicada");
}

// Una variacion en cola se puede cancelar y reemplazar.
void testQueuedSwapCancelAndReplace() {
    Rig r;
    fourKicks(r.s);
    r.cmd(Sequencer::kPatternPlay, 1);
    r.cmd(Sequencer::kPlay);
    r.runUntilTick(50);
    int32_t t[] = {0}; int32_t p[] = {1}; float v[] = {1};
    r.s.setQueuedPattern(t, p, v, 1, 384);
    r.cmd(Sequencer::kPatternQueue, 384);
    r.cmd(Sequencer::kPatternQueue, 0);  // cancelar
    int32_t t2[] = {0}; int32_t p2[] = {2}; float v2[] = {1};
    r.s.setQueuedPattern(t2, p2, v2, 1, 384);
    r.cmd(Sequencer::kPatternQueue, 384);  // reemplaza
    r.run(1000);
    int snares = 0, toms = 0;
    for (auto& h : r.k.hits) { if (h.pad == 1) ++snares; if (h.pad == 2) ++toms; }
    CHECK(snares == 0 && toms > 0, "cola: la ultima variacion encolada es la que entra");
    CHECK(r.s.swapCount.load() == 1, "cola: un solo cambio tras reemplazar");
}

}  // namespace

// Altura por autocorrelación (busca el primer máximo entre 30 y 400 Hz).
double zeroCrossHz(const std::vector<float>& x, int sr) {
    int best = 0; double bestV = -1e9;
    for (int lag = sr / 400; lag <= sr / 30; ++lag) {
        double acc = 0;
        for (size_t i = 0; i + lag < x.size(); ++i) acc += static_cast<double>(x[i]) * x[i + lag];
        acc /= static_cast<double>(x.size() - lag);
        if (acc > bestV) { bestV = acc; best = lag; }
    }
    return best ? static_cast<double>(sr) / best : 0;
}

// Bajo: suena en la altura pedida, respeta el desplazamiento dentro del bloque y se apaga al terminar la duración.
void testBassNote() {
    static BassSynth b;
    b.init(48000);
    b.trigger(42.0f, 0.8f, 100, 48000 / 2);  // F#2 (92.5 Hz), medio segundo
    std::vector<float> st(2 * 512);
    std::vector<float> mono;
    for (int blk = 0; blk < 120; ++blk) {
        std::fill(st.begin(), st.end(), 0.0f);
        b.render(st.data(), 512, 0.1f, 1.0f);
        for (int i = 0; i < 512; ++i) mono.push_back(st[2 * i]);
    }
    bool silentBefore = true;
    for (int i = 0; i < 100; ++i) if (std::fabs(mono[i]) > 1e-6f) silentBefore = false;
    CHECK(silentBefore, "bajo: nada antes del desplazamiento");
    std::vector<float> seg(mono.begin() + 4800, mono.begin() + 4800 + 9600);
    const double hz = zeroCrossHz(seg, 48000);
    CHECK(hz > 89 && hz < 96, "bajo: F#2 cerca de 92.5 Hz");
    float peak = 0;
    for (float x : seg) peak = std::fmax(peak, std::fabs(x));
    CHECK(peak > 0.02f, "bajo: suena");
    CHECK(!b.active(), "bajo: se apaga despues de la duracion");
}

// Guitarras del solista: suenan, sin NaN, y la cuerda sigue al dedo.
void testSoloGuitars() {
    for (int inst = 5; inst <= 6; ++inst) {
        static SynthEngine eng;
        eng.init(48000);
        SynthParams sp;
        sp.soloInstrument = inst;
        sp.room = 0; sp.chorus = 0;
        eng.soloOn(0, 64.0f, sp);
        eng.setSolo(0, 64.0f, 0.5f, 0.0f, 1.0f);
        std::vector<float> l(512), rr(512);
        float peak = 0;
        bool finite = true;
        for (int blk = 0; blk < 40; ++blk) {
            if (blk == 20) eng.setSolo(0, 66.0f, 0.9f, 0.0f, 1.0f);  // bend de un tono
            std::fill(l.begin(), l.end(), 0.0f); std::fill(rr.begin(), rr.end(), 0.0f);
            eng.render(l.data(), rr.data(), 512, sp);
            for (float x : l) { peak = std::fmax(peak, std::fabs(x)); if (!std::isfinite(x)) finite = false; }
        }
        CHECK(finite, "guitarra: sin NaN");
        CHECK(peak > 0.01f && peak < 4.0f, "guitarra: nivel razonable");
        CHECK(std::fabs(eng.currentMidi(SynthEngine::kSoloBase) - 66.0f) < 0.1f, "guitarra: sigue el bend");
    }
}

int main() {
    testPatternSampleAccurate();
    testPolyrhythm();
    testRecordQuantizeAndUndo();
    testDeferredChord();
    testEndPattern();
    testSwing();
    testMetronome();
    testDoubleSetPattern();
    testPatternPlayWaitsForBar();
    testStopPlayRestarts();
    testTripleBufferStress();
    testSynthSmoke();
    testHeldChordDoesNotFade();
    testQueuedSwapOnBarBoundary();
    testQueuedSwapMidBlock();
    testQueuedSwapImmediateWhenIdle();
    testQueuedSwapCancelAndReplace();
    testBassNote();
    testSoloGuitars();
    if (fails) { std::printf("FALLOS: %d\n", fails); return 1; }
    std::printf("OK: todas las pruebas de host pasan\n");
    return 0;
}
