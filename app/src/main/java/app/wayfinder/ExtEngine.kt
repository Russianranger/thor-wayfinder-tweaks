package app.wayfinder

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Input layer, phase 3 — the buttons the app plays (docs/INPUT_LAYER_PLAN.md §6g). wfpad sends
 * their presses as "X" lines ([PadRemap.byApp]); here they become keyboard keys, mouse buttons,
 * Wayfinder actions, several pad buttons at once, typed key sequences, macros, long / double
 * presses and chords. Pad outputs go back to the game's pad as wfpad VIRTUAL presses
 * ("G p <code> <0|1>", silent while Home / Back are held).
 *
 * Own thread (timing must not wait for the UI). Everything it holds is tracked, so Home / Back,
 * a profile switch or the layer going away release all of it ([releaseAll]).
 */
object ExtEngine {
    private const val TAG = "ThorExt"
    const val CHORD_MS = 60L
    const val LONG_MS = 400L
    const val DOUBLE_MS = 250L
    const val TAP_MS = 60L
    const val TYPE_MS = 30L

    private val thread by lazy { HandlerThread("ext").apply { start() } }
    private val h by lazy { Handler(thread.looper) }
    private val main = Handler(Looper.getMainLooper())
    /** Token of everything scheduled here: [releaseAll] cancels it all at once. */
    private val TOK = Any()

    /** The profile of the app that has the controller (the service's 500 ms tick). */
    @Volatile var remap: PadRemap? = null
        private set
    /** Where keyboard keys go: the screen that has the controller. */
    @Volatile var display: () -> Int = { 0 }
    /** A Wayfinder action (run on the main thread). */
    @Volatile var perform: (ThorAction) -> Unit = {}
    private var screenOn = true

    fun setScreenOn(on: Boolean) { h.post {
        screenOn = on
        if (!on) reset()
        else for (d in remap?.stickDirections?.keys.orEmpty())
            if (stickAmounts[d.ordinal] > StickMotion.MOUSE_DEAD) stickRearm.add(d)
    } }

    fun onExt(code: Int, value: Int) { h.post { handle(code, value) } }

    /** Profile changes and key releases are ordered together on the input thread. A new key
     *  target can share the old native direction mask, so compare the entire mapping too. */
    fun follow(next: PadRemap?, force: Boolean = false) { h.post {
        if (force || remap != next) {
            reset()
            remap = next
            // Editing a target while its stick is held must not press the replacement key.
            for (d in next?.stickDirections?.keys.orEmpty())
                if (stickAmounts[d.ordinal] > StickMotion.MOUSE_DEAD) stickRearm.add(d)
        }
    } }

    fun onSticks(lx: Int, ly: Int, rx: Int, ry: Int) { h.post {
        stickAmounts = StickMotion.amounts(lx, ly, rx, ry)
        if (!screenOn || !PadLayerCtl.wanted || !PadLayerCtl.active || !InputMonitor.connected) {
            releaseSticks(); return@post
        }
        val mapping = remap?.stickDirections.orEmpty()
        for (d in StickDirection.values()) {
            val amount = stickAmounts[d.ordinal]
            if (d in stickRearm) {
                if (amount <= StickMotion.MOUSE_DEAD) stickRearm.remove(d)
                else continue
            }
            val t = mapping[d]
            val held = stickKeys[d]
            val want = t as? RemapTarget.Key
            val down = want != null && PadRemap.isStickTarget(want) && StickMotion.keyHeld(held == want, amount)
            if (held != null && (!down || held != want)) {
                key(held.code, false, held.meta); stickKeys.remove(d)
            }
            if (down && stickKeys[d] == null) {
                key(want!!.code, true, want.meta); stickKeys[d] = want
            }
            if (t is RemapTarget.Mouse && t.b in 5..8) {
                val strength = StickMotion.mouseAmount(amount)
                if (strength > 0f) stickMouse[d] = t.b to strength else stickMouse.remove(d)
            } else stickMouse.remove(d)
        }
        updateMouseMotion()
    } }

    private var stickAmounts = FloatArray(8)
    private val stickKeys = HashMap<StickDirection, RemapTarget.Key>()
    private val stickMouse = HashMap<StickDirection, Pair<Int, Float>>()
    private val stickRearm = HashSet<StickDirection>()

    private fun releaseSticks() {
        for (t in stickKeys.values) key(t.code, false, t.meta)
        stickKeys.clear(); stickMouse.clear()
        updateMouseMotion()
    }

