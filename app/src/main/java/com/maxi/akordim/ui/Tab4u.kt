package com.maxi.akordim.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import java.net.URLEncoder

private const val TAB4U = "https://www.tab4u.com"

/** Pulls the chord sheet (chords + words) of a tab4u song page as plain text, line by line. */
private const val EXTRACT_JS = """
(function(){
  var e = document.querySelector('#songContentTPL') || document.querySelector('.song_content')
       || document.querySelector('#songContent') || document.querySelector('.songContent');
  var title = (document.querySelector('h1') || {}).innerText || document.title;
  return (title || '') + '\n\n' + ((e || document.body).innerText || '');
})()
"""

/** Search words from the song's name: file noise removed. */
private fun searchWords(title: String): String =
    title.replace(Regex("[_\\-–|()\\[\\]]+"), " ")
        .replace(Regex("(?i)\\b(official|video|audio|lyrics|hd|4k|mp3|קליפ|רשמי|מילים)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim()

/**
 * tab4u inside the app: opens a search for the song, the user taps the right result,
 * and one tap saves that page's chords and words with the song.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Tab4uScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val title = vm.song?.title ?: ""
    var web by remember { mutableStateOf<WebView?>(null) }
    var url by remember { mutableStateOf("") }
    var loading by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val onSongPage = url.contains("/tabs/songs/")
    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else vm.showTab4u = false
    }
    Column(Modifier.fillMaxSize().background(Stage.Paper)) {
        Row(
            Modifier.fillMaxWidth().background(Stage.Bar).statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.showTab4u = false }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה", tint = Stage.Ink)
            }
            Column(Modifier.weight(1f)) {
                Text("אקורדים ומילים מטאב4יו", style = MaterialTheme.typography.titleSmall, color = Stage.Ink)
                Text(
                    if (onSongPage) "זה השיר? לוחצים \"שמירה\" למטה" else "בוחרים את השיר הנכון מהתוצאות",
                    style = MaterialTheme.typography.labelSmall, color = Stage.Mustard,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { vm.showTab4u = false; vm.showLyrics = true }) { Text("ידני") }
        }
        if (loading in 1..99) LinearProgressIndicator(
            progress = { loading / 100f }, modifier = Modifier.fillMaxWidth(), color = Stage.Mustard
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                !request.url.host.orEmpty().endsWith("tab4u.com") // stay on tab4u

                            override fun onPageFinished(view: WebView, u: String) {
                                url = u
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, p: Int) {
                                loading = p
                                view.url?.let { url = it }
                            }
                        }
                        loadUrl("$TAB4U/resultsSimple?tab=songs&q=" + URLEncoder.encode(searchWords(title), "UTF-8"))
                        web = this
                    }
                },
                onRelease = { it.destroy() }
            )
        }
        if (onSongPage) {
            Row(
                Modifier.fillMaxWidth().background(Stage.Bar).navigationBarsPadding().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "האקורדים והמילים יישמרו עם השיר ויכנסו לייצוא",
                    style = MaterialTheme.typography.bodySmall, color = Stage.InkSoft, modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = !saving,
                    colors = ButtonDefaults.buttonColors(containerColor = Stage.Mustard, contentColor = Stage.Bar),
                    onClick = {
                        val w = web ?: return@Button
                        saving = true
                        w.evaluateJavascript(EXTRACT_JS) { res ->
                            saving = false
                            val text = try { JSONArray("[$res]").getString(0) } catch (_: Exception) { "" }
                            if (text.isBlank() || text.length < 40) {
                                Toast.makeText(context, "לא מצאתי אקורדים בדף הזה", Toast.LENGTH_SHORT).show()
                            } else {
                                vm.saveLyrics("טאב4יו: $url\n\n" + text.trim())
                                Toast.makeText(context, "נשמר עם השיר", Toast.LENGTH_SHORT).show()
                                vm.showTab4u = false
                            }
                        }
                    }
                ) { Text(if (saving) "שומר…" else "שמירה") }
            }
        }
    }
}
