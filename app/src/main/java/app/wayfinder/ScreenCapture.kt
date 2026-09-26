package app.wayfinder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File

/**
 * Fast per-display screenshots via root `screencap` (through pservice). Unlike
 * the accessibility takeScreenshot API this has no ~333ms rate limit — both
 * screens capture in ~150ms total — and returns each display already in its
 * correct on-screen orientation and exact pixel size.
 *
 * `screencap -d` needs the PHYSICAL display id, which is embedded in the logical
 * Display's uniqueId ("local:<physicalId>"). Output PNG is written into the app's
 * own cache dir (root writes it, chmod 666 so this app can read it back), decoded,
 * then deleted.
 */
object ScreenCapture {

    private const val TAG = "ThorCapture"

    /** True if a capture backend is available (root via pservice). */
    fun isAvailable(): Boolean = PServiceBridge.isAvailable()

    /** The physical display id `screencap -d` wants (from the hidden Display.getUniqueId). */
    fun physicalId(display: Display): String? = runCatching {
        (HiddenApiBypass.invoke(Display::class.java, display, "getUniqueId") as? String)?.removePrefix("local:")
    }.getOrNull()

    /** Screenshot each [displayIds] entry; returns only the ones that succeeded. */
    fun captureDisplays(context: Context, displayIds: List<Int>): Map<Int, Bitmap> {
        val dm = context.getSystemService(DisplayManager::class.java)
        val out = HashMap<Int, Bitmap>()
        for (id in displayIds) {
            val display = dm.getDisplay(id) ?: continue
            // Display.getUniqueId() is @hide ("local:<physicalId>"); screencap -d needs
            // that physical id. Read it through the hidden-API bypass.
            val phys = try {
                (HiddenApiBypass.invoke(Display::class.java, display, "getUniqueId") as? String)
                    ?.removePrefix("local:")
            } catch (t: Throwable) {
                Log.w(TAG, "uniqueId for display $id: ${t.message}"); null
            } ?: continue
            // A public virtual display's id is chosen by the app that made it — digits only.
            if (!Shell.isPhysId(phys)) { Log.w(TAG, "display $id: not a physical id"); continue }
            val file = File(context.cacheDir, "thorcap_$id.png")
            try {
                val path = file.absolutePath
                // Hand the file to this app (not chmod 666: nobody else needs to read it).
                val uid = android.os.Process.myUid()
                val res = PServiceBridge.exec("screencap -d $phys -p ${Shell.q(path)}; chown $uid:$uid ${Shell.q(path)}")
                if (res == null) { Log.w(TAG, "screencap display $id: no backend"); continue }
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp != null) out[id] = bmp else Log.w(TAG, "decode failed for display $id")
            } catch (e: Exception) {
                Log.w(TAG, "capture display $id: ${e.message}")
            } finally {
                file.delete()
            }
        }
        return out
    }
}
