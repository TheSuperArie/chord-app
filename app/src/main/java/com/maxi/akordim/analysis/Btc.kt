package com.maxi.akordim.analysis

import android.content.Context
import com.maxi.akordim.music.Quality
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AI chord engine: BTC - Bi-directional Transformer for Chord recognition
 * (J. Park et al., ISMIR 2019, MIT licence, github.com/jayg996/BTC-ISMIR19), large vocabulary
 * (12 roots x 14 qualities + no-chord). Weights in assets/btc.bin (f16).
 * Front end: log constant-Q spectrum, 144 bins, 24 per octave from C1, one frame per 2048
 * samples at 22050 Hz, computed here multi-rate from the app's 11025 Hz audio.
 * Checked against the original (librosa + torch) in tools/btc: 99.4% of frames agree.
 */
object Btc {
    private const val T = 108
    private const val NB = 144
    private const val H = 128
    private const val NC = 170

    @Volatile private var model: Model? = null
    @Volatile private var failed = false

    fun init(context: Context) {
        if (model != null || failed) return
        Thread {
            try {
                synchronized(this) { if (model == null) model = context.assets.open("btc.bin").use { Model.load(it) } }
            } catch (e: Throwable) {
                failed = true
            }
        }.start()
    }

    /** Waits up to a few seconds for the weights (they load in the background at start-up). */
    fun ready(): Boolean {
        var waited = 0
        while (model == null && !failed && waited < 8000) { Thread.sleep(50); waited += 50 }
        return model != null
    }

    /**
     * Per-beat chord path (Analyzer.VOCAB index, -1 = no chord) for the given beat times.
     * [x] is the 11025 Hz audio, [level] the factor that restores its original loudness.
     */
    fun beatPath(x: FloatArray, level: Float, beats: List<Double>, bpb: Int, phase: Int): IntArray? {
        val m = model ?: return null
        val nb = beats.size - 1
        if (nb <= 0) return IntArray(0)
        val (feat, times) = Cqt.features(x, level)
        val nf = feat.size
        if (nf == 0) return null
        for (f in feat) for (i in f.indices) f[i] = (f[i] - m.mean) / m.std
        val logits = m.run(feat)
        // frame probabilities collapsed onto the app's chord list (+ "none")
        val nv = Analyzer.VOCAB.size
        val ns = nv + 1
        val logp = Array(nf) { DoubleArray(ns) }
        for (t in 0 until nf) {
            val lg = logits[t]
            var mx = Float.NEGATIVE_INFINITY
            for (v in lg) mx = max(mx, v)
            var sum = 0.0
            val p = DoubleArray(NC)
            for (c in 0 until NC) { p[c] = exp((lg[c] - mx).toDouble()); sum += p[c] }
            val acc = DoubleArray(ns)
            for (c in 0 until NC) acc[stateOf(c)] += p[c] / sum
            for (s in 0 until ns) logp[t][s] = ln(acc[s] + 1e-6)
        }
        // beat emissions: mean log-probability of the frames inside the beat
        val em = Array(nb) { DoubleArray(ns) }
        var f0 = 0
        for (b in 0 until nb) {
            val s0 = beats[b]; val s1 = beats[b + 1]
            while (f0 < nf && times[f0] < s0) f0++
            var f1 = f0
            while (f1 < nf && times[f1] < s1) f1++
            val lo: Int; val hi: Int
            if (f1 > f0) { lo = f0; hi = f1 } else {
                val mid = (s0 + s1) / 2
                var best = 0
                for (t in max(0, f0 - 2) until min(nf, f0 + 2)) if (kotlin.math.abs(times[t] - mid) < kotlin.math.abs(times[best] - mid)) best = t
                lo = best; hi = best + 1
            }
            for (t in lo until hi) for (s in 0 until ns) em[b][s] += logp[t][s]
            for (s in 0 until ns) em[b][s] = EMIT * em[b][s] / (hi - lo)
        }
        // Viterbi with the same bar-aware change probabilities as the classic engine
        var prev = em[0].copyOf()
        val back = Array(nb) { IntArray(ns) }
        for (b in 1 until nb) {
            val down = b >= phase && (b - phase) % bpb == 0
            val halfBar = bpb == 4 && b >= phase && (b - phase) % bpb == 2
            val pChange = when { down -> 0.35; halfBar -> 0.15; else -> 0.06 }
            val stay = ln(1 - pChange)
            val move = ln(pChange / (ns - 1))
            var bestPrev = 0
            for (s in 1 until ns) if (prev[s] > prev[bestPrev]) bestPrev = s
            val cur = DoubleArray(ns)
            for (s in 0 until ns) {
                val viaStay = prev[s] + stay
                val viaMove = prev[bestPrev] + move
                if (bestPrev != s && viaMove > viaStay) { cur[s] = viaMove + em[b][s]; back[b][s] = bestPrev }
                else { cur[s] = viaStay + em[b][s]; back[b][s] = s }
            }
            prev = cur
        }
        val path = IntArray(nb)
        var s = 0
        for (i in 1 until ns) if (prev[i] > prev[s]) s = i
        for (b in nb - 1 downTo 0) { path[b] = s; s = back[b][s] }
        return IntArray(nb) { if (path[it] == nv) -1 else path[it] }
    }

