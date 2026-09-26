package app.wayfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.glassSurface
import kotlinx.coroutines.delay

private fun label(ctx: android.content.Context, pkg: String?): String =
    if (pkg == null) "(as is)" else runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

/** "Firefox + Spotify", or just "Firefox" when the other screen is left as it is. */
internal fun pairTitle(ctx: android.content.Context, top: String?, bottom: String?) =
    listOfNotNull(top, bottom).joinToString("  +  ") { label(ctx, it) }

internal fun pairWhere(ctx: android.content.Context, top: String?, bottom: String?) = listOf(
    top?.let { "▲ ${label(ctx, it)} on top" } ?: "▲ top screen unchanged",
    bottom?.let { "▼ ${label(ctx, it)} below" } ?: "▼ bottom screen unchanged",
).joinToString(" · ")

/**
 * #15 — the quick panel's "App pairs" tile: open a saved pair (the panel closes, both apps
 * go to their screens), or save the two apps under the panel as a new pair. Editing
 * (swap ⇅, delete, build from a list) stays in Wayfinder, then Quick panel, then App pairs.
 */
@Composable
internal fun PanelPairsDialog(closePanel: () -> Unit, onClose: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    val (top, bottom) = remember { ForegroundAppService.currentLayout() }
    var saved by remember { mutableStateOf(Layouts.pairs.any { it.top == top && it.bottom == bottom }) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(24.dp)
        Box(Modifier.fillMaxWidth(0.92f).background(g.base.copy(alpha = 0.97f), shape).glassSurface(g, shape, raised = true)) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("App pairs", color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
                        Text("Pick one: both apps open, each on its screen", color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
                    }
                    FocusableGlass(onClick = onClose, radius = 14.dp) {
                        Text("Close  ·  B", color = g.textSecondary, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                    }
                }
                Layouts.pairs.forEachIndexed { i, p ->
                    FocusableGlass(
                        onClick = { onClose(); closePanel(); ForegroundAppService.later(350) { ForegroundAppService.openPair(p) } },
                        modifier = Modifier.fillMaxWidth(), radius = 16.dp, focusRequester = if (i == 0) first else null,
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(pairTitle(ctx, p.top, p.bottom), color = g.textPrimary,
                                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pairWhere(ctx, p.top, p.bottom), color = g.textTertiary,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (Layouts.pairs.isEmpty()) Text("No pairs yet.", color = g.textTertiary, style = MaterialTheme.typography.bodyMedium)
                val nothing = top == null && bottom == null
                FocusableGlass(
                    onClick = { if (!nothing && !saved) { Layouts.add(top, bottom); saved = true } },
                    modifier = Modifier.fillMaxWidth(), radius = 16.dp,
                    focusRequester = if (Layouts.pairs.isEmpty()) first else null,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(when { nothing -> "Nothing to save — no app on the screens"; saved -> "✓ Saved as a pair"; else -> "+ Save what's on the screens now" },
                            color = if (nothing) g.textTertiary else g.accent, style = MaterialTheme.typography.bodyLarge)
                        if (!nothing) Text(pairWhere(ctx, top, bottom), color = g.textTertiary,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("Swap, delete or build pairs from a list: Wayfinder, then Quick panel, then App pairs.",
                    color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
