package app.wayfinder.ui

import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp

/**
 * Light play on the glass panels (Android 13+, AGSL; elsewhere plain frost).
 *
 *  - [glassLight] — every panel: a specular highlight on the bevel lit from the
 *    top-left, a faint glow along the edge where the glass is "thick", and a warm/cool
 *    colour split across the rim. Pure drawing, no backdrop needed.
 *  - [glassLens] — true refraction: the rim bends what's behind the panel. Only
 *    possible when the backdrop is drawn by us (the aurora): with "your background"
 *    the system blurs the live screen behind the window and apps never get those
 *    pixels. (A snapshot would work but freezes animated backgrounds — rejected.)
 */
class GlassBackdrop {
    internal val node = RenderNode("glassBackdrop")
    /** Something is recorded in [node]; lenses stay off until then. */
    var ready by mutableStateOf(false)
    /** Bumped when [node] is re-recorded outside an animation. */
    var generation by mutableIntStateOf(0)
    /** The aurora's frame clock — lenses redraw with it. */
    var tick: androidx.compose.runtime.State<Float>? = null

    private val cds = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()

    /** Re-record [node] at [scope]'s size (window coordinates) with Compose drawing. */
    fun record(scope: DrawScope, block: DrawScope.() -> Unit) {
        node.setPosition(0, 0, scope.size.width.toInt(), scope.size.height.toInt())
        val rc = node.beginRecording()
        cds.draw(scope, scope.layoutDirection, androidx.compose.ui.graphics.Canvas(rc), scope.size, block)
        node.endRecording()
        if (!ready) { ready = true; generation++ } else if (tick == null) generation++
    }
}

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** Global switch (Appearance → Light refraction), mirrored from AppSettings. */
object GlassLensConfig {
    var enabled by mutableStateOf(true)
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33
}

private const val SDF = """
float sdRR(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
float2 normalAt(float2 p, float2 hs, float r) {
    float2 e = float2(1.0, 0.0);
    float2 n = float2(sdRR(p + e.xy, hs, r) - sdRR(p - e.xy, hs, r),
                      sdRR(p + e.yx, hs, r) - sdRR(p - e.yx, hs, r));
    return n / max(length(n), 0.0001);                  // outward surface normal
}
"""

private const val REFRACT_AGSL = """
uniform shader content;
uniform float2 size;      // panel size, px
uniform float2 origin;    // panel top-left inside the lens node (the margin)
uniform float radius;     // corner radius, px
uniform float rim;        // width of the bevel that bends light, px
uniform float strength;   // displacement at the very edge, px
uniform float chroma;     // colour split at the edge (0..1)
""" + SDF + """
half4 main(float2 xy) {
    float2 hs = size * 0.5;
    float2 p = xy - origin - hs;
    float d = sdRR(p, hs, radius);
    if (d > 0.0) return content.eval(xy);
    float t = clamp(1.0 + d / rim, 0.0, 1.0);          // 0 in the flat middle → 1 at the edge
    float2 n = normalAt(p, hs, radius);
    // Circular bevel: flat in the middle, steepening toward the rim, so the rim
    // shows (bent) what lies just beyond the edge.
    float bend = strength * (1.0 - sqrt(max(1.0 - t * t, 0.0)));
    float2 off = n * bend;
    half4 c = content.eval(xy + off);
    c.r = content.eval(xy + off * (1.0 + chroma)).r;
    c.b = content.eval(xy + off * (1.0 - chroma)).b;
    return half4(c.rgb, 1.0);
}
"""

private const val LIGHT_AGSL = """
uniform float2 size;
uniform float radius;
uniform float rim;
uniform float highlight;  // specular strength
uniform float glow;       // edge glow strength
""" + SDF + """
half4 main(float2 xy) {
    float2 hs = size * 0.5;
    float2 p = xy - hs;
    float d = sdRR(p, hs, radius);
    if (d > 0.0) return half4(0.0);
    float t = clamp(1.0 + d / rim, 0.0, 1.0);
    float2 n = normalAt(p, hs, radius);
    float2 L = normalize(float2(-0.55, -0.85));         // light from the top-left
    float lit = pow(max(dot(n, L), 0.0), 2.0) + 0.35 * pow(max(dot(n, -L), 0.0), 2.0);
    float a = highlight * lit * t * t * t + glow * t * t;
    // Dispersion hint: the rim runs slightly warm on one side, cool on the other.
    float s = dot(n, float2(0.8, -0.6)) * t;
    half3 col = half3(1.0 + 0.04 * s, 1.0, 1.0 - 0.04 * s);   // near-neutral (was ±35 %: read as a blue edge)
    a = clamp(a, 0.0, 1.0);
    return half4(clamp(col, 0.0, 1.0) * a, a);          // premultiplied
}
"""

