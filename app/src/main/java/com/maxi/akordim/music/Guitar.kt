package com.maxi.akordim.music

import kotlin.math.max
import kotlin.math.min

/** A guitar fingering. frets[0] is the low E string, -1 = muted, 0 = open. */
data class Voicing(val frets: IntArray, val barreFret: Int, val difficulty: Double) {
    val minFret get() = frets.filter { it > 0 }.minOrNull() ?: 0
    val maxFret get() = frets.maxOrNull() ?: 0
    /** First fret to draw: 1 when the shape fits near the nut. */
    val baseFret get() = if (maxFret <= 4) 1 else minFret
    val isBarre get() = barreFret > 0

    override fun equals(other: Any?) = other is Voicing && frets.contentEquals(other.frets)
    override fun hashCode() = frets.contentHashCode()
}

object Guitar {
    /** Standard tuning, MIDI numbers, low E to high E. */
    val TUNING = intArrayOf(40, 45, 50, 55, 59, 64)

    private val cache = HashMap<Chord, List<Voicing>>()

    fun voicings(chord: Chord, count: Int = 4): List<Voicing> {
        if (chord.isNone) return emptyList()
        synchronized(cache) { cache[chord] }?.let { return it.take(count) }
        // search outside the lock so the UI can keep reading the cache meanwhile
        val all = search(chord).take(8)
        synchronized(cache) { cache[chord] = all }
        return all.take(count)
    }

    /** Shapes if already computed, otherwise null (never blocks). */
    fun cached(chord: Chord): List<Voicing>? = if (chord.isNone) emptyList() else synchronized(cache) { cache[chord] }

    /** Ease of playing the chord in its easiest shape (lower = easier). */
    fun difficulty(chord: Chord): Double = maxOf(-2.0, voicings(chord, 1).firstOrNull()?.difficulty ?: 20.0)

    private fun search(chord: Chord): List<Voicing> {
        val tones = chord.allPitchClasses.toSet()
        val bass = chord.bassNote
        val iv = chord.quality.intervals
        val required = HashSet<Int>()
        required.add(chord.root)
        if (chord.hasSlash) required.add(chord.bass)
        // third (or sus note) always; seventh always; altered fifths always
        required.add(Notes.pc(chord.root + iv[1]))
        if (iv.size == 4) required.add(Notes.pc(chord.root + iv[3]))
        if (iv[2] != 7) required.add(Notes.pc(chord.root + iv[2]))

        val found = HashMap<String, Voicing>()
        val frets = IntArray(6)
        for (pos in 0..11) {
            val window = if (pos == 0) (1..4) else (pos..pos + 3)
            fun rec(s: Int, started: Boolean) {
                if (s == 6) {
                    evaluate(frets, required, tones)?.let { v ->
                        val key = v.frets.joinToString(",")
                        val old = found[key]
                        if (old == null || old.difficulty > v.difficulty) found[key] = v
                    }
                    return
                }
                // mute
                frets[s] = -1
                rec(s + 1, started)
                // open and fretted notes
                val options = ArrayList<Int>()
                options.add(0)
                for (f in window) options.add(f)
                for (f in options) {
                    val pc = Notes.pc(TUNING[s] + f)
                    if (pc !in tones) continue
                    if (!started && pc != bass) continue // lowest note must be the bass
                    frets[s] = f
                    rec(s + 1, true)
                }
                frets[s] = -1
            }
            rec(0, false)
        }
        // Familiar shapes (open chords, E/A barre forms) beat odd fingerings with similar scores
        for (shape in familiar(chord)) {
            val v = evaluate(shape.first, required, tones) ?: continue
            if (Notes.pc(TUNING[v.frets.indexOfFirst { it >= 0 }] + v.frets.first { it >= 0 }) != bass) continue
            val key = v.frets.joinToString(",")
            found[key] = v.copy(difficulty = v.difficulty - shape.second)
        }
        return found.values.sortedBy { it.difficulty }
    }

    private val OPEN = mapOf(
        "C" to "x32010", "C7" to "x32310", "Cmaj7" to "x32000",
        "D" to "xx0232", "Dm" to "xx0231", "D7" to "xx0212", "Dmaj7" to "xx0222", "Dm7" to "xx0211",
        "Dsus4" to "xx0233", "Dsus2" to "xx0230",
        "E" to "022100", "Em" to "022000", "E7" to "020100", "Em7" to "020000", "Emaj7" to "021100", "Esus4" to "022200",
        "G" to "320003", "G7" to "320001", "Gmaj7" to "320002",
        "A" to "x02220", "Am" to "x02210", "A7" to "x02020", "Am7" to "x02010", "Amaj7" to "x02120",
        "Asus4" to "x02230", "Asus2" to "x02200", "Fmaj7" to "xx3210", "B7" to "x21202",
        "C/G" to "332010", "C/E" to "032010", "C/B" to "x22010", "Am/G" to "302010", "Am/F#" to "202210",
        "D/F#" to "200232", "D/A" to "x00232", "G/B" to "x20003", "G/D" to "xx0003", "Em/D" to "xx0000",
        "A/C#" to "x42220", "F/C" to "x33211", "E/G#" to "422100", "Am/E" to "002210", "Dm/F" to "1x0231",
    )

    private fun parseShape(s: String) = IntArray(6) { if (s[it] == 'x') -1 else s[it] - '0' }

