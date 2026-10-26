package com.maxi.akordim.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Tiny offline piano-like synthesizer (plus metronome clicks) on its own audio thread. */
class PianoSynth {
    private val sr = 22050
    private val events = ConcurrentLinkedQueue<Event>()
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var volume = 0.6f

    private sealed class Event {
        class Note(val midi: Int, val vel: Float) : Event()
        class Click(val accent: Boolean) : Event()
        object Silence : Event()
    }

    private class Voice(freq: Double, val vel: Float, srate: Int) {
        val partials = 6
        val inc = DoubleArray(partials) { (it + 1) * freq * (1 + 0.0004 * it * it) * 2 * PI / srate }
        val amp = DoubleArray(partials) { 0.55.pow(it) }
        val decay = DoubleArray(partials) { exp(-(1.2 + 0.9 * it) / srate) }
        val phase = DoubleArray(partials)
        val level = DoubleArray(partials) { 1.0 }
        var age = 0
        var released = false
        val attack = (0.004 * srate).toInt()
        fun done() = level[0] < 0.002
    }

    private class Click(val accent: Boolean, srate: Int) {
        var pos = 0
        val len = (0.03 * srate).toInt()
        val freq = if (accent) 1600.0 else 1100.0
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "piano-synth").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    fun note(midi: Int, vel: Float = 0.8f) {
        start(); events.add(Event.Note(midi, vel))
    }

    fun chord(midis: List<Int>, vel: Float = 0.7f) {
        start()
        events.add(Event.Silence)
        midis.forEachIndexed { i, m -> events.add(Event.Note(m, vel * if (i == 0) 0.9f else 0.65f)) }
    }

    fun click(accent: Boolean) {
        start(); events.add(Event.Click(accent))
    }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 2048))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val block = 256
        val out = ShortArray(block)
        val voices = ArrayList<Voice>()
        val clicks = ArrayList<Click>()
        var idle = 0
        while (running) {
            while (true) {
                when (val e = events.poll() ?: break) {
                    is Event.Note -> {
                        if (voices.size > 16) voices.removeAt(0)
                        voices.add(Voice(440.0 * 2.0.pow((e.midi - 69) / 12.0), e.vel, sr))
                    }
                    is Event.Click -> clicks.add(Click(e.accent, sr))
                    Event.Silence -> voices.forEach { it.released = true }
                }
            }
            for (i in 0 until block) {
                var s = 0.0
                val vit = voices.iterator()
                while (vit.hasNext()) {
                    val v = vit.next()
                    var vs = 0.0
                    val env = if (v.age < v.attack) v.age.toDouble() / v.attack else 1.0
                    for (p in 0 until v.partials) {
                        vs += sin(v.phase[p]) * v.amp[p] * v.level[p]
                        v.phase[p] += v.inc[p]
                        if (v.phase[p] > 2 * PI) v.phase[p] -= 2 * PI
                        v.level[p] *= if (v.released) v.decay[p] * 0.9993 else v.decay[p]
                    }
                    v.age++
                    s += vs * env * v.vel * 0.22
                    if (v.done()) vit.remove()
                }
                val cit = clicks.iterator()
                while (cit.hasNext()) {
                    val c = cit.next()
                    val t = c.pos.toDouble() / sr
                    s += sin(2 * PI * c.freq * t) * exp(-t * 180) * (if (c.accent) 0.5 else 0.3)
                    c.pos++
                    if (c.pos >= c.len) cit.remove()
                }
                val v = (s * volume).coerceIn(-1.0, 1.0)
                out[i] = (v * 32000).toInt().toShort()
            }
            track.write(out, 0, block)
            if (voices.isEmpty() && clicks.isEmpty()) {
                idle++
                if (idle > sr / block * 20) { // 20 s of silence: let the thread go
                    running = false
                }
            } else idle = 0
        }
        track.stop()
        track.release()
    }
}
