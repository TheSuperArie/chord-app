package com.maxi.akordim.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.maxi.akordim.analysis.SongAnalysis
import kotlin.math.pow

private fun tempoName(bpm: Double) = when {
    bpm < 60 -> "לרגו · איטי מאוד"
    bpm < 76 -> "אדג'יו · איטי"
    bpm < 108 -> "אנדנטה · בינוני-רגוע"
    bpm < 120 -> "מודרטו · בינוני"
    bpm < 168 -> "אלגרו · מהיר"
    else -> "פרסטו · מהיר מאוד"
}

@Composable
fun DetailsTab(vm: AppViewModel, a: SongAnalysis) {
    val usage = vm.usageShown(a)
    val totalChordTime = usage.sumOf { it.second }.coerceAtLeast(1e-6)
    val barsCount = remember(a) { bars(a).size }
    val avgConf = remember(a) {
        val w = a.segments.filter { !it.chord.isNone }
        if (w.isEmpty()) 0f else (w.sumOf { it.confidence.toDouble() * (it.end - it.start) } / w.sumOf { it.end - it.start }).toFloat()
    }
    val edits = a.segments.count { it.chord != it.detected }
    var confirm by remember { mutableStateOf(false) }
    val key = a.key.transpose(vm.transpose)
    val sp = vm.spelling

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(Modifier.weight(1f), "מהירות", "${"%.0f".format(a.bpm)}", "BPM · ${tempoName(a.bpm)}")
            Stat(Modifier.weight(1f), "משקל", "${a.beatsPerBar}/4", "$barsCount תיבות · ${a.beats.size - 1} פעימות")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(
                Modifier.weight(1f), "סולם", key.name(),
                key.hebrewName() + if (vm.transpose != 0) "\nבמקור ${a.key.name()}" else ""
            )
            Stat(
                Modifier.weight(1f), "סולם מקביל", key.relative.name(),
                key.relative.hebrewName()
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(Modifier.weight(1f), "אורך", formatTime(a.durationSec), "יציבות קצב ${(a.tempoStability * 100).toInt()}%")
            Stat(
                Modifier.weight(1f), "כיוון", "A=${"%.1f".format(440 * 2.0.pow(a.tuningCents / 1200))}",
                "סטייה ${"%+.0f".format(a.tuningCents)} סנט"
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(Modifier.weight(1f), "ביטחון בסולם", "${(a.keyConfidence * 100).toInt()}%", "")
            Stat(Modifier.weight(1f), "ביטחון באקורדים", "${(avgConf * 100).toInt()}%", if (edits > 0) "תיקנת $edits אקורדים" else "")
        }

        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("אקורדים בשיר (${usage.size})", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                usage.forEach { (c, t) ->
                    val frac = (t / totalChordTime).toFloat()
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name(sp), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(70.dp))
                        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Stage.PaperDark)) {
                            Box(Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).fillMaxHeight().background(Stage.Mustard))
                        }
                        Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp).padding(start = 6.dp))
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("על הניתוח", style = MaterialTheme.typography.titleSmall)
                Text(
                    "נותח בטלפון ב-${"%.1f".format(a.analysisMillis / 1000.0)} שניות · עוצמה ממוצעת ${"%.0f".format(a.loudnessDb)} dB",
                    style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft
                )
                Text(
                    "הזיהוי מדויק יותר בשירים עם כלי אחד או שניים. אם אקורד לא נכון, לחץ עליו במסך הנגן ובחר חלופה.",
                    style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { confirm = true }) { Text("לנתח מחדש") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Stage.Cream,
            title = { Text("לנתח את השיר מחדש?") },
            text = { Text(if (edits > 0) "התיקונים שעשית ($edits) יימחקו." else "הניתוח יתחיל מההתחלה.") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.reanalyze() }) { Text("כן") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("ביטול") } },
        )
    }
}

@Composable
private fun Stat(modifier: Modifier, label: String, value: String, sub: String) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Stage.InkSoft)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = Stage.Rust)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft)
        }
    }
}
