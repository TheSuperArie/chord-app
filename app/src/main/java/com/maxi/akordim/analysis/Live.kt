package com.maxi.akordim.analysis

import com.maxi.akordim.music.Chord
import com.maxi.akordim.music.MusicalKey
import com.maxi.akordim.music.Notes
import com.maxi.akordim.music.Spelling
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ------------------------------------------------------------------ tuner

data class Pitch(val freq: Double, val clarity: Double) {
    val midiFloat get() = 69 + 12 * log2(freq / 440.0)
    val midi get() = midiFloat.roundToInt()
    val cents get() = ((midiFloat - midi) * 100)
}

/** YIN pitch detector for monophonic input (voice, one guitar string). */
class PitchDetector(private val sr: Int, private val size: Int = 2048) {
    private val half = size / 2
    private val d = FloatArray(half)

    /** [x] must hold at least [size] samples. Returns null for silence / noise. */
    fun detect(x: FloatArray, minFreq: Double = 60.0, maxFreq: Double = 1100.0): Pitch? {
        var rms = 0.0
        for (i in 0 until size) rms += x[i] * x[i]
        rms = sqrt(rms / size)
        if (rms < 0.01) return null
        val tauMin = max(2, (sr / maxFreq).toInt())
        val tauMax = min(half - 1, (sr / minFreq).toInt())
        // difference function
        for (tau in 0 until half) {
            var s = 0f
            for (i in 0 until half) {
                val v = x[i] - x[i + tau]
                s += v * v
            }
            d[tau] = s
        }
        // cumulative mean normalised difference
        d[0] = 1f
        var run = 0f
        for (tau in 1 until half) {
            run += d[tau]
            d[tau] = if (run == 0f) 1f else d[tau] * tau / run
        }
        var tau = tauMin
        var found = -1
        while (tau <= tauMax) {
            if (d[tau] < 0.15f) {
                while (tau + 1 <= tauMax && d[tau + 1] < d[tau]) tau++
                found = tau; break
            }
            tau++
        }
        if (found < 0) return null
        // parabolic interpolation
        val t = found
        val better = if (t in 1 until half - 1) {
            val a = d[t - 1]; val b = d[t]; val c = d[t + 1]
            val den = a - 2 * b + c
            if (den != 0f) t + 0.5 * (a - c) / den else t.toDouble()
        } else t.toDouble()
        return Pitch(sr / better, 1.0 - d[t])
    }

    companion object {
        /** Standard guitar strings, low to high. */
        val GUITAR = intArrayOf(40, 45, 50, 55, 59, 64)
        val GUITAR_NAMES = arrayOf("E", "A", "D", "G", "B", "e")
        fun nearestString(midiFloat: Double): Int = GUITAR.indices.minByOrNull { abs(GUITAR[it] - midiFloat) } ?: 0
        fun noteName(midi: Int, spelling: Spelling = Spelling.SHARP) = Notes.name(midi, spelling) + (midi / 12 - 1)
    }
}

// ------------------------------------------------------------------ live chords

data class LiveResult(val best: Chord, val others: List<Chord>, val confidence: Float, val chroma: DoubleArray)

/** Recognises the chord being played right now from the microphone (mono, [Analyzer.SR]). */
class LiveChordDetector {
    private val sr = Analyzer.SR
    private val n = 4096
    private val buf = FloatArray(sr) // last second
    private var filled = 0
    private val fft = Fft(n)
    private val mag = FloatArray(n / 2 + 1)
    private val templates = Analyzer.VOCAB.map { Analyzer.template(it) }
    private var smooth = DoubleArray(12)
    private var smoothBass = DoubleArray(12)

    fun push(x: FloatArray, len: Int) {
        if (len >= buf.size) {
            System.arraycopy(x, len - buf.size, buf, 0, buf.size)
        } else {
            System.arraycopy(buf, len, buf, 0, buf.size - len)
            System.arraycopy(x, 0, buf, buf.size - len, len)
        }
        filled = min(buf.size, filled + len)
    }

