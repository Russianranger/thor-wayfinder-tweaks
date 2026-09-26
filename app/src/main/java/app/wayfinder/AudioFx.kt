package app.wayfinder

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Installs Wayfinder's native audio effect (fx/wfwide.c → assets/fx/libwfwide.so, the
 * stereo widener) into the audio HAL — the only way to mix L and R, which no effect an
 * app can attach does. Same mechanism the DSP apps use on this ROM (verified 2026-09-23):
 *   - a tmpfs-backed copy of /vendor/lib64/soundfx with our .so added, bind-mounted over it;
 *   - the LIVE audio_effects.xml (whatever is mounted now — other apps' entries kept) with
 *     our library and effect entries, bind-mounted over it (SpeakerTune attaches the effect);
 *   - the audio HAL + audioserver restarted once (init brings them back; ~1–2 s of silence).
 * Nothing is written to /vendor: a reboot undoes it all. Once per boot, and only when the
 * speaker fix is on. The effect itself is driven by `persist.wayfinder.wide` (0 = neutral).
 */
object AudioFx {
    private const val TAG = "ThorAudioFx"
    @Volatile var installed = false
        private set

    /** Blocking (root + an audio restart): call off the main thread. Returns true when in place. */
    @Synchronized fun ensureInstalled(ctx: Context): Boolean {
        if (installed) return true
        if (!PServiceBridge.isAvailable()) { Log.w(TAG, "no root bridge"); return false }
        val dir = ctx.filesDir
        val lib = File(dir, "libwfwide.so")
        runCatching { ctx.assets.open("fx/libwfwide.so").use { i -> lib.outputStream().use { i.copyTo(it) } } }
            .onFailure { Log.w(TAG, "no effect library in the APK: $it"); return false }
        lib.setReadable(true, false)
        dir.setExecutable(true, false)
        val cur = File(dir, "fx_cur.xml"); val path = File(dir, "fx_path.txt"); cur.delete(); path.delete()

        // 1. Which config the HAL reads, and a copy of it as it is NOW (already ours → done).
        val prep = File(dir, "fx_prep.sh")
        prep.writeText("""
            |X=/vendor/etc/audio/sku_$(getprop ro.board.platform)/audio_effects.xml
            |[ -f ${'$'}X ] || X=/vendor/etc/audio_effects.xml
            |[ -f ${'$'}X ] || { echo NOXML; exit 0; }
            |L=/vendor/lib64/soundfx/libwfwide.so
            |if grep -q libwfwide ${'$'}X; then
            |  cmp -s ${lib.absolutePath} ${'$'}L && { echo ALREADY; exit 0; }
            |  rm -f ${'$'}L; cp ${lib.absolutePath} ${'$'}L; chmod 644 ${'$'}L; chcon u:object_r:vendor_file:s0 ${'$'}L 2>/dev/null
            |  kill ${'$'}(pidof android.hardware.audio.service_64) ${'$'}(pidof android.hardware.audio.service) ${'$'}(pidof audioserver) 2>/dev/null
            |  echo UPDATED; exit 0
            |fi
            |cp ${'$'}X ${cur.absolutePath}; chmod 644 ${cur.absolutePath}
            |echo ${'$'}X > ${path.absolutePath}; chmod 644 ${path.absolutePath}
            |echo COPIED
            |""".trimMargin())
        val r1 = PServiceBridge.exec("sh ${prep.absolutePath}")?.trim()
        Log.i(TAG, "prep: $r1")
        // (UPDATED: a newer library replaced ours — rm + cp, a new file: the HAL keeps the old one
        // mapped until the restart the script just did. Never overwrite a mapped .so in place.)
        if (r1 == "ALREADY" || r1 == "UPDATED") { installed = true; return true }
        if (r1 != "COPIED") return false
        val xmlPath = path.readText().trim()
        var xml = cur.readText()
        if (!xml.contains("<libraries>") || !xml.contains("<effects>")) { Log.w(TAG, "unexpected effects config"); return false }

        // 2. Our entries: the library and the effect.
        xml = xml.replaceFirst("<libraries>", "<libraries>\n        <library name=\"wfwide\" path=\"libwfwide.so\"/>")
            .replaceFirst("<effects>", "<effects>\n        <effect name=\"wfwide\" library=\"wfwide\" uuid=\"$UUID\"/>")
        // (No post-process <apply>: SpeakerTune attaches it to the output mix itself — as a
        // post-process, AYN's AudioFlinger moved it to session 0 and then refused it.)
        val mine = File(dir, "fx_wf.xml"); mine.writeText(xml); mine.setReadable(true, false)

        // 3. Overlay, mount, restart audio. (Long → a script file: pservice drops long commands.)
        val mount = File(dir, "fx_mount.sh")
        mount.writeText("""
            |W=/dev/wayfinder_fx
            |X=$xmlPath
            |grep -q libwfwide ${'$'}X && { echo ALREADY; exit 0; }
            |mkdir -p ${'$'}W/soundfx
            |cp -a /vendor/lib64/soundfx/. ${'$'}W/soundfx/
            |cp ${lib.absolutePath} ${'$'}W/soundfx/libwfwide.so
            |cp ${mine.absolutePath} ${'$'}W/audio_effects.xml
            |chmod 755 ${'$'}W/soundfx; chmod 644 ${'$'}W/soundfx/* ${'$'}W/audio_effects.xml
            |chcon -R u:object_r:vendor_file:s0 ${'$'}W/soundfx 2>/dev/null
            |chcon u:object_r:vendor_configs_file:s0 ${'$'}W/audio_effects.xml 2>/dev/null
            |mount --bind ${'$'}W/soundfx /vendor/lib64/soundfx || { echo MOUNTFAIL; exit 0; }
            |mount --bind ${'$'}W/audio_effects.xml ${'$'}X || { umount /vendor/lib64/soundfx; echo MOUNTFAIL; exit 0; }
            |kill ${'$'}(pidof android.hardware.audio.service_64) ${'$'}(pidof android.hardware.audio.service) ${'$'}(pidof audioserver) 2>/dev/null
            |echo OK
            |""".trimMargin())
        val r2 = PServiceBridge.exec("sh ${mount.absolutePath}")?.trim()
        Log.i(TAG, "mount: $r2")
        installed = r2 == "OK" || r2 == "ALREADY"
        return installed
    }

    /** The widener's width, live (0 = neutral). */
    fun setWidth(w: Float) {
        // One worker, latest value wins (a slider drag fires dozens; separate threads could
        // land out of order and leave an older width).
        wantWidth = w
        widthWorker.execute {
            val v = wantWidth
            if (v == appliedWidth) return@execute
            appliedWidth = v
            PServiceBridge.exec("setprop persist.wayfinder.wide ${"%.2f".format(java.util.Locale.US, v)}")
        }
    }
    @Volatile private var wantWidth = 0f
    @Volatile private var appliedWidth = Float.NaN
    private val widthWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    const val UUID = "7f3a1c2f-51b4-4e8f-9a21-576179666e64"
    const val TYPE_UUID = "7f3a1c2e-51b4-4e8f-9a21-576179666e64"
}
