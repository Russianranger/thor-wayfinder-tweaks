package app.wayfinder.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The right stick's position (-1..1 each axis), fed by MainActivity. */
object RightStick {
    var pos by mutableStateOf(Offset.Zero)
        private set
    fun set(x: Float, y: Float) { pos = Offset(x, y) }
}

/** Fraction of the radius where colour reaches full saturation; the band outside it is pure colour. */
private const val FULL_SAT = 0.72f

/**
 * A hue/saturation colour wheel: touch or drag to pick. RIGHT STICK (while the wheel
 * is focused): point it at a colour — direction = hue, how far you push = saturation. With the controller: focus it,
 * press A to start moving the picker with the D-pad / left stick (←/→ around the wheel,
 * ↑/↓ out to the rim or in to white), A or B to finish — so the pad never gets stuck
 * inside the wheel. [argb] in, [onPick] out (full brightness; brightness is its own slider).
 */
@Composable
fun ColorWheel(argb: Int, size: Dp, onPick: (Int) -> Unit) {
    val g = LocalGlass.current
    val hsv = remember(argb) { FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) } }
    val latestPick by androidx.compose.runtime.rememberUpdatedState(onPick)
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(if (editing) 1f else if (focused) 0.8f else 0f, label = "ring")
    val grow by animateFloatAsState(if (focused) 1.04f else 1f, label = "grow")
    val pad = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    fun pick(h: Float, s: Float) = latestPick(android.graphics.Color.HSVToColor(floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), 1f)))
    fun pickAt(o: Offset, w: Float) {
        val c = w / 2f - pad
        val dx = o.x - w / 2f; val dy = o.y - w / 2f
        pick(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat(), hypot(dx, dy) / (c * FULL_SAT))
    }
    // Right stick PUSHES the puck from where it is (like a cursor), while focused.
    // The puck keeps its own position so moving through the pure-colour band is smooth.
    var puck by remember { mutableStateOf<Offset?>(null) }
    androidx.compose.runtime.LaunchedEffect(focused) {
        if (!focused) { puck = null; return@LaunchedEffect }
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                val s = RightStick.pos
                val mag = hypot(s.x, s.y)
                if (mag > 0.15f) {
                    val start = puck ?: run {
                        val a = Math.toRadians(hsv[0].toDouble())
                        val f = if (hsv[1] >= 0.999f) (1f + FULL_SAT) / 2f else hsv[1] * FULL_SAT
                        Offset((cos(a) * f).toFloat(), (sin(a) * f).toFloat())
                    }
                    val speed = 0.9f * ((mag - 0.15f) / 0.85f)   // radius per second at full tilt
                    var x = start.x + s.x / mag * speed * dt
                    var y = start.y + s.y / mag * speed * dt
                    val len = hypot(x, y)
                    if (len > 1f) { x /= len; y /= len }
                    puck = Offset(x, y)
                    pick(Math.toDegrees(atan2(y, x).toDouble()).toFloat(), hypot(x, y) / FULL_SAT)
                }
            }
        }
    }
    Box(
        Modifier.size(size)
            .graphicsLayerScale(grow)
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) editing = false }
            .onKeyEvent { e ->
                val select = e.key == Key.ButtonA || e.key == Key.DirectionCenter || e.key == Key.Enter
                if (select || (editing && (e.key == Key.ButtonB || e.key == Key.Back || e.key == Key.Escape))) {
                    if (e.type == KeyEventType.KeyUp) editing = select && !editing
                    return@onKeyEvent true
                }
                if (!editing || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { pick(hsv[0] - 6f, hsv[1]); true }
                    Key.DirectionRight -> { pick(hsv[0] + 6f, hsv[1]); true }
                    Key.DirectionUp -> { pick(hsv[0], hsv[1] + 0.08f); true }
                    Key.DirectionDown -> { pick(hsv[0], hsv[1] - 0.08f); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures { puck = null; pickAt(it, this.size.width.toFloat()) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> puck = null; pickAt(change.position, this.size.width.toFloat()) }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val r = this.size.minDimension / 2f - pad
            // Focus ring OUTSIDE the wheel (on top of it, an accent ring vanished into the blues).
            if (ring > 0.01f) {
                drawCircle(Color.White.copy(alpha = ring), r + 6.dp.toPx(), style = Stroke(5.dp.toPx()))
                drawCircle(g.accent.copy(alpha = ring), r + 6.dp.toPx(), style = Stroke(2.5.dp.toPx()))
            }
            val hues = (0..12).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 30f, 1f, 1f))) }
            drawCircle(Brush.sweepGradient(hues), r)
            // White only in the middle: the outer band is fully saturated colour.
            drawCircle(Brush.radialGradient(0f to Color.White, FULL_SAT to Color.White.copy(alpha = 0f), 1f to Color.White.copy(alpha = 0f), radius = r), r)
            // The picker: where the colour sits on the wheel (full saturation → middle of the pure band).
            val pk = puck
            val p = if (pk != null) Offset(center.x + pk.x * r, center.y + pk.y * r) else {
                val a = Math.toRadians(hsv[0].toDouble())
                val frac = if (hsv[1] >= 0.999f) (1f + FULL_SAT) / 2f else hsv[1] * FULL_SAT
                Offset(center.x + (cos(a) * frac * r).toFloat(), center.y + (sin(a) * frac * r).toFloat())
            }
            drawCircle(Color.Black.copy(alpha = 0.35f), 15.dp.toPx(), p)
            drawCircle(Color.White, 13.dp.toPx(), p)
            drawCircle(Color(argb), 10.dp.toPx(), p)
        }
    }
}