    fun detect(): LiveResult? {
        if (filled < n) return null
        var rms = 0.0
        for (i in buf.size - n until buf.size) rms += buf[i] * buf[i]
        rms = sqrt(rms / n)
        if (rms < 0.008) {
            for (p in 0 until 12) { smooth[p] *= 0.6; smoothBass[p] *= 0.6 }
            return null
        }
        fft.magnitudes(buf, buf.size - n, mag)
        val treble = DoubleArray(12)
        val bass = DoubleArray(12)
        var mx = 1e-9f
        for (k in 1 until n / 2) if (mag[k] > mx) mx = mag[k]
        for (k in 1 until n / 2) {
            val f = k.toDouble() * sr / n
            if (f < 50 || f > 2000) continue
            val m = 69 + 12 * log2(f / 440.0)
            val s = m.roundToInt()
            val v = ln(1.0 + 30.0 * mag[k] / mx)
            if (s in 45..90) treble[Notes.pc(s)] = max(treble[Notes.pc(s)], v)
            if (s in 33..55) bass[Notes.pc(s)] = max(bass[Notes.pc(s)], v)
        }
        for (p in 0 until 12) {
            smooth[p] = 0.5 * smooth[p] + 0.5 * treble[p]
            smoothBass[p] = 0.5 * smoothBass[p] + 0.5 * bass[p]
        }
        val c = smooth.copyOf()
        val floor = c.sorted()[3]
        for (p in 0 until 12) c[p] = max(0.0, c[p] - floor)
        Analyzer.normalize(c)
        val bn = smoothBass.copyOf().also { Analyzer.normalize(it) }
        val scores = DoubleArray(templates.size) { i ->
            val ch = Analyzer.VOCAB[i]
            val complexity = if (ch.quality.intervals.size == 4) -0.06 else if (ch.quality.name.startsWith("SUS") || ch.quality.name == "DIM" || ch.quality.name == "AUG") -0.05 else 0.0
            Analyzer.dot(c, templates[i]) + 0.08 * bn[ch.root] + complexity
        }
        val order = scores.indices.sortedByDescending { scores[it] }
        var best = Analyzer.VOCAB[order[0]]
        // slash bass if one low note clearly dominates and is not the root
        var bi = 0
        for (p in 1 until 12) if (smoothBass[p] > smoothBass[bi]) bi = p
        val sorted = smoothBass.sortedDescending()
        if (bi != best.root && sorted[0] > 1.3 * sorted[1] && smoothBass[bi] > 1.3 * smoothBass[best.root]) {
            best = Chord(best.root, best.quality, bi)
        }
        val margin = scores[order[0]] - scores[order[1]]
        return LiveResult(
            best,
            order.drop(1).take(3).map { Analyzer.VOCAB[it] },
            (0.5 + margin * 6).coerceIn(0.05, 0.99).toFloat(),
            c,
        )
    }
}

// ------------------------------------------------------------------ voice range

data class VoiceSuggestion(val transpose: Int, val key: MusicalKey, val songLow: Int, val songHigh: Int, val margin: Int)

object VoiceFit {
    /**
     * The song's melody range is estimated from its key (most melodies move between the
     * fifth below the tonic and the sixth above it, about an octave and a bit) and the
     * transposition that leaves the most room inside the singer's range wins.
     */
    fun suggest(key: MusicalKey, low: Int, high: Int): VoiceSuggestion {
        var best: VoiceSuggestion? = null
        for (t in -6..6) {
            val tonic = Notes.pc(key.tonic + t)
            for (oct in 2..6) {
                val sLow = 12 * (oct + 1) + tonic - 5
                val sHigh = sLow + 14
                val margin = min(sLow - low, high - sHigh)
                val cand = VoiceSuggestion(t, key.transpose(t), sLow, sHigh, margin)
                val b = best
                if (b == null || margin > b.margin || (margin == b.margin && abs(t) < abs(b.transpose))) best = cand
            }
        }
        return best!!
    }
}