    private const val EMIT = 2.0

    /** For tests: load weights from a stream and return the frame-wise BTC class (0..169). */
    internal fun testFrames(weights: java.io.InputStream, x: FloatArray): IntArray {
        val m = Model.load(weights)
        val (feat, _) = Cqt.features(x, 1f)
        for (f in feat) for (i in f.indices) f[i] = (f[i] - m.mean) / m.std
        val lg = m.run(feat)
        return IntArray(lg.size) { t -> var b = 0; for (c in 1 until NC) if (lg[t][c] > lg[t][b]) b = c; b }
    }

    // BTC qualities: min maj dim aug min6 maj6 min7 minmaj7 maj7 7 dim7 hdim7 sus2 sus4
    private val QMAP = arrayOf(
        Quality.MIN, Quality.MAJ, Quality.DIM, Quality.AUG, Quality.MIN, Quality.MAJ, Quality.MIN7,
        Quality.MIN, Quality.MAJ7, Quality.DOM7, Quality.DIM, Quality.HDIM, Quality.SUS2, Quality.SUS4,
    )

    private fun stateOf(c: Int): Int =
        if (c >= 168) Analyzer.VOCAB.size else QMAP[c % 14].ordinal * 12 + c / 14

    // ------------------------------------------------------------------ constant-Q front end

    private object Cqt {
        const val SR = 11025
        const val HOP = 1024
        const val BPO = 24
        const val FMIN = 32.70319566257483
        val Q = 1.0 / (2.0.pow(1.0 / BPO) - 1)
        const val CHUNK = 110250 // the original computes the spectrum in separate 10 s pieces

        private val half: FloatArray = run {
            val m = 63
            val h = DoubleArray(m) { n ->
                val x = 0.5 * (n - (m - 1) / 2.0)
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val bl = 0.42 - 0.5 * cos(2 * PI * n / (m - 1)) + 0.08 * cos(4 * PI * n / (m - 1))
                0.5 * sinc * bl
            }
            val s = h.sum()
            FloatArray(m) { (h[it] / s).toFloat() }
        }

        private class Kernel(val start: Int, val re: FloatArray, val im: FloatArray, val scale: Float)

        private val kernels: Array<Array<Kernel>> = Array(6) { o ->
            val sr = SR / 2.0.pow(5 - o)
            Array(BPO) { j ->
                val f = FMIN * 2.0.pow((o * BPO + j) / BPO.toDouble())
                val l = Q * sr / f
                val n = floor(l).toInt()
                val start = Math.floorDiv(-n, 2)
                val re = FloatArray(n); val im = FloatArray(n)
                var wsum = 0.0
                val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
                for (w in win) wsum += w
                for (t in 0 until n) {
                    val ph = 2 * PI * f * (t + start) / sr
                    re[t] = (win[t] * cos(ph) / wsum).toFloat()
                    im[t] = (win[t] * sin(ph) / wsum).toFloat()
                }
                Kernel(start, re, im, sqrt(Q * 22050 / f).toFloat())
            }
        }

        private fun decimate(y: FloatArray): FloatArray {
            val m = half.size; val c = (m - 1) / 2
            val out = FloatArray((y.size + 1) / 2)
            for (i in out.indices) {
                val center = 2 * i + c
                var s = 0f
                for (k in 0 until m) {
                    val idx = center - k
                    if (idx >= 0 && idx < y.size) s += y[idx] * half[k]
                }
                out[i] = s
            }
            return out
        }

        private fun reflect(i: Int, n: Int): Int {
            if (n == 1) return 0
            val p = 2 * n - 2
            var k = i % p
            if (k < 0) k += p
            return if (k < n) k else p - k
        }

        /** [frames][144] log spectrum and the time (s) of each frame. */
        fun features(x: FloatArray, level: Float): Pair<Array<FloatArray>, DoubleArray> {
            val frames = ArrayList<FloatArray>()
            val times = ArrayList<Double>()
            var s = 0
            while (s < x.size) {
                val e = if (x.size > s + CHUNK) s + CHUNK else x.size
                val part = x.copyOfRange(s, e)
                val c = chunk(part, level)
                for (j in c.indices) { frames.add(c[j]); times.add(s.toDouble() / SR + j * HOP.toDouble() / SR) }
                s = e
                if (e == x.size) break
            }
            return frames.toTypedArray() to times.toDoubleArray()
        }

