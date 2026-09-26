package app.wayfinder

import android.content.Context
import android.content.pm.ApplicationInfo

/** Is an app a game? Android marks real games itself; emulators and launchers often aren't. */
object GameApps {
    private val PREFIXES = listOf(
        "org.dolphinemu", "org.ppsspp", "org.azahar", "org.citra", "io.github.lime3ds", "com.retroarch", "dev.eden",
        "org.yuzu", "info.cemu", "me.magnum.melon", "com.github.stenzek.duckstation", "xyz.aethersx2", "xyz.nethersx2",
        "net.pcsx2", "org.vita3k", "app.gamenative", "com.winlator", "org.mupen64plusae", "com.flycast", "io.recompiled",
        "com.dsemu.drastic", "io.github.mgba", "aenu.aps3e", "com.valvesoftware", "com.nvidia.geforcenow", "com.mojang",
        "rip.moth.cocoon", "com.explusalpha", "org.scummvm", "com.antutu",
    )

    @Suppress("DEPRECATION")
    fun isGame(info: ApplicationInfo, pkg: String): Boolean =
        info.category == ApplicationInfo.CATEGORY_GAME || (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0 ||
            PREFIXES.any { pkg.startsWith(it) } || GameProfiles.forApp(pkg).isNotEmpty()

    fun isGame(ctx: Context, pkg: String): Boolean =
        runCatching { isGame(ctx.packageManager.getApplicationInfo(pkg, 0), pkg) }.getOrDefault(false)
}
