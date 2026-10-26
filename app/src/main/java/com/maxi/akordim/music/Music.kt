package com.maxi.akordim.music

/** Chord qualities the recognizer knows. Intervals are semitones above the root. */
enum class Quality(val intervals: IntArray, val suffix: String, val hebrew: String) {
    MAJ(intArrayOf(0, 4, 7), "", "מז'ור"),
    MIN(intArrayOf(0, 3, 7), "m", "מינור"),
    DOM7(intArrayOf(0, 4, 7, 10), "7", "ספטים"),
    MAJ7(intArrayOf(0, 4, 7, 11), "maj7", "מז'ור 7"),
    MIN7(intArrayOf(0, 3, 7, 10), "m7", "מינור 7"),
    SUS4(intArrayOf(0, 5, 7), "sus4", "סאס 4"),
    SUS2(intArrayOf(0, 2, 7), "sus2", "סאס 2"),
    DIM(intArrayOf(0, 3, 6), "dim", "מוקטן"),
    AUG(intArrayOf(0, 4, 8), "aug", "מוגדל"),
    HDIM(intArrayOf(0, 3, 6, 10), "m7b5", "חצי מוקטן");

    val isSeventh get() = intervals.size == 4
    /** The plain triad a seventh chord is built on (used for Am7/G -> Am/G). */
    val triad: Quality
        get() = when (this) {
            DOM7, MAJ7 -> MAJ
            MIN7 -> MIN
            HDIM -> DIM
            else -> this
        }
}

object Notes {
    val SHARP = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val FLAT = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    val COMMON = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    val HEB_SHARP = arrayOf("דו", "דו דיאז", "רה", "רה דיאז", "מי", "פה", "פה דיאז", "סול", "סול דיאז", "לה", "לה דיאז", "סי")
    val HEB_FLAT = arrayOf("דו", "רה במול", "רה", "מי במול", "מי", "פה", "סול במול", "סול", "לה במול", "לה", "סי במול", "סי")

    fun pc(x: Int) = ((x % 12) + 12) % 12

    fun name(pc: Int, spelling: Spelling) = when (spelling) {
        Spelling.SHARP -> SHARP[pc(pc)]
        Spelling.FLAT -> FLAT[pc(pc)]
        Spelling.COMMON -> COMMON[pc(pc)]
    }

    fun hebrew(pc: Int, spelling: Spelling) =
        if (spelling == Spelling.FLAT) HEB_FLAT[pc(pc)] else HEB_SHARP[pc(pc)]
}

enum class Spelling { SHARP, FLAT, COMMON }

data class Chord(val root: Int, val quality: Quality, val bass: Int = -1) {
    val isNone get() = root < 0
    val hasSlash get() = !isNone && bass >= 0 && bass != root

    /** Pitch classes of the chord (without the slash bass). */
    val pitchClasses: IntArray
        get() = if (isNone) IntArray(0) else quality.intervals.map { Notes.pc(root + it) }.toIntArray()

    /** Pitch classes including a slash bass that is not already a chord tone. */
    val allPitchClasses: IntArray
        get() {
            val p = pitchClasses.toMutableList()
            if (hasSlash && bass !in p) p.add(0, bass)
            return p.toIntArray()
        }

    val bassNote get() = if (hasSlash) bass else root

    fun transpose(n: Int): Chord =
        if (isNone) this else Chord(Notes.pc(root + n), quality, if (bass >= 0) Notes.pc(bass + n) else -1)

    fun withoutSlash() = Chord(root, quality)

    fun name(spelling: Spelling = Spelling.COMMON): String {
        if (isNone) return "—"
        val base = Notes.name(root, spelling) + quality.suffix
        return if (hasSlash) base + "/" + Notes.name(bass, spelling) else base
    }

    fun hebrewName(spelling: Spelling = Spelling.COMMON): String {
        if (isNone) return "ללא אקורד"
        val base = Notes.hebrew(root, spelling) + " " + quality.hebrew
        return if (hasSlash) base + " / בס " + Notes.hebrew(bass, spelling) else base
    }

    fun encode(): String = if (isNone) "N" else "$root:${quality.name}:$bass"

    companion object {
        val NONE = Chord(-1, Quality.MAJ)
        fun decode(s: String): Chord {
            if (s == "N") return NONE
            val p = s.split(":")
            return Chord(p[0].toInt(), Quality.valueOf(p[1]), p[2].toInt())
        }
    }
}

data class MusicalKey(val tonic: Int, val minor: Boolean) {
    val spelling: Spelling
        get() {
            val flatMajors = setOf(5, 10, 3, 8, 1)
            val flatMinors = setOf(2, 7, 0, 5, 10, 3)
            return if (minor) {
                if (tonic in flatMinors) Spelling.FLAT else Spelling.SHARP
            } else {
                if (tonic in flatMajors) Spelling.FLAT else Spelling.SHARP
            }
        }

    fun name(): String = Notes.name(tonic, spelling) + if (minor) "m" else ""
    fun hebrewName(): String = Notes.hebrew(tonic, spelling) + if (minor) " מינור" else " מז'ור"
    fun transpose(n: Int) = MusicalKey(Notes.pc(tonic + n), minor)
    val relative: MusicalKey get() = if (minor) MusicalKey(Notes.pc(tonic + 3), false) else MusicalKey(Notes.pc(tonic + 9), true)

    /** Chords that belong to the key, used as a gentle prior by the recognizer. */
    fun diatonic(): List<Chord> {
        val t = tonic
        fun c(i: Int, q: Quality) = Chord(Notes.pc(t + i), q)
        return if (!minor) listOf(
            c(0, Quality.MAJ), c(0, Quality.MAJ7), c(2, Quality.MIN), c(2, Quality.MIN7),
            c(4, Quality.MIN), c(4, Quality.MIN7), c(5, Quality.MAJ), c(5, Quality.MAJ7),
            c(7, Quality.MAJ), c(7, Quality.DOM7), c(9, Quality.MIN), c(9, Quality.MIN7),
            c(11, Quality.DIM), c(11, Quality.HDIM), c(2, Quality.SUS2), c(7, Quality.SUS4), c(0, Quality.SUS4)
        ) else listOf(
            c(0, Quality.MIN), c(0, Quality.MIN7), c(2, Quality.DIM), c(2, Quality.HDIM),
            c(3, Quality.MAJ), c(3, Quality.MAJ7), c(5, Quality.MIN), c(5, Quality.MIN7),
            c(7, Quality.MIN), c(7, Quality.MAJ), c(7, Quality.DOM7), c(8, Quality.MAJ),
            c(8, Quality.MAJ7), c(10, Quality.MAJ), c(10, Quality.DOM7), c(7, Quality.SUS4)
        )
    }
}
