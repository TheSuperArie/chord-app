package com.maxi.akordim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxi.akordim.analysis.SongAnalysis
import com.maxi.akordim.music.Notes

/** Bars of the song as lists of beat indices (a short pick-up bar first if needed). */
fun bars(a: SongAnalysis): List<List<Int>> {
    val nb = a.beats.size - 1
    val out = ArrayList<List<Int>>()
    if (a.downbeatPhase > 0) out.add((0 until minOf(a.downbeatPhase, nb)).toList())
    var b = a.downbeatPhase
    while (b < nb) {
        out.add((b until minOf(b + a.beatsPerBar, nb)).toList())
        b += a.beatsPerBar
    }
    return out
}

fun beatToSegment(a: SongAnalysis): IntArray {
    val nb = a.beats.size - 1
    val m = IntArray(nb) { -1 }
    a.segments.forEachIndexed { i, s -> for (b in s.startBeat until minOf(s.endBeat, nb)) m[b] = i }
    return m
}

@Composable
fun PlayerTab(vm: AppViewModel, a: SongAnalysis) {
    val barList = remember(a) { bars(a) }
    val rows = remember(barList) { barList.chunked(2) }
    val b2s = remember(a) { beatToSegment(a) }
    var dialogSeg by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()

    val seg = vm.segmentIndex
    val cur = if (seg >= 0) a.segments[seg] else null
    val next = if (seg + 1 in a.segments.indices) a.segments[seg + 1] else if (seg < 0 && a.segments.isNotEmpty()) a.segments[0] else null
    val sp = vm.spelling

    // keep the current row in view while playing
    val curRow = remember(vm.beatIndex, rows) {
        rows.indexOfFirst { r -> r.any { bar -> vm.beatIndex in bar } }
    }
    LaunchedEffect(curRow) {
        if (curRow >= 0 && vm.isPlaying) listState.animateScrollToItem((curRow - 1).coerceAtLeast(0))
    }

    // The F21 Pro screen is ~430dp tall: squeeze everything except the chord chart
    val compact = LocalConfiguration.current.screenHeightDp < 600

    Column(Modifier.fillMaxSize()) {
        // ---- now playing
        Row(
            Modifier.fillMaxWidth().background(Vintage.PaperDark)
                .padding(horizontal = 12.dp, vertical = if (compact) 3.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(enabled = seg >= 0) { dialogSeg = seg },
            ) {
                if (!compact) Text("עכשיו", style = MaterialTheme.typography.labelSmall, color = Vintage.InkSoft)
                Text(
                    cur?.let { vm.shown(it.chord).name(sp) } ?: "—",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = if (compact) 32.sp else 46.sp),
                    color = Vintage.Rust, maxLines = 1,
                )
                Text(
                    cur?.let { vm.shown(it.chord).hebrewName(sp) } ?: "לחץ ▶ כדי להתחיל",
                    style = MaterialTheme.typography.bodySmall, color = Vintage.InkSoft, maxLines = 1
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("הבא ", style = MaterialTheme.typography.labelSmall, color = Vintage.InkSoft)
                    Text(
                        next?.let { vm.shown(it.chord).name(sp) } ?: "",
                        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                        color = Vintage.Ink
                    )
                }
                Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
                BeatDots(a, vm.beatIndex)
                if (compact) {
                    Row {
                        MiniToggle(Icons.Filled.Piano, "פסנתר", vm.pianoOn) { vm.pianoOn = !vm.pianoOn }
                        MiniToggle(Icons.Filled.Timer, "קליק", vm.clickOn) { vm.clickOn = !vm.clickOn }
                    }
                }
            }
        }

        // ---- chord chart
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            ) {
                itemsIndexed(rows) { ri, row ->
                    Row(Modifier.fillMaxWidth().height(if (compact) 36.dp else 46.dp).padding(vertical = 2.dp)) {
                        row.forEach { bar ->
                            BarCell(
                                modifier = Modifier.weight(1f),
                                a = a, vm = vm, bar = bar, b2s = b2s,
                                onChord = { dialogSeg = it },
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    if (ri == rows.size - 1) Spacer(Modifier.height(8.dp))
                }
            }
        }

        // ---- transport
        if (compact) CompactTransport(vm, a) else Transport(vm, a)

        // ---- piano
        val chord = cur?.let { vm.shown(it.chord) }
        val hl = HashMap<Int, Color>()
        if (chord != null && !chord.isNone) {
            hl[48 + chord.bassNote] = Vintage.Rust
            chord.pitchClasses.forEach { hl[60 + it] = Vintage.Mustard }
        }
        Box(Modifier.background(Vintage.Ink).padding(horizontal = 4.dp, vertical = if (compact) 2.dp else 4.dp)) {
            PianoKeyboard(48, 71, hl, sp, height = if (compact) 56.dp else 84.dp) { m -> vm.synth.note(m) }
        }
    }

    if (dialogSeg >= 0 && dialogSeg < a.segments.size) {
        ChordDialog(vm, a, dialogSeg, onDismiss = { dialogSeg = -1 })
    }
}

