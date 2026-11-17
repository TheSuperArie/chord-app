package com.maxi.akordim.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** File name for an export of the user's chord corrections. */
fun correctionsFileName(): String =
    "akordim-shirim-" + java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".json"

/** Saves [text] to Downloads/Akordim. Returns where it went (for the message), or null. */
fun saveToDownloads(context: Context, name: String, text: String): String? = try {
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Akordim")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        val ok = uri != null && context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()); true } == true
        if (ok) "הורדות/Akordim/$name" else null
    } else {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Akordim").apply { mkdirs() }
        val f = File(dir, name)
        f.writeText(text)
        f.absolutePath
    }
} catch (_: Exception) {
    null
}

/** Shares [text] as a file through any app that accepts files. Returns false if nothing can. */
fun shareTextFile(context: Context, name: String, text: String): Boolean = try {
    val dir = File(context.cacheDir, "sheets").apply { mkdirs() }
    val f = File(dir, name)
    f.writeText(text)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "תיקוני אקורדים")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(i, "שליחת קובץ התיקונים").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: Exception) {
    false
}
