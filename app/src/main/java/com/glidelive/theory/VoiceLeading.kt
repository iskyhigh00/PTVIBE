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
        val pick = IntArray(n)
        var best: Result? = null
        var bestScore = Double.MAX_VALUE
        val center = (lo + hi) / 2.0

        fun evaluate() {
            val order = (0 until n).sortedBy { pick[it] }
            val notes = order.map { pick[it] }
            if (notes.zipWithNext().any { (a, b) -> a == b }) return
            if (bass != null && order[0] != bass) return
            val (links, cost) = align(prev, notes)
            val spread = notes.last() - notes.first()
            var score = cost + 0.01 * spread
            if (prev.isEmpty()) score += 0.05 * abs(notes.average() - center)
            if (score < bestScore) {
                bestScore = score
                best = Result(notes, order, links, cost)
            }
        }

        fun rec(i: Int) {
            if (i == n) { evaluate(); return }
            for (c in cands[i]) { pick[i] = c; rec(i + 1) }
        }
        rec(0)
        return best
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