    // ── hold-to-shift (§6l): while the profile's shift button is held, wfpad withholds everything
    // from the game and echoes it ("J" lines); a button with a "With Shift held" job plays it here.
    private var shiftHeld = false
    private val shiftActive = HashMap<ThorButton, RemapTarget>()
    private val shiftHint = Runnable { if (shiftHeld) showShiftHint() }
    fun onGated(code: Int, value: Int) { h.post { gated(code, value) } }
    private fun gated(code: Int, value: Int) {
        if (value == 2 || !screenOn) return
        val b = PadRemap.BY_CODE[code] ?: return
        val r = remap ?: return
        val sh = r.shift
        if (b == sh) {
            if (value != 0 && !shiftHeld) reset()
            shiftHeld = value != 0
            h.removeCallbacks(shiftHint)
            if (shiftHeld) h.postDelayed(shiftHint, 450) else ForegroundAppService.hideShiftHint()
            return
        }
        if (value == 1) {
            if (!shiftHeld || sh == null) return
            val t = r.shifted[b] ?: return
            h.removeCallbacks(shiftHint); ForegroundAppService.hideShiftHint()
            shiftActive[b] = t; out(t, true, 0x2000 + code)
        } else shiftActive.remove(b)?.let { out(it, false, 0x2000 + code) }   // its own release ends it
    }
    private fun showShiftHint() {
        val r = remap ?: return
        val sh = r.shift ?: return
        if (r.shifted.isEmpty()) return
        ForegroundAppService.showShiftHint(sh.spoken, r.shifted.map { (k, v) -> k.spoken to v.short() })
    }
    /** Home / Back pressed, a new profile, the layer off: stop macros, release everything. */
    fun releaseAll() { h.post { reset() } }

    /** Injected keyboard events survive a helper crash. Retry their releases on the new
     *  helper before it can deliver another controller event. */
    fun helperConnected() { h.post {
        for ((code, screen) in pendingKeyUps.toMap())
            if (InputMonitor.send("K $code 0 0 $screen")) pendingKeyUps.remove(code)
    } }

    // ── what's held (reference counts: two buttons may hold the same output) ──
    private val padDown = HashMap<Int, Int>()
    private val keyDown = LinkedHashMap<Int, Int>()
    private val mouseDown = HashMap<Int, Int>()
    private val mouseMoveDown = HashMap<Int, Int>()
    private var mouseMoving = false
    private var mouseAt = 0L
    private var mouseX = 0f
    private var mouseY = 0f

    private fun mouseVelocity(): Pair<Float, Float> {
        val amounts = FloatArray(4)
        for (b in mouseMoveDown.keys) amounts[b - 5] = 1f
        for ((b, amount) in stickMouse.values) amounts[b - 5] += amount
        return StickMotion.mouseVector(amounts[0], amounts[1], amounts[2], amounts[3])
    }

    private fun updateMouseMotion() {
        val (x, y) = mouseVelocity()
        if (x == 0f && y == 0f) {
            h.removeCallbacks(mouseTick); mouseMoving = false; mouseX = 0f; mouseY = 0f
        } else if (!mouseMoving) {
            mouseMoving = true; mouseAt = SystemClock.uptimeMillis()
            h.postDelayed(mouseTick, TOK, 16)
        }
    }

    /** A stationary, held stick does not produce more evdev events: integrate its latest
     *  deflection at 60 Hz, carrying fractional pixels for smooth low-speed movement. */
    private val mouseTick = object : Runnable {
        override fun run() {
            if (!mouseMoving) return
            if (!screenOn || !InputMonitor.connected || !PadLayerCtl.wanted || !PadLayerCtl.active) { reset(); return }
            val now = SystemClock.uptimeMillis()
            val seconds = (now - mouseAt).coerceIn(0L, 50L) / 1000f
            mouseAt = now
            val (x, y) = mouseVelocity()
            mouseX += x * StickMotion.PIXELS_PER_SECOND * seconds
            mouseY += y * StickMotion.PIXELS_PER_SECOND * seconds
            val dx = mouseX.toInt(); val dy = mouseY.toInt()
            if (dx != 0 || dy != 0) {
                if (!InputMonitor.send("M $dx $dy")) { reset(); return }
                mouseX -= dx; mouseY -= dy
            }
            h.postDelayed(this, TOK, 16)
        }
    }

    private fun later(ms: Long, r: () -> Unit) { h.postDelayed(r, TOK, ms) }

