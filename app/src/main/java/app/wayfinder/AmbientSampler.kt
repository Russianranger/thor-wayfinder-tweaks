package app.wayfinder

import android.util.Log
import java.io.BufferedInputStream

/**
 * Stick lights "Screen colour" — runs INSIDE the root input helper (app_process, uid 0).
 *
 * The average colour of a screen, ~12 times a second: raw `screencap -d <physical id>`
 * (no PNG: ~70 ms for 1920×1080), a sparse grid of pixels averaged while streaming
 * through. The accessibility screenshot the lights used before is limited to one per
 * ~333 ms and came back a frame late — the rings lagged the game by up to ~0.8 s.
 * Sends `C <r> <g> <b>` lines back over the helper's channel.
 */
object AmbientSampler {
    private const val TAG = "ThorAmbient"
    private const val STEP = 12            // every 12th pixel of every 12th row
    @Volatile private var phys: String? = null
    private var thread: Thread? = null
    var send: ((String) -> Unit)? = null

    /** `A <physical display id>` starts, `A -` stops. */
    fun watch(p: String?) {
        phys = p?.takeIf { it.isNotBlank() && it != "-" }
        if (phys != null && thread?.isAlive != true) thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    private fun loop() {
        while (true) {
            val id = phys ?: run { thread = null; return }
            val t0 = System.nanoTime()
            runCatching { sample(id) }.onSuccess { c -> if (c != null) send?.invoke("C ${c[0]} ${c[1]} ${c[2]}\n") }
                .onFailure { Log.w(TAG, "sample: $it") }
            val spent = (System.nanoTime() - t0) / 1_000_000
            Thread.sleep((80 - spent).coerceIn(5, 80))
        }
    }

    private fun sample(id: String): IntArray? {
        val p = ProcessBuilder("screencap", "-d", id).start()
        try {
            val input = BufferedInputStream(p.inputStream, 1 shl 16)
            val head = ByteArray(16)
            if (!readFully(input, head)) return null
            fun le(o: Int) = (head[o].toInt() and 0xFF) or ((head[o + 1].toInt() and 0xFF) shl 8) or
                ((head[o + 2].toInt() and 0xFF) shl 16) or ((head[o + 3].toInt() and 0xFF) shl 24)
            val w = le(0); val h = le(4)
            if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null
            val row = ByteArray(w * 4)
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (y in 0 until h) {
                if (!readFully(input, row)) break
                if (y % STEP != 0) continue
                var x = 0
                while (x < w) {
                    val o = x * 4
                    r += row[o].toInt() and 0xFF; g += row[o + 1].toInt() and 0xFF; b += row[o + 2].toInt() and 0xFF
                    n++; x += STEP
                }
            }
            if (n == 0L) return null
            return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        } finally {
            p.destroy()
        }
    }

    private fun readFully(input: BufferedInputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val k = input.read(buf, off, buf.size - off)
            if (k < 0) return false
            off += k
        }
        return true
    }
}
