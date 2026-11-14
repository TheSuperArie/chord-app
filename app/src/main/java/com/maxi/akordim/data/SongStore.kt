package com.maxi.akordim.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.maxi.akordim.analysis.SongAnalysis
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class SongFile(
    val uri: Uri,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val size: Long,
    val isVideo: Boolean,
)

data class Recent(
    val uri: String,
    val title: String,
    val key: String,
    val opened: Long,
    val transpose: Int,
    val capo: Int,
    val summary: String,
)

/** Songs on the device, the analysis cache and the recent list. */
class SongStore(private val context: Context) {
    private val dir = File(context.filesDir, "analyses").apply { mkdirs() }
    private val recentFile = File(context.filesDir, "recents.json")

    fun deviceSongs(): List<SongFile> {
        val out = ArrayList<SongFile>()
        val audioProj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
        )
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioProj,
                "${MediaStore.Audio.Media.DURATION} > 20000", null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out.add(
                        SongFile(
                            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                            c.getString(1) ?: "ללא שם",
                            c.getString(2)?.takeIf { it != "<unknown>" } ?: "",
                            c.getLong(3), c.getLong(4), false
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        val videoProj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
        )
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoProj,
                "${MediaStore.Video.Media.DURATION} > 20000", null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out.add(
                        SongFile(
                            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                            c.getString(1) ?: "סרטון", "", c.getLong(2), c.getLong(3), true
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** Display name and size for a picked document. */
    fun describe(uri: Uri): Pair<String, Long> {
        var name = "שיר"
        var size = 0L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it.substringBeforeLast('.') }
                    size = c.getLong(1)
                }
            }
        } catch (_: Exception) {}
        return name to size
    }

    fun cacheKey(uri: Uri, size: Long): String {
        val md = MessageDigest.getInstance("SHA-1").digest("$uri|$size".toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(20)
    }

    fun loadAnalysis(key: String): SongAnalysis? = try {
        val f = File(dir, "$key.json")
        val o = if (f.exists()) JSONObject(f.readText()) else null
        // older engine versions are re-analysed with the improved recogniser -
        // except songs the user corrected by hand: their work is never thrown away
        when {
            o == null -> null
            o.optInt("v") == ENGINE_VERSION -> SongAnalysis.fromJson(o)
            else -> SongAnalysis.fromJson(o).takeIf { a -> a.segments.any { it.chord != it.detected } }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * All songs with chords the user fixed by hand, as one JSON document: the corrected chord
     * timeline next to what the recogniser found, so the engine can be measured and tuned on
     * real songs. Returns the text and the number of songs in it.
     */
    fun exportCorrections(): Pair<String, Int> {
        val songs = JSONArray()
        for (r in recents()) {
            val a = try {
                val f = File(dir, "${r.key}.json")
                if (f.exists()) SongAnalysis.fromJson(JSONObject(f.readText())) else null
            } catch (_: Exception) {
                null
            } ?: continue
            val edited = a.segments.count { it.chord != it.detected }
            if (edited == 0) continue
            val (fileName, size) = try { describe(Uri.parse(r.uri)) } catch (_: Exception) { r.title to 0L }
            songs.put(JSONObject().apply {
                put("title", r.title)
                put("file", fileName)
                put("size", size)
                put("duration", a.durationSec)
                put("key", a.key.name())
                put("bpm", a.bpm)
                put("bpb", a.beatsPerBar)
                put("downbeatPhase", a.downbeatPhase)
                put("tuning", a.tuningCents)
                put("edited", edited)
                put("beats", JSONArray().also { b -> a.beats.forEach { b.put(Math.round(it * 1000) / 1000.0) } })
                put("chords", JSONArray().also { arr ->
                    a.segments.forEach { s ->
                        arr.put(JSONObject().apply {
                            put("start", Math.round(s.start * 1000) / 1000.0)
                            put("end", Math.round(s.end * 1000) / 1000.0)
                            put("sb", s.startBeat); put("eb", s.endBeat)
                            put("chord", s.chord.name()); put("c", s.chord.encode())
                            put("detected", s.detected.name()); put("d", s.detected.encode())
                            put("fixed", s.chord != s.detected)
                        })
                    }
                })
            })
        }
        val doc = JSONObject().apply {
            put("app", "akordim")
            put("format", 1)
            put("engine", ENGINE_VERSION)
            put("exported", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
            put("songs", songs)
        }
        return doc.toString(1) to songs.length()
    }

    companion object {
        /** Bump together with the "v" written by SongAnalysis.toJson when the recogniser changes. */
        const val ENGINE_VERSION = 6
    }

    fun saveAnalysis(key: String, a: SongAnalysis) {
        try {
            File(dir, "$key.json").writeText(a.toJson().toString())
        } catch (_: Exception) {}
    }

    fun recents(): List<Recent> = try {
        if (!recentFile.exists()) emptyList() else {
            val a = JSONArray(recentFile.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Recent(
                    o.getString("uri"), o.getString("title"), o.getString("key"), o.getLong("opened"),
                    o.optInt("transpose"), o.optInt("capo"), o.optString("summary")
                )
            }.sortedByDescending { it.opened }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun recent(key: String) = recents().firstOrNull { it.key == key }

    fun saveRecent(r: Recent) {
        val list = recents().filter { it.key != r.key }.toMutableList()
        list.add(0, r)
        val a = JSONArray()
        list.take(40).forEach {
            a.put(JSONObject().apply {
                put("uri", it.uri); put("title", it.title); put("key", it.key); put("opened", it.opened)
                put("transpose", it.transpose); put("capo", it.capo); put("summary", it.summary)
            })
        }
        try {
            recentFile.writeText(a.toString())
        } catch (_: Exception) {}
    }

    fun removeRecent(key: String) {
        val list = recents().filter { it.key != key }
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().apply {
                put("uri", it.uri); put("title", it.title); put("key", it.key); put("opened", it.opened)
                put("transpose", it.transpose); put("capo", it.capo); put("summary", it.summary)
            })
        }
        try {
            recentFile.writeText(a.toString())
        } catch (_: Exception) {}
        File(dir, "$key.json").delete()
    }
}
