package com.maxi.akordim.analysis

import com.maxi.akordim.music.Chord
import com.maxi.akordim.music.MusicalKey
import org.json.JSONArray
import org.json.JSONObject

/** One run of a single chord on the beat grid. */
data class ChordSegment(
    val startBeat: Int,
    val endBeat: Int, // exclusive
    val start: Double,
    val end: Double,
    val chord: Chord,
    val detected: Chord, // what the recognizer chose, before any user edit
    val alternatives: List<Chord>,
    val confidence: Float,
)

data class SongAnalysis(
    val durationSec: Double,
    val bpm: Double,
    val beatsPerBar: Int,
    val downbeatPhase: Int, // index of the first downbeat in [beats]
    val beats: DoubleArray,
    val key: MusicalKey,
    val keyConfidence: Float,
    val tuningCents: Double,
    val segments: List<ChordSegment>,
    val sections: List<Section> = emptyList(),
    val tempoStability: Float, // 0..1, how steady the beat is
    val loudnessDb: Double,
    val analysisMillis: Long,
) {
    fun segmentAt(t: Double): Int {
        if (segments.isEmpty()) return -1
        var lo = 0
        var hi = segments.size - 1
        if (t < segments[0].start) return -1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (segments[mid].start <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun beatAt(t: Double): Int {
        var lo = 0
        var hi = beats.size - 1
        if (beats.isEmpty() || t < beats[0]) return -1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (beats[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Bars of the song as lists of beat indices (a short pick-up bar first if needed). */
    fun bars(): List<List<Int>> {
        val nb = beats.size - 1
        val out = ArrayList<List<Int>>()
        if (downbeatPhase > 0) out.add((0 until minOf(downbeatPhase, nb)).toList())
        var b = downbeatPhase
        while (b < nb) {
            out.add((b until minOf(b + beatsPerBar, nb)).toList())
            b += beatsPerBar
        }
        return out
    }

    fun beatToSegment(): IntArray {
        val nb = beats.size - 1
        val m = IntArray(nb) { -1 }
        segments.forEachIndexed { i, s -> for (b in s.startBeat until minOf(s.endBeat, nb)) m[b] = i }
        return m
    }

    fun sectionAt(t: Double): Int = sections.indexOfLast { it.start <= t }

    fun sectionOfBeat(b: Int): Int = sections.indexOfFirst { b >= it.startBeat && b < it.endBeat }

    fun isDownbeat(beatIndex: Int) = beatIndex >= downbeatPhase && (beatIndex - downbeatPhase) % beatsPerBar == 0

    /** Unique chords weighted by how long they sound. */
    fun chordUsage(): List<Pair<Chord, Double>> {
        val m = LinkedHashMap<Chord, Double>()
        for (s in segments) if (!s.chord.isNone) m[s.chord] = (m[s.chord] ?: 0.0) + (s.end - s.start)
        return m.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", com.maxi.akordim.data.SongStore.ENGINE_VERSION)
        put("duration", durationSec)
        put("bpm", bpm)
        put("bpb", beatsPerBar)
        put("dbPhase", downbeatPhase)
        put("beats", JSONArray().also { a -> beats.forEach { a.put(it) } })
        put("keyTonic", key.tonic)
        put("keyMinor", key.minor)
        put("keyConf", keyConfidence.toDouble())
        put("tuning", tuningCents)
        put("stability", tempoStability.toDouble())
        put("loud", loudnessDb)
        put("ms", analysisMillis)
        put("secs", JSONArray().also { a ->
            sections.forEach { x ->
                a.put(JSONObject().apply {
                    put("sb", x.startBeat); put("eb", x.endBeat); put("s", x.start); put("e", x.end)
                    put("k", x.kind.name); put("g", x.group); put("n", x.number)
                })
            }
        })
        put("segs", JSONArray().also { a ->
            segments.forEach { s ->
                a.put(JSONObject().apply {
                    put("sb", s.startBeat); put("eb", s.endBeat)
                    put("s", s.start); put("e", s.end)
                    put("c", s.chord.encode()); put("d", s.detected.encode())
                    put("conf", s.confidence.toDouble())
                    put("alt", JSONArray().also { b -> s.alternatives.forEach { b.put(it.encode()) } })
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): SongAnalysis {
            val b = o.getJSONArray("beats")
            val segs = o.getJSONArray("segs")
            return SongAnalysis(
                durationSec = o.getDouble("duration"),
                bpm = o.getDouble("bpm"),
                beatsPerBar = o.getInt("bpb"),
                downbeatPhase = o.getInt("dbPhase"),
                beats = DoubleArray(b.length()) { b.getDouble(it) },
                key = MusicalKey(o.getInt("keyTonic"), o.getBoolean("keyMinor")),
                keyConfidence = o.getDouble("keyConf").toFloat(),
                tuningCents = o.getDouble("tuning"),
                segments = (0 until segs.length()).map { i ->
                    val s = segs.getJSONObject(i)
                    val alts = s.getJSONArray("alt")
                    ChordSegment(
                        s.getInt("sb"), s.getInt("eb"), s.getDouble("s"), s.getDouble("e"),
                        Chord.decode(s.getString("c")), Chord.decode(s.getString("d")),
                        (0 until alts.length()).map { Chord.decode(alts.getString(it)) },
                        s.getDouble("conf").toFloat()
                    )
                },
                sections = o.optJSONArray("secs")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val x = arr.getJSONObject(i)
                        Section(
                            x.getInt("sb"), x.getInt("eb"), x.getDouble("s"), x.getDouble("e"),
                            SectionKind.valueOf(x.getString("k")), x.getInt("g"), x.getInt("n")
                        )
                    }
                } ?: emptyList(),
                tempoStability = o.getDouble("stability").toFloat(),
                loudnessDb = o.getDouble("loud"),
                analysisMillis = o.getLong("ms"),
            )
        }
    }
}
