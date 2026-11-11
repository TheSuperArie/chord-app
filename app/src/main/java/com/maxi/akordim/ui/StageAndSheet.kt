package com.maxi.akordim.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.maxi.akordim.analysis.ChordSheet
import com.maxi.akordim.analysis.SongAnalysis
import java.io.File

// ------------------------------------------------------------ stage mode

/** Full screen: huge current chord, the next one and the beat, for playing along. */
@Composable
fun StageMode(vm: AppViewModel, a: SongAnalysis) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val seg = vm.segmentIndex
    val cur = a.segments.getOrNull(seg)
    val next = a.segments.getOrNull(seg + 1) ?: if (seg < 0) a.segments.firstOrNull() else null
    val sec = a.sections.getOrNull(a.sectionOfBeat(vm.beatIndex.coerceAtLeast(0)))
    val sp = vm.spelling
    val beatsLeft = if (next != null && vm.beatIndex >= 0) (next.startBeat - vm.beatIndex).coerceAtLeast(0) else -1

    Box(
        Modifier.fillMaxSize().background(Stage.Bar).clickable { vm.togglePlay() }
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sec != null) Text(
                sec.label, style = MaterialTheme.typography.titleMedium, color = Stage.OnAccent,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(sectionColor(sec.kind))
                    .padding(horizontal = 12.dp, vertical = 3.dp)
            )
            Spacer(Modifier.weight(1f))
            if (vm.capo > 0) Text("קאפו ${vm.capo}  ", style = MaterialTheme.typography.labelLarge, color = Stage.InkSoft)
            IconButton(onClick = { vm.stageMode = false }) { Icon(Icons.Filled.Close, "סגור", tint = Stage.Ink) }
        }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val name = cur?.let { vm.shown(it.chord).name(sp) } ?: "—"
                val size = (maxWidth.value / (name.length.coerceAtLeast(2) * 0.62f)).coerceAtMost(150f)
                Text(name, fontSize = size.sp, style = MaterialTheme.typography.displayLarge, color = Stage.Rust, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val inBar = if (vm.beatIndex >= a.downbeatPhase) (vm.beatIndex - a.downbeatPhase) % a.beatsPerBar else -1
                    for (i in 0 until a.beatsPerBar) Box(
                        Modifier.size(if (i == 0) 20.dp else 16.dp).clip(CircleShape)
                            .background(if (i == inBar) (if (i == 0) Stage.Rust else Stage.Mustard) else Stage.Cream)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("הבא", style = MaterialTheme.typography.labelLarge, color = Stage.InkSoft)
            Text(next?.let { vm.shown(it.chord).name(sp) } ?: "", fontSize = 44.sp, style = MaterialTheme.typography.displayLarge, color = Stage.Ink)
            if (beatsLeft in 1..8) Text("בעוד $beatsLeft", style = MaterialTheme.typography.titleMedium, color = Stage.Mustard)
        }
        Text(
            (if (vm.isPlaying) "" else "▶ הקש על המסך כדי לנגן   ") + formatTime(vm.position) + " / " + formatTime(a.durationSec),
            style = MaterialTheme.typography.labelMedium, color = Stage.InkSoft,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
        )
    }
}

// ------------------------------------------------------------ chord sheet

