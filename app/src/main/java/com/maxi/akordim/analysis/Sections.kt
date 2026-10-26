package com.maxi.akordim.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class SectionKind(val hebrew: String) {
    INTRO("פתיחה"),
    VERSE("בית"),
    PRECHORUS("טרום פזמון"),
    CHORUS("פזמון"),
    BRIDGE("גשר"),
    OUTRO("סיום"),
    PART("קטע"),
}

data class Section(
    val startBeat: Int,
    val endBeat: Int, // exclusive
    val start: Double,
    val end: Double,
    val kind: SectionKind,
    val group: Int, // sections with the same music share a group
    val number: Int, // 1-based occurrence number of this kind
) {
    val label: String get() = if (number > 0) "${kind.hebrew} $number" else kind.hebrew
}

/**
 * Finds the song's form (intro / verse / chorus / bridge / outro) from bar-level harmony,
 * chord labels and loudness: a self-similarity matrix gives the boundaries (novelty), and
 * sections that sound alike are grouped; the loudest well-repeated group is the chorus.
 */
object SectionFinder {
    var debug: ((String) -> Unit)? = null

    fun find(
        chroma: Array<DoubleArray>, // processed beat chroma (unit vectors)
        path: IntArray, // chord index per beat (-1 = none)
        energy: DoubleArray, // per beat
        beats: List<Double>,
        beatsPerBar: Int,
        phase: Int,
    ): List<Section> {
        val nb = chroma.size
        if (nb == 0) return emptyList()
        // bars as beat ranges
        val bars = ArrayList<IntRange>()
        if (phase > 0) bars.add(0 until min(phase, nb))
        var b = phase
        while (b < nb) {
            bars.add(b until min(b + beatsPerBar, nb)); b += beatsPerBar
        }
        val n = bars.size
        fun whole(kind: SectionKind) = listOf(Section(0, nb, beats[0], beats[nb], kind, 0, 0))
        if (n < 12) return whole(SectionKind.PART)

        val vec = Array(n) { i ->
            val v = DoubleArray(12)
            for (k in bars[i]) for (p in 0 until 12) v[p] += chroma[k][p]
            Analyzer.normalize(v); v
        }
        val en = DoubleArray(n) { i -> bars[i].map { energy[it] }.average() }
        val maxE = en.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0
        for (i in 0 until n) en[i] /= maxE

        fun chordMatch(i: Int, j: Int): Double {
            val a = bars[i]; val c = bars[j]
            val len = min(a.count(), c.count())
            if (len == 0) return 0.0
            var same = 0
            for (k in 0 until len) if (path[a.first + k] == path[c.first + k]) same++
            return same.toDouble() / len
        }
        val sim = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i until n) {
            val s = 0.45 * Analyzer.dot(vec[i], vec[j]) + 0.35 * chordMatch(i, j) + 0.2 * (1 - abs(en[i] - en[j]))
            sim[i][j] = s; sim[j][i] = s
        }

        // ---- 1. cut the song into 4-bar phrases on the best-fitting grid
        val first = if (phase > 0) 1 else 0
        var bestOff = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (off in 0 until 4) {
            var sc = 0.0
            var c = first + off
            while (c < n) {
                if (c > 0) sc += 2 * abs(en[c] - en[c - 1]) + (1 - sim[c - 1][c])
                c += 4
            }
            // a short lead-in before the grid is fine, but don't prefer it for nothing
            sc -= off * 0.05
            if (sc > bestScore) { bestScore = sc; bestOff = off }
        }
        val cuts = ArrayList<Int>()
        cuts.add(0)
        var c = first + bestOff
        if (c == 0) c += 4
        while (c < n) { cuts.add(c); c += 4 }
        cuts.add(n)
        // a last stub of 1 bar joins the phrase before it
        if (cuts.size >= 3 && cuts[cuts.size - 1] - cuts[cuts.size - 2] < 2) cuts.removeAt(cuts.size - 2)
        if (cuts.size >= 3 && cuts[1] - cuts[0] < 2) cuts.removeAt(1)
        // a short, near-silent tail is not a section of its own
        if (cuts.size >= 3) {
            val tail = cuts[cuts.size - 2] until n
            if (tail.count() <= 2 && tail.map { en[it] }.average() < 0.2) cuts.removeAt(cuts.size - 2)
        }
        val phrases = (0 until cuts.size - 1).map { cuts[it] until cuts[it + 1] }

        // ---- 2. cluster phrases that sound the same
        // a leading pick-up bar is ignored when comparing phrases
        fun core(p: IntRange) = if (p.first == 0 && p.count() > 4) (p.last - 3)..p.last else p
        fun phraseSim(p0: IntRange, q0: IntRange): Double {
            val p = core(p0); val q = core(q0)
            val len = min(p.count(), q.count())
            var t = 0.0
            for (kk in 0 until len) t += sim[p.first + kk][q.first + kk]
            return t / len * (len.toDouble() / max(p.count(), q.count())).pow(0.3)
        }
        val pc = IntArray(phrases.size) { -1 }
        val pcMembers = ArrayList<MutableList<Int>>()
        for (i in phrases.indices) {
            var best = -1; var bestS = 0.0
            for (g in pcMembers.indices) {
                val v = pcMembers[g].map { phraseSim(phrases[i], phrases[it]) }.average()
                if (v > bestS) { bestS = v; best = g }
            }
            if (best >= 0 && bestS > 0.8) { pcMembers[best].add(i); pc[i] = best }
            else { pcMembers.add(mutableListOf(i)); pc[i] = pcMembers.size - 1 }
        }
        debug?.invoke("phrases=" + phrases.map { beats[bars[it.first].first] } + " clusters=" + pc.toList())

