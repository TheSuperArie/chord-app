package com.maxi.akordim.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.maxi.akordim.analysis.Analyzer
import com.maxi.akordim.analysis.StreamingResampler
import java.nio.ByteOrder

/** Decodes any audio (or the sound of a video) to mono floats at [Analyzer.SR]. */
object AudioDecoder {

    class DecodeException(msg: String) : Exception(msg)

    fun decode(
        context: Context,
        uri: Uri,
        maxSeconds: Int = 15 * 60,
        cancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit,
    ): FloatArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
        } catch (e: Exception) {
            ex.release()
            throw DecodeException("לא הצלחתי לפתוח את הקובץ")
        }
        var track = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                track = i; break
            }
        }
        if (track < 0) {
            ex.release()
            throw DecodeException("לא נמצא שמע בקובץ הזה")
        }
        ex.selectTrack(track)
        val inFormat = ex.getTrackFormat(track)
        val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        val maxUs = maxSeconds * 1_000_000L

        val codec = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(inFormat, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            ex.release()
            throw DecodeException("סוג הקובץ לא נתמך במכשיר ($mime)")
        }

        var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var resampler: StreamingResampler? = null
        var mono = FloatArray(8192)

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastProgress = -1
        try {
            while (!outputDone) {
                if (cancelled()) throw DecodeException("בוטל")
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime > maxUs) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    }
                    out >= 0 -> {
                        val buf = codec.getOutputBuffer(out)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val rs = resampler ?: StreamingResampler(sampleRate, Analyzer.SR).also { resampler = it }
                            val ch = channels.coerceAtLeast(1)
                            val frames: Int
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = buf.slice().order(ByteOrder.nativeOrder()).asFloatBuffer()
                                frames = fb.remaining() / ch
                                if (mono.size < frames) mono = FloatArray(frames)
                                for (i in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until ch) s += fb.get(i * ch + c)
                                    mono[i] = s / ch
                                }
                            } else {
                                val sb = buf.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                                frames = sb.remaining() / ch
                                if (mono.size < frames) mono = FloatArray(frames)
                                for (i in 0 until frames) {
                                    var s = 0
                                    for (c in 0 until ch) s += sb.get(i * ch + c)
                                    mono[i] = s / (32768f * ch)
                                }
                            }
                            rs.push(mono, frames)
                            if (durationUs > 0) {
                                val p = ((info.presentationTimeUs.toDouble() / minOf(durationUs, maxUs)) * 100).toInt()
                                if (p != lastProgress) {
                                    lastProgress = p
                                    onProgress((p / 100f).coerceIn(0f, 1f))
                                }
                            }
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            ex.release()
        }
        val result = resampler?.finish() ?: FloatArray(0)
        if (result.size < Analyzer.SR * 3) throw DecodeException("הקובץ קצר מדי או ריק")
        return result
    }
}
