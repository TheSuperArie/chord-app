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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
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
    Tab("כלים", Icons.Filled.Tune),
)

@Composable
fun SongScreen(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 600
    val a = vm.analysis
    if (a != null && vm.stageMode) {
        androidx.activity.compose.BackHandler { vm.stageMode = false }
        StageMode(vm, a)
        return
    }
    if (vm.showTab4u) {
        Tab4uScreen(vm)
        return
    }
    if (a != null && vm.showSheet) {
        androidx.activity.compose.BackHandler { vm.showSheet = false }
        SheetScreen(vm, a)
        return
    }
    vm.song?.key?.let { k -> androidx.compose.runtime.LaunchedEffect(k) { vm.loadLyrics(k) } }
    if (vm.showLyrics) LyricsDialog(vm)
    // keep the screen awake while a song plays
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(vm.isPlaying) {
        view.keepScreenOn = vm.isPlaying
        onDispose { view.keepScreenOn = false }
    }
    Column(Modifier.fillMaxSize().background(Stage.Paper)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().background(Stage.Bar).statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = if (compact) 0.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.closeSong() }, modifier = if (compact) Modifier.size(40.dp) else Modifier) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה", tint = Stage.Ink)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    vm.song?.title ?: "", style = MaterialTheme.typography.titleMedium, color = Stage.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (a != null) {
                    val key = a.key.transpose(vm.transpose)
                    Text(
                        "סולם ${key.name()} · ${a.bpm.toInt()} BPM · ${a.beatsPerBar}/4" +
                            (if (vm.capo > 0) " · קאפו ${vm.capo} (צורות ${key.transpose(-vm.capo).name()})" else ""),
                        style = MaterialTheme.typography.labelMedium, color = Stage.Mustard
                    )
                }
            }
            if (a != null) {
                IconButton(onClick = { if (vm.lyrics.isBlank()) vm.showTab4u = true else vm.showLyrics = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Edit, "מילים", tint = if (vm.lyrics.isNotBlank()) Stage.Mustard else Stage.Ink)
                }
                IconButton(onClick = { vm.showSheet = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Description, "דף אקורדים", tint = Stage.Ink)
                }
                IconButton(onClick = { vm.stageMode = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Fullscreen, "מצב במה", tint = Stage.Ink)
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
                    3 -> DetailsTab(vm, a)
                    else -> ToolsContent(vm, a)
                }
            }
        }
        if (a != null) {
            NavigationBar(
                containerColor = Stage.Bar,
                modifier = Modifier.height(if (compact) 50.dp else 64.dp),
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            ) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, t.title, modifier = Modifier.size(if (compact) 20.dp else 22.dp)) },
                        label = { Text(t.title, style = MaterialTheme.typography.labelMedium) },
                        alwaysShowLabel = !compact,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Stage.Bar,
                            selectedTextColor = Stage.Mustard,
                            indicatorColor = Stage.Mustard,
                            unselectedIconColor = Stage.InkSoft,
                            unselectedTextColor = Stage.InkSoft,
                        )
                    )
                }
            }
            Spacer(Modifier.fillMaxWidth().background(Stage.Bar).navigationBarsPadding())
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
            Modifier.size(120.dp).clip(RoundedCornerShape(60.dp)).background(Stage.Bar)
                .border(6.dp, Stage.Mustard, RoundedCornerShape(60.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium, color = Stage.Mustard)
        }
        Spacer(Modifier.height(20.dp))
        Text(text.ifBlank { "מכין…" }, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Stage.Rust, trackColor = Stage.PaperDark,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "הניתוח נעשה כולו בטלפון. בפעם הבאה שתפתח את השיר הוא ייפתח מיד.",
            style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft, textAlign = TextAlign.Center
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
        Text(msg, style = MaterialTheme.typography.bodyMedium, color = Stage.InkSoft, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRetry) { Text("נסה שוב") }
            OutlinedButton(onClick = onBack) { Text("חזרה") }
        }
    }
}

@Composable
fun Pill(text: String, bg: androidx.compose.ui.graphics.Color = Stage.PaperDark, fg: androidx.compose.ui.graphics.Color = Stage.Ink) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = fg,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Stage.Cream)
            .border(1.dp, Stage.PaperDark, RoundedCornerShape(12.dp)).padding(12.dp)
    ) { content() }
}


@Composable
private fun LyricsDialog(vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(vm.lyrics) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.showLyrics = false },
        containerColor = Stage.Cream,
        title = { Text("מילים לשיר") },
        text = {
            Column {
                Text(
                    "האקורדים והמילים של השיר (נכנסים לייצוא). \"חיפוש בטאב4יו\" מביא אותם אוטומטית.",
                    style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Stage.Ink),
                    placeholder = { Text("כאן מדביקים…") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.TextButton(onClick = {
                        val clip = clipboard.getText()?.text
                        if (clip.isNullOrBlank()) android.widget.Toast.makeText(context, "אין טקסט מועתק", android.widget.Toast.LENGTH_SHORT).show()
                        else text = if (text.isBlank()) clip else text.trimEnd() + "\n" + clip
                    }) { Text("הדבקה") }
                    if (text.isNotBlank()) androidx.compose.material3.TextButton(onClick = { text = "" }) { Text("ניקוי") }
                    androidx.compose.material3.TextButton(onClick = { vm.showLyrics = false; vm.showTab4u = true }) { Text("חיפוש בטאב4יו") }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                vm.saveLyrics(text); vm.showLyrics = false
                android.widget.Toast.makeText(context, if (text.isBlank()) "המילים נמחקו" else "המילים נשמרו", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("שמירה") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { vm.showLyrics = false }) { Text("ביטול") }
        }
    )
}