@Composable
fun SheetScreen(vm: AppViewModel, a: SongAnalysis) {
    val context = LocalContext.current
    val sp = vm.spelling
    val bars = remember(a) { a.bars() }
    val b2s = remember(a) { a.beatToSegment() }
    val lines = ChordSheet.lines(a, bars, b2s, { vm.shown(it) }, sp)
    val title = vm.song?.title ?: "שיר"
    val key = a.key.transpose(vm.transpose)
    val header = "סולם ${key.name()} · ${a.bpm.toInt()} BPM · ${a.beatsPerBar}/4" +
        if (vm.capo > 0) " · קאפו ${vm.capo} (צורות ${key.transpose(-vm.capo).name()})" else ""

    Column(Modifier.fillMaxSize().background(Stage.Paper)) {
        Row(
            Modifier.fillMaxWidth().background(Stage.Bar).statusBarsPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.showSheet = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה", tint = Stage.Ink) }
            Text("דף אקורדים", style = MaterialTheme.typography.titleMedium, color = Stage.Ink)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(header, style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft)
            }
            items(lines) { l ->
                if (l.label != null) {
                    val kind = a.sections.firstOrNull { it.label == l.label }?.kind
                    Text(
                        l.label, style = MaterialTheme.typography.labelLarge,
                        color = kind?.let { sectionColor(it) } ?: Stage.Mustard,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        l.bars.forEach { b ->
                            Text(
                                b, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                modifier = Modifier.weight(1f).padding(horizontal = 2.dp).clip(RoundedCornerShape(4.dp))
                                    .border(1.dp, Stage.PaperDark, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                        for (i in l.bars.size until 4) Spacer(Modifier.weight(1f))
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        Row(
            Modifier.fillMaxWidth().background(Stage.Bar).navigationBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = {
                val bmp = renderSheet(title, header, lines, a)
                val ok = saveToGallery(context, bmp, title)
                Toast.makeText(context, if (ok) "נשמר בגלריה (Pictures/Akordim)" else "השמירה נכשלה", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.weight(1f)) { Text("שמור בגלריה", textAlign = TextAlign.Center) }
            OutlinedButton(onClick = {
                val bmp = renderSheet(title, header, lines, a)
                shareImage(context, bmp, title)
            }, modifier = Modifier.weight(1f)) { Text("שתף תמונה") }
            OutlinedButton(onClick = {
                val text = ChordSheet.text(title, header, lines)
                val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                try {
                    context.startActivity(Intent.createChooser(i, "שיתוף"))
                } catch (_: Exception) {
                    Toast.makeText(context, "אין אפליקציה לשיתוף במכשיר", Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.weight(1f)) { Text("שתף טקסט") }
        }
    }
}

/** Printable light page: title, details, then each section with four bars per line. */
private fun renderSheet(title: String, header: String, lines: List<ChordSheet.Line>, a: SongAnalysis): Bitmap {
    val w = 1080
    val pad = 60f
    val labelH = 64f
    val rowH = 96f
    var h = pad + 90 + 50 + 30
    for (l in lines) {
        if (l.label != null) h += labelH
        h += rowH
    }
    h += pad + 40
    val bmp = Bitmap.createBitmap(w, h.toInt(), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(0xFFFFFFFF.toInt())
    val ink = 0xFF1B1C2E.toInt()
    val soft = 0xFF6E7090.toInt()
    val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 64f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
    val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = soft; textSize = 34f; textAlign = Paint.Align.RIGHT }
    val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 36f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
    val pChord = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 44f; typeface = Typeface.DEFAULT_BOLD }
    val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9CAE0.toInt(); strokeWidth = 3f }
    var y = pad + 64
    c.drawText(title, w - pad, y, pTitle)
    y += 50
    c.drawText(header, w - pad, y, pSub)
    y += 30
    val colW = (w - 2 * pad) / 4
    for (l in lines) {
        if (l.label != null) {
            y += labelH
            val kind = a.sections.firstOrNull { it.label == l.label }?.kind
            val col = kind?.let { sectionColor(it) }?.toArgb() ?: ink
            pLabel.color = col
            c.drawText(l.label, w - pad, y - 14, pLabel)
            c.drawRoundRect(RectF(w - pad + 10, y - 44, w - pad + 22, y - 18), 6f, 6f, Paint().apply { color = col })
        }
        val top = y + 10
        val bottom = y + rowH - 10
        for (i in 0..4) {
            val x = pad + i * colW
            if (i <= l.bars.size) c.drawLine(x, top, x, bottom, pLine)
        }
        l.bars.forEachIndexed { i, b ->
            var size = 44f
            pChord.textSize = size
            while (pChord.measureText(b) > colW - 24 && size > 22) { size -= 2; pChord.textSize = size }
            c.drawText(b, pad + i * colW + 14, top + (bottom - top) / 2 + size / 3, pChord)
        }
        y += rowH
    }
    val pFoot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = soft; textSize = 26f; textAlign = Paint.Align.CENTER }
    c.drawText("נוצר באפליקציית אקורדים", w / 2f, h - pad / 2, pFoot)
    return bmp
}

private fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)

private fun saveToGallery(context: Context, bmp: Bitmap, title: String): Boolean = try {
    val name = "אקורדים - ${safeName(title)}.png"
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Akordim")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri != null && context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
    } else {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Akordim").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
} catch (_: Exception) {
    false
}

private fun shareImage(context: Context, bmp: Bitmap, title: String) {
    try {
        val dir = File(context.cacheDir, "sheets").apply { mkdirs() }
        val f = File(dir, "akordim-${safeName(title)}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(i, "שיתוף"))
    } catch (_: Exception) {
        Toast.makeText(context, "אין אפליקציה לשיתוף במכשיר — אפשר לשמור בגלריה", Toast.LENGTH_SHORT).show()
    }
}
