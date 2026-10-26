package com.maxi.akordim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.maxi.akordim.analysis.SongAnalysis
import com.maxi.akordim.music.Chord
import com.maxi.akordim.music.Notes
import com.maxi.akordim.music.Quality

/**
 * Tapping a chord: shows the 4 most likely alternatives (plus a manual picker) and lets the
 * user replace it here or everywhere it appears.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChordDialog(vm: AppViewModel, a: SongAnalysis, index: Int, onDismiss: () -> Unit) {
    val seg = a.segments[index]
    val sp = vm.spelling
    val current = vm.shown(seg.chord)
    var everywhere by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    val count = a.segments.count { it.chord == seg.chord }

    fun apply(original: Chord) {
        vm.replaceChord(index, original, everywhere)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Stage.Cream,
        title = {
            Column {
                Text("החלפת אקורד · ${formatTime(seg.start)}", style = MaterialTheme.typography.labelMedium, color = Stage.InkSoft)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(current.name(sp), style = MaterialTheme.typography.headlineMedium, color = Stage.Rust)
                    Spacer(Modifier.width(8.dp))
                    Text("ביטחון ${(seg.confidence * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Stage.InkSoft)
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("אולי זה:", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                seg.alternatives.distinctBy { vm.shown(it) }.filter { vm.shown(it) != current }.take(4).forEach { alt ->
                    val shown = vm.shown(alt)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp))
                            .background(Stage.Paper).border(1.dp, Stage.PaperDark, RoundedCornerShape(8.dp))
                            .clickable { apply(alt) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(shown.name(sp), style = MaterialTheme.typography.titleLarge)
                            Text(shown.hebrewName(sp), style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft)
                        }
                        IconButton(onClick = { vm.playChordPreview(shown) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, "השמע", tint = Stage.Rust)
                        }
                    }
                }
                if (count > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { everywhere = !everywhere }) {
                        Checkbox(
                            checked = everywhere, onCheckedChange = { everywhere = it },
                            colors = CheckboxDefaults.colors(checkedColor = Stage.Rust)
                        )
                        Text("להחליף בכל ${count} המקומות בשיר", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row {
                    TextButton(onClick = { vm.markLoopStart(seg.start); onDismiss() }) { Text("↻ תחילת לופ כאן") }
                    if (vm.loopMark >= 0) TextButton(onClick = { vm.markLoopEnd(seg.end); if (!vm.isPlaying) vm.togglePlay(); onDismiss() }) { Text("סוף לופ כאן") }
                }
                TextButton(onClick = { manual = !manual }) {
                    Text(if (manual) "הסתר בחירה ידנית" else "אקורד אחר… (בחירה ידנית)")
                }
                if (manual) ManualPicker(vm, seg.chord) { apply(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.seekTo(seg.start)
                if (!vm.isPlaying) vm.togglePlay()
                onDismiss()
            }) { Text("נגן מכאן") }
        },
        dismissButton = {
            Row {
                if (seg.chord != seg.detected) {
                    TextButton(onClick = { vm.resetChord(index); onDismiss() }) { Text("לזיהוי המקורי") }
                }
                TextButton(onClick = onDismiss) { Text("סגור") }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualPicker(vm: AppViewModel, start: Chord, onPick: (Chord) -> Unit) {
    val sp = vm.spelling
    // the user picks in the shown (transposed) key; we store it in the original key
    val startShown = if (start.isNone) Chord(0, Quality.MAJ) else vm.shown(start)
    var root by remember { mutableIntStateOf(startShown.root) }
    var quality by remember { mutableStateOf(startShown.quality) }
    var bass by remember { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxWidth()) {
        Text("שורש", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (r in 0 until 12) Choice(Notes.name(r, sp), root == r) { root = r }
        }
        Spacer(Modifier.height(6.dp))
        Text("סוג", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (q in Quality.entries) Choice(q.suffix.ifEmpty { "maj" }, quality == q) { quality = q }
        }
        Spacer(Modifier.height(6.dp))
        Text("בס (לא חובה)", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Choice("ללא", bass < 0) { bass = -1 }
            for (r in 0 until 12) if (r != root) Choice("/" + Notes.name(r, sp), bass == r) { bass = r }
        }
        val picked = Chord(root, quality, bass)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(picked.name(sp), style = MaterialTheme.typography.titleLarge, color = Stage.Rust, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.playChordPreview(picked) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, "השמע", tint = Stage.Rust)
            }
            TextButton(onClick = { onPick(picked.transpose(-vm.transpose)) }) { Text("בחר") }
        }
    }
}

@Composable
fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Stage.OnAccent else Stage.Ink,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (selected) Stage.Rust else Stage.Paper)
            .border(1.dp, Stage.PaperDark, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp)
    )
}
