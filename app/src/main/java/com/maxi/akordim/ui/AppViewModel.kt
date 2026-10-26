package com.maxi.akordim.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.maxi.akordim.analysis.Analyzer
import com.maxi.akordim.analysis.SongAnalysis
import com.maxi.akordim.audio.AudioDecoder
import com.maxi.akordim.audio.PianoSynth
import com.maxi.akordim.data.Recent
import com.maxi.akordim.data.SongFile
import com.maxi.akordim.data.SongStore
import com.maxi.akordim.music.Chord
import com.maxi.akordim.music.Guitar
import com.maxi.akordim.music.Spelling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

data class OpenSong(val uri: Uri, val title: String, val key: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SongStore(app)
    val synth = PianoSynth()
    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    // library
    var deviceSongs by mutableStateOf<List<SongFile>>(emptyList())
        private set
    var deviceSongsLoaded by mutableStateOf(false)
        private set
    var recents by mutableStateOf(store.recents())
        private set

    // current song
    var song by mutableStateOf<OpenSong?>(null)
        private set
    var analysis by mutableStateOf<SongAnalysis?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var progressText by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    // playback
    var position by mutableDoubleStateOf(0.0)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var segmentIndex by mutableIntStateOf(-1)
        private set
    var beatIndex by mutableIntStateOf(-1)
        private set

    // settings
    var transpose by mutableIntStateOf(0)
        private set
    var shiftAudio by mutableStateOf(true)
        private set
    var capo by mutableIntStateOf(0)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var pianoOn by mutableStateOf(true)
    var clickOn by mutableStateOf(false)

    private var analysisJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    player.pause(); player.seekTo(0)
                }
            }
        })
        viewModelScope.launch {
            while (isActive) {
                tick()
                delay(if (player.isPlaying) 20 else 120)
            }
        }
    }

    // ------------------------------------------------------------ library

    fun loadDeviceSongs() {
        viewModelScope.launch {
            deviceSongs = withContext(Dispatchers.IO) { store.deviceSongs() }
            deviceSongsLoaded = true
        }
    }

    fun refreshRecents() {
        recents = store.recents()
    }

    fun removeRecent(r: Recent) {
        store.removeRecent(r.key); refreshRecents()
    }

    fun openPicked(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        val (name, size) = store.describe(uri)
        open(uri, name, size)
    }

    fun openDevice(f: SongFile) = open(f.uri, f.title, f.size)

    fun openRecent(r: Recent) {
        val uri = Uri.parse(r.uri)
        open(uri, r.title, -1, r.key)
    }

    private fun open(uri: Uri, title: String, size: Long, knownKey: String? = null) {
        closeSong()
        val key = knownKey ?: store.cacheKey(uri, size)
        song = OpenSong(uri, title, key)
        val r = store.recent(key)
        transpose = r?.transpose ?: 0
        capo = r?.capo ?: 0
        speed = 1f
        shiftAudio = true
        error = null
        try {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        } catch (_: Exception) {}
        applyPlaybackParams()
        val cached = store.loadAnalysis(key)
        if (cached != null) {
            analysis = cached
            saveRecent()
        } else analyze(uri, key)
    }

    fun reanalyze() {
        val s = song ?: return
        analysis = null
        analyze(s.uri, s.key)
    }

    private fun analyze(uri: Uri, key: String) {
        analysisJob?.cancel()
        busy = true
        progress = 0f
        progressText = "קורא את הקובץ"
        val job = viewModelScope.launch(Dispatchers.Default) {
            try {
                val audio = AudioDecoder.decode(getApplication(), uri, cancelled = { !isActive }) { p ->
                    progress = 0.35f * p
                }
                val result = Analyzer { p, text ->
                    progress = 0.35f + 0.65f * p
                    progressText = text
                }.analyze(audio)
                // warm up guitar shapes so the guitar tab opens instantly
                result.chordUsage().forEach { Guitar.voicings(it.first) }
                store.saveAnalysis(key, result)
                withContext(Dispatchers.Main) {
                    if (song?.key == key) {
                        analysis = result
                        busy = false
                        saveRecent()
                    }
                }
            } catch (e: AudioDecoder.DecodeException) {
                withContext(Dispatchers.Main) { error = e.message; busy = false }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main) { error = "אין מספיק זיכרון לשיר ארוך כל כך"; busy = false }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = "שגיאה בניתוח: ${e.message ?: e.javaClass.simpleName}"; busy = false }
            }
        }
        analysisJob = job
    }

    fun closeSong() {
        analysisJob?.cancel()
        analysisJob = null
        busy = false
        player.stop()
        player.clearMediaItems()
        song = null
        analysis = null
        position = 0.0
        segmentIndex = -1
        beatIndex = -1
        error = null
        refreshRecents()
    }

    private fun saveRecent() {
        val s = song ?: return
        val a = analysis
        val summary = if (a != null) "${a.key.name()} · ${a.bpm.toInt()} BPM · ${a.beatsPerBar}/4" else ""
        store.saveRecent(Recent(s.uri.toString(), s.title, s.key, System.currentTimeMillis(), transpose, capo, summary))
    }

    // ------------------------------------------------------------ playback

    fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun seekTo(t: Double) {
        player.seekTo((t * 1000).toLong().coerceAtLeast(0))
        position = t
        segmentIndex = analysis?.segmentAt(t) ?: -1
        beatIndex = analysis?.beatAt(t) ?: -1
    }

    fun skip(dt: Double) = seekTo((position + dt).coerceIn(0.0, analysis?.durationSec ?: 1e9))

    fun setSpeed(s: Float) {
        speed = s; applyPlaybackParams()
    }

    private fun applyPlaybackParams() {
        val pitch = if (shiftAudio) 2.0.pow(transpose / 12.0).toFloat() else 1f
        player.playbackParameters = PlaybackParameters(speed, pitch)
    }

    private fun tick() {
        val a = analysis ?: return
        if (!player.isPlaying) return
        position = player.currentPosition / 1000.0
        val seg = a.segmentAt(position)
        val beat = a.beatAt(position)
        if (seg != segmentIndex) {
            segmentIndex = seg
            if (pianoOn && seg >= 0) {
                val c = a.segments[seg].chord
                if (!c.isNone) synth.chord(pianoMidis(c.transpose(transpose)))
            }
        }
        if (beat != beatIndex) {
            beatIndex = beat
            if (clickOn && beat >= 0) synth.click(a.isDownbeat(beat))
        }
    }

    // ------------------------------------------------------------ transpose / capo

    fun changeTranspose(n: Int) {
        var t = n
        while (t > 6) t -= 12
        while (t < -6) t += 12
        transpose = t
        applyPlaybackParams()
        saveRecent()
    }

    fun changeShiftAudio(on: Boolean) {
        shiftAudio = on; applyPlaybackParams()
    }

    fun changeCapo(c: Int) {
        capo = c; saveRecent()
    }

    val spelling: Spelling get() = analysis?.key?.transpose(transpose)?.spelling ?: Spelling.COMMON

    fun shown(c: Chord): Chord = c.transpose(transpose)

    // ------------------------------------------------------------ editing chords

    fun replaceChord(index: Int, newChord: Chord, everywhere: Boolean) {
        val a = analysis ?: return
        val target = a.segments[index]
        val segs = a.segments.mapIndexed { i, s ->
            if (i == index || (everywhere && s.chord == target.chord)) s.copy(chord = newChord) else s
        }
        updateSegments(a, segs)
    }

    fun resetChord(index: Int) {
        val a = analysis ?: return
        val segs = a.segments.mapIndexed { i, s -> if (i == index) s.copy(chord = s.detected) else s }
        updateSegments(a, segs)
    }

    private fun updateSegments(a: SongAnalysis, segs: List<com.maxi.akordim.analysis.ChordSegment>) {
        val updated = a.copy(segments = segs)
        analysis = updated
        song?.let { s -> viewModelScope.launch(Dispatchers.IO) { store.saveAnalysis(s.key, updated) } }
    }

    fun playChordPreview(c: Chord) {
        if (!c.isNone) synth.chord(pianoMidis(c))
    }

    override fun onCleared() {
        player.release()
        synth.stop()
    }

    companion object {
        /** Bass in octave 2-3, chord tones from middle C upward. */
        fun pianoMidis(c: Chord): List<Int> {
            if (c.isNone) return emptyList()
            val out = ArrayList<Int>()
            out.add(36 + c.bassNote) // low bass note
            var last = 59
            for (pc in c.pitchClasses) {
                var m = 60 + pc
                while (m <= last) m += 12
                if (m - 60 > 14) m -= 12
                out.add(m); last = m
            }
            return out
        }
    }
}
