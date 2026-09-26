package app.wayfinder

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Keeps the analog half of L2 / R2 away from the app while a combo modifier is held.
 *
 * The triggers send a KEY (swallowed by our key filter when it completes a combo) AND an
 * analog axis (how far it's pressed). An accessibility service can't filter motion
 * events on Android 13 — so Home + R2 (brighter) also reached the app: Cocoon swiped to
 * the next game (reproduced 2026-09-24 with a virtual pad sending key + axis).
 *
 * While the modifier is held, a 1-px transparent window that CAN take focus sits on the
 * screen that has the controller: joystick motion goes to the focused window, i.e. here,
 * and is dropped. Touch passes through (not touch-modal). Removed on release. The app
 * underneath loses window focus for that moment (like when the notification shade is
 * pulled) — only while the modifier is held, and only when it has L2/R2 combos.
 */
class AnalogShield(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private var view: Pair<WindowManager, View>? = null

    fun raise(displayId: Int) = main.post {
        if (view != null) return@post
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return@post
        val ctx = service.createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val v = object : View(ctx) {
            override fun onGenericMotionEvent(e: MotionEvent) =
                e.isFromSource(InputDevice.SOURCE_JOYSTICK) || e.isFromSource(InputDevice.SOURCE_GAMEPAD)
        }.apply { isFocusable = true; isFocusableInTouchMode = true }
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSPARENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            wm.addView(v, lp)
            v.requestFocus()
            view = wm to v
            // Never left up: a missed release would steal the game's focus for good.
            main.removeCallbacks(cap); main.postDelayed(cap, 10_000)
        } catch (e: Exception) { Log.w("ThorShield", "raise: $e") }
    }

    private val cap = Runnable { lowerNow() }

    fun lower() = main.post { lowerNow() }

    private fun lowerNow() {
        main.removeCallbacks(cap)
        val (wm, v) = view ?: return
        view = null
        // Not "if attached": right after addView a view isn't attached yet (next frame), and a
        // quick Home tap lowers within that frame — the guard would leave the window up.
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
    }
}
