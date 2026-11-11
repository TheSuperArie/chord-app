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
    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    /** Show simplified chords (no slash bass, no 7ths/sus) instead of the full ones. */
    var simpleChords by mutableStateOf(prefs.getBoolean("simple", false))
        private set
    /** Bumped whenever guitar shapes for the current capo/transpose are ready. */
    var guitarVersion by mutableIntStateOf(0)
        private set
    var suggestedCapo by mutableIntStateOf(-1)
        private set
    private var guitarJob: Job? = null

    // loop (seconds, -1 = off)
    var loopStart by mutableDoubleStateOf(-1.0)
        private set
    var loopEnd by mutableDoubleStateOf(-1.0)
        private set
    var loopLabel by mutableStateOf("")
        private set
    var stageMode by mutableStateOf(false)
    var showTools by mutableStateOf(false)
    var showSheet by mutableStateOf(false)

    // the singer's range, remembered between songs (MIDI, -1 = unknown)
    var voiceLow by mutableIntStateOf(prefs.getInt("voiceLow", -1))
        private set
    var voiceHigh by mutableIntStateOf(prefs.getInt("voiceHigh", -1))
        private set
    var strumIndex by mutableIntStateOf(0)
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
            warmGuitar()
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
                        warmGuitar()
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
        clearLoop()
        stageMode = false
        showSheet = false
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

    fun changeSpeed(s: Float) {
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
        if (loopEnd > 0 && (position >= loopEnd || position < loopStart - 0.5)) {
            seekTo(loopStart)
            return
        }
        val seg = a.segmentAt(position)
        val beat = a.beatAt(position)
        if (seg != segmentIndex) segmentIndex = seg
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
        warmGuitar()
    }

    fun changeShiftAudio(on: Boolean) {
        shiftAudio = on; applyPlaybackParams()
    }

    fun changeCapo(c: Int) {
        capo = c; saveRecent(); warmGuitar()
    }

    /** How far the chord names on screen are from the original recording. */
    val displayShift: Int get() = transpose - capo

    /** Spelling for chord names on screen (with a capo: the key of the shapes you finger). */
    val spelling: Spelling get() = analysis?.key?.transpose(displayShift)?.spelling ?: Spelling.COMMON

    /** Spelling for what is actually heard (piano keys, "sounds like"). */
    val soundingSpelling: Spelling get() = analysis?.key?.transpose(transpose)?.spelling ?: Spelling.COMMON

    /** The chord as it sounds after transposing. */
    fun sounding(c: Chord): Chord = c.transpose(transpose).let { if (simpleChords) simplify(it) else it }

    /** The chord as written everywhere in the app: with a capo these are the shapes to play. */
    fun shown(c: Chord): Chord = sounding(c).transpose(-capo)

    fun toggleSimple() {
        simpleChords = !simpleChords
        prefs.edit().putBoolean("simple", simpleChords).apply()
        warmGuitar()
    }

    /** Chords as written on screen (transposed / simplified / capo), merged, longest first. */
    fun usageShown(a: SongAnalysis): List<Pair<Chord, Double>> =
        usageSounding(a).map { it.first.transpose(-capo) to it.second }

    /** Chords as they sound (transposed / simplified), merged, longest first. */
    fun usageSounding(a: SongAnalysis): List<Pair<Chord, Double>> {
        val m = LinkedHashMap<Chord, Double>()
        for ((c, t) in a.chordUsage()) {
            val k = sounding(c)
            m[k] = (m[k] ?: 0.0) + t
        }
        return m.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** Compute guitar shapes and the capo suggestion off the main thread. */
    fun warmGuitar() {
        val a = analysis ?: return
        guitarJob?.cancel()
        val c = capo
        guitarJob = viewModelScope.launch(Dispatchers.Default) {
            val usage = usageSounding(a)
            for ((ch, _) in usage) Guitar.voicings(ch.transpose(-c))
            withContext(Dispatchers.Main) { guitarVersion++ }
            val sug = Guitar.suggestCapo(usage)
            withContext(Dispatchers.Main) { suggestedCapo = sug; guitarVersion++ }
        }
    }

    // ------------------------------------------------------------ loop / sections

    fun setLoop(start: Double, end: Double, label: String) {
        loopStart = minOf(start, end); loopEnd = maxOf(start, end); loopLabel = label
        if (position < loopStart || position > loopEnd) seekTo(loopStart)
    }

    fun clearLoop() {
        loopStart = -1.0; loopEnd = -1.0; loopLabel = ""
    }

    /** First tap marks where the loop starts, the second where it ends. */
    var loopMark by mutableDoubleStateOf(-1.0)
        private set

    fun markLoopStart(t: Double) {
        loopMark = t
    }

    fun markLoopEnd(t: Double) {
        if (loopMark >= 0) {
            setLoop(loopMark, t, "${formatTimeVm(minOf(loopMark, t))}–${formatTimeVm(maxOf(loopMark, t))}")
            loopMark = -1.0
        }
    }

    fun renameSection(index: Int, kind: com.maxi.akordim.analysis.SectionKind) {
        val a = analysis ?: return
        val secs = a.sections.toMutableList()
        secs[index] = secs[index].copy(kind = kind)
        // renumber occurrences
        val totals = secs.groupingBy { it.kind }.eachCount()
        val counter = HashMap<com.maxi.akordim.analysis.SectionKind, Int>()
        val renum = secs.map { x ->
            val nmb = (counter[x.kind] ?: 0) + 1
            counter[x.kind] = nmb
            x.copy(number = if ((totals[x.kind] ?: 0) > 1) nmb else 0)
        }
        val updated = a.copy(sections = renum)
        analysis = updated
        song?.let { sg -> viewModelScope.launch(Dispatchers.IO) { store.saveAnalysis(sg.key, updated) } }
    }

    fun saveVoice(low: Int, high: Int) {
        voiceLow = minOf(low, high); voiceHigh = maxOf(low, high)
        prefs.edit().putInt("voiceLow", voiceLow).putInt("voiceHigh", voiceHigh).apply()
    }

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
        warmGuitar()
        song?.let { s -> viewModelScope.launch(Dispatchers.IO) { store.saveAnalysis(s.key, updated) } }
    }

    /** Preview a chord given the way it is written on screen (capo shape). */
    fun playShown(c: Chord) = playChordPreview(c.transpose(capo))

    fun playChordPreview(c: Chord) {
        if (!c.isNone) synth.chord(pianoMidis(c))
    }

    override fun onCleared() {
        player.release()
        synth.stop()
    }

    companion object {
        fun formatTimeVm(sec: Double): String {
            val x = sec.toInt().coerceAtLeast(0)
            return "%d:%02d".format(x / 60, x % 60)
        }

        fun simplify(c: Chord): Chord {
            if (c.isNone) return c
            val q = when (c.quality) {
                com.maxi.akordim.music.Quality.SUS2, com.maxi.akordim.music.Quality.SUS4,
                com.maxi.akordim.music.Quality.AUG -> com.maxi.akordim.music.Quality.MAJ
                else -> c.quality.triad
            }
            return Chord(c.root, q)
        }

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
