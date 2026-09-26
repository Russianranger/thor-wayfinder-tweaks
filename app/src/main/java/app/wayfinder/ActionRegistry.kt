package app.wayfinder

/**
 * The set of actions Thor Wayfinder can perform. This is the shared vocabulary
 * that the UI (Hub buttons, quick menu) and the input layer (gestures, button
 * chords) both target, so every trigger routes through one execution path in
 * [ForegroundAppService.perform]. New features add an entry here and a branch there.
 *
 * UI-agnostic on purpose (no icons/Compose here) — the UI maps actions to icons.
 */
enum class ThorAction(val title: String, val description: String) {
    SWAP_OR_SEND("Move / swap apps", "Move the app to the other screen — or swap them if both screens have one"),
    CLEAR_BACKGROUND("Close background apps", "Closes the apps you're not using — keeps the ones on the screens"),
    RECENTS("Recent apps", "Open the multitask view"),
    BACK("Back", "Android's normal Back"),
    // Wired in later phases; present now so bindings/UI can reference them.
    SCREENSHOT("Screenshot", "Capture the top, bottom or both screens (pick which in Screens & power)"),
    TOGGLE_SECOND_SCREEN("Bottom screen off / on", "Turn the bottom screen off, or back on"),
    TOGGLE_KEEP_AWAKE("Stay awake", "The screens don't turn off on their own (press again to stop)"),
    FOCUS_SWITCH_UP("Controller to the top screen", "The controller now works the top screen"),
    FOCUS_SWITCH_DOWN("Controller to the bottom screen", "The controller now works the bottom screen"),
    FOCUS_LOCK_TOGGLE("Lock the controller", "Keep the controller on its screen — touching the other one won't move it"),
    KEYBOARD("Keyboard & mouse", "Keys and a trackpad for the game, on the other screen"),
    QUICK_MENU("Quick panel", "Brightness, volume, performance, fan and your shortcuts — on the bottom screen"),
    BRIGHTER("Brighter", "Both screens brighter, keeping their difference"),
    DIMMER("Dimmer", "Both screens dimmer, keeping their difference"),
    FPS_COUNTER("Frame rate (FPS)", "Show or hide the frame-rate counter (where: Screens & power)"),
    GAME_CONTROLS("Game controls", "The game's buttons, gyro and macros, while you play; the same combo or B goes back to the game"),
    /** Round 8: opens an app, an app pair or a Wayfinder page — the target is on the binding
     *  ([Binding.arg], see [OpenTargets]); several can exist, each on its own combo. */
    OPEN("Open an app, a pair or a page", "Your own combos that open something (Combos page, bottom)"),
    ;
}

/** Thin façade so callers don't reach into the service directly. */
object ActionRegistry {
    fun perform(action: ThorAction): Boolean = ForegroundAppService.perform(action)

    /** Actions that are fully implemented today (for enabling/greying UI). */
    fun isImplemented(action: ThorAction): Boolean = when (action) {
        ThorAction.SWAP_OR_SEND, ThorAction.CLEAR_BACKGROUND,
        ThorAction.RECENTS, ThorAction.BACK, ThorAction.TOGGLE_SECOND_SCREEN,
        ThorAction.TOGGLE_KEEP_AWAKE, ThorAction.FOCUS_SWITCH_UP, ThorAction.FOCUS_SWITCH_DOWN,
        ThorAction.FOCUS_LOCK_TOGGLE, ThorAction.SCREENSHOT, ThorAction.KEYBOARD,
        ThorAction.BRIGHTER, ThorAction.DIMMER, ThorAction.FPS_COUNTER, ThorAction.QUICK_MENU,
        ThorAction.GAME_CONTROLS, ThorAction.OPEN -> true
        else -> false
    }
}
