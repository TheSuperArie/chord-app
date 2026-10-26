package com.maxi.akordim.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.maxi.akordim.analysis.LiveChordDetector
import com.maxi.akordim.analysis.LiveResult
import com.maxi.akordim.analysis.Pitch
import com.maxi.akordim.analysis.PitchDetector
import com.maxi.akordim.analysis.SongAnalysis
import com.maxi.akordim.analysis.VoiceFit
import com.maxi.akordim.audio.MicCapture
import com.maxi.akordim.music.Notes
import com.maxi.akordim.music.Spelling
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Stand-alone tools screen (from the library). */
@Composable
fun ToolsScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().background(Stage.Paper)) {
        Row(
            Modifier.fillMaxWidth().background(Stage.Bar).statusBarsPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.showTools = false }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה", tint = Stage.Ink)
            }
            Text("כלים", style = MaterialTheme.typography.titleMedium, color = Stage.Ink)
        }
        ToolsContent(vm, null)
    }
}

@Composable
fun ToolsContent(vm: AppViewModel, analysis: SongAnalysis?) {
    val context = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var hasMic by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = granted() }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("מכוון", "זיהוי חי", "הקול שלי").forEachIndexed { i, t -> Choice(t, tab == i) { tab = i } }
        }
        if (!hasMic) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "הכלים האלה מקשיבים דרך המיקרופון. שום דבר לא מוקלט או נשמר — הכול נשאר בטלפון.",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("אישור מיקרופון") }
            }
            return@Column
        }
        when (tab) {
            0 -> TunerView()
            1 -> LiveView(vm)
            else -> VoiceView(vm, analysis)
        }
    }
}

// ------------------------------------------------------------ tuner

