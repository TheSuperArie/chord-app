package com.maxi.akordim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maxi.akordim.analysis.SongAnalysis

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransposeTab(vm: AppViewModel, a: SongAnalysis) {
    val usage = remember(a) { a.chordUsage() }
    val orig = a.key
    val now = a.key.transpose(vm.transpose)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    KeyBox("מקורי", orig.name(), orig.hebrewName(), Stage.InkSoft)
                    Text("←", style = MaterialTheme.typography.headlineMedium, color = Stage.InkSoft, modifier = Modifier.padding(horizontal = 10.dp))
                    KeyBox("עכשיו", now.name(), now.hebrewName(), Stage.Rust)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icons.Filled.Remove, "הורד") { vm.changeTranspose(vm.transpose - 1) }
                    Text(
                        (if (vm.transpose > 0) "+" else "") + vm.transpose,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.width(80.dp)
                    )
                    RoundButton(Icons.Filled.Add, "העלה") { vm.changeTranspose(vm.transpose + 1) }
                }
                Text("חצאי טונים", style = MaterialTheme.typography.labelSmall, color = Stage.InkSoft)
                if (vm.transpose != 0) TextButton(onClick = { vm.changeTranspose(0) }) { Text("חזרה לסולם המקורי") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("לשנות גם את גובה השיר", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "השיר יתנגן בסולם החדש, באותה מהירות",
                            style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft
                        )
                    }
                    Switch(
                        checked = vm.shiftAudio, onCheckedChange = { vm.changeShiftAudio(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Stage.Rust, checkedThumbColor = Stage.OnAccent)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("מהירות נגינה (לתרגול)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f).forEach { s ->
                            Choice("${if (s == 1f) "1" else s.toString()}×", vm.speed == s) { vm.changeSpeed(s) }
                        }
                    }
                }
            }
        }

        Text("האקורדים בשיר", style = MaterialTheme.typography.titleSmall)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                usage.forEach { (c, _) ->
                    val shown = vm.shown(c)
                    if (vm.simpleChords && usage.firstOrNull { vm.shown(it.first) == shown }?.first != c) return@forEach
                    Column(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(Stage.Cream)
                            .border(1.dp, Stage.PaperDark, RoundedCornerShape(8.dp))
                            .clickable { vm.playShown(shown) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(shown.name(vm.spelling), style = MaterialTheme.typography.titleMedium, color = Stage.Rust)
                        if (vm.displayShift != 0) Text(c.name(a.key.spelling), style = MaterialTheme.typography.labelSmall, color = Stage.InkSoft)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun KeyBox(label: String, name: String, heb: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Stage.InkSoft)
        Text(name, style = MaterialTheme.typography.headlineMedium, color = color)
        Text(heb, style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft)
    }
}

@Composable
fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Stage.Bar).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = Stage.Mustard) }
}