        private fun chunk(y: FloatArray, level: Float): Array<FloatArray> {
            val nfr = 1 + y.size / HOP
            val out = Array(nfr) { FloatArray(NB) }
            val sigs = arrayOfNulls<FloatArray>(6)
            var sig = y
            for (o in 5 downTo 0) { sigs[o] = sig; if (o > 0) sig = decimate(sig) }
            for (o in 0 until 6) {
                val sgl = sigs[o]!!
                val n = sgl.size
                val hop = HOP shr (5 - o)
                for (j in 0 until BPO) {
                    val k = kernels[o][j]
                    val len = k.re.size
                    for (fr in 0 until nfr) {
                        val base = fr * hop + k.start
                        var sr = 0f; var si = 0f
                        if (n > 1 && base >= 0 && base + len <= n) {
                            for (t in 0 until len) { val v = sgl[base + t]; sr += v * k.re[t]; si += v * k.im[t] }
                        } else if (n > 0) {
                            for (t in 0 until len) { val v = sgl[reflect(base + t, n)]; sr += v * k.re[t]; si += v * k.im[t] }
                        }
                        val mag = sqrt(sr * sr + si * si) * k.scale * level
                        out[fr][o * BPO + j] = ln(mag + 1e-6f)
                    }
                }
            }
            return out
        }
    }

    // ------------------------------------------------------------------ the transformer

    private class Block(
        val q: FloatArray, val k: FloatArray, val v: FloatArray, val o: FloatArray,
        val c0w: FloatArray, val c0b: FloatArray, val c1w: FloatArray, val c1b: FloatArray,
        val ln1g: FloatArray, val ln1b: FloatArray, val ln2g: FloatArray, val ln2b: FloatArray,
    )

    private class Layer(val fwd: Block, val bwd: Block, val linW: FloatArray, val linB: FloatArray)

    private class Model(
        val mean: Float, val std: Float, val embed: FloatArray, val layers: List<Layer>,
        val lnG: FloatArray, val lnB: FloatArray, val outW: FloatArray, val outB: FloatArray,
    ) {
        private val timing: FloatArray = FloatArray(T * H).also { ts ->
            val nts = H / 2
            val inc = ln(1e4) / (nts - 1)
            for (p in 0 until T) for (i in 0 until nts) {
                val st = p * exp(-i * inc)
                ts[p * H + i] = sin(st).toFloat(); ts[p * H + nts + i] = cos(st).toFloat()
            }
        }

        fun run(feat: Array<FloatArray>): Array<FloatArray> {
            val n = feat.size
            val windows = (n + T - 1) / T
            val out = Array(n) { FloatArray(NC) }
            val threads = max(1, min(4, Runtime.getRuntime().availableProcessors()))
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val jobs = ArrayList<Future<*>>()
                for (w in 0 until windows) jobs.add(pool.submit {
                    val x = FloatArray(T * NB)
                    for (t in 0 until T) { val i = w * T + t; if (i < n) feat[i].copyInto(x, t * NB) }
                    val lg = window(x)
                    for (t in 0 until T) { val i = w * T + t; if (i < n) lg.copyInto(out[i], 0, t * NC, (t + 1) * NC) }
                })
                for (j in jobs) j.get()
            } finally {
                pool.shutdown()
            }
            return out
        }

        private fun window(feat: FloatArray): FloatArray {
            var x = linear(feat, NB, embed, H, null)
            for (i in x.indices) x[i] += timing[i]
            for (l in layers) {
                val f = block(x, l.fwd, true)
                val b = block(x, l.bwd, false)
                val cat = FloatArray(T * 2 * H)
                for (t in 0 until T) { f.copyInto(cat, t * 2 * H, t * H, (t + 1) * H); b.copyInto(cat, t * 2 * H + H, t * H, (t + 1) * H) }
                x = linear(cat, 2 * H, l.linW, H, l.linB)
            }
            x = layerNorm(x, lnG, lnB)
            return linear(x, H, outW, NC, outB)
        }

