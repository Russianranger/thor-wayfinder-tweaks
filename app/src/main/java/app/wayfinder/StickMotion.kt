package app.wayfinder

import kotlin.math.sqrt

/** Pure stick-to-key / cursor math, shared by the runtime and its JVM tests. */
object StickMotion {
    const val PRESS = .45f
    const val RELEASE = .30f
    const val MOUSE_DEAD = .18f
    const val PIXELS_PER_SECOND = 900f

    /** Shaped, normalized signed axes from wfpad, before swap / inversion. */
    fun amounts(lx: Int, ly: Int, rx: Int, ry: Int): FloatArray {
        fun n(v: Int) = v.coerceIn(-32767, 32767) / 32767f
        val x = n(lx); val y = n(ly); val z = n(rx); val r = n(ry)
        return floatArrayOf(-y, y, -x, x, -r, r, -z, z).also { a ->
            for (i in a.indices) a[i] = a[i].coerceAtLeast(0f)
        }
    }

    fun keyHeld(wasHeld: Boolean, amount: Float): Boolean =
        if (wasHeld) amount > RELEASE else amount >= PRESS

    fun mouseAmount(amount: Float): Float = ((amount - MOUSE_DEAD) / (1f - MOUSE_DEAD)).coerceIn(0f, 1f)

    /** Mouse targets 5/6/7/8 mean up/down/left/right. Opposites cancel; diagonals have a
     *  speed cap so two sticks or two mapped buttons cannot unexpectedly double the speed. */
    fun mouseVector(up: Float, down: Float, left: Float, right: Float): Pair<Float, Float> {
        var x = (right - left).coerceIn(-1f, 1f)
        var y = (down - up).coerceIn(-1f, 1f)
        val length = sqrt(x * x + y * y)
        if (length > 1f) { x /= length; y /= length }
        return x to y
    }
}
