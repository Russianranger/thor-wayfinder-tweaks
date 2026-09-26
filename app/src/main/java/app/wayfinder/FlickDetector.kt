package app.wayfinder

/**
 * Stick FLICKS for Home/Back combos ("Home + right stick ↑" = controller to the top screen).
 * Fed raw stick axes (evdev ±32767; Y up = negative): a flick fires once when a stick passes
 * 60 % of its range, in its dominant direction, and re-arms only once the stick is back under
 * 40 % — so holding it pushed fires once, and a stick resting slightly off-centre never does.
 */
class FlickDetector(private val onFlick: (ThorButton) -> Unit) {
    private val x = IntArray(2); private val y = IntArray(2)   // [0] left stick, [1] right stick
    private val armed = booleanArrayOf(true, true)

    fun onAbs(code: Int, value: Int) {
        val s = when (code) {
            ThorInput.ABS_LX -> { x[0] = value; 0 }
            ThorInput.ABS_LY -> { y[0] = value; 0 }
            ThorInput.ABS_RX -> { x[1] = value; 1 }
            ThorInput.ABS_RY -> { y[1] = value; 1 }
            else -> return
        }
        val ax = Math.abs(x[s]); val ay = Math.abs(y[s])
        val mag = maxOf(ax, ay)
        if (!armed[s]) { if (mag < ThorInput.STICK_MAX * 0.4) armed[s] = true; return }
        if (mag <= ThorInput.STICK_MAX * 0.6) return
        armed[s] = false
        onFlick(
            if (ay >= ax) {
                if (y[s] < 0) (if (s == 1) ThorButton.RS_UP else ThorButton.LS_UP) else (if (s == 1) ThorButton.RS_DOWN else ThorButton.LS_DOWN)
            } else {
                if (x[s] < 0) (if (s == 1) ThorButton.RS_LEFT else ThorButton.LS_LEFT) else (if (s == 1) ThorButton.RS_RIGHT else ThorButton.LS_RIGHT)
            }
        )
    }

    /** Forget the sticks (a new stream, or the layer switched): everything re-armed at rest. */
    fun reset() { x.fill(0); y.fill(0); armed.fill(true) }
}
