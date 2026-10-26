package com.maxi.akordim.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Reads the microphone on its own thread and hands out mono float blocks. */
class MicCapture(val sampleRate: Int = 22050, private val block: Int = 1024, private val onBlock: (FloatArray, Int) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, block * 4)
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); return false
        }
        running = true
        thread = Thread({
            val s = ShortArray(block)
            val f = FloatArray(block)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(s, 0, block)
                    if (n > 0) {
                        for (i in 0 until n) f[i] = s[i] / 32768f
                        onBlock(f, n)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }, "mic").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}