        private fun block(x: FloatArray, p: Block, forward: Boolean): FloatArray {
            val xn = layerNorm(x, p.ln1g, p.ln1b)
            val q = linear(xn, H, p.q, H, null)
            val k = linear(xn, H, p.k, H, null)
            val v = linear(xn, H, p.v, H, null)
            val ctx = FloatArray(T * H)
            val dh = H / 4
            val scale = 1f / sqrt(dh.toFloat())
            val wts = FloatArray(T)
            for (h in 0 until 4) {
                val off = h * dh
                for (i in 0 until T) {
                    val j0 = if (forward) 0 else i
                    val j1 = if (forward) i else T - 1
                    var mx = Float.NEGATIVE_INFINITY
                    for (j in j0..j1) {
                        var s = 0f
                        val qi = i * H + off; val kj = j * H + off
                        for (d in 0 until dh) s += q[qi + d] * k[kj + d]
                        s *= scale
                        wts[j] = s
                        if (s > mx) mx = s
                    }
                    var sum = 0f
                    for (j in j0..j1) { val e = exp(wts[j] - mx); wts[j] = e; sum += e }
                    val ci = i * H + off
                    for (j in j0..j1) {
                        val w = wts[j] / sum
                        val vj = j * H + off
                        for (d in 0 until dh) ctx[ci + d] += w * v[vj + d]
                    }
                }
            }
            val att = linear(ctx, H, p.o, H, null)
            val x1 = FloatArray(T * H) { x[it] + att[it] }
            val xn2 = layerNorm(x1, p.ln2g, p.ln2b)
            val h1 = conv(xn2, p.c0w, p.c0b)
            val h2 = conv(h1, p.c1w, p.c1b)
            for (i in x1.indices) x1[i] += h2[i]
            return x1
        }

        /** kernel-3 convolution over time, zero padded on both sides, then ReLU. w = [out][tap][in]. */
        private fun conv(x: FloatArray, w: FloatArray, b: FloatArray): FloatArray {
            val y = FloatArray(T * H)
            for (t in 0 until T) for (o in 0 until H) {
                var s = b[o]
                for (tap in 0 until 3) {
                    val tt = t + tap - 1
                    if (tt < 0 || tt >= T) continue
                    val xo = tt * H; val wo = (o * 3 + tap) * H
                    for (i in 0 until H) s += x[xo + i] * w[wo + i]
                }
                y[t * H + o] = if (s > 0f) s else 0f
            }
            return y
        }

        private fun linear(x: FloatArray, inD: Int, w: FloatArray, outD: Int, b: FloatArray?): FloatArray {
            val rows = x.size / inD
            val y = FloatArray(rows * outD)
            for (t in 0 until rows) {
                val xo = t * inD
                for (o in 0 until outD) {
                    var s = b?.get(o) ?: 0f
                    val wo = o * inD
                    for (i in 0 until inD) s += x[xo + i] * w[wo + i]
                    y[t * outD + o] = s
                }
            }
            return y
        }

        private fun layerNorm(x: FloatArray, g: FloatArray, b: FloatArray): FloatArray {
            val y = FloatArray(x.size)
            for (t in 0 until x.size / H) {
                val o = t * H
                var m = 0f
                for (i in 0 until H) m += x[o + i]
                m /= H
                var v = 0f
                for (i in 0 until H) { val d = x[o + i] - m; v += d * d }
                val sd = sqrt(v / (H - 1))
                for (i in 0 until H) y[o + i] = g[i] * (x[o + i] - m) / (sd + 1e-6f) + b[i]
            }
            return y
        }

        companion object {
            fun load(input: java.io.InputStream): Model {
                val bytes = DataInputStream(input).readBytes()
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { bb.get(it) }
                require(String(magic) == "BTC1")
                val mean = bb.float; val std = bb.float
                fun arr(n: Int) = FloatArray(n) { halfToFloat(bb.short.toInt() and 0xffff) }
                val embed = arr(H * NB)
                val layers = (0 until 8).map {
                    fun blk() = Block(
                        arr(H * H), arr(H * H), arr(H * H), arr(H * H),
                        arr(H * 3 * H), arr(H), arr(H * 3 * H), arr(H),
                        arr(H), arr(H), arr(H), arr(H),
                    )
                    val f = blk(); val b = blk()
                    Layer(f, b, arr(H * 2 * H), arr(H))
                }
                val lnG = arr(H); val lnB = arr(H)
                val outW = arr(NC * H); val outB = arr(NC)
                require(!bb.hasRemaining())
                return Model(mean, std, embed, layers, lnG, lnB, outW, outB)
            }

            private fun halfToFloat(h: Int): Float {
                val sign = (h ushr 15) and 1
                val exp = (h ushr 10) and 0x1f
                val mant = h and 0x3ff
                val v = when (exp) {
                    0 -> mant / 1024f * 2f.pow(-14)
                    31 -> if (mant == 0) Float.POSITIVE_INFINITY else Float.NaN
                    else -> (1 + mant / 1024f) * 2f.pow(exp - 15)
                }
                return if (sign == 1) -v else v
            }
        }
    }
}