    /** Standard shapes for this chord with a familiarity bonus. */
    private fun familiar(chord: Chord): List<Pair<IntArray, Double>> {
        val out = ArrayList<Pair<IntArray, Double>>()
        for (sp in listOf(Spelling.SHARP, Spelling.FLAT)) {
            OPEN[chord.name(sp)]?.let { out.add(parseShape(it) to 3.5) }
        }
        if (chord.hasSlash) return out
        val eForm = mapOf(
            Quality.MAJ to intArrayOf(0, 2, 2, 1, 0, 0), Quality.MIN to intArrayOf(0, 2, 2, 0, 0, 0),
            Quality.DOM7 to intArrayOf(0, 2, 0, 1, 0, 0), Quality.MIN7 to intArrayOf(0, 2, 0, 0, 0, 0),
            Quality.MAJ7 to intArrayOf(0, -99, 1, 1, 0, -99), Quality.SUS4 to intArrayOf(0, 2, 2, 2, 0, 0),
        )
        val aForm = mapOf(
            Quality.MAJ to intArrayOf(-99, 0, 2, 2, 2, 0), Quality.MIN to intArrayOf(-99, 0, 2, 2, 1, 0),
            Quality.DOM7 to intArrayOf(-99, 0, 2, 0, 2, 0), Quality.MIN7 to intArrayOf(-99, 0, 2, 0, 1, 0),
            Quality.MAJ7 to intArrayOf(-99, 0, 2, 1, 2, 0), Quality.SUS4 to intArrayOf(-99, 0, 2, 2, 3, 0),
            Quality.SUS2 to intArrayOf(-99, 0, 2, 2, 0, 0), Quality.HDIM to intArrayOf(-99, 0, 1, 0, 1, -99),
        )
        val rE = Notes.pc(chord.root - 4).let { if (it == 0) 12 else it }
        val rA = Notes.pc(chord.root - 9).let { if (it == 0) 12 else it }
        eForm[chord.quality]?.let { f -> out.add(IntArray(6) { if (f[it] < -50) -1 else f[it] + rE } to 3.2) }
        aForm[chord.quality]?.let { f -> out.add(IntArray(6) { if (f[it] < -50) -1 else f[it] + rA } to 3.2) }
        return out
    }

    private fun evaluate(frets: IntArray, required: Set<Int>, tones: Set<Int>): Voicing? {
        val played = frets.indices.filter { frets[it] >= 0 }
        if (played.size < 3) return null
        val pcs = played.map { Notes.pc(TUNING[it] + frets[it]) }.toSet()
        if (!pcs.containsAll(required)) return null
        val fretted = played.filter { frets[it] > 0 }
        val minF = fretted.minOfOrNull { frets[it] } ?: 0
        val maxF = fretted.maxOfOrNull { frets[it] } ?: 0
        if (maxF - minF > 3) return null

        // barre: the lowest fret held on several strings with nothing open/muted between them
        var barre = 0
        var fingers = fretted.size
        if (minF > 0 && fingers > 4) {
            val atMin = fretted.filter { frets[it] == minF }
            if (atMin.size >= 2) {
                val lo = atMin.first()
                val hi = atMin.last()
                val solid = (lo..hi).all { frets[it] >= minF }
                if (solid) {
                    barre = minF
                    fingers = 1 + fretted.count { frets[it] > minF }
                }
            }
        }
        if (fingers > 4) return null

        val firstPlayed = played.first()
        val lastPlayed = played.last()
        val innerMutes = (firstPlayed..lastPlayed).count { frets[it] < 0 }
        val topMutes = 5 - lastPlayed
        val opens = played.count { frets[it] == 0 }

        var d = 0.0
        d += fingers * 0.8
        if (barre > 0) d += if (fretted.count { frets[it] == minF } >= 5) 3.5 else 2.5
        if (minF > 0) d += 0.45 * (minF - 1)
        // open strings sandwiched inside a shape far from the nut are unusual
        if (minF >= 1 && fretted.isNotEmpty()) {
            val lo = fretted.first(); val hi = fretted.last()
            d += 0.8 * (lo..hi).count { frets[it] == 0 }
        }
        d += (maxF - minF) * 0.6
        d += innerMutes * 2.5
        d += topMutes * 1.5
        d += firstPlayed * 0.5
        d -= opens * 0.25
        d -= played.size * 0.5
        if (played.size < 4) d += 2.0
        // open-string shapes that stretch up to the 4th fret are unusual
        if (opens > 0 && maxF >= 4) d += 1.5
        // open strings far from the fretted notes are awkward
        if (opens > 0 && minF >= 5) d += 1.5
        return Voicing(frets.copyOf(), barre, d)
    }

    /** Best capo position (0..7) for a set of chords, weighted by how long each sounds. */
    fun suggestCapo(usage: List<Pair<Chord, Double>>): Int {
        if (usage.isEmpty()) return 0
        var best = 0
        var bestScore = Double.MAX_VALUE
        for (capo in 0..7) {
            var s = 0.0
            for ((c, w) in usage) s += w * difficulty(c.transpose(-capo))
            s += capo * 0.35 * usage.sumOf { it.second } / usage.size // slight preference for no capo
            if (s < bestScore - 1e-9) {
                bestScore = s; best = capo
            }
        }
        return best
    }

    fun minFretLabel(v: Voicing) = max(1, min(v.baseFret, 12))
}
