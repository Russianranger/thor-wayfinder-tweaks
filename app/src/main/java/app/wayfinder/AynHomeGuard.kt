package app.wayfinder

import android.content.Context
import android.provider.Settings

/**
 * AYN's "prevent pressing Home accidentally" option (Thor settings) makes Home need two
 * presses — it holds back the first one, so Wayfinder's Home combos (Home + R1 screenshot,
 * Home + Y input deck) can never see Home going down. We detect it and offer to turn it off.
 */
object AynHomeGuard {
    private const val KEY = "prevent_press_home_accidentally"

    fun isOn(ctx: Context): Boolean = Settings.System.getInt(ctx.contentResolver, KEY, 0) == 1

    /** Through the root bridge (a system setting of AYN's). Runs off the main thread. */
    fun turnOff(done: () -> Unit) = set(false, done)

    fun set(on: Boolean, done: () -> Unit) = Thread {
        PServiceBridge.exec("settings put system $KEY ${if (on) 1 else 0}")
        done()
    }.apply { isDaemon = true }.start()
}
