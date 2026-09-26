package app.wayfinder

import android.content.Context
import android.util.Log

/**
 * Shell operations via `su` (uid 0) for rooted devices (e.g. a Thor rooted
 * with Magisk through its "Run Script as Root" settings feature).
 *
 * Root replaces Shizuku entirely: same `am` commands, but no companion app
 * and no per-boot restart dance. All methods are blocking — call from a
 * background thread.
 */
object RootHelper {

    private const val TAG = "ThorRoot"
    private const val EXEC_TIMEOUT_SECONDS = 10L

    // Longer than the exec timeout: the first-ever su call pops the Magisk
    // grant dialog, which auto-denies after ~10s if the user doesn't react.
    private const val PROBE_TIMEOUT_SECONDS = 20L

    @Volatile private var suChecked = false
    @Volatile private var suAvailable = false

    /** Non-blocking read of the cached probe result (false until probed). */
    fun cachedAvailable(): Boolean = suChecked && suAvailable

    /** True once a probe has completed (successfully or not). */
    fun probed(): Boolean = suChecked

    /** Kick off the (blocking) probe on a background thread if not done yet. */
    fun probeAsync() {
        if (suChecked) return
        Thread { isAvailable() }.apply { isDaemon = true }.start()
    }

    /**
     * True if `su` works and grants uid 0. Blocking on first call (may show
     * the root manager's grant dialog); cached afterwards.
     */
    fun isAvailable(): Boolean {
        if (!suChecked) {
            synchronized(this) {
                if (!suChecked) {
                    suAvailable = probeSu()
                    suChecked = true
                    Log.d(TAG, "su probe → available=$suAvailable")
                }
            }
        }
        return suAvailable
    }

    private fun probeSu(): Boolean {
        return try {
            val (exit, out) = exec("id", PROBE_TIMEOUT_SECONDS)
            exit == 0 && out.contains("uid=0")
        } catch (e: Exception) {
            // No su binary (not rooted) lands here as an instant IOException
            false
        }
    }

    private fun exec(command: String, timeoutSeconds: Long = EXEC_TIMEOUT_SECONDS): Pair<Int, String> {
        val process = ProcessBuilder("su", "-c", command).start()
        return runProcessWithTimeout(process, "su -c $command", timeoutSeconds)
    }

    /** `am force-stop <pkg>` as root. */
    fun forceStop(pkg: String): Boolean {
        if (!Shell.isPkg(pkg)) return false
        return try {
            val (exit, _) = exec("am force-stop ${Shell.q(pkg)}")
            Log.d(TAG, "su am force-stop $pkg → exit=$exit")
            exit == 0
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "su am force-stop $pkg failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Run one of the app's app_process entry points as root via su. Returns stdout or null. */
    fun runEntryPoint(context: Context, simpleClassName: String, vararg args: String): String? {
        val apk = context.applicationInfo.sourceDir
        val a = args.joinToString(" ") { Shell.q(it) }
        return try {
            val (_, out) = exec(
                "CLASSPATH='$apk' app_process /system/bin app.wayfinder.$simpleClassName $a 2>&1"
            )
            out.trim()
        } catch (e: Exception) {
            Log.w(TAG, "runEntryPoint $simpleClassName failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Best root path: reparent the live task via binder by spawning [RootMover]
     * in an app_process VM as root. No relaunch (state preserved), no new
     * instance (no duplicates), works on singleTask apps — the Shizuku-quality
     * move, without Shizuku. Returns false on any failure so the caller can fall
     * back to `am start` / trampoline.
     */
    fun moveTaskViaBinder(context: Context, pkg: String, displayId: Int, fromDisplay: Int? = null): Boolean {
        val out = if (fromDisplay == null) runEntryPoint(context, "RootMover", pkg, displayId.toString())
            else runEntryPoint(context, "RootMover", pkg, displayId.toString(), fromDisplay.toString())
        Log.d(TAG, "RootMover $pkg → display $displayId: $out")
        return out?.contains("OK") == true
    }

    /** Bring the launcher/home to the top of [displayId] as root. */
    fun goHomeOnDisplay(displayId: Int): Boolean {
        return try {
            val (exit, _) = exec(
                "am start --display $displayId " +
                    "-a android.intent.action.MAIN -c android.intent.category.HOME"
            )
            exit == 0
        } catch (e: Exception) {
            Log.w(TAG, "su home on display $displayId failed: ${e.message}")
            false
        }
    }

    /** `am start --display <id> -n <component>` as root. */
    fun startOnDisplay(context: Context, pkg: String, displayId: Int): Boolean {
        val component = context.packageManager.getLaunchIntentForPackage(pkg)?.component
        if (component == null) {
            if (BuildConfig.DEBUG) Log.w(TAG, "No launch component for $pkg")
            return false
        }
        return try {
            // Single-quoted: component class names may contain '$'
            val (exit, stdout) = exec(
                "am start --display $displayId -n " + (Shell.component(component.flattenToString()) ?: return false)
            )
            Log.d(TAG, "su am start --display $displayId $pkg → exit=$exit" +
                if (stdout.isNotBlank()) " ($stdout)" else "")
            exit == 0
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "su am start $pkg failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}