/**
 * A glass slider 0..1: drag or tap with touch; with the controller, focus it and
 * ←/→ step by [step] (hold to keep going).
 */
private fun Modifier.graphicsLayerScale(s: Float) =
    this.then(androidx.compose.ui.Modifier.graphicsLayer(scaleX = s, scaleY = s))

@Composable
fun GlassSlider(value: Float, modifier: Modifier = Modifier, step: Float = 0.05f, height: androidx.compose.ui.unit.Dp = 44.dp, onChange: (Float) -> Unit) {
    val g = LocalGlass.current
    var focused by remember { mutableStateOf(false) }
    val latest by androidx.compose.runtime.rememberUpdatedState(onChange)
    val shape = RoundedCornerShape(50)   // a capsule: the round knob nests in its ends
    Box(
        modifier.fillMaxWidth().height(height)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { latest((value - step).coerceIn(0f, 1f)); true }
                    Key.DirectionRight -> { latest((value + step).coerceIn(0f, 1f)); true }
                    else -> false
                }
            }
            .focusable()
            .clip(shape)
            // The empty part must read on light glass too (white-on-white vanished).
            .background(if (g.dark) Color(0x26FFFFFF) else Color(0x1F28306A), shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else g.rimBottom, shape)
            .pointerInput(Unit) { detectTapGestures { latest(posToValue(it.x, size.width.toFloat(), size.height.toFloat())) } }
            // Sideways drags only: a vertical swipe that starts on the slider scrolls the page
            // (it used to move the slider — the saturation changed while scrolling, 2026-09-24)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { c, _ ->
                    latest(posToValue(c.position.x, size.width.toFloat(), size.height.toFloat()))
                }
            },
    ) {
        // One geometry for knob, fill and touch: the knob (nearly the track's height, like
        // a switch) travels between the rounded ends; the fill runs to the knob's CENTRE so
        // its end always hides under it — no sliver at 0, no bulge beside the knob.
        Canvas(Modifier.matchParentSize()) {
            val h = this.size.height; val w = this.size.width
            // The knob is exactly the track's height (a smaller round button looked detached
            // from the slider), nested in the capsule's rounded ends.
            val r = h / 2f
            val x = r + (w - 2f * r) * value.coerceIn(0f, 1f)
            drawRect(Brush.horizontalGradient(listOf(g.accent2, g.accent), endX = w), size = androidx.compose.ui.geometry.Size(x, h))
            val c = Offset(x, h / 2f)
            drawCircle(Color.Black.copy(alpha = 0.22f), r, c + Offset(1.dp.toPx(), 0f))   // soft edge on the track side
            drawCircle(Color.White, r - 0.5.dp.toPx(), c)
            if (focused) drawCircle(g.accent, r - 2.5.dp.toPx(), c, style = Stroke(3.dp.toPx()))
        }
    }
}

/** Touch x → slider value, with the same knob travel the slider draws (see GlassSlider). */
private fun androidx.compose.ui.unit.Density.posToValue(x: Float, w: Float, h: Float): Float {
    val edge = h / 2f   // inset + knob radius = half the height
    return ((x - edge) / (w - 2f * edge).coerceAtLeast(1f)).coerceIn(0f, 1f)
}