private fun DrawScope.cornerRadius(shape: Shape): Float {
    val outline = shape.createOutline(size, layoutDirection, this)
    return ((outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f)
        .coerceAtMost(minOf(size.width, size.height) / 2f)
}

// ── light (every panel) ────────────────────────────────────────────────
private class LightState {
    val shader = RuntimeShader(LIGHT_AGSL)
    val paint = android.graphics.Paint()
    var key = ""
}

/** Specular bevel + edge glow over the frost. Cheap: one shader pass, only when the panel redraws. */
fun Modifier.glassLight(shape: Shape, dark: Boolean): Modifier = composed {
    if (!GlassLensConfig.supported || !GlassLensConfig.enabled) return@composed this
    val state = remember { LightState() }
    drawBehind {
        if (size.width < 2f || size.height < 2f) return@drawBehind
        val r = cornerRadius(shape)
        val key = "${size.width}|${size.height}|$r|$dark"
        if (key != state.key) {
            state.key = key
            state.shader.setFloatUniform("size", size.width, size.height)
            state.shader.setFloatUniform("radius", r)
            state.shader.setFloatUniform("rim", minOf(22.dp.toPx(), minOf(size.width, size.height) / 2f))
            state.shader.setFloatUniform("highlight", if (dark) 0.34f else 0.55f)
            state.shader.setFloatUniform("glow", if (dark) 0.07f else 0.12f)
            state.paint.shader = state.shader
        }
        drawContext.canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, state.paint)
    }
}

// ── refraction (aurora backdrop) ───────────────────────────────────────
private class LensState {
    val node = RenderNode("glassLens")
    val shader = RuntimeShader(REFRACT_AGSL)
    var key = ""
}

/**
 * Draws the backdrop bent through the panel's bevel, behind its frost (must come
 * after the clip). No-op without a backdrop we drew ourselves.
 */
fun Modifier.glassLens(shape: Shape): Modifier = composed {
    val bd = LocalGlassBackdrop.current
    if (bd == null || !GlassLensConfig.supported || !GlassLensConfig.enabled) return@composed this
    val state = remember { LensState() }
    var pos by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { pos = it.positionInWindow() }
        .drawBehind {
            // Re-draw every aurora frame (and when the backdrop is re-recorded).
            @Suppress("UNUSED_VARIABLE") val gen = bd.generation
            @Suppress("UNUSED_VARIABLE") val t = bd.tick?.value
            if (bd.ready) drawLens(state, bd, pos, shape)
        }
}

private fun DrawScope.drawLens(s: LensState, bd: GlassBackdrop, pos: Offset, shape: Shape) {
    val canvas = drawContext.canvas.nativeCanvas
    if (!canvas.isHardwareAccelerated || size.width < 2f || size.height < 2f) return
    val m = 36.dp.toPx()
    val w = size.width; val h = size.height
    val radius = cornerRadius(shape)
    val key = "$w|$h|$radius"
    if (key != s.key) {
        s.key = key
        s.shader.setFloatUniform("size", w, h)
        s.shader.setFloatUniform("origin", m, m)
        s.shader.setFloatUniform("radius", radius)
        // Subtle on purpose (2026-09-23): a wide, strong bend + a big colour split painted a
        // blue/purple outline round every panel. Now: a thin bevel, a small bend, a trace of split.
        s.shader.setFloatUniform("rim", minOf(14.dp.toPx(), minOf(w, h) / 2f))
        s.shader.setFloatUniform("strength", 8.dp.toPx())
        s.shader.setFloatUniform("chroma", 0.06f)
        s.node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s.shader, "content"))
    }
    s.node.setPosition(0, 0, (w + 2 * m).toInt(), (h + 2 * m).toInt())
    val rc = s.node.beginRecording()
    rc.translate(m - pos.x, m - pos.y)
    rc.drawRenderNode(bd.node)
    s.node.endRecording()
    canvas.save()
    canvas.translate(-m, -m)
    canvas.drawRenderNode(s.node)
    canvas.restore()
}
