package com.maxi.akordim.analysis

import com.maxi.akordim.music.Chord
import com.maxi.akordim.music.MusicalKey
import com.maxi.akordim.music.Notes
import com.maxi.akordim.music.Quality
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline song analysis: tempo + beats, meter, tuning, key and beat-synchronous chords
 * (with slash basses and ranked alternatives). Pure Kotlin, no Android dependencies.
 * Input: mono audio at [SR] Hz.
 */
class Analyzer(private val progress: (Float, String) -> Unit = { _, _ -> }) {

    companion object {
        const val SR = 11025
        private const val ONSET_N = 512
        private const val ONSET_HOP = 128
        private const val CHROMA_HOP = 512
        private const val TREBLE_N = 4096
        private const val LOW_N = 8192 // long window: the only way to tell low bass notes apart
        private const val NOTE_LO = 28 // E1
        private const val NOTE_HI = 96 // C7
        private const val NOTES = NOTE_HI - NOTE_LO + 1
        private const val SPLIT_NOTE = 55 // below G3 the long window is used
        val VOCAB: List<Chord> = buildList {
            for (q in Quality.entries) for (r in 0 until 12) add(Chord(r, q))
        }
        private val COMPLEXITY = mapOf(
            Quality.MAJ to 0.0, Quality.MIN to 0.0,
            Quality.DOM7 to -0.025, Quality.MIN7 to -0.03, Quality.MAJ7 to -0.035,
            Quality.SUS4 to -0.07, Quality.SUS2 to -0.075,
            Quality.DIM to -0.06, Quality.AUG to -0.08, Quality.HDIM to -0.06,
        )

        // Krumhansl-Kessler key profiles
        private val KK_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        private val KK_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

        /** Chord template that accounts for the first harmonics of every chord tone. */
        fun template(c: Chord): DoubleArray {
            val t = DoubleArray(12)
            val partials = intArrayOf(0, 12, 19, 24, 28)
            val weights = doubleArrayOf(1.0, 0.5, 0.33, 0.25, 0.2)
            c.quality.intervals.forEachIndexed { idx, iv ->
                val noteW = if (idx == 0) 1.1 else 1.0
                for (p in partials.indices) t[Notes.pc(c.root + iv + partials[p])] += noteW * weights[p]
            }
            normalize(t)
            return t
        }

        fun normalize(v: DoubleArray) {
            var s = 0.0
            for (x in v) s += x * x
            s = sqrt(s)
            if (s > 1e-12) for (i in v.indices) v[i] /= s
        }

        fun dot(a: DoubleArray, b: DoubleArray): Double {
            var s = 0.0
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }

    private val templates = VOCAB.map { template(it) }

    /** Per-beat harmony of the last analysed song, kept for exporting (engine tuning). */
    var lastFeatures: BeatFeatures? = null
        private set

    fun analyze(x: FloatArray): SongAnalysis {
        val t0 = System.currentTimeMillis()
        val duration = x.size.toDouble() / SR

        // Normalise level
        var peak = 1e-9f
        var sq = 0.0
        for (v in x) {
            if (abs(v) > peak) peak = abs(v); sq += v * v
        }
        val rms = sqrt(sq / max(1, x.size))
        val loudnessDb = 20 * log10(max(rms, 1e-9))
        val gain = 1f / peak
        for (i in x.indices) x[i] *= gain

        progress(0.05f, "מזהה את הקצב")
        val onset = onsetEnvelope(x)
        val fps = SR.toDouble() / ONSET_HOP

        val period = estimatePeriod(onset, fps)
        progress(0.2f, "מסמן פעימות")
        val beatFrames = fixOctave(trackBeats(onset, period), onset, period, fps)
        var beats = beatFrames.map { it * ONSET_HOP.toDouble() / SR }.toMutableList()
        beats = extendBeats(beats, duration)
        val intervals = (1 until beats.size).map { beats[it] - beats[it - 1] }.sorted()
        val medIoi = if (intervals.isEmpty()) 60.0 / 120 else intervals[intervals.size / 2]
        val bpm = 60.0 / medIoi
        val stability = tempoStability(beats, medIoi)

        progress(0.3f, "מנתח צלילים")
        val tuning = estimateTuning(x)
        val spec = noteFrames(x, tuning)

        progress(0.7f, "מזהה אקורדים")
        val nb = beats.size - 1
        val beatChroma = Array(nb) { DoubleArray(12) }
        val beatBass = Array(nb) { DoubleArray(12) }
        val beatEnergy = DoubleArray(nb)
        beatFeatures(spec, beats, beatChroma, beatBass, beatEnergy)
        lastFeatures = BeatFeatures(
            beats.toDoubleArray(), beatChroma.map { it.copyOf() }, beatBass.map { it.copyOf() }, beatEnergy.copyOf(), tuning,
        )
        val processed = Array(nb) { b -> processChroma(beatChroma[b]) }
        val bassNorm = Array(nb) { b -> beatBass[b].copyOf().also { normalize(it) } }
        val medEnergy = beatEnergy.sorted().let { if (it.isEmpty()) 1.0 else it[it.size / 2] }

        // Key from the overall pitch profile
        val total = DoubleArray(12)
        for (b in 0 until nb) for (p in 0 until 12) total[p] += beatChroma[b][p] * beatEnergy[b]
        var keyScores = keyProfileScores(total)

        val accent = beatAccents(onset, beats, period)
        val change = DoubleArray(nb) { b -> if (b == 0) 0.0 else 1 - dot(processed[b], processed[b - 1]) }
        val (bpb, phase) = detectMeter(accent, change)

        var key = bestKey(keyScores, null)
        var path = viterbi(processed, bassNorm, beatEnergy, medEnergy, key, bpb, phase)
        // Refine the key using the recognised chords, then redo the chords once if it moved
        val refined = bestKey(keyScores, path)
        if (refined != key) {
            key = refined
            path = viterbi(processed, bassNorm, beatEnergy, medEnergy, key, bpb, phase)
        }
        absorbPassingBass(path, processed, bpb, phase)
        refineQualities(path, processed, key)
        val keyConf = keyConfidence(keyScores, path, key)

        progress(0.9f, "מזהה בס")
        val segments = buildSegments(path, processed, beatBass, bassNorm, beatEnergy, medEnergy, beats, key, bpb, phase)
        progress(0.95f, "מזהה בית ופזמון")
        val sections = try {
            SectionFinder.find(processed, path, beatEnergy, beats, bpb, phase)
        } catch (e: Exception) {
            emptyList()
        }

        progress(1f, "מוכן")
        return SongAnalysis(
            durationSec = duration,
            bpm = bpm,
            beatsPerBar = bpb,
            downbeatPhase = phase,
            beats = beats.toDoubleArray(),
            key = key,
            keyConfidence = keyConf,
            tuningCents = tuning,
            segments = segments,
            sections = sections,
            tempoStability = stability,
            loudnessDb = loudnessDb,
            analysisMillis = System.currentTimeMillis() - t0,
        )
    }

    // ---------------------------------------------------------------- rhythm

    private fun onsetEnvelope(x: FloatArray): FloatArray {
        val fft = Fft(ONSET_N)
        val nFrames = x.size / ONSET_HOP + 1
        val mag = FloatArray(ONSET_N / 2 + 1)
        val prev = FloatArray(ONSET_N / 2 + 1)
        val maxBin = (4000.0 * ONSET_N / SR).toInt()
        val env = FloatArray(nFrames)
        for (f in 0 until nFrames) {
            fft.magnitudes(x, f * ONSET_HOP - ONSET_N / 2, mag)
            var flux = 0f
            for (k in 1..maxBin) {
                val l = ln(1f + 100f * mag[k])
                val d = l - prev[k]
                if (d > 0) flux += d
                prev[k] = l
            }
            env[f] = if (f == 0) 0f else flux
        }
        // remove slow trend, half-wave rectify, normalise
        val w = 16
        val out = FloatArray(nFrames)
        var acc = 0.0
        for (i in 0 until min(nFrames, w)) acc += env[i]
        for (i in 0 until nFrames) {
            val lo = i - w - 1
            val hi = i + w
            if (hi < nFrames && i > 0) acc += env[hi]
            if (lo >= 0) acc -= env[lo]
            val cnt = min(nFrames - 1, hi) - max(0, i - w) + 1
            val mean = acc / cnt
            out[i] = max(0.0, env[i] - mean).toFloat()
        }
        var s = 0.0
        for (v in out) s += v * v
        val sd = sqrt(s / max(1, nFrames)).toFloat().coerceAtLeast(1e-6f)
        for (i in out.indices) out[i] /= sd
        return out
    }

    /** Beat period in onset frames (fractional). */
    private fun estimatePeriod(o: FloatArray, fps: Double): Double {
        val minLag = (fps * 60 / 220).toInt()
        val maxLag = (fps * 60 / 45).toInt()
        val n = o.size
        val ac = DoubleArray(maxLag * 2 + 2)
        for (lag in minLag / 2..min(maxLag * 2 + 1, n - 1)) {
            var s = 0.0
            var i = lag
            while (i < n) {
                s += o[i] * o[i - lag]; i++
            }
            ac[lag] = s / (n - lag)
        }
        val center = fps * 60 / 115.0
        var bestLag = center.toInt()
        var best = -1.0
        val score = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            val w = exp(-0.5 * (log2(lag / center) / 0.9).pow(2))
            // A real beat period also repeats at twice the lag
            val twice = if (2 * lag < ac.size) ac[2 * lag] else 0.0
            score[lag] = w * (ac[lag] + 0.35 * twice)
            if (score[lag] > best) {
                best = score[lag]; bestLag = lag
            }
        }
        // parabolic refinement
        if (bestLag in minLag + 1 until maxLag) {
            val a = score[bestLag - 1]
            val b = score[bestLag]
            val c = score[bestLag + 1]
            val d = a - 2 * b + c
            if (d < 0) return bestLag + 0.5 * (a - c) / d
        }
        return bestLag.toDouble()
    }

