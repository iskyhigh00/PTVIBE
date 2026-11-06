package com.glidelive.theory

import kotlin.math.abs

/**
 * Voice leading de mínimo movimiento.
 *
 * Los pitches resultantes van ordenados de grave a agudo (las voces no se cruzan).
 * [links] une cada voz anterior con la nota nueva a la que se desliza: con el mismo número
 * de voces es 1 a 1; si cambia, una voz se divide en dos o dos se fusionan (alineación monótona).
 */
object VoiceLeading {
    data class Result(
        val notes: List<Int>,
        /** Índice de tono (en `tones`) de cada nota de [notes]. */
        val toneIndex: List<Int>,
        /** Pares (voz anterior, nota nueva). */
        val links: List<Pair<Int, Int>>,
        val cost: Int,
    )

    /**
     * @param prev voicing anterior (ordenado); vacío para el primer acorde.
     * @param tones clases de altura del acorde, fundamental primero.
     * @param bassTone índice de tono que debe ir en el bajo; null = libre (fundamental si no hay previo).
     * @param fixed tonePc -> nota MIDI fija (notas editadas con candado).
     */
    fun resolve(
        prev: List<Int>,
        tones: List<Int>,
        lo: Int,
        hi: Int,
        bassTone: Int? = null,
        fixed: Map<Int, Int> = emptyMap(),
    ): Result {
        require(tones.isNotEmpty())
        val effectiveBass = bassTone ?: if (prev.isEmpty()) 0 else null
        val cands = tones.map { pc ->
            fixed[pc]?.let { listOf(it) } ?: candidatesFor(pc, lo, hi)
        }
        return search(prev, tones, cands, effectiveBass, lo, hi)
            ?: search(prev, tones, cands, null, lo, hi)!!
    }

    private fun candidatesFor(pc: Int, lo: Int, hi: Int): List<Int> {
        val list = (lo..hi).filter { Math.floorMod(it, 12) == pc }
        if (list.isNotEmpty()) return list
        // Rango menor que una octava: la nota más cercana al centro.
        val mid = (lo + hi) / 2
        val base = mid - Math.floorMod(mid - pc, 12)
        return listOf(if (mid - base <= base + 12 - mid) base else base + 12)
    }

    private fun search(
        prev: List<Int>,
        tones: List<Int>,
        cands: List<List<Int>>,
        bass: Int?,
        lo: Int,
        hi: Int,
    ): Result? {
        val n = tones.size
        val candArr = Array(n) { cands[it].toIntArray() }
        val prevArr = prev.toIntArray()
        val pick = IntArray(n)
        val order = IntArray(n)      // índices de tono ordenados por altura
        val sorted = IntArray(n)     // alturas ordenadas
        val dp = IntArray(prevArr.size * n)
        val bassIdx = bass ?: -1
        var best: Result? = null
        var bestScore = Double.MAX_VALUE
        val center = (lo + hi) / 2.0

        fun evaluate() {
            if (bassIdx >= 0) {
                val b = pick[bassIdx]
                for (j in 0 until n) if (j != bassIdx && pick[j] <= b) return  // el bajo debe ser la nota más grave
            }
            for (i in 0 until n) {  // inserción estable (n <= 16)
                var j = i
                val v = pick[i]
                while (j > 0 && sorted[j - 1] > v) { sorted[j] = sorted[j - 1]; order[j] = order[j - 1]; j-- }
                sorted[j] = v
                order[j] = i
            }
            val cost = costOnly(prevArr, sorted, n, dp)
            val spread = sorted[n - 1] - sorted[0]
            var score = cost + 0.01 * spread
            if (prevArr.isEmpty()) {
                var sum = 0L
                for (v in sorted) sum += v
                score += 0.05 * abs(sum.toDouble() / n - center)
            }
            if (score < bestScore) {
                bestScore = score
                val notes = sorted.toList()
                val (links, c) = align(prev, notes)
                best = Result(notes, order.toList(), links, c)
            }
        }

        fun rec(i: Int) {
            if (i == n) { evaluate(); return }
            val cs = candArr[i]
            for (k in cs.indices) {
                val c = cs[k]
                var skip = false
                for (j in 0 until i) if (pick[j] == c) { skip = true; break }  // alturas repetidas
                if (skip) continue
                if (bassIdx in 0 until i && c <= pick[bassIdx]) continue        // por debajo del bajo
                pick[i] = c
                rec(i + 1)
            }
        }
        rec(0)
        return best
    }

    /** Mismo coste que [align] pero sin construir listas (se usa para cada combinación candidata). */
    private fun costOnly(prev: IntArray, notes: IntArray, m: Int, dp: IntArray): Int {
        val n = prev.size
        if (n == 0) return 0
        if (n == m) {
            var s = 0
            for (i in 0 until n) s += abs(prev[i] - notes[i])
            return s
        }
        val inf = Int.MAX_VALUE / 4
        for (i in 0 until n) for (j in 0 until m) {
            val d = abs(prev[i] - notes[j])
            dp[i * m + j] = d + if (i == 0 && j == 0) 0 else minOf(
                if (i > 0 && j > 0) dp[(i - 1) * m + j - 1] else inf,
                if (i > 0) dp[(i - 1) * m + j] else inf,
                if (j > 0) dp[i * m + j - 1] else inf,
            )
        }
        return dp[(n - 1) * m + m - 1]
    }

    /** Alineación monótona prev -> nuevas notas (DTW); 1 a 1 si los tamaños coinciden. */
    fun align(prev: List<Int>, notes: List<Int>): Pair<List<Pair<Int, Int>>, Int> {
        if (prev.isEmpty()) return emptyList<Pair<Int, Int>>() to 0
        val n = prev.size
        val m = notes.size
        if (n == m) {
            return prev.indices.map { it to it } to prev.indices.sumOf { abs(prev[it] - notes[it]) }
        }
        val inf = Int.MAX_VALUE / 4
        val dp = Array(n) { IntArray(m) { inf } }
        for (i in 0 until n) for (j in 0 until m) {
            val d = abs(prev[i] - notes[j])
            dp[i][j] = d + when {
                i == 0 && j == 0 -> 0
                else -> minOf(
                    if (i > 0 && j > 0) dp[i - 1][j - 1] else inf,
                    if (i > 0) dp[i - 1][j] else inf,
                    if (j > 0) dp[i][j - 1] else inf,
                )
            }
        }
        val links = ArrayList<Pair<Int, Int>>()
        var i = n - 1
        var j = m - 1
        links.add(i to j)
        while (i > 0 || j > 0) {
            val diag = if (i > 0 && j > 0) dp[i - 1][j - 1] else inf
            val up = if (i > 0) dp[i - 1][j] else inf
            val left = if (j > 0) dp[i][j - 1] else inf
            when {
                diag <= up && diag <= left -> { i--; j-- }
                up <= left -> i--
                else -> j--
            }
            links.add(i to j)
        }
        links.reverse()
        return links to dp[n - 1][m - 1]
    }
}
