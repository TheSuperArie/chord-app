package com.maxi.akordim.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val permissions = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    fun granted() = permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var hasPermission by remember { mutableStateOf(granted()) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = granted()
        if (hasPermission) vm.loadDeviceSongs()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openPicked(uri)
    }
    LaunchedEffect(hasPermission) {
        vm.refreshRecents()
        if (hasPermission && !vm.deviceSongsLoaded) vm.loadDeviceSongs()
    }
    var query by remember { mutableStateOf("") }
    // corrections export: null = closed, otherwise the prepared text and song count
    var export by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val exportName = remember(export) { correctionsFileName() }
    export?.let { (text, n) ->
        AlertDialog(
            onDismissRequest = { export = null },
            containerColor = Stage.Cream,
            title = { Text("ייצוא תיקונים") },
            text = {
                Text(
                    if (n == 0) "עוד אין שירים שתיקנת בהם אקורדים. פותחים שיר, עוברים עליו ומתקנים מה שצריך — ואז מייצאים מכאן."
                    else "נמצאו $n שירים עם תיקונים. הקובץ כולל את האקורדים שתיקנת לצד מה שהזיהוי מצא, כדי לשפר את מנוע הזיהוי.\n\n" +
                        "חשוב: לשלוח יחד איתו גם את קבצי השירים עצמם.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                if (n > 0) TextButton(onClick = {
                    val where = saveToDownloads(context, exportName, text)
                    Toast.makeText(context, if (where != null) "נשמר: $where" else "השמירה נכשלה", Toast.LENGTH_LONG).show()
                    if (where != null) export = null
                }) { Text("שמירה בהורדות") }
            },
            dismissButton = {
                Row {
                    if (n > 0) TextButton(onClick = {
                        if (!shareTextFile(context, exportName, text))
                            Toast.makeText(context, "אין אפליקציה לשליחה — אפשר לשמור בהורדות", Toast.LENGTH_SHORT).show()
                    }) { Text("שליחה") }
                    TextButton(onClick = { export = null }) { Text("סגור") }
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(Stage.Paper)) {
        // Header
        Column(
            Modifier.fillMaxWidth().background(Stage.Bar).statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("אקורדים", style = MaterialTheme.typography.headlineMedium, color = Stage.Mustard, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Stage.Cream).clickable { vm.showTools = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Tune, null, tint = Stage.Mustard, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("מכוון וכלים", style = MaterialTheme.typography.labelLarge, color = Stage.Ink)
                }
            }
            Text(
                "בוחרים שיר — והאקורדים, הסולם והקצב מופיעים. הכול בלי אינטרנט.",
                style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Button(
                    onClick = { picker.launch(arrayOf("audio/*", "video/*")) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Filled.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("בחירת שיר מהקבצים", style = MaterialTheme.typography.titleSmall)
                }
            }
            if (vm.recents.isNotEmpty()) {
                item { SectionTitle(Icons.Filled.History, "פתחת לאחרונה") }
                items(vm.recents.take(8), key = { "r" + it.key }) { r ->
                    SongRow(
                        icon = Icons.Filled.MusicNote,
                        title = r.title,
                        subtitle = r.summary + if (r.transpose != 0) " · סולם ${if (r.transpose > 0) "+" else ""}${r.transpose}" else "",
                        onClick = { vm.openRecent(r) },
                        onRemove = { vm.removeRecent(r) },
                    )
                }
            }
            if (vm.recents.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { vm.exportCorrections { t, n -> export = t to n } },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Filled.Upload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("ייצוא תיקונים לשיפור הזיהוי")
                    }
                }
            }
            item { SectionTitle(Icons.Filled.LibraryMusic, "שירים וסרטונים בטלפון") }
            if (!hasPermission) {
                item {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Stage.Cream)
                            .border(1.dp, Stage.PaperDark, RoundedCornerShape(10.dp)).padding(14.dp)
                    ) {
                        Text("כדי להציג את השירים שבטלפון צריך אישור גישה לקבצי מדיה.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { permLauncher.launch(permissions) }) { Text("אישור גישה") }
                    }
                }
            } else {
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("חיפוש שיר") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        shape = RoundedCornerShape(10.dp),
                    )
                }
                val list = vm.deviceSongs.filter {
                    query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true)
                }
                if (vm.deviceSongsLoaded && list.isEmpty()) {
                    item {
                        Text(
                            if (query.isBlank()) "לא נמצאו קבצי שמע או וידאו בטלפון. אפשר לבחור קובץ בכפתור למעלה."
                            else "אין תוצאות לחיפוש",
                            style = MaterialTheme.typography.bodyMedium, color = Stage.InkSoft,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                items(list, key = { it.uri.toString() }) { f ->
                    SongRow(
                        icon = if (f.isVideo) Icons.Filled.Videocam else Icons.Filled.MusicNote,
                        title = f.title,
                        subtitle = listOf(f.artist, formatTime(f.durationMs / 1000.0)).filter { it.isNotBlank() }.joinToString(" · "),
                        onClick = { vm.openDevice(f) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, text: String) {
    Row(Modifier.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Stage.Rust, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = Stage.Ink)
    }
}

@Composable
private fun SongRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Stage.Cream)
            .border(1.dp, Stage.PaperDark, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Stage.Bar),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = Stage.Mustard, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft, maxLines = 1)
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, "הסר", tint = Stage.InkSoft, modifier = Modifier.size(16.dp))
            }
        }
    }
}

fun formatTime(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
