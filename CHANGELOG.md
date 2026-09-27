# Changelog

## 1.2 — September 2026

- **New: a Guide tab in Keyboard & mouse (Home + Y)** — the game's guide page and your notes on the other screen,
  one combo away, in any game — even dual-screen ones (melonDS, Azahar, Cemu…). Pin a page, go back, or open the
  full Guide page to edit your notes.
- **Guides and notes per game:** inside an emulator, each game now has its own pinned page and notes (before,
  all the games of one emulator shared them), and the guide search uses the game's title.
- **Pinned guides work offline:** pinning a page saves a copy of it (refreshed whenever it loads online); with no
  connection, or if the site fails, the pinned guide opens from that copy.
- A guide page that can't load now says so (with Retry) instead of staying blank — e.g. on a Wi-Fi that needs a
  login page first.

## 1.1 — September 2026

- **New: "Close this app"** — an action for any combo or game button: closes the app on the screen that has
  the controller and goes home there, without Android's multitask view. (No combo by default — pick one.)
- **Fixed:** in dual-screen games (melonDS, Azahar, Cemu…) the AYN button's quick panel opened hidden under the
  game's bottom screen. It now opens as the top screen's side sheet over the game; the Guide no longer opens
  hidden there either.
- **Fixed:** the quick panel's top bar (CPU, GPU, RAM, Battery) overflowed with the default text size or a
  12-hour clock — "Battery" wrapped letter by letter or was cut off. Its text now shrinks to fit your text
  size and clock, so all four readouts stay (RAM steps aside only with the very largest text sizes).

## 1.0 — September 2026

A new app: **Wayfinder — For the AYN Thor** (package `app.wayfinder`). It replaces the beta "Thor Wayfinder"
(`com.thorwayfinder.app`) — **uninstall the beta first**; its settings don't carry over.

### New
- **Nothing to install besides the app**: uses the Thor's own system service — no root, Shizuku or computer; the tour
  sets up the rest. Apps move live
  between the screens (no restart, no duplicate), verified and retried; a glass slide animation.
- **The controller follows you**: send it with Home + right stick; it stays where sent (or follows touch, always
  top, always bottom). Home goes home on the screen that has the controller.
- **Combos** on Home and Back, all changeable, per app too; hold Home for a cheat sheet. Combos can open an app,
  an app pair or a Wayfinder page.
- **Input layer**: games see a clean copy of the controller (Home / Back combos never reach them), the controller stays player 1,
  emergency off (hold Home + Back 5 s).
- **Game controls** (Home + X): per app and per game — remaps, keyboard keys, mouse, turbo, toggle, long / double
  press, macros, chords, hold-to-Shift layer, gyro aiming, stick deadzone / curve / full-at, trigger range, face
  buttons, emulator presets, per-game performance / fan / refresh rate / lights. Share and import mappings safely.
- **Quick panel** (AYN button): per-screen brightness and volume, screen modes, performance, fan, refresh rate,
  live stats, 39 shortcut tiles, Now playing + Keep for this game, Details and Media widgets, arrangeable.
- **Keyboard & mouse** (Home + Y) on the other screen and **Wayfinder Keyboard** (17 languages, controller typing).
- **Recent apps** driven with the controller.
- **App profiles**: opens on, bottom-screen rule, performance, fan, refresh rate, combos, lights, Guide & notes.
- **App pairs**, restore the screens after a restart.
- **Test the controller**: drift measurement and a one-press deadzone.
- **Sleep & standby** (lid guard, sleep actions, standby stats), **speaker sound fix**, **stick lights**, **Do not
  disturb while playing**, **FPS counter** (+ battery, + temperatures), screenshots of one or both screens, screen
  recording, **backup and restore**.
- An interactive **tour** that sets everything up and teaches the combos by doing them.
- A "Recent apps can't open" warning with a one-press fix when a system reset leaves Android thinking the
  Thor's setup isn't finished.

### Security
Dedicated security review passes (AI-assisted) before release; findings fixed and re-tested on a Thor. See [SECURITY.md](SECURITY.md).

## 0.1 — beta (April 2026)
- Hold Back to move / swap apps between screens, double Back for Recents; Shizuku for live moves.