@Composable
private fun TunerView() {
    var pitch by remember { mutableStateOf<Pitch?>(null) }
    var lastSeen by remember { mutableLongStateOf(0L) }
    var smoothCents by remember { mutableFloatStateOf(0f) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(250); now = System.currentTimeMillis() } }
    DisposableEffect(Unit) {
        val det = PitchDetector(22050, 2048)
        val ring = FloatArray(2048)
        val mic = MicCapture(22050, 1024) { x, n ->
            System.arraycopy(ring, n, ring, 0, ring.size - n)
            System.arraycopy(x, 0, ring, ring.size - n, n)
            val p = det.detect(ring, 60.0, 1100.0)
            if (p != null && p.clarity > 0.8) {
                pitch = p
                lastSeen = System.currentTimeMillis()
            }
        }
        mic.start()
        onDispose { mic.stop() }
    }
    val p = pitch
    val fresh = p != null && now - lastSeen < 1500
    // target: nearest guitar string when close to one, otherwise the nearest note
    val str = if (p != null) PitchDetector.nearestString(p.midiFloat) else -1
    val target = if (p != null && abs(PitchDetector.GUITAR[str] - p.midiFloat) < 1.6) PitchDetector.GUITAR[str] else p?.midi ?: 0
    val cents = if (p != null) ((p.midiFloat - target) * 100).toFloat() else 0f
    LaunchedEffect(cents, fresh) { smoothCents = if (fresh) smoothCents * 0.5f + cents * 0.5f else 0f }
    val inTune = fresh && abs(cents) < 5
    val color = if (inTune) Stage.Mustard else Stage.Rust

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (fresh && p != null) Notes.name(target, Spelling.SHARP) else "—",
            fontSize = 72.sp, style = MaterialTheme.typography.displayLarge, color = if (fresh) color else Stage.InkSoft
        )
        Text(
            if (fresh && p != null) "%.1f Hz · %+d סנט".format(p.freq, cents.toInt()) else "נגן מיתר אחד",
            style = MaterialTheme.typography.bodyMedium, color = Stage.InkSoft
        )
        Spacer(Modifier.height(10.dp))
        Gauge(if (fresh) smoothCents else 0f, color, fresh)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                !fresh -> ""
                inTune -> "מכוון ✓"
                cents < 0 -> "נמוך מדי — הדק את המיתר ↑"
                else -> "גבוה מדי — שחרר את המיתר ↓"
            },
            style = MaterialTheme.typography.titleMedium, color = color
        )
        Spacer(Modifier.height(16.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PitchDetector.GUITAR_NAMES.forEachIndexed { i, nme ->
                    val on = fresh && i == str && target == PitchDetector.GUITAR[i]
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(if (on) color else Stage.Cream)
                            .border(1.dp, Stage.PaperDark, CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Text(nme, style = MaterialTheme.typography.titleMedium, color = if (on) Stage.OnAccent else Stage.Ink) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("כיוון רגיל: E A D G B E", style = MaterialTheme.typography.labelSmall, color = Stage.InkSoft)
    }
}

@Composable
private fun Gauge(cents: Float, color: Color, active: Boolean) {
    Canvas(Modifier.width(260.dp).height(120.dp)) {
        val cx = size.width / 2
        val cy = size.height * 0.95f
        val r = size.width * 0.45f
        drawArc(
            Stage.PaperDark, 200f, 140f, false, Offset(cx - r, cy - r), Size(2 * r, 2 * r),
            style = Stroke(10f, cap = StrokeCap.Round)
        )
        drawArc(
            Stage.Mustard.copy(alpha = 0.6f), 266f, 8f, false, Offset(cx - r, cy - r), Size(2 * r, 2 * r),
            style = Stroke(10f, cap = StrokeCap.Round)
        )
        for (t in -50..50 step 10) {
            val ang = (270 + t * 1.4) * PI / 180
            val o = Offset(cx + (r - 14) * cos(ang).toFloat(), cy + (r - 14) * sin(ang).toFloat())
            val i2 = Offset(cx + (r - (if (t == 0) 30 else 22)) * cos(ang).toFloat(), cy + (r - (if (t == 0) 30 else 22)) * sin(ang).toFloat())
            drawLine(Stage.InkSoft, o, i2, strokeWidth = if (t == 0) 3f else 1.5f)
        }
        val c = cents.coerceIn(-50f, 50f)
        val ang = (270 + c * 1.4) * PI / 180
        val tip = Offset(cx + (r - 6) * cos(ang).toFloat(), cy + (r - 6) * sin(ang).toFloat())
        drawLine(if (active) color else Stage.InkSoft, Offset(cx, cy), tip, strokeWidth = 5f, cap = StrokeCap.Round)
        drawCircle(if (active) color else Stage.InkSoft, 9f, Offset(cx, cy))
    }
}

// ------------------------------------------------------------ live chords

@Composable
private fun LiveView(vm: AppViewModel) {
    var result by remember { mutableStateOf<LiveResult?>(null) }
    var lastSeen by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(250); now = System.currentTimeMillis() } }
    DisposableEffect(Unit) {
        val det = LiveChordDetector()
        val half = FloatArray(1024)
        var count = 0
        val mic = MicCapture(22050, 1024) { x, n ->
            val m = n / 2
            for (i in 0 until m) half[i] = (x[2 * i] + x[2 * i + 1]) * 0.5f
            det.push(half, m)
            count += m
            if (count >= 2700) { // ~4 times a second
                count = 0
                det.detect()?.let { result = it; lastSeen = System.currentTimeMillis() }
            }
        }
        mic.start()
        onDispose { mic.stop() }
    }
    val r = result
    val fresh = r != null && now - lastSeen < 2000
    val sp = Spelling.COMMON
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("נגן אקורד והחזק אותו", style = MaterialTheme.typography.bodyMedium, color = Stage.InkSoft)
        Spacer(Modifier.height(8.dp))
        Text(
            if (r != null) r.best.name(sp) else "—", fontSize = 80.sp,
            style = MaterialTheme.typography.displayLarge, color = if (fresh) Stage.Rust else Stage.InkSoft
        )
        if (r != null) Text(r.best.hebrewName(sp), style = MaterialTheme.typography.bodyMedium, color = Stage.InkSoft)
        Spacer(Modifier.height(10.dp))
        if (r != null) {
            LinearProgressIndicator(
                progress = { r.confidence },
                modifier = Modifier.width(200.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = Stage.Mustard, trackColor = Stage.PaperDark,
            )
            Spacer(Modifier.height(10.dp))
            Text("או אולי:", style = MaterialTheme.typography.labelMedium, color = Stage.InkSoft)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.others.forEach { Pill(it.name(sp), bg = Stage.Cream, fg = Stage.Ink) }
                }
            }
            Spacer(Modifier.height(16.dp))
            val hl = HashMap<Int, Color>()
            hl[48 + r.best.bassNote] = Stage.Rust
            r.best.pitchClasses.forEach { hl[60 + it] = Stage.Mustard }
            Box(Modifier.background(Stage.Bar).padding(4.dp)) {
                PianoKeyboard(48, 71, hl, sp, height = 70.dp) { m -> vm.synth.note(m) }
            }
        }
    }
}

// ------------------------------------------------------------ voice range

