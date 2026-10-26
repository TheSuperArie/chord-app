package com.maxi.akordim.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxi.akordim.music.Notes
import com.maxi.akordim.music.Spelling

private val WHITE_PCS = setOf(0, 2, 4, 5, 7, 9, 11)

/**
 * A two-octave keyboard. [highlight] maps MIDI note -> colour; tapping a key calls [onKey].
 */
@Composable
fun PianoKeyboard(
    low: Int,
    high: Int,
    highlight: Map<Int, Color>,
    spelling: Spelling,
    height: Dp = 92.dp,
    onKey: (Int) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val whites = (low..high).filter { Notes.pc(it) in WHITE_PCS }
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(low, high) {
                detectTapGestures { p ->
                    val w = size.width.toFloat() / whites.size
                    val bh = size.height * 0.6f
                    val bw = w * 0.6f
                    var hit: Int? = null
                    if (p.y < bh) {
                        for (m in low..high) {
                            if (Notes.pc(m) in WHITE_PCS) continue
                            val left = whites.indexOf(m - 1)
                            if (left < 0) continue
                            val x = (left + 1) * w - bw / 2
                            if (p.x >= x && p.x <= x + bw) hit = m
                        }
                    }
                    if (hit == null) {
                        val i = (p.x / w).toInt().coerceIn(0, whites.size - 1)
                        hit = whites[i]
                    }
                    onKey(hit)
                }
            }
        ) {
            val w = size.width / whites.size
            val bh = size.height * 0.6f
            val bw = w * 0.6f
            // white keys
            whites.forEachIndexed { i, m ->
                val c = highlight[m] ?: Vintage.Cream
                drawRoundRect(
                    c, Offset(i * w + 1, 0f), Size(w - 2, size.height),
                    cornerRadius = CornerRadius(4f, 4f)
                )
                drawRoundRect(
                    Vintage.InkSoft, Offset(i * w + 1, 0f), Size(w - 2, size.height),
                    cornerRadius = CornerRadius(4f, 4f), style = Stroke(1f)
                )
                val pc = Notes.pc(m)
                if (highlight.containsKey(m) || pc == 0) {
                    val label = if (highlight.containsKey(m)) Notes.name(pc, spelling) else "C${m / 12 - 1}"
                    val tl = measurer.measure(
                        label,
                        TextStyle(
                            fontSize = 9.sp,
                            color = if (highlight.containsKey(m)) Vintage.Ink else Vintage.InkSoft
                        )
                    )
                    drawText(tl, topLeft = Offset(i * w + (w - tl.size.width) / 2, size.height - tl.size.height - 4))
                }
            }
            // black keys
            for (m in low..high) {
                if (Notes.pc(m) in WHITE_PCS) continue
                val left = whites.indexOf(m - 1)
                if (left < 0) continue
                val x = (left + 1) * w - bw / 2
                val c = highlight[m] ?: Vintage.Ink
                drawRoundRect(c, Offset(x, 0f), Size(bw, bh), cornerRadius = CornerRadius(3f, 3f))
                if (highlight.containsKey(m)) {
                    drawRoundRect(Vintage.Ink, Offset(x, 0f), Size(bw, bh), cornerRadius = CornerRadius(3f, 3f), style = Stroke(1.5f))
                }
            }
            // felt strip on top, vintage piano style
            drawRect(Vintage.Rust, Offset(0f, 0f), Size(size.width, 4f))
        }
    }
}