@Composable
private fun BeatDots(a: SongAnalysis, beat: Int) {
    val inBar = if (beat >= a.downbeatPhase) (beat - a.downbeatPhase) % a.beatsPerBar else -1
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until a.beatsPerBar) {
                val on = i == inBar
                Box(
                    Modifier.size(if (i == 0) 12.dp else 9.dp).clip(CircleShape)
                        .background(if (on) (if (i == 0) Vintage.Rust else Vintage.Mustard) else Vintage.Cream)
                        .border(1.dp, Vintage.InkSoft, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun BarCell(
    modifier: Modifier, a: SongAnalysis, vm: AppViewModel, bar: List<Int>, b2s: IntArray, onChord: (Int) -> Unit,
) {
    val playingHere = vm.beatIndex in bar
    Row(
        modifier.fillMaxHeight().padding(horizontal = 2.dp).clip(RoundedCornerShape(6.dp))
            .background(if (playingHere) Vintage.Cream else Vintage.Paper)
            .border(1.dp, if (playingHere) Vintage.Mustard else Vintage.PaperDark, RoundedCornerShape(6.dp))
    ) {
        bar.forEachIndexed { i, b ->
            val s = b2s.getOrElse(b) { -1 }
            val startsHere = s >= 0 && (a.segments[s].startBeat == b)
            val showName = startsHere || i == 0
            val isNow = b == vm.beatIndex
            val edited = s >= 0 && a.segments[s].chord != a.segments[s].detected
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (isNow) Vintage.Mustard.copy(alpha = 0.55f) else Color.Transparent)
                    .clickable(enabled = s >= 0) { onChord(s) },
                contentAlignment = Alignment.CenterStart
            ) {
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight(0.4f).background(Vintage.PaperDark).align(Alignment.CenterStart))
                if (s >= 0 && showName) {
                    val c = vm.shown(a.segments[s].chord)
                    Text(
                        if (c.isNone) "·" else c.name(vm.spelling),
                        fontSize = if (c.hasSlash || c.quality.suffix.length > 2) 11.sp else 14.sp,
                        fontWeight = if (startsHere) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            edited -> Vintage.Teal
                            startsHere -> Vintage.Ink
                            else -> Vintage.InkSoft.copy(alpha = 0.6f)
                        },
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(start = 3.dp),
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
        // pad short bars (pick-up / last bar)
        for (i in bar.size until a.beatsPerBar) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Transport(vm: AppViewModel, a: SongAnalysis) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxWidth().background(Vintage.Paper).padding(horizontal = 10.dp)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(if (dragging) dragValue.toDouble() else vm.position), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = if (dragging) dragValue else vm.position.toFloat().coerceIn(0f, a.durationSec.toFloat()),
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = { vm.seekTo(dragValue.toDouble()); dragging = false },
                    valueRange = 0f..a.durationSec.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp).height(30.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Vintage.Rust, activeTrackColor = Vintage.Rust, inactiveTrackColor = Vintage.PaperDark
                    )
                )
                Text(formatTime(a.durationSec), style = MaterialTheme.typography.labelSmall)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ToggleChip("פסנתר", Icons.Filled.Piano, vm.pianoOn) { vm.pianoOn = !vm.pianoOn }
                IconButton(onClick = { vm.skip(-5.0) }) { Icon(Icons.Filled.Replay5, "אחורה") }
                Box(
                    Modifier.size(50.dp).clip(CircleShape).background(Vintage.Rust).clickable { vm.togglePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (vm.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (vm.isPlaying) "השהה" else "נגן", tint = Vintage.Cream, modifier = Modifier.size(30.dp)
                    )
                }
                IconButton(onClick = { vm.skip(5.0) }) { Icon(Icons.Filled.Forward5, "קדימה") }
                ToggleChip("קליק", Icons.Filled.Timer, vm.clickOn) { vm.clickOn = !vm.clickOn }
            }
        }
    }
}

/** One-line transport for small screens: play, time, seek bar. */
@Composable
private fun CompactTransport(vm: AppViewModel, a: SongAnalysis) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier.fillMaxWidth().background(Vintage.Paper).padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Vintage.Rust).clickable { vm.togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (vm.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (vm.isPlaying) "השהה" else "נגן", tint = Vintage.Cream, modifier = Modifier.size(26.dp)
                )
            }
            Icon(
                Icons.Filled.Replay5, "אחורה", tint = Vintage.Ink,
                modifier = Modifier.padding(start = 6.dp).size(26.dp).clip(CircleShape).clickable { vm.skip(-5.0) }
            )
            Text(
                formatTime(if (dragging) dragValue.toDouble() else vm.position),
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp)
            )
            Slider(
                value = if (dragging) dragValue else vm.position.toFloat().coerceIn(0f, a.durationSec.toFloat()),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { vm.seekTo(dragValue.toDouble()); dragging = false },
                valueRange = 0f..a.durationSec.toFloat().coerceAtLeast(1f),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Vintage.Rust, activeTrackColor = Vintage.Rust, inactiveTrackColor = Vintage.PaperDark
                )
            )
            Text(formatTime(a.durationSec), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MiniToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Icon(
        icon, label,
        tint = if (on) Vintage.Rust else Vintage.InkSoft.copy(alpha = 0.45f),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp).size(22.dp).clip(CircleShape).clickable(onClick = onClick)
    )
}

@Composable
fun ToggleChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = if (on) Vintage.Rust else Vintage.InkSoft.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) Vintage.Ink else Vintage.InkSoft.copy(alpha = 0.6f))
    }
}

