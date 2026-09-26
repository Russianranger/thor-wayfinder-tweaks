package app.wayfinder

/**
 * Safety for everything that ends up in a ROOT shell (pservice, su, the helper protocol).
 * Security review 2026-09-24: a package name read from an app's own accessibility node is
 * set by that app — never trust it in a command. Validate, then quote.
 */
object Shell {
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val DIGITS = Regex("^\\d+$")

    /** A real Android package name (nothing a shell could interpret). */
    fun isPkg(s: String?): Boolean = s != null && s.length <= 255 && PKG.matches(s)

    /** A physical display id as `screencap -d` wants it: digits only. */
    fun isPhysId(s: String?): Boolean = s != null && s.length <= 32 && DIGITS.matches(s)

    private val COMPONENT = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+$")

    /** `pkg/class` as `am start -n` takes it, quoted — or null if it isn't a plain component
     *  name. Class names come from apps (an accessibility event's className, a HOME activity's
     *  name in `dumpsys`): one with a quote in it ran commands as root (review 2026-09-25). */
    fun component(s: String?): String? = s?.takeIf { it.length <= 400 && COMPONENT.matches(it) }?.let { q(it) }

    /** Single-quote for sh, whatever the content. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