    private fun ref(m: MutableMap<Int, Int>, c: Int, down: Boolean): Boolean {
        val n = m[c] ?: 0
        return if (down) { m[c] = n + 1; n == 0 } else if (n > 0) { if (n == 1) m.remove(c) else m[c] = n - 1; n == 1 } else false
    }
    private fun pad(b: ThorButton, down: Boolean) {
        val c = PadRemap.outCode(b) ?: return
        if (ref(padDown, c, down)) InputMonitor.send("G p $c ${if (down) 1 else 0}")
    }
    private fun meta() = keyDown.keys.fold(0) { m, c -> m or RemapTarget.metaOf(c) }
    private val keyDisplay = HashMap<Int, Int>()
    private val pendingKeyUps = HashMap<Int, Int>()
    private fun key(code: Int, down: Boolean, extraMeta: Int = 0) {
        if (!ref(keyDown, code, down)) return
        // the up goes where the down went: a touch on the other screen in between left it held (review 2026-09-25)
        val d = if (down) display().also { keyDisplay[code] = it } else keyDisplay.remove(code) ?: display()
        if (!InputMonitor.send("K $code ${if (down) 1 else 0} ${meta() or extraMeta or (if (down) 0 else RemapTarget.metaOf(code))} $d") && !down)
            pendingKeyUps[code] = d
    }
    private fun mouse(b: Int, down: Boolean) {
        if (b in 5..8) { ref(mouseMoveDown, b, down); updateMouseMotion(); return }
        if (b in 3..4) { if (down) InputMonitor.send("W ${if (b == 3) 1 else -1} 0"); return }
        if (b !in 0..2) return
        if (ref(mouseDown, b, down)) InputMonitor.send("B $b ${if (down) 1 else 0}")
    }

    /** Press / release one output. [who] = the source (a button code, or a chord's id). */
    private fun out(t: RemapTarget, down: Boolean, who: Int) {
        when (t) {
            is RemapTarget.Button -> pad(t.b, down)
            is RemapTarget.Buttons -> (if (down) t.bs else t.bs.reversed()).forEach { pad(it, down) }
            RemapTarget.None -> {}
            is RemapTarget.Key -> key(t.code, down, t.meta)
            is RemapTarget.Keys -> if (t.inOrder) { if (down) type(t.codes) }
                else (if (down) t.codes else t.codes.reversed()).forEach { key(it, down) }
            is RemapTarget.Mouse -> mouse(t.b, down)
            is RemapTarget.Action -> if (down) main.post { perform(t.a) }
            is RemapTarget.Macro -> if (!t.armed) {}                       // imported, not turned on yet
                else if (down) play(who, t) else plays[who]?.held = false
        }
    }
    private fun tap(t: RemapTarget, who: Int) { out(t, true, who); later(TAP_MS) { out(t, false, who) } }

    /** Keys typed one after another. */
    private fun type(codes: List<Int>) {
        var at = 0L
        for (c in codes) { later(at) { key(c, true) }; later(at + TYPE_MS) { key(c, false) }; at += 2 * TYPE_MS }
    }

    // ── macros ──
    private class Play(val m: RemapTarget.Macro) { var held = true; var step = 0 }
    private val plays = HashMap<Int, Play>()

    private fun play(who: Int, m: RemapTarget.Macro) {
        plays[who]?.let { it.held = true; return }          // already running: keep it going
        val p = Play(m); plays[who] = p
        fun next() {
            if (plays[who] !== p) return
            if (p.step >= m.steps.size) {
                if (m.repeat && p.held) p.step = 0 else { plays.remove(who); return }
            }
            val s = m.steps[p.step++]
            out(s.out, true, who)
            later(s.hold.toLong()) { if (plays[who] === p) out(s.out, false, who); later(s.gap.toLong()) { next() } }
        }
        next()
    }

    // ── the sources ──
    private class Src {
        var active: RemapTarget? = null         // what its press holds (normal / turbo)
        var latched: RemapTarget? = null        // toggle
        var pendingChord: Runnable? = null
        var chord: Chord? = null
        var swallow = false
        var longFired = false
        var holdTimer: Runnable? = null         // long press, or a double-press button held
        var holding = false                     // double-press button held past the window
        var dblWait: Runnable? = null
        var dblActive = false
        var turbo: Runnable? = null
    }
    private val srcs = HashMap<ThorButton, Src>()

    private fun cancel(r: Runnable?) { if (r != null) h.removeCallbacks(r) }
    private fun post(ms: Long, f: () -> Unit): Runnable = Runnable(f).also { h.postDelayed(it, TOK, ms) }

