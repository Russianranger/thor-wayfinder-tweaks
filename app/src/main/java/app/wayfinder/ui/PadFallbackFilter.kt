package app.wayfinder.ui

import android.view.KeyEvent

/**
 * Android's Generic.kcm turns unhandled gamepad buttons into "fallback" keys
 * (THUMBL/THUMBR/START → DPAD_CENTER, X → DEL, Y → SPACE, SELECT → MENU). In our
 * UI that makes R3 — a combo modifier — click whatever is focused. On this ROM the
 * synthesized key does NOT carry FLAG_FALLBACK, but it shares the source press's
 * downTime, so we drop it by that. A (→ DPAD_CENTER) and B (→ BACK) are left alone:
 * those fallbacks are the intended select / back.
 *
 * One instance per window; call [shouldDrop] first in dispatchKeyEvent.
 */
class PadFallbackFilter {
    private var sourceDownTime = -1L

    fun shouldDrop(e: KeyEvent): Boolean {
        if (e.keyCode in SOURCES) { sourceDownTime = e.downTime; return false }
        return e.keyCode in FALLBACKS && e.downTime == sourceDownTime
    }

    private companion object {
        val SOURCES = setOf(
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_SELECT,
        )
        val FALLBACKS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MENU,
        )
    }
}
