package com.maxi.akordim.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class Tab(val title: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("נגן", Icons.Filled.PlayCircle),
    Tab("סולם", Icons.Filled.SwapVert),
    Tab("גיטרה", Icons.Filled.MusicNote),
    Tab("פרטים", Icons.Filled.Info),
)

@Composable
fun SongScreen(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val a = vm.analysis
    Column(Modifier.fillMaxSize().background(Vintage.Paper)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().background(Vintage.Ink).statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.closeSong() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה", tint = Vintage.Paper)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    vm.song?.title ?: "", style = MaterialTheme.typography.titleMedium, color = Vintage.Paper,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (a != null) {
                    val key = a.key.transpose(vm.transpose)
                    Text(
                        "סולם ${key.name()} · ${a.bpm.toInt()} BPM · ${a.beatsPerBar}/4" +
                            (if (vm.capo > 0) " · קאפו ${vm.capo}" else ""),
                        style = MaterialTheme.typography.labelMedium, color = Vintage.Mustard
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                vm.error != null -> ErrorView(vm.error!!, onRetry = { vm.reanalyze() }, onBack = { vm.closeSong() })
                a == null -> AnalyzingView(vm.progress, vm.progressText)
                else -> when (tab) {
                    0 -> PlayerTab(vm, a)
                    1 -> TransposeTab(vm, a)
                    2 -> GuitarTab(vm, a)
                    else -> DetailsTab(vm, a)
                }
            }
        }
        if (a != null) {
            NavigationBar(
                containerColor = Vintage.Ink,
                modifier = Modifier.height(64.dp + 0.dp),
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            ) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, t.title, modifier = Modifier.size(22.dp)) },
                        label = { Text(t.title, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Vintage.Ink,
                            selectedTextColor = Vintage.Mustard,
                            indicatorColor = Vintage.Mustard,
                            unselectedIconColor = Vintage.PaperDark,
                            unselectedTextColor = Vintage.PaperDark,
                        )
                    )
                }
            }
            Spacer(Modifier.fillMaxWidth().background(Vintage.Ink).navigationBarsPadding())
        }
    }
}

@Composable
private fun AnalyzingView(progress: Float, text: String) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(120.dp).clip(RoundedCornerShape(60.dp)).background(Vintage.Ink)
                .border(6.dp, Vintage.Mustard, RoundedCornerShape(60.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium, color = Vintage.Mustard)
        }
        Spacer(Modifier.height(20.dp))
        Text(text.ifBlank { "מכין…" }, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Vintage.Rust, trackColor = Vintage.PaperDark,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "הניתוח נעשה כולו בטלפון. בפעם הבאה שתפתח את השיר הוא ייפתח מיד.",
            style = MaterialTheme.typography.bodySmall, color = Vintage.InkSoft, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ErrorView(msg: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("לא הצלחתי לנתח את השיר", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(msg, style = MaterialTheme.typography.bodyMedium, color = Vintage.InkSoft, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRetry) { Text("נסה שוב") }
            OutlinedButton(onClick = onBack) { Text("חזרה") }
        }
    }
}

@Composable
fun Pill(text: String, bg: androidx.compose.ui.graphics.Color = Vintage.PaperDark, fg: androidx.compose.ui.graphics.Color = Vintage.Ink) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = fg,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Vintage.Cream)
            .border(1.dp, Vintage.PaperDark, RoundedCornerShape(12.dp)).padding(12.dp)
    ) { content() }
}

