package com.maxi.akordim.analysis

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The Kotlin BTC port must reproduce the numpy reference (tools/btc), which matches librosa + torch. */
class BtcTest {
    @Test
    fun matchesReference() {
        val res = File("src/test/resources")
        val raw = File(res, "btc_excerpt.f32").readBytes()
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val x = FloatArray(raw.size / 4) { bb.float }
        val expected = File(res, "btc_excerpt.expected").readText().trim().split(" ").map { it.toInt() }
        val t0 = System.currentTimeMillis()
        val got = File("src/main/assets/btc.bin").inputStream().use { Btc.testFrames(it, x) }
        val n = minOf(got.size, expected.size)
        val agree = (0 until n).count { got[it] == expected[it] }.toDouble() / n
        println("BTC-TEST frames=${got.size} expected=${expected.size} agree=$agree ms=${System.currentTimeMillis() - t0}")
        assertTrue("BTC-TEST agreement $agree", agree > 0.97 && got.size == expected.size)
    }
}