@Composable
private fun VoiceView(vm: AppViewModel, analysis: SongAnalysis?) {
    // 0 = idle, 1 = singing the low note, 2 = singing the high note
    var mode by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<Pitch?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var low by remember { mutableIntStateOf(vm.voiceLow) }
    var high by remember { mutableIntStateOf(vm.voiceHigh) }

    if (mode != 0) {
        DisposableEffect(mode) {
            val det = PitchDetector(22050, 2048)
            val ring = FloatArray(2048)
            val heard = ArrayList<Double>()
            val started = System.currentTimeMillis()
            val m = mode
            val mic = MicCapture(22050, 1024) { x, n ->
                System.arraycopy(ring, n, ring, 0, ring.size - n)
                System.arraycopy(x, 0, ring, ring.size - n, n)
                val p = det.detect(ring, 70.0, 1000.0)
                if (p != null && p.clarity > 0.85) {
                    current = p
                    synchronized(heard) { heard.add(p.midiFloat) }
                }
                val el = System.currentTimeMillis() - started
                progress = (el / 3500f).coerceAtMost(1f)
                if (el > 3500 && mode == m) {
                    val v = synchronized(heard) { heard.sorted() }
                    if (v.size >= 8) {
                        val med = v[v.size / 2].toInt()
                        if (m == 1) low = med else high = med
                        if (low > 0 && high > 0) vm.saveVoice(low, high)
                    }
                    mode = 0
                }
            }
            mic.start()
            onDispose { mic.stop() }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("נמצא לך את הסולם הנוח לשיר", style = MaterialTheme.typography.titleMedium)
        Text(
            "שיר \"אאא\" ארוך בצליל הכי נמוך שנוח לך (לא הכי נמוך שאתה מסוגל), ואחר כך בהכי גבוה שנוח לך.",
            style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VoiceBox(Modifier.weight(1f), "הכי נמוך", low, mode == 1, progress) { mode = 1; current = null }
            VoiceBox(Modifier.weight(1f), "הכי גבוה", high, mode == 2, progress) { mode = 2; current = null }
        }
        if (mode != 0) {
            Text(
                current?.let { "שומע: ${PitchDetector.noteName(it.midi)} (${Notes.hebrew(it.midi, Spelling.SHARP)})" } ?: "מקשיב…",
                style = MaterialTheme.typography.titleMedium, color = Stage.Mustard
            )
        }
        if (low > 0 && high > 0 && mode == 0) {
            val lo = minOf(low, high); val hi = maxOf(low, high)
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "הטווח שלך: ${PitchDetector.noteName(lo)} עד ${PitchDetector.noteName(hi)} (${hi - lo} חצאי טונים)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (analysis == null) {
                        Text("פתח שיר כדי לקבל המלצה על סולם בשבילו.", style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft)
                    } else {
                        val s = VoiceFit.suggest(analysis.key, lo, hi)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                s.transpose == 0 -> "הסולם המקורי (${analysis.key.name()}) מתאים לך"
                                s.transpose > 0 -> "מומלץ להעלות ${s.transpose} חצאי טונים → סולם ${s.key.name()}"
                                else -> "מומלץ להוריד ${-s.transpose} חצאי טונים → סולם ${s.key.name()}"
                            },
                            style = MaterialTheme.typography.titleMedium, color = Stage.Rust
                        )
                        if (s.margin < 0) Text(
                            "השיר רחב מהטווח שלך — זו ההתאמה הכי קרובה.",
                            style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft
                        )
                        Text(
                            "ההמלצה היא הערכה לפי הסולם של השיר. תמיד אפשר לכוונן עוד בחצי טון למעלה או למטה.",
                            style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft
                        )
                        if (s.transpose != vm.transpose) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { vm.changeTranspose(s.transpose) }) { Text("החל על השיר") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceBox(modifier: Modifier, label: String, midi: Int, active: Boolean, progress: Float, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(if (active) Stage.Mustard.copy(alpha = 0.2f) else Stage.Cream)
            .border(1.dp, if (active) Stage.Mustard else Stage.PaperDark, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Stage.InkSoft)
        Text(if (midi > 0) PitchDetector.noteName(midi) else "—", style = MaterialTheme.typography.headlineMedium, color = Stage.Ink)
        Spacer(Modifier.height(6.dp))
        if (active) {
            LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp),
                color = Stage.Mustard, trackColor = Stage.PaperDark
            )
        } else {
            OutlinedButton(onClick = onClick) { Text(if (midi > 0) "שוב" else "שיר") }
        }
    }
}
