package com.maxi.akordim.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Radix-2 FFT for real input with a Hann window, returning magnitudes. */
class Fft(val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }
    val window = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }
    private val re = FloatArray(n)
    private val im = FloatArray(n)

    init {
        require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two" }
    }

    /**
     * Magnitude spectrum of x[start until start+n] (zero padded outside the signal).
     * [out] must hold n/2+1 values.
     */
    fun magnitudes(x: FloatArray, start: Int, out: FloatArray) {
        for (i in 0 until n) {
            val j = start + i
            val v = if (j >= 0 && j < x.size) x[j] * window[i] else 0f
            re[rev[i]] = v
            im[rev[i]] = 0f
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]
                    val wi = -sinT[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
        for (k in 0..n / 2) out[k] = sqrt(re[k] * re[k] + im[k] * im[k])
    }
}

/**
 * Streaming windowed-sinc resampler. Feed mono blocks with [push]; resampled audio
 * accumulates in an internal growable buffer that [finish] returns.
 */
class StreamingResampler(private val inRate: Int, private val outRate: Int) {
    private val ratio = inRate.toDouble() / outRate // input samples per output sample
    private val cutoff = 0.45 * minOf(1.0, 1.0 / ratio) // relative to input Nyquist*2
    private val zeros = 8
    private val halfWidth = (zeros / (2 * cutoff)).toInt() + 1 // in input samples
    private val tableRes = 256
    private val table: FloatArray

    private var buf = FloatArray(1 shl 16)
    private var bufLen = 0
    private var bufStart = 0L // absolute index of buf[0]
    private var nextOut = 0L // absolute index of the next output sample

    private var out = FloatArray(1 shl 18)
    private var outLen = 0

    init {
        // Kernel as a function of distance in input samples, tabulated.
        val n = halfWidth * tableRes + 2
        table = FloatArray(n) { i ->
            val d = i.toDouble() / tableRes
            val xs = 2 * cutoff * d
            val sinc = if (xs == 0.0) 1.0 else sin(PI * xs) / (PI * xs)
            val w = if (d >= halfWidth) 0.0 else 0.5 + 0.5 * cos(PI * d / halfWidth)
            (2 * cutoff * sinc * w).toFloat()
        }
    }

    private fun kernel(d: Double): Float {
        val a = kotlin.math.abs(d) * tableRes
        val i = a.toInt()
        if (i >= table.size - 1) return 0f
        val f = (a - i).toFloat()
        return table[i] * (1 - f) + table[i + 1] * f
    }

    fun push(x: FloatArray, len: Int = x.size) {
        if (inRate == outRate) {
            ensureOut(len)
            System.arraycopy(x, 0, out, outLen, len)
            outLen += len
            return
        }
        if (bufLen + len > buf.size) {
            // drop consumed input first
            compact()
            if (bufLen + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + len))
        }
        System.arraycopy(x, 0, buf, bufLen, len)
        bufLen += len
        produce(false)
    }

    private fun compact() {
        val center = (nextOut * ratio).toLong()
        val keepFrom = center - halfWidth - 2
        val drop = (keepFrom - bufStart).toInt()
        if (drop > 0 && drop <= bufLen) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
    }

    private fun ensureOut(extra: Int) {
        if (outLen + extra > out.size) out = out.copyOf(maxOf(out.size * 2, outLen + extra))
    }

    private fun produce(final: Boolean) {
        val available = bufStart + bufLen
        while (true) {
            val pos = nextOut * ratio
            val center = pos.toLong()
            if (!final && center + halfWidth + 1 >= available) break
            if (final && center >= available) break
            var acc = 0f
            val from = maxOf(center - halfWidth + 1, bufStart)
            val to = minOf(center + halfWidth, available - 1)
            var j = from
            while (j <= to) {
                acc += buf[(j - bufStart).toInt()] * kernel(pos - j)
                j++
            }
            ensureOut(1)
            out[outLen++] = acc
            nextOut++
        }
    }

    fun finish(): FloatArray {
        if (inRate != outRate) produce(true)
        return out.copyOf(outLen)
    }
}
