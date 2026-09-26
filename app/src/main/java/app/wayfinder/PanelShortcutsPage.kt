package app.wayfinder

import androidx.compose.runtime.Composable
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.SectionHeader

/**
 * Hub → Quick panel: the AYN button's panel — whether the button opens it, and its
 * shortcuts in the same home-screen-style grid as the panel's own edit mode (✎).
 */
@Composable
fun PanelShortcutsPage(myDisplayId: Int, onBack: () -> Unit, go: (String) -> Unit) =
    SubPage(myDisplayId, "Quick panel", "Press the AYN button: screen modes, brightness, volume, live stats and your shortcuts — on the bottom screen", onBack) {
        SettingCard(
            "The AYN button opens the quick panel",
            if (AppSettings.aynButtonOurs) "Press it again (or B) to close it. Off: the AYN button opens AYN's own drawer again — nothing of AYN's is changed either way."
            else "Off — the AYN button opens AYN's drawer. Turn on to open Wayfinder's quick panel instead.",
            checked = AppSettings.aynButtonOurs, onChecked = { AppSettings.setAynButtonOursOn(it) },
        )
        // App pairs are opened from the panel: they live here now (critique 2026-09-25)
        app.wayfinder.ui.GlassListRow("App pairs", value = "${Layouts.pairs.size} saved · open from the panel's “App pairs” tile",
            icon = androidx.compose.material.icons.Icons.Rounded.ViewAgenda) { go(HubPage.PAIRS) }
        SectionHeader("Shortcuts — also editable in the panel (its Arrange button)")
        ShortcutGridEditor(columns = 4, cellHeight = 72.dp)
    }
