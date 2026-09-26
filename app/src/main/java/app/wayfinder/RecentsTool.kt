package app.wayfinder

import android.app.ActivityManager
import android.os.IBinder

/**
 * Entry point run in a bare VM via `app_process` as root (uid 0), the same way
 * as [RootMover]. Clears background app tasks the way the Recents "Clear all"
 * button does — removeTask over binder — but keeping a caller-supplied set of
 * packages (the launcher, this app, and whatever is currently on each screen).
 *
 * Invoked by [ForegroundAppService.clearBackgroundApps] as:
 *   ... app_process ... app.wayfinder.RecentsTool "pkgA,pkgB,pkgC"
 * Prints "OK removed=N kept=K" on success, "ERR ..." otherwise.
 */
object RecentsTool {

    private const val MAX = 500

    @JvmStatic
    fun main(args: Array<String>) {
        val keep = (args.getOrNull(0) ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as? IBinder
                ?: return fail("activity_task unavailable")
            val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!

            val getRecents = atm.javaClass.methods.firstOrNull { it.name == "getRecentTasks" }
                ?: return fail("getRecentTasks not found; " + sig(atm, "Recent"))
            // (maxNum int, flags int, userId int) — first int = maxNum, rest 0
            val gArgs = getRecents.parameterTypes.mapIndexed { i, t ->
                when {
                    t == Int::class.javaPrimitiveType && i == 0 -> MAX
                    t == Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }.toTypedArray()
            val raw = getRecents.invoke(atm, *gArgs)
            // getRecentTasks returns a ParceledListSlice — unwrap via getList()
            val list = when {
                raw is List<*> -> raw
                raw != null -> raw.javaClass.getMethod("getList").invoke(raw) as? List<*> ?: emptyList<Any?>()
                else -> emptyList<Any?>()
            }

            val removeTask = atm.javaClass.methods
                .firstOrNull { it.name == "removeTask" && it.parameterTypes.size == 1 }
                ?: return fail("removeTask not found; " + sig(atm, "remove"))

            var removed = 0
            var kept = 0
            val removedPkgs = StringBuilder()
            val keptPkgs = StringBuilder()
            for (rt in list) {
                val info = rt as? ActivityManager.RecentTaskInfo ?: continue
                val taskId = info.taskId
                if (taskId < 0) continue
                val pkg = info.baseActivity?.packageName
                    ?: info.topActivity?.packageName
                    ?: info.baseIntent?.component?.packageName
                    ?: info.baseIntent?.`package`
                if (pkg != null && pkg in keep) { kept++; keptPkgs.append(pkg).append(' '); continue }
                try { removeTask.invoke(atm, taskId); removed++; removedPkgs.append(pkg ?: "?").append(' ') } catch (_: Throwable) {}
            }
            println("OK removed=$removed kept=$kept | removed:[${removedPkgs.trim()}] kept:[${keptPkgs.trim()}]")
            halt(0)
        } catch (t: Throwable) {
            fail(chain(t))
        }
    }

    private fun sig(obj: Any, needle: String): String =
        obj.javaClass.methods.filter { it.name.contains(needle) }.joinToString("; ") { m ->
            "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
        }

    private fun chain(t: Throwable): String {
        val sb = StringBuilder(); var c: Throwable? = t
        while (c != null) { sb.append(c.javaClass.name).append(": ").append(c.message).append(" | "); c = c.cause }
        return sb.toString()
    }

    private fun fail(reason: String) { println("ERR $reason"); halt(1) }

    private fun halt(code: Int) {
        System.out.flush(); System.err.flush(); Runtime.getRuntime().halt(code)
    }
}
