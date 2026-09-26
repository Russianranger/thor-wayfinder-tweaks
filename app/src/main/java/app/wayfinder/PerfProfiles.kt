package app.wayfinder

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors

/** AYN's performance modes (`persist.vendor.debug.mode`). */
enum class PerfMode(val value: Int, val label: String) { STANDARD(0, "Standard"), MEDIUM(1, "Medium"), HIGH(2, "High") }

/** AYN's fan modes (`Settings.System fan_mode`). */
enum class FanMode(val value: Int, val label: String) { OFF(0, "Off"), QUIET(1, "Quiet"), SMART(4, "Smart"), SPORTS(5, "Sports") }

/**
 * #21 Per-app performance and fan. The apps on screen can ask for their own performance /
 * fan mode (two apps: the most demanding request wins); leaving puts back the user's own AYN
 * settings ([baseline], remembered in prefs in case we die mid-game).
 *
 * How AYN applies them (verified 2026-09-23, docs/THOR_PLATFORM_NOTES.md):
 *  - performance: prop `persist.vendor.debug.mode` 0/1/2 (CPU / GPU minimum clocks).
 *    AYN's game assistant re-sets it from `performance_mode` on every app change, so
 *    we write both (and again shortly after, to win that race).
 *  - fan: `settings put system fan_mode` — AYN's settings app drives the fan from it.
 */
object PerfProfiles {
    private const val TAG = "ThorPerf"
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var active: Triple<Int?, Int?, Int?> = Triple(null, null, null)   // what we applied (perf, fan, Hz)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("thor_perf", Context.MODE_PRIVATE)

    /** Something else changed the mode behind our back (the lid heat guard): apply again next time. */
    fun forget() { active = Triple(-1, -1, -1) }

    /** "Keep for this game" in the panel: the values from BEFORE the user changed them there are
     *  their usual ones — remembered now if no override has recorded them yet. */
    fun setBaselineIfMissing(ctx: Context, perf: Int, fan: Int, minHz: Float, peakHz: Float) = worker.execute {
        val p = prefs(ctx)
        if (!p.contains("base_perf")) p.edit().putInt("base_perf", perf).putInt("base_fan", fan).commit()
        if (!p.contains("base_peak")) p.edit().putFloat("base_min", minHz).putFloat("base_peak", peakHz).commit()
    }

    /** Apply [perf]/[fan]/[hz] (null = the user's own setting). Off the main thread. */
    fun apply(ctx: Context, perf: PerfMode?, fan: FanMode?, hz: Int? = null) {
        val want = Triple(perf?.value, fan?.value, hz)
        if (want == active) return
        active = want
        worker.execute {
            val p = prefs(ctx)
            val cr = ctx.contentResolver
            // Remember the user's own values the moment an override starts.
            // Both, even for a performance-only override: AYN's perf→fan link can move the fan.
            if ((want.first != null || want.second != null) && !p.contains("base_perf"))
                p.edit().putInt("base_perf", Settings.System.getInt(cr, "performance_mode", 0))
                    .putInt("base_fan", Settings.System.getInt(cr, "fan_mode", 4)).commit()
            // refresh rate: its own baseline (min and peak: "adaptive" is min 60 / peak 120)
            if (want.third != null && !p.contains("base_peak"))
                p.edit().putFloat("base_min", runCatching { Settings.System.getFloat(cr, "min_refresh_rate", 60f) }.getOrDefault(60f))
                    .putFloat("base_peak", runCatching { Settings.System.getFloat(cr, "peak_refresh_rate", 60f) }.getOrDefault(60f)).commit()
            if (want.third != null) PServiceBridge.exec("settings put system min_refresh_rate ${want.third}.0; settings put system peak_refresh_rate ${want.third}.0")
            else if (p.contains("base_peak")) {
                PServiceBridge.exec("settings put system min_refresh_rate ${p.getFloat("base_min", 60f)}; settings put system peak_refresh_rate ${p.getFloat("base_peak", 60f)}")
                p.edit().remove("base_min").remove("base_peak").apply()
            }
            val perfV = want.first ?: p.getInt("base_perf", -1).takeIf { it >= 0 }
            val fanV = want.second ?: p.getInt("base_fan", -1).takeIf { it >= 0 }
            // AYN links them: the performance SETTING changing to Standard makes AYN set the
            // fan to Quiet (its "quick set performance and fan" option, on by default —
            // verified 2026-09-23). So performance first, let AYN react, THEN the fan, and
            // check the fan stuck.
            val perfChanges = perfV != null && Settings.System.getInt(cr, "performance_mode", -1) != perfV
            perfV?.let { PServiceBridge.exec("settings put system performance_mode $it; setprop persist.vendor.debug.mode $it") }
            if (fanV != null) {
                if (perfChanges) Thread.sleep(1200)
                repeat(3) {
                    if (Settings.System.getInt(cr, "fan_mode", -1) == fanV) return@repeat
                    PServiceBridge.exec("settings put system fan_mode $fanV")
                    Thread.sleep(700)
                }
            }
            if (want.first == null && want.second == null) p.edit().remove("base_perf").remove("base_fan").apply()
            Log.d(TAG, "applied perf=${want.first ?: "user($perfV)"} fan=${want.second ?: "user($fanV)"} hz=${want.third ?: "user"}")
            // AYN's assistant re-applies its own performance value on app changes.
            if (want.first != null) {
                Thread.sleep(1500)
                if (active == want) PServiceBridge.exec("setprop persist.vendor.debug.mode ${want.first}")
            }
        }
    }

    /** Service start: undo a leftover override (we may have died mid-game). */
    fun restoreIfLeftOver(ctx: Context) {
        val p = prefs(ctx)
        if (p.contains("base_perf") || p.contains("base_fan") || p.contains("base_peak")) { forget(); apply(ctx, null, null, null) }
    }
}
