package com.maxi.akordim.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxi.akordim.analysis.SongAnalysis
import com.maxi.akordim.music.Chord
import com.maxi.akordim.music.Guitar
import com.maxi.akordim.music.Voicing

@Composable
fun GuitarTab(vm: AppViewModel, a: SongAnalysis) {
    val usage = remember(a) { a.chordUsage() }
    val shownUsage = usage.map { vm.shown(it.first) to it.second }
    val suggested = remember(a, vm.transpose) { Guitar.suggestCapo(shownUsage) }
    val choice = remember { mutableStateMapOf<Chord, Int>() } // shape chord -> voicing index
    val sp = vm.spelling
    val shapeSpelling = a.key.transpose(vm.transpose - vm.capo).spelling

    val seg = vm.segmentIndex
    val current = if (seg >= 0) vm.shown(a.segments[seg].chord) else null
    val next = if (seg + 1 in a.segments.indices) vm.shown(a.segments[seg + 1].chord) else null

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("קאפו", style = MaterialTheme.typography.titleSmall)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        for (c in 0..9) Choice(if (c == 0) "בלי" else "$c", vm.capo == c) { vm.changeCapo(c) }
                    }
                }
                if (suggested != vm.capo) {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(Vintage.Olive.copy(alpha = 0.15f))
                            .clickable { vm.changeCapo(suggested) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (suggested == 0) "הכי קל בלי קאפו — לחץ להחלה" else "הכי קל: קאפו $suggested — לחץ להחלה",
                            style = MaterialTheme.typography.labelLarge, color = Vintage.Olive
                        )
                    }
                }
                if (vm.capo > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "שים קאפו בשריג ${vm.capo} ונגן את הצורות שלמטה. השם הקטן הוא האקורד שנשמע בפועל.",
                        style = MaterialTheme.typography.bodySmall, color = Vintage.InkSoft
                    )
                }
                if (current != null && !current.isNone) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Vintage.Ink).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val curShape = current.transpose(-vm.capo)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("עכשיו", style = MaterialTheme.typography.labelSmall, color = Vintage.PaperDark)
                            Text(curShape.name(shapeSpelling), style = MaterialTheme.typography.headlineSmall, color = Vintage.Mustard)
                            Guitar.voicings(curShape).getOrNull(choice[curShape] ?: 0)?.let {
                                ChordDiagram(it, 110.dp, dark = true)
                            }
                        }
                        if (next != null && !next.isNone) {
                            val nextShape = next.transpose(-vm.capo)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("הבא", style = MaterialTheme.typography.labelSmall, color = Vintage.PaperDark)
                                Text(nextShape.name(shapeSpelling), style = MaterialTheme.typography.titleMedium, color = Vintage.PaperDark)
                                Guitar.voicings(nextShape).getOrNull(choice[nextShape] ?: 0)?.let {
                                    ChordDiagram(it, 80.dp, dark = true)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "כל האקורדים בשיר (לחיצה על אקורד מחליפה לצורת נגינה אחרת)",
                    style = MaterialTheme.typography.labelMedium, color = Vintage.InkSoft
                )
            }
        }
        items(shownUsage, key = { it.first.encode() }) { (sounding, _) ->
            val shape = sounding.transpose(-vm.capo)
            val voicings = Guitar.voicings(shape)
            val idx = (choice[shape] ?: 0).coerceAtMost((voicings.size - 1).coerceAtLeast(0))
            val playing = current == sounding
            Column(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (playing) Vintage.Mustard.copy(alpha = 0.35f) else Vintage.Cream)
                    .border(if (playing) 2.dp else 1.dp, if (playing) Vintage.Rust else Vintage.PaperDark, RoundedCornerShape(10.dp))
                    .clickable {
                        if (voicings.isNotEmpty()) choice[shape] = (idx + 1) % voicings.size
                        vm.playChordPreview(sounding)
                    }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(shape.name(shapeSpelling), style = MaterialTheme.typography.titleMedium, color = Vintage.Ink, maxLines = 1)
                if (vm.capo > 0) Text("נשמע ${sounding.name(sp)}", style = MaterialTheme.typography.labelSmall, color = Vintage.InkSoft)
                voicings.getOrNull(idx)?.let { ChordDiagram(it, 92.dp, dark = false) }
                    ?: Text("אין צורה", style = MaterialTheme.typography.bodySmall)
                if (voicings.size > 1) Text("${idx + 1}/${voicings.size}", style = MaterialTheme.typography.labelSmall, color = Vintage.InkSoft)
            }
        }
    }
}

/** Fretboard diagram: strings vertical (low E on the left), nut at the top. */
@Composable
fun ChordDiagram(v: Voicing, width: Dp, dark: Boolean) {
    val measurer = rememberTextMeasurer()
    val line = if (dark) Vintage.PaperDark else Vintage.Ink
    val dot = if (dark) Vintage.Mustard else Vintage.Rust
    val label = if (dark) Vintage.PaperDark else Vintage.InkSoft
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(Modifier.width(width).height(width * 1.15f)) {
            val frets = 5
            val top = size.height * 0.16f
            val left = size.width * 0.18f
            val right = size.width * 0.94f
            val bottom = size.height * 0.97f
            val sx = (right - left) / 5
            val fy = (bottom - top) / frets
            val base = v.baseFret
            // frets
            for (f in 0..frets) {
                val y = top + f * fy
                drawLine(line, Offset(left, y), Offset(right, y), strokeWidth = if (f == 0 && base == 1) 5f else 1.5f)
            }
            // strings
            for (s in 0 until 6) {
                val x = left + s * sx
                drawLine(line, Offset(x, top), Offset(x, bottom), strokeWidth = 1f + (5 - s) * 0.25f)
            }
            if (base > 1) {
                val t = measurer.measure("$base", TextStyle(fontSize = 10.sp, color = label))
                drawText(t, topLeft = Offset(left - t.size.width - 4, top + fy / 2 - t.size.height / 2))
            }
            val r = sx * 0.36f
            // barre
            if (v.barreFret > 0) {
                val strings = v.frets.indices.filter { v.frets[it] == v.barreFret }
                val y = top + (v.barreFret - base + 0.5f) * fy
                val x0 = left + strings.first() * sx
                val x1 = left + strings.last() * sx
                drawRoundRect(dot, Offset(x0 - r, y - r), Size(x1 - x0 + 2 * r, 2 * r), CornerRadius(r, r))
            }
            for (s in 0 until 6) {
                val f = v.frets[s]
                val x = left + s * sx
                when {
                    f < 0 -> {
                        val d = r * 0.7f
                        val y = top - r * 1.4f
                        drawLine(label, Offset(x - d, y - d), Offset(x + d, y + d), strokeWidth = 2f)
                        drawLine(label, Offset(x - d, y + d), Offset(x + d, y - d), strokeWidth = 2f)
                    }
                    f == 0 -> drawCircle(label, r * 0.7f, Offset(x, top - r * 1.4f), style = Stroke(2f))
                    else -> {
                        val y = top + (f - base + 0.5f) * fy
                        drawCircle(dot, r, Offset(x, y))
                    }
                }
            }
        }
    }
}