    /** Ellis (2007) dynamic-programming beat tracker. Returns beat frame indices. */
    private fun trackBeats(o: FloatArray, period: Double): List<Int> {
        val n = o.size
        if (n < 4) return listOf(0)
        // smooth onsets with a gaussian of width period/32
        val sigma = max(1.0, period / 32)
        val half = (sigma * 3).toInt()
        val kern = DoubleArray(2 * half + 1) { exp(-0.5 * ((it - half) / sigma).pow(2)) }
        val local = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (k in -half..half) {
                val j = i + k
                if (j in 0 until n) s += o[j] * kern[k + half]
            }
            local[i] = s
        }
        val alpha = 100.0
        val cscore = DoubleArray(n)
        val back = IntArray(n) { -1 }
        val pMin = (period / 2).roundToInt().coerceAtLeast(1)
        val pMax = (period * 2).roundToInt()
        for (t in 0 until n) {
            var best = Double.NEGATIVE_INFINITY
            var bi = -1
            val lo = max(0, t - pMax)
            val hi = t - pMin
            var tau = lo
            while (tau <= hi) {
                val r = ln((t - tau) / period)
                val v = cscore[tau] - alpha * r * r
                if (v > best) {
                    best = v; bi = tau
                }
                tau++
            }
            cscore[t] = local[t] + if (bi >= 0 && best > 0) best else 0.0
            back[t] = if (bi >= 0 && best > 0) bi else -1
        }
        // best end point inside the last two periods
        var end = n - 1
        var bestEnd = Double.NEGATIVE_INFINITY
        for (t in max(0, n - (2 * period).toInt()) until n) {
            if (cscore[t] > bestEnd) {
                bestEnd = cscore[t]; end = t
            }
        }
        val out = ArrayList<Int>()
        var t = end
        while (t >= 0) {
            out.add(t); t = back[t]
        }
        out.reverse()
        return out
    }

    /**
     * Correct the classic double/half tempo mistake: if every second beat is much weaker
     * (only hi-hats on the off-beats) keep the strong ones; if the tempo is slow and the
     * off-beats are as strong as the beats, add the midpoints.
     */
    private fun fixOctave(beats: List<Int>, o: FloatArray, period: Double, fps: Double): List<Int> {
        if (beats.size < 16) return beats
        val win = max(1, (period * 0.1).toInt())
        fun acc(c: Int): Double {
            var m = 0.0
            for (k in c - win..c + win) if (k in o.indices) m = max(m, o[k].toDouble())
            return m
        }
        val a = beats.map { acc(it) }
        val even = a.filterIndexed { i, _ -> i % 2 == 0 }.average()
        val odd = a.filterIndexed { i, _ -> i % 2 == 1 }.average()
        val bpm = 60 * fps / period
        if (bpm > 90 && min(even, odd) < 0.6 * max(even, odd)) {
            val keep = if (even >= odd) 0 else 1
            return beats.filterIndexed { i, _ -> i % 2 == keep }
        }
        if (bpm < 72) {
            val mids = (1 until beats.size).map { (beats[it - 1] + beats[it]) / 2 }
            val offAcc = mids.map { acc(it) }.average()
            if (offAcc > 0.8 * a.average()) {
                val out = ArrayList<Int>()
                for (i in beats.indices) {
                    out.add(beats[i]); if (i < mids.size) out.add(mids[i])
                }
                return out
            }
        }
        return beats
    }

    /** Fill in beats before the first and after the last detected beat. */
    private fun extendBeats(b: MutableList<Double>, duration: Double): MutableList<Double> {
        if (b.size < 2) {
            val p = 0.5
            return MutableList((duration / p).toInt() + 2) { it * p }
        }
        val headP = (b[min(4, b.size - 1)] - b[0]) / min(4, b.size - 1)
        val tailP = (b[b.size - 1] - b[max(0, b.size - 5)]) / min(4, b.size - 1)
        val out = ArrayList<Double>()
        var t = b[0] - headP
        while (t > 0.05) {
            out.add(0, t); t -= headP
        }
        out.add(0, 0.0)
        out.addAll(b.filter { it > out.last() + 0.05 })
        t = out.last() + tailP
        while (t < duration) {
            out.add(t); t += tailP
        }
        if (out.last() < duration) out.add(duration)
        return out
    }

    private fun tempoStability(beats: List<Double>, med: Double): Float {
        if (beats.size < 4) return 0f
        var dev = 0.0
        for (i in 1 until beats.size) dev += abs((beats[i] - beats[i - 1]) - med) / med
        dev /= (beats.size - 1)
        return (1 - dev * 4).coerceIn(0.0, 1.0).toFloat()
    }

    private fun beatAccents(o: FloatArray, beats: List<Double>, period: Double): DoubleArray {
        val fps = SR.toDouble() / ONSET_HOP
        val win = max(1, (period * 0.12).toInt())
        return DoubleArray(beats.size - 1) { b ->
            val c = (beats[b] * fps).roundToInt()
            var m = 0.0
            for (k in c - win..c + win) if (k in o.indices) m = max(m, o[k].toDouble())
            m
        }
    }

    /** Returns beats per bar (3 or 4) and the index of the first downbeat. */
    private fun detectMeter(accent: DoubleArray, change: DoubleArray): Pair<Int, Int> {
        fun best(m: Int): Pair<Double, Int> {
            var bestS = Double.NEGATIVE_INFINITY
            var bestP = 0
            for (ph in 0 until m) {
                var ad = 0.0; var ao = 0.0; var cd = 0.0; var co = 0.0
                var nd = 0; var no = 0
                for (i in accent.indices) {
                    if ((i - ph) % m == 0 && i >= ph) {
                        ad += accent[i]; cd += change[i]; nd++
                    } else {
                        ao += accent[i]; co += change[i]; no++
                    }
                }
                if (nd == 0 || no == 0) continue
                val s = (ad / nd - ao / no) / (1 + ao / no) + 2.0 * (cd / nd - co / no)
                if (s > bestS) {
                    bestS = s; bestP = ph
                }
            }
            return bestS to bestP
        }
        if (accent.size < 12) return 4 to 0
        val (s2, _) = best(2)
        val (s3, p3) = best(3)
        val (s4, p4) = best(4)
        val competitor = max(s2, s4)
        return if (s3 > 0 && s3 > competitor * 1.15 + 0.01) 3 to p3 else 4 to p4
    }

    // ---------------------------------------------------------------- pitch

    /** Tuning offset from A=440 in cents, from spectral peaks. */
    private fun estimateTuning(x: FloatArray): Double {
        val fft = Fft(TREBLE_N)
        val mag = FloatArray(TREBLE_N / 2 + 1)
        var cx = 0.0
        var cy = 0.0
        val hop = CHROMA_HOP * 12
        var start = 0
        val loBin = (100.0 * TREBLE_N / SR).toInt()
        val hiBin = (2000.0 * TREBLE_N / SR).toInt()
        while (start + TREBLE_N < x.size) {
            fft.magnitudes(x, start, mag)
            var mx = 0f
            for (k in loBin..hiBin) mx = max(mx, mag[k])
            val th = mx * 0.2f
            for (k in loBin..hiBin) {
                val m = mag[k]
                if (m > th && m > mag[k - 1] && m >= mag[k + 1]) {
                    val a = ln(mag[k - 1] + 1e-9f); val b = ln(m + 1e-9f); val c = ln(mag[k + 1] + 1e-9f)
                    val d = a - 2 * b + c
                    val off = if (d < 0) 0.5 * (a - c) / d else 0.0
                    val f = (k + off) * SR / TREBLE_N
                    val cents = 1200 * log2(f / 440.0)
                    val ang = 2 * PI * cents / 100.0
                    cx += m * cos(ang); cy += m * sin(ang)
                }
            }
            start += hop
        }
        if (cx == 0.0 && cy == 0.0) return 0.0
        return atan2(cy, cx) / (2 * PI) * 100.0
    }

    private class NoteSpec(val frames: Array<FloatArray>, val energy: DoubleArray)

    /**
     * Note-level spectrum (one value per semitone, E1..C7) for every analysis frame.
     * Only real spectral peaks are counted, after removing the broadband floor that drums
     * and noise leave behind, and each peak goes to the note it is actually tuned to.
     */
    private fun noteFrames(x: FloatArray, tuning: Double): NoteSpec {
        val nFrames = x.size / CHROMA_HOP + 1
        val hiFft = Fft(TREBLE_N)
        val loFft = Fft(LOW_N)
        val hiMag = FloatArray(TREBLE_N / 2 + 1)
        val loMag = FloatArray(LOW_N / 2 + 1)
        val prefix = DoubleArray(LOW_N / 2 + 2)
        val split = 440.0 * 2.0.pow((SPLIT_NOTE - 69 - 0.5 + tuning / 100.0) / 12.0)
        val fMin = 440.0 * 2.0.pow((NOTE_LO - 69 - 0.5 + tuning / 100.0) / 12.0)
        val fMax = min(SR * 0.47, 440.0 * 2.0.pow((NOTE_HI - 69 + 0.5 + tuning / 100.0) / 12.0))
        val frames = Array(nFrames) { FloatArray(NOTES) }
        val energy = DoubleArray(nFrames)
        for (f in 0 until nFrames) {
            val c = f * CHROMA_HOP
            loFft.magnitudes(x, c - LOW_N / 2, loMag)
            hiFft.magnitudes(x, c - TREBLE_N / 2, hiMag)
            val out = frames[f]
            addPeaks(loMag, LOW_N, fMin, split, tuning, prefix, out)
            addPeaks(hiMag, TREBLE_N, split, fMax, tuning, prefix, out)
            var e = 0.0
            for (v in out) e += v * v
            energy[f] = sqrt(e)
            if (f % 200 == 0) progress(0.3f + 0.4f * f / nFrames, "מנתח צלילים")
        }
        return NoteSpec(frames, energy)
    }

    private fun addPeaks(
        mag: FloatArray, n: Int, fLo: Double, fHi: Double, tuning: Double, prefix: DoubleArray, out: FloatArray,
    ) {
        val half = n / 2
        val k0 = max(2, (fLo * n / SR).toInt() - 1)
        val k1 = min(half - 2, (fHi * n / SR).toInt() + 1)
        if (k1 <= k0) return
        val w = max(8, n / 96) // floor window: about +-115 Hz
        val a0 = max(1, k0 - w)
        val a1 = min(half - 1, k1 + w)
        prefix[a0] = 0.0
        for (k in a0..a1) prefix[k + 1] = prefix[k] + mag[k]
        val scale = 2f / n
        for (k in k0..k1) {
            val m = mag[k]
            if (m <= mag[k - 1] || m < mag[k + 1]) continue
            val lo = max(a0, k - w)
            val hi = min(a1, k + w)
            val floor = ((prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)).toFloat()
            if (m < 1.3f * floor) continue
            val la = ln(mag[k - 1] + 1e-9f)
            val lb = ln(m + 1e-9f)
            val lc = ln(mag[k + 1] + 1e-9f)
            val d = la - 2 * lb + lc
            val off = if (d < 0) (0.5f * (la - lc) / d).coerceIn(-0.5f, 0.5f) else 0f
            val freq = (k + off).toDouble() * SR / n
            if (freq < fLo || freq >= fHi) continue
            val midi = 69 + 12 * log2(freq / 440.0) - tuning / 100.0
            val note = midi.roundToInt()
            if (note < NOTE_LO || note > NOTE_HI) continue
            val dev = midi - note
            val cw = cos(PI * dev)
            out[note - NOTE_LO] += (cw * cw).toFloat() * (m - floor) * scale
        }
    }

    /**
     * Per-beat harmony: the median note spectrum of the beat (which drops drum hits and
     * other short events), with the overtones of every note explained away so that only
     * the notes really being played are left, folded to treble and bass pitch classes.
     */
    private fun beatFeatures(
        spec: NoteSpec, beats: List<Double>,
        beatChroma: Array<DoubleArray>, beatBass: Array<DoubleArray>, beatEnergy: DoubleArray,
    ) {
        val nb = beats.size - 1
        val fps = SR.toDouble() / CHROMA_HOP
        val nf = spec.frames.size
        val beatSpec = Array(nb) { DoubleArray(NOTES) }
        val bassSpec = Array(nb) { DoubleArray(NOTES) }
        val tmp = FloatArray(64)
        for (b in 0 until nb) {
            val f0 = (beats[b] * fps).roundToInt().coerceIn(0, nf - 1)
            val f1 = max(f0 + 1, (beats[b + 1] * fps).roundToInt()).coerceAtMost(nf)
            // bass only from the first part of the beat, so the next bar's bass can't leak in
            val fb = (f0 + max(1, ((f1 - f0) * 0.7).roundToInt())).coerceAtMost(f1)
            for (s in 0 until NOTES) {
                beatSpec[b][s] = aggregate(spec.frames, f0, f1, s, tmp)
                bassSpec[b][s] = if (fb == f1) beatSpec[b][s] else aggregate(spec.frames, f0, fb, s, tmp)
            }
            var e = 0.0
            for (f in f0 until f1) e += spec.energy[f]
            beatEnergy[b] = e / (f1 - f0)
        }
        // common reference level, then compress so loud partials do not swamp quiet notes
        val all = ArrayList<Double>()
        for (b in 0 until nb step max(1, nb / 400)) for (v in beatSpec[b]) if (v > 0) all.add(v)
        all.sort()
        val ref = if (all.isEmpty()) 1.0 else max(1e-9, all[((all.size - 1) * 0.95).toInt()])
        val act = DoubleArray(NOTES)
        for (b in 0 until nb) {
            for (pass in 0..1) {
                val src = if (pass == 0) beatSpec[b] else bassSpec[b]
                for (s in 0 until NOTES) src[s] = (src[s] / ref).pow(0.45)
                System.arraycopy(src, 0, act, 0, NOTES)
                if (pass == 1) {
                    // a bass note's own overtones (octave, fifth above it...) must not be
                    // mistaken for other bass notes: walk upward and take them out
                    for (j in 0 until NOTES) {
                        val a = act[j]
                        if (a <= 0 || j + NOTE_LO > 57) continue
                        if (j + 12 < NOTES) act[j + 12] = max(0.0, act[j + 12] - 0.7 * a)
                        if (j + 19 < NOTES) act[j + 19] = max(0.0, act[j + 19] - 0.6 * a)
                        if (j + 24 < NOTES) act[j + 24] = max(0.0, act[j + 24] - 0.5 * a)
                    }
                }
                if (pass == 0) {
                    for (s in 0 until NOTES) {
                        val m = s + NOTE_LO
                        val w = when {
                            m < 40 -> 0.0
                            m < 48 -> (m - 40) / 8.0
                            m <= 79 -> 1.0
                            m < 91 -> (91 - m) / 12.0
                            else -> 0.0
                        }
                        beatChroma[b][m % 12] += w * act[s]
                    }
                } else {
                    for (s in 0 until NOTES) {
                        val m = s + NOTE_LO
                        // lower notes weigh more: the lowest note is what the ear calls the bass
                        val w = when {
                            m <= 45 -> 1.0
                            m < 57 -> (57 - m) / 12.0
                            else -> 0.0
                        }
                        beatBass[b][m % 12] += w * act[s]
                    }
                }
            }
        }
    }

    private fun aggregate(frames: Array<FloatArray>, f0: Int, f1: Int, s: Int, tmp: FloatArray): Double {
        val n = f1 - f0
        if (n < 3 || n > tmp.size) {
            var sum = 0.0
            for (f in f0 until f1) sum += frames[f][s]
            return sum / n
        }
        for (i in 0 until n) tmp[i] = frames[f0 + i][s]
        java.util.Arrays.sort(tmp, 0, n)
        return if (n % 2 == 1) tmp[n / 2].toDouble() else 0.5 * (tmp[n / 2 - 1] + tmp[n / 2])
    }

    /** Remove the noise floor and normalise. */
    private fun processChroma(c: DoubleArray): DoubleArray {
        val sorted = c.sorted()
        val floor = sorted[3] // 4th smallest: roughly the noise floor
        val out = DoubleArray(12) { max(0.0, c[it] - floor) }
        normalize(out)
        return out
    }

    // ---------------------------------------------------------------- key

    private fun keyProfileScores(total: DoubleArray): DoubleArray {
        val s = DoubleArray(24)
        for (k in 0 until 24) {
            val prof = if (k < 12) KK_MAJOR else KK_MINOR
            val tonic = k % 12
            val rotated = DoubleArray(12) { prof[Notes.pc(it - tonic)] }
            s[k] = pearson(total, rotated)
        }
        return s
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in a.indices) {
            num += (a[i] - ma) * (b[i] - mb); da += (a[i] - ma).pow(2); db += (b[i] - mb).pow(2)
        }
        return if (da == 0.0 || db == 0.0) 0.0 else num / sqrt(da * db)
    }

    private fun keyFromIndex(k: Int) = MusicalKey(k % 12, k >= 12)

    private fun keyTotalScores(profile: DoubleArray, path: IntArray?): DoubleArray {
        val out = profile.copyOf()
        if (path == null || path.isEmpty()) return out
        val chords = path.filter { it >= 0 }.map { VOCAB[it] }
        if (chords.isEmpty()) return out
        val first = chords.first()
        val last = chords.last()
        for (k in 0 until 24) {
            val key = keyFromIndex(k)
            val dia = key.diatonic().toSet()
            val frac = chords.count { it in dia }.toDouble() / chords.size
            var s = 0.6 * profile[k] + 0.8 * frac
            val tonicChord = Chord(key.tonic, if (key.minor) Quality.MIN else Quality.MAJ)
            if (last.root == tonicChord.root && last.quality.triad == tonicChord.quality) s += 0.12
            if (first.root == tonicChord.root && first.quality.triad == tonicChord.quality) s += 0.06
            // tonic chord should actually be used a lot
            val tonicShare = chords.count { it.root == key.tonic && it.quality.triad == tonicChord.quality }.toDouble() / chords.size
            s += 0.3 * tonicShare
            out[k] = s
        }
        return out
    }

    private fun bestKey(profile: DoubleArray, path: IntArray?): MusicalKey {
        val s = keyTotalScores(profile, path)
        var bi = 0
        for (k in 1 until 24) if (s[k] > s[bi]) bi = k
        return keyFromIndex(bi)
    }

    private fun keyConfidence(profile: DoubleArray, path: IntArray, key: MusicalKey): Float {
        val s = keyTotalScores(profile, path)
        val idx = key.tonic + if (key.minor) 12 else 0
        val sorted = s.sorted()
        val second = if (sorted.last() == s[idx]) sorted[sorted.size - 2] else sorted.last()
        val rel = key.relative
        val relIdx = rel.tonic + if (rel.minor) 12 else 0
        // margin over the best key that is not the relative one is what matters most
        var other = Double.NEGATIVE_INFINITY
        for (k in 0 until 24) if (k != idx && k != relIdx) other = max(other, s[k])
        val m1 = s[idx] - other
        val m2 = s[idx] - second
        return (0.5 + 1.5 * m1 + 2.0 * m2).coerceIn(0.05, 0.99).toFloat()
    }

    // ---------------------------------------------------------------- chords

    private val kappa = 24.0
    private val noneIndex get() = VOCAB.size

    /** Score (higher = better) of every vocab chord plus "no chord" for one beat. */
    private fun beatScores(
        chroma: DoubleArray, bassN: DoubleArray, energy: Double, medEnergy: Double, diatonic: Set<Chord>,
    ): DoubleArray {
        val out = DoubleArray(VOCAB.size + 1)
        for (i in VOCAB.indices) {
            val c = VOCAB[i]
            var s = dot(chroma, templates[i]) + COMPLEXITY.getValue(c.quality)
            if (c in diatonic) s += 0.035
            // the bass usually plays the root
            s += 0.12 * bassN[c.root]
            out[i] = s
        }
        // "no chord": silence or very flat (noise / drums only) pitch content
        var mean = 0.0
        for (v in chroma) mean += v
        mean /= 12
        var geo = 0.0
        for (v in chroma) geo += ln(v + 1e-3)
        geo = exp(geo / 12)
        val flatness = geo / (mean + 1e-9)
        val quiet = energy < 0.08 * medEnergy
        out[noneIndex] = if (quiet) 2.0 else 0.45 + 0.5 * flatness
        return out
    }

    private fun viterbi(
        chroma: Array<DoubleArray>, bassN: Array<DoubleArray>, energy: DoubleArray, medEnergy: Double,
        key: MusicalKey, bpb: Int, phase: Int,
    ): IntArray {
        val nb = chroma.size
        val ns = VOCAB.size + 1
        if (nb == 0) return IntArray(0)
        val dia = key.diatonic().toSet()
        var prev = DoubleArray(ns)
        val back = Array(nb) { IntArray(ns) }
        var em = beatScores(chroma[0], bassN[0], energy[0], medEnergy, dia)
        for (s in 0 until ns) prev[s] = kappa * em[s]
        for (b in 1 until nb) {
            em = beatScores(chroma[b], bassN[b], energy[b], medEnergy, dia)
            val down = b >= phase && (b - phase) % bpb == 0
            val halfBar = bpb == 4 && b >= phase && (b - phase) % bpb == 2
            val pChange = when {
                down -> 0.35
                halfBar -> 0.15
                else -> 0.06
            }
            val stay = ln(1 - pChange)
            val move = ln(pChange / (ns - 1))
            var bestPrev = 0
            for (s in 1 until ns) if (prev[s] > prev[bestPrev]) bestPrev = s
            val cur = DoubleArray(ns)
            for (s in 0 until ns) {
                val viaStay = prev[s] + stay
                val viaMove = prev[bestPrev] + move
                if (bestPrev != s && viaMove > viaStay) {
                    cur[s] = viaMove + kappa * em[s]; back[b][s] = bestPrev
                } else {
                    cur[s] = viaStay + kappa * em[s]; back[b][s] = s
                }
            }
            prev = cur
        }
        val path = IntArray(nb)
        var s = 0
        for (i in 1 until ns) if (prev[i] > prev[s]) s = i
        for (b in nb - 1 downTo 0) {
            path[b] = s
            s = back[b][s]
        }
        return IntArray(nb) { if (path[it] == noneIndex) -1 else path[it] }
    }

    /**
     * A bass that walks towards the next bar (usually on beat 3 or 4) can fool the recogniser
     * into a short new chord. If such a short run in the tail of a bar is explained almost as
     * well by the chord before it once the bass is ignored, it is that chord still sounding.
     */
    private fun absorbPassingBass(path: IntArray, chroma: Array<DoubleArray>, bpb: Int, phase: Int) {
        val nb = path.size
        fun isDown(b: Int) = b >= phase && (b - phase) % bpb == 0
        var b = 0
        while (b < nb) {
            var e = b + 1
            while (e < nb && path[e] == path[b]) e++
            val cur = path[b]
            val prev = if (b > 0) path[b - 1] else -1
            if (cur >= 0 && prev >= 0 && VOCAB[cur].root != VOCAB[prev].root &&
                !isDown(b) && e - b < bpb && (e >= nb || isDown(e))
            ) {
                var sCur = 0.0
                var sPrev = 0.0
                for (k in b until e) {
                    sCur += dot(chroma[k], templates[cur]); sPrev += dot(chroma[k], templates[prev])
                }
                if (sPrev >= sCur - 0.05 * (e - b)) for (k in b until e) path[k] = prev
            }
            b = e
        }
    }

    private val MAJOR_FAMILY = setOf(Quality.MAJ, Quality.DOM7, Quality.MAJ7, Quality.AUG)
    private val MINOR_FAMILY = setOf(Quality.MIN, Quality.MIN7, Quality.DIM, Quality.HDIM)

    /**
     * Second look at every chord run: the root is kept, but major / minor and the flavour
     * (7, maj7, sus...) are chosen again from the harmony of the whole run, which is far
     * steadier than single beats. When the third is barely heard (mostly root and fifth),
     * minor is only chosen on clear evidence; otherwise the way the same root is played in
     * the rest of the song decides, then the key, then plain major.
     */
    private fun refineQualities(path: IntArray, chroma: Array<DoubleArray>, key: MusicalKey) {
        val dia = key.diatonic().toSet()
        val nb = path.size
        // how each root is played across the song: +1 major, -1 minor, 0 unclear
        val sumMaj = DoubleArray(12)
        val sumMin = DoubleArray(12)
        for (b in 0 until nb) {
            val idx = path[b]
            if (idx < 0) continue
            val r = VOCAB[idx].root
            sumMaj[r] += chroma[b][Notes.pc(r + 4)]; sumMin[r] += chroma[b][Notes.pc(r + 3)]
        }
        val songMode = IntArray(12) { r ->
            when {
                sumMaj[r] > 1.25 * sumMin[r] -> 1
                sumMin[r] > 1.25 * sumMaj[r] -> -1
                else -> 0
            }
        }
        var b = 0
        while (b < nb) {
            var e = b + 1
            while (e < nb && path[e] == path[b]) e++
            val idx = path[b]
            if (idx >= 0) {
                val avg = DoubleArray(12)
                for (k in b until e) for (p in 0 until 12) avg[p] += chroma[k][p]
                normalize(avg)
                val root = VOCAB[idx].root
                val eRoot = avg[root]
                val e3M = avg[Notes.pc(root + 4)]
                val e3m = avg[Notes.pc(root + 3)]
                val third = max(e3M, e3m)
                val strong = third > 0.3 * eRoot
                val mode = when {
                    strong && e3m > 1.3 * e3M -> -1
                    strong && e3M > 1.3 * e3m -> 1
                    songMode[root] != 0 -> songMode[root]
                    Chord(root, Quality.MIN) in dia && Chord(root, Quality.MAJ) !in dia -> -1
                    else -> 1
                }
                // sus only when the 4th / 2nd really replaces the third
                val e4 = avg[Notes.pc(root + 5)]
                val e2 = avg[Notes.pc(root + 2)]
                val sus4ok = e4 > 1.5 * third && e4 > 0.4 * eRoot
                val sus2ok = e2 > 1.5 * third && e2 > 0.4 * eRoot
                var best = -1
                var bestS = Double.NEGATIVE_INFINITY
                for (i in VOCAB.indices) {
                    val c = VOCAB[i]
                    if (c.root != root) continue
                    val ok = when (c.quality) {
                        Quality.SUS4 -> sus4ok
                        Quality.SUS2 -> sus2ok
                        in MAJOR_FAMILY -> mode > 0
                        else -> mode < 0
                    }
                    if (!ok) continue
                    var sc = dot(avg, templates[i]) + 0.7 * COMPLEXITY.getValue(c.quality)
                    if (c in dia) sc += 0.02
                    if (i == idx) sc += 0.01
                    if (sc > bestS) {
                        bestS = sc; best = i
                    }
                }
                if (best >= 0) for (k in b until e) path[k] = best
            }
            b = e
        }
    }

    private fun buildSegments(
        path: IntArray, chroma: Array<DoubleArray>, bassRaw: Array<DoubleArray>, bassN: Array<DoubleArray>,
        energy: DoubleArray, medEnergy: Double, beats: List<Double>, key: MusicalKey, bpb: Int, phase: Int,
    ): List<ChordSegment> {
        val nb = path.size
        val dia = key.diatonic().toSet()
        // Per-beat bass pitch class, -1 when unclear
        val beatBass = IntArray(nb) { b ->
            val v = bassRaw[b]
            var i1 = 0
            for (p in 1 until 12) if (v[p] > v[i1]) i1 = p
            var second = 0.0
            for (p in 0 until 12) if (p != i1) second = max(second, v[p])
            if (v[i1] > 1.15 * second && v[i1] > 0.05) i1 else -1
        }

        val out = ArrayList<ChordSegment>()
        var b = 0
        while (b < nb) {
            var e = b + 1
            while (e < nb && path[e] == path[b]) e++
            val idx = path[b]
            if (idx < 0) {
                out.add(segment(b, e, Chord.NONE, beats, chroma, bassN, energy, medEnergy, dia, idx))
                b = e
                continue
            }
            val base = VOCAB[idx]
            // Split the run where the bass clearly moves (e.g. Am -> Am/G)
            val runs = bassRuns(beatBass, b, e, base, bpb, phase)
            for ((rs, re, bassPc) in runs) {
                var chord = base
                var rerooted: Chord? = null
                if (bassPc >= 0 && bassPc != base.root) {
                    val bassAvg = DoubleArray(12)
                    for (k in rs until re) for (p in 0 until 12) bassAvg[p] += bassRaw[k][p]
                    val dominant = bassAvg[bassPc] > 1.3 * bassAvg[base.root]
                    if (dominant) {
                        chord = Chord(base.root, base.quality, bassPc)
                        // Am7 with G in the bass reads better as Am/G
                        val seventh = Notes.pc(base.root + (base.quality.intervals.getOrNull(3) ?: -100))
                        if (base.quality.isSeventh && bassPc == seventh) chord = Chord(base.root, base.quality.triad, bassPc)
                        // a plain chord built on that bass that fits almost as well (Abm/B -> B,
                        // Am/F -> Fmaj7) is offered as the first alternative
                        val scores = avgScores(rs, re, chroma, bassN, energy, medEnergy, dia)
                        var bestOnBass = -1
                        for (i in VOCAB.indices) {
                            if (VOCAB[i].root != bassPc) continue
                            if (bestOnBass < 0 || scores[i] > scores[bestOnBass]) bestOnBass = i
                        }
                        if (bestOnBass >= 0 && scores[bestOnBass] >= scores[idx] - 0.05) rerooted = VOCAB[bestOnBass]
                    }
                }
                var sg = segment(rs, re, chord, beats, chroma, bassN, energy, medEnergy, dia, idx)
                rerooted?.let { r ->
                    sg = sg.copy(alternatives = (listOf(r) + sg.alternatives.filter { it != r }).take(4))
                }
                out.add(sg)
            }
            b = e
        }
        // merge neighbours that ended up identical
        val merged = ArrayList<ChordSegment>()
        for (s in out) {
            val last = merged.lastOrNull()
            if (last != null && last.chord == s.chord) {
                merged[merged.size - 1] = last.copy(endBeat = s.endBeat, end = s.end, confidence = min(last.confidence, s.confidence))
            } else merged.add(s)
        }
        return merged
    }

    private fun avgScores(
        b: Int, e: Int, chroma: Array<DoubleArray>, bassN: Array<DoubleArray>,
        energy: DoubleArray, medEnergy: Double, dia: Set<Chord>,
    ): DoubleArray {
        val avg = DoubleArray(VOCAB.size + 1)
        for (k in b until e) {
            val s = beatScores(chroma[k], bassN[k], energy[k], medEnergy, dia)
            for (i in s.indices) avg[i] += s[i]
        }
        for (i in avg.indices) avg[i] /= max(1, e - b)
        return avg
    }

    private data class Run(val start: Int, val end: Int, val bass: Int)

    private fun bassRuns(beatBass: IntArray, b: Int, e: Int, chord: Chord, bpb: Int, phase: Int): List<Run> {
        // bass per beat, unclear beats inherit the chord root
        val bassSeq = IntArray(e - b) { val v = beatBass[b + it]; if (v < 0) chord.root else v }
        if (bassSeq.isNotEmpty() && bassSeq[0] == chord.root) {
            // root-fifth alternating bass is an accompaniment pattern, not a new chord
            val fifth = Notes.pc(chord.root + 7)
            for (i in bassSeq.indices) if (bassSeq[i] == fifth) bassSeq[i] = chord.root
        }
        val runs = ArrayList<Run>()
        var i = 0
        while (i < bassSeq.size) {
            var j = i + 1
            while (j < bassSeq.size && bassSeq[j] == bassSeq[i]) j++
            runs.add(Run(b + i, b + j, bassSeq[i]))
            i = j
        }
        // absorb runs shorter than 2 beats into the previous (or next) run
        var changed = true
        while (changed && runs.size > 1) {
            changed = false
            for (k in runs.indices) {
                val r = runs[k]
                if (r.end - r.start < 2) {
                    if (k > 0) {
                        val p = runs[k - 1]; runs[k - 1] = Run(p.start, r.end, p.bass)
                    } else {
                        val n = runs[k + 1]; runs[k + 1] = Run(r.start, n.end, n.bass)
                    }
                    runs.removeAt(k)
                    changed = true
                    break
                }
            }
        }
        // join neighbours with the same bass after absorption; a bass that moves in the
        // middle of a bar is on its way to the next chord, not a new slash chord
        val joined = ArrayList<Run>()
        for (r in runs) {
            val l = joined.lastOrNull()
            val midBar = !(r.start >= phase && (r.start - phase) % bpb == 0)
            if (l != null && midBar) joined[joined.size - 1] = Run(l.start, r.end, l.bass)
            else if (l != null && l.bass == r.bass) joined[joined.size - 1] = Run(l.start, r.end, l.bass) else joined.add(r)
        }
        return joined
    }

    private fun segment(
        b: Int, e: Int, chord: Chord, beats: List<Double>, chroma: Array<DoubleArray>, bassN: Array<DoubleArray>,
        energy: DoubleArray, medEnergy: Double, dia: Set<Chord>, idx: Int,
    ): ChordSegment {
        val avg = DoubleArray(VOCAB.size + 1)
        for (k in b until e) {
            val s = beatScores(chroma[k], bassN[k], energy[k], medEnergy, dia)
            for (i in s.indices) avg[i] += s[i]
        }
        for (i in avg.indices) avg[i] /= (e - b)
        val order = avg.indices.sortedByDescending { avg[it] }
        val alts = ArrayList<Chord>()
        if (chord.hasSlash) {
            alts.add(chord.withoutSlash())
            if (chord.quality != VOCAB.getOrNull(idx)?.quality && idx >= 0) alts.add(Chord(VOCAB[idx].root, VOCAB[idx].quality, chord.bass))
        }
        for (i in order) {
            if (alts.size >= 4) break
            val c = if (i == noneIndex) Chord.NONE else VOCAB[i]
            if (c == chord || c == chord.withoutSlash() && !chord.hasSlash || c in alts) continue
            if (c.isNone && chord.isNone) continue
            alts.add(c)
        }
        val chosen = if (idx < 0) avg[noneIndex] else avg[idx]
        val bestOther = order.first { it != (if (idx < 0) noneIndex else idx) }.let { avg[it] }
        val margin = chosen - bestOther
        val conf = (1.0 / (1.0 + exp(-margin * 40))).toFloat()
        return ChordSegment(b, e, beats[b], beats[e], chord, chord, alts.take(4), conf)
    }
}