        // ---- 3. sections = repeated phrase pairs (8 bars) or single phrases
        val pairCount = HashMap<Pair<Int, Int>, Int>()
        for (i in 0 until pc.size - 1) {
            val key = pc[i] to pc[i + 1]
            pairCount[key] = (pairCount[key] ?: 0) + 1
        }
        val secPhr = ArrayList<IntRange>() // ranges of phrase indices
        var i = 0
        while (i < pc.size) {
            val pair = if (i + 1 < pc.size) pc[i] to pc[i + 1] else null
            val single = pcMembers[pc[i]].size
            if (pair != null && (pairCount[pair] ?: 0) >= 2 && pair.first != pair.second &&
                // keep a loud/quiet change as a boundary even inside a repeated pair
                abs(phrases[i].map { en[it] }.average() - phrases[i + 1].map { en[it] }.average()) < 0.25
            ) {
                secPhr.add(i..i + 1); i += 2
            } else if (single >= 1 && i + 1 < pc.size && pc[i + 1] == pc[i]) {
                // same phrase twice in a row (e.g. a 4-bar progression played twice)
                var j = i + 1
                while (j + 1 < pc.size && pc[j + 1] == pc[i] && j - i < 3) j++
                secPhr.add(i..j); i = j + 1
            } else {
                secPhr.add(i..i); i++
            }
        }
        val segs = secPhr.map { r -> phrases[r.first].first until phrases[r.last].last + 1 }
        val sig = secPhr.map { r -> r.map { pc[it] }.joinToString(",") }
        val sigIds = sig.distinct()
        val group = IntArray(segs.size) { sigIds.indexOf(sig[it]) }
        val members = sigIds.indices.map { g -> group.indices.filter { group[it] == g }.toMutableList() }
        val gEnergy = members.map { m -> m.flatMap { segs[it].toList() }.map { en[it] }.average() }

        // ---- 4. name the groups
        val last = segs.size - 1
        fun edgeOnly(g: Int) = members[g].all { it == 0 || it == last }
        val repeated = members.indices.filter { members[it].size >= 2 && !edgeOnly(it) }
        val chorus = repeated.maxByOrNull { g ->
            gEnergy[g] * 1.0 + 0.12 * members[g].size + 0.002 * segs[members[g].first()].first
        }
        val verse = repeated.filter { it != chorus }.minByOrNull { members[it].first() }
        // a pre-chorus sits between the verse and the chorus every time
        val prechorus = repeated.filter { it != chorus && it != verse }.firstOrNull { g ->
            chorus != null && verse != null && members[g].all {
                it + 1 < segs.size && group[it + 1] == chorus && it > 0 && group[it - 1] == verse
            }
        }
        fun barsOf(x: Int) = segs[x].count()
        val kinds = Array(segs.size) { SectionKind.PART }
        for (x in segs.indices) {
            val g = group[x]
            kinds[x] = when {
                g == chorus -> SectionKind.CHORUS
                g == verse -> SectionKind.VERSE
                g == prechorus -> SectionKind.PRECHORUS
                x == 0 && barsOf(x) <= 8 && segs.size > 2 -> SectionKind.INTRO
                x == last && barsOf(x) <= 8 && segs.size > 2 -> SectionKind.OUTRO
                g !in repeated && x > 0 && x < last -> SectionKind.BRIDGE
                else -> SectionKind.PART
            }
        }
        // a song that is one progression over and over: call the repeats verses
        if (chorus == null) {
            for (x in segs.indices) if (kinds[x] == SectionKind.PART || kinds[x] == SectionKind.BRIDGE) kinds[x] = SectionKind.VERSE
        }
        // merge neighbours of the same group and kind
        data class Piece(var bars: IntRange, val kind: SectionKind, val group: Int)
        val pieces = ArrayList<Piece>()
        for (x in segs.indices) {
            val lp = pieces.lastOrNull()
            if (lp != null && lp.group == group[x] && lp.kind == kinds[x]) lp.bars = lp.bars.first until segs[x].last + 1
            else pieces.add(Piece(segs[x], kinds[x], group[x]))
        }
        val counts = HashMap<SectionKind, Int>()
        val totals = pieces.groupingBy { it.kind }.eachCount()
        return pieces.map { p ->
            val num = counts.getOrDefault(p.kind, 0) + 1
            counts[p.kind] = num
            val sb = bars[p.bars.first].first
            val eb = bars[p.bars.last].last + 1
            Section(sb, eb, beats[sb], beats[eb], p.kind, p.group, if ((totals[p.kind] ?: 0) > 1) num else 0)
        }
    }

    private fun firstFull(phase: Int) = if (phase > 0) 1 else 0
}