    private fun handle(code: Int, value: Int) {
        if (value == 2 || !screenOn) return
        if (BuildConfig.DEBUG) Log.d(TAG, "X $code $value")
        val b = PadRemap.BY_CODE[code] ?: return
        val r = remap ?: PadRemap()
        // a release whose press was forgotten by reset(): nothing to finish (it fired a spurious
        // tap of the button's output — review 2026-09-25)
        if (value == 0 && srcs[b] == null) return
        val s = srcs.getOrPut(b) { Src() }
        if (value == 1) {
            val chord = r.chords.firstOrNull { it.has(b) }
            if (chord != null) {
                val other = if (chord.a == b) chord.b else chord.a
                val o = srcs[other]
                if (o?.pendingChord != null) {                   // the pair, within the window
                    cancel(o.pendingChord); o.pendingChord = null
                    o.chord = chord; s.chord = chord
                    out(chord.target, true, chordId(r, chord))
                    return
                }
                s.pendingChord = post(CHORD_MS) { s.pendingChord = null; start(b, s, r) }
                return
            }
            start(b, s, r)
        } else {
            s.chord?.let { ch ->                                 // a chord ends with its first release
                s.chord = null
                val o = srcs[if (ch.a == b) ch.b else ch.a]
                if (o?.chord === ch) { o.chord = null; o.swallow = true }
                out(ch.target, false, chordId(r, ch))
                return
            }
            if (s.swallow) { s.swallow = false; return }
            s.pendingChord?.let { cancel(it); s.pendingChord = null; start(b, s, r) }   // a quick tap
            stop(b, s, r)
        }
    }
    private fun chordId(r: PadRemap, c: Chord) = 0x1000 + r.chords.indexOf(c).coerceAtLeast(0)

    private fun mainOf(b: ThorButton, r: PadRemap) = r.buttons[b] ?: RemapTarget.Button(b)
    private fun altOf(b: ThorButton, r: PadRemap) = r.alt[b] ?: RemapTarget.None

    private fun start(b: ThorButton, s: Src, r: PadRemap) {
        val who = PadRemap.CODE[b] ?: 0
        val t = mainOf(b, r)
        when (r.fire[b] ?: Fire.NORMAL) {
            Fire.NORMAL -> { s.active = t; out(t, true, who) }
            Fire.TOGGLE -> s.latched?.let { out(it, false, who); s.latched = null } ?: run { s.latched = t; out(t, true, who) }
            Fire.TURBO -> {
                s.active = t
                val half = (500L / r.turboHz.coerceIn(2, 30)).coerceAtLeast(10)
                var on = true
                out(t, true, who)
                s.turbo = object : Runnable { override fun run() {
                    on = !on; out(t, on, who); h.postDelayed(this, TOK, half)
                } }.also { h.postDelayed(it, TOK, half) }
            }
            Fire.LONG -> { s.longFired = false; s.holdTimer = post(LONG_MS) { s.holdTimer = null; s.longFired = true; out(altOf(b, r), true, who) } }
            Fire.DOUBLE -> {
                val w = s.dblWait
                if (w != null) { cancel(w); s.dblWait = null; s.dblActive = true; out(altOf(b, r), true, who) }
                else s.holdTimer = post(DOUBLE_MS) { s.holdTimer = null; s.holding = true; out(t, true, who) }
            }
        }
    }

    private fun stop(b: ThorButton, s: Src, r: PadRemap) {
        val who = PadRemap.CODE[b] ?: 0
        cancel(s.turbo); s.turbo = null
        s.active?.let { out(it, false, who); s.active = null }
        when (r.fire[b] ?: Fire.NORMAL) {
            Fire.LONG -> if (s.longFired) { s.longFired = false; out(altOf(b, r), false, who) }
                else { cancel(s.holdTimer); s.holdTimer = null; tap(mainOf(b, r), who) }
            Fire.DOUBLE -> when {
                s.dblActive -> { s.dblActive = false; out(altOf(b, r), false, who) }
                s.holding -> { s.holding = false; out(mainOf(b, r), false, who) }
                else -> { cancel(s.holdTimer); s.holdTimer = null
                    s.dblWait = post(DOUBLE_MS) { s.dblWait = null; tap(mainOf(b, r), who) } }
            }
            else -> {}
        }
    }

    private fun reset() {
        h.removeCallbacksAndMessages(TOK)
        mouseMoving = false; mouseX = 0f; mouseY = 0f
        mouseMoveDown.clear(); stickKeys.clear(); stickMouse.clear(); stickRearm.clear()
        shiftHeld = false; shiftActive.clear(); h.removeCallbacks(shiftHint)
        plays.clear(); srcs.clear()
        if (padDown.isNotEmpty()) InputMonitor.send("G p 0 0")
        padDown.clear()
        for (c in keyDown.keys.reversed()) {
            val screen = keyDisplay[c] ?: display()
            if (!InputMonitor.send("K $c 0 0 $screen")) pendingKeyUps[c] = screen
        }
        keyDown.clear(); keyDisplay.clear()
        for (b in mouseDown.keys) InputMonitor.send("B $b 0")
        mouseDown.clear()
        if (BuildConfig.DEBUG) Log.d(TAG, "released everything")
    }
}
