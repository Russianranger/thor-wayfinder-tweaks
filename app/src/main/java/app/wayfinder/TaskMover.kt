package app.wayfinder

import android.app.ActivityManager
import android.os.IBinder
import android.util.Log
import android.view.Display
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * True task moves via IActivityTaskManager.moveRootTaskToDisplay (Android 13+
 * signature verified on android13-release).
 *
 * Unlike `am start --display`, this reparents the LIVE task:
 *  - no new instance is ever created (no duplicates)
 *  - the app is not relaunched (state fully preserved)
 *  - launchMode is irrelevant (singleTask/singleInstance apps move too)
 *
 * Requires INTERNAL_SYSTEM_WINDOW, which the Shell package holds on
 * Android 13 — so this works through Shizuku (ADB or root mode). Everything
 * here is reflection on hidden APIs; every failure degrades gracefully to
 * the caller's `am start` / trampoline fallbacks.
 */
object TaskMover {

    private const val TAG = "ThorTaskMover"
    private const val MAX_TASKS = 200

    @Volatile private var bypassDone = false

    private fun ensureHiddenApiAccess(): Boolean {
        if (bypassDone) return true
        return try {
            HiddenApiBypass.addHiddenApiExemptions("")
            bypassDone = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Hidden API bypass failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    fun isUsable(): Boolean = ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission()

    // Fresh proxy per call: the Shizuku binder can die and reconnect between swaps
    private fun activityTaskManager(): Any? {
        val binder = SystemServiceHelper.getSystemService("activity_task") ?: return null
        val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(binder))
    }

    /**
     * Move [pkg]'s task to [targetDisplayId]. Returns false on any failure —
     * callers fall back to `am start` / trampoline.
     */
    fun moveTaskToDisplay(pkg: String, targetDisplayId: Int): Boolean {
        if (!isUsable() || !ensureHiddenApiAccess()) return false
        return try {
            val atm = activityTaskManager() ?: return false
            // Resolve by name — getTasks' parameter list drifts between ROMs
            // (AOSP 13 is (int,boolean,boolean); some vendor builds differ)
            val getTasks = atm.javaClass.methods.firstOrNull { it.name == "getTasks" } ?: return false
            val gArgs = getTasks.parameterTypes.mapIndexed { i, t ->
                when {
                    t == Int::class.javaPrimitiveType && i == 0 -> MAX_TASKS
                    t == Int::class.javaPrimitiveType -> -1
                    t == Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }.toTypedArray()
            val tasks = getTasks.invoke(atm, *gArgs) as? List<*> ?: return false
            val candidates = tasks.filterIsInstance<ActivityManager.RunningTaskInfo>().filter {
                it.baseActivity?.packageName == pkg || it.topActivity?.packageName == pkg
            }
            if (candidates.isEmpty()) {
                Log.d(TAG, "No running task found for $pkg")
                return false
            }
            // Prefer a task not already on the target display (getTasks is
            // most-recent-first, so ties resolve to the freshest task)
            val task = candidates.firstOrNull { displayIdOf(it) != targetDisplayId }
                ?: candidates.first()
            val move = atm.javaClass.methods.firstOrNull { it.name == "moveRootTaskToDisplay" }
                ?: return false
            move.invoke(atm, task.taskId, targetDisplayId)
            Log.i(TAG, "moveRootTaskToDisplay task=${task.taskId} ($pkg) → display $targetDisplayId")
            true
        } catch (t: Throwable) {
            // Throwable, not Exception: reflection can raise linkage errors
            if (BuildConfig.DEBUG) Log.w(TAG, "Task move failed for $pkg: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    // TaskInfo.displayId is @hide — best-effort read, INVALID_DISPLAY when unreadable (a fallback:
    // root moves come first; if the platform blocks the field this path just reports "unknown")
    private val displayIdField by lazy(LazyThreadSafetyMode.PUBLICATION) { findDisplayIdField() }

    @android.annotation.SuppressLint("BlockedPrivateApi")
    private fun findDisplayIdField(): java.lang.reflect.Field? = try {
        android.app.TaskInfo::class.java.getDeclaredField("displayId").apply { isAccessible = true }
    } catch (t: Throwable) {
        null
    }

    private fun displayIdOf(task: ActivityManager.RunningTaskInfo): Int {
        return try {
            displayIdField?.getInt(task) ?: Display.INVALID_DISPLAY
        } catch (t: Throwable) {
            Display.INVALID_DISPLAY
        }
    }
}