// ------------------------------------------------------------------ strumming

/** One strumming / picking pattern on an eighth-note grid: D down, U up, - rest, B bass, 1-3 strings. */
data class StrumPattern(val name: String, val steps: List<String>, val tip: String)

object Strum {
    fun suggest(beatsPerBar: Int, bpm: Double): List<StrumPattern> {
        fun p(name: String, s: String, tip: String) = StrumPattern(name, s.split(" "), tip)
        return if (beatsPerBar == 3) listOf(
            p("ואלס", "B - D - D -", "בס בפעמה הראשונה, אקורד בשתיים ובשלוש"),
            p("ואלס זורם", "D - D U D U", "למטה בכל פעמה, ולמעלה בין הפעמות"),
            p("פריטה", "B 3 2 3 1 3", "בס ואז מיתרים 3-2-1 לסירוגין"),
        ) else {
            val pop = p("פופ קלאסי", "D - D U - U D U", "המקצב הכי נפוץ בשירים ישראליים")
            val ballad = p("בלדה", "D - D - D U D U", "רגוע, למטה על כל פעמה")
            val pick = p("פריטה", "B 3 2 3 1 3 2 3", "בס ואחריו מיתרים 3-2-3-1")
            val rock = p("קצבי", "D - D U D U D U", "למטה ולמעלה בשמיניות, הדגשה ב-2 וב-4")
            val drive = p("מהיר", "D - D - D - D -", "למטה על כל פעמה, חזק ופשוט")
            when {
                bpm < 78 -> listOf(ballad, pick, pop)
                bpm < 125 -> listOf(pop, ballad, pick)
                bpm < 150 -> listOf(rock, pop, drive)
                else -> listOf(drive, rock, pop)
            }
        }
    }
}

// ------------------------------------------------------------------ chord sheet

object ChordSheet {
    /** Bars of a section as chord text, e.g. "Am", "F G", "C . G ." */
    fun barText(a: SongAnalysis, bar: List<Int>, b2s: IntArray, shown: (Chord) -> Chord, sp: Spelling): String {
        val names = bar.map { b ->
            val s = b2s.getOrElse(b) { -1 }
            if (s < 0) "" else shown(a.segments[s].chord).let { if (it.isNone) "N.C." else it.name(sp) }
        }
        if (names.distinct().size == 1) return names[0]
        val out = ArrayList<String>()
        names.forEachIndexed { i, nme -> out.add(if (i > 0 && nme == names[i - 1]) "." else nme) }
        // drop trailing dots of a two-chord bar: "F . G ." -> "F G" when evenly split
        if (out.size == 4 && out[1] == "." && out[3] == ".") return "${out[0]} ${out[2]}"
        return out.joinToString(" ")
    }

    data class Line(val label: String?, val bars: List<String>)

    fun lines(a: SongAnalysis, bars: List<List<Int>>, b2s: IntArray, shown: (Chord) -> Chord, sp: Spelling, perLine: Int = 4): List<Line> {
        val out = ArrayList<Line>()
        val secs = a.sections.ifEmpty { listOf(Section(0, a.beats.size - 1, 0.0, a.durationSec, SectionKind.PART, 0, 0)) }
        for (sec in secs) {
            val secBars = bars.filter { it.isNotEmpty() && it.first() >= sec.startBeat && it.first() < sec.endBeat }
            secBars.chunked(perLine).forEachIndexed { i, chunk ->
                out.add(Line(if (i == 0) sec.label else null, chunk.map { barText(a, it, b2s, shown, sp) }))
            }
        }
        return out
    }

    fun text(title: String, header: String, lines: List<Line>): String {
        val sb = StringBuilder()
        sb.append(title).append('\n').append(header).append("\n\n")
        for (l in lines) {
            if (l.label != null) sb.append('\n').append('[').append(l.label).append("]\n")
            sb.append("| ").append(l.bars.joinToString(" | ") { it.padEnd(6) }).append(" |\n")
        }
        return sb.toString()
    }
}
