# Wayfinder — For the AYN Thor

**Two screens, one controller.** Wayfinder moves apps between the Thor's screens without restarting them,
sends the controller where you want it, gives every game its own buttons, and puts the Thor's settings one
press away — all without leaving your game.

## What it does

**Screens**
- **Move or swap apps** between the top and bottom screens — hold Back. Apps keep their place; nothing restarts.
- **App pairs**: two apps, one press, each opens on its screen. Put your screens back after a restart.
- Per app: the screen it opens on, what the bottom screen does while it plays (keep on / off).
- Turn the bottom screen off with a 3-finger tap, or when unused. Screenshots of one or both screens.

**The controller**
- **Combos** on Home and Back (hold Home to see them all): move apps, send the controller to the top or bottom
  screen, screenshot, brightness, Keyboard & mouse, Game controls — every one can be changed, and combos can also
  open an app, an app pair or a Wayfinder page.
- The controller **stays where you send it** — touching the other screen doesn't steal it (or: follows touch,
  always top, always bottom).
- **Recent apps with the controller**: browse, open, close, clear all.

**Game controls** (Home + X, in any game)

- Every game its own buttons — for the whole emulator or for one game inside it (RetroArch, PPSSPP, Dolphin,
  DuckStation, GameNative… games are detected).
- Remap buttons, keyboard keys, mouse clicks, turbo, toggle, long / double press, macros, chords, an optional
  **Shift button** (hold it for a second layer), **gyro aiming**, stick deadzone / response curve, trigger range,
  Nintendo or Xbox face buttons, emulator hotkey presets.
- Per game: performance, fan, refresh rate and stick lights too. Share a mapping as a file.
- Games see a clean copy of the controller: Home and Back combos never reach the game.

**Quick panel** (the AYN button)
- Brightness and volume for each screen, performance, fan, 60/120 Hz, live temperatures and battery, your shortcut
  tiles, "Now playing" with "Keep for this game", media controls. Arrange it the way you like.

**And**
- **Keyboard & mouse** (Home + Y) on the other screen: PC keys, trackpad, numpad, emulator and media pads.
- **Wayfinder Keyboard**: types into any text field, driven with the controller, on the other screen.
- **Test the controller**: measures stick drift and sets a deadzone for every game in one press.
- Sleep & standby (stay asleep in the case, turn off what drains the battery while asleep), a speaker sound fix
  (the community EQ by Joey, Retro Handhelds, built in),
  stick lights (colours, effects, follow the screen), Do not disturb while playing, an FPS counter, backups.

Everything works with the controller.

## Requirements

- An **AYN Thor** on its stock system ("Force SELinux" off — the default).
- Nothing else: no root, no computer, no Shizuku. Wayfinder uses the Thor's own system service (the one AYN's
  "Run script as root" uses). Root and Shizuku are only fallbacks: on a rooted Thor your root manager may ask
  once, and if Shizuku is running it asks for its permission — saying no is fine to both.

## Install

1. Get the APK from the Wayfinder store page ([Ko-fi](https://ko-fi.com/thorwayfinder)) and check it (below)
   against the checksums listed on this repository's release page.
2. **Beta users: uninstall "Thor Wayfinder" (`com.thorwayfinder.app`) first** — 1.0 is a new app (`app.wayfinder`).
3. Open Wayfinder: the tour sets everything up (accessibility service, keyboard, background running) and teaches
   the combos by doing them.

### Verify the APK

Every release lists the APK's **SHA-256** and the **signing certificate's SHA-256**. Check them before installing:

```
certutil -hashfile wayfinder-1.0.apk SHA256            (Windows)
sha256sum wayfinder-1.0.apk                             (Linux / macOS)
apksigner verify --print-certs wayfinder-1.0.apk        (the certificate)
```

## Privacy and security

- Nothing is collected or sent. The only internet use is the optional Guide page (a small browser: a DuckDuckGo
  search for your game, or a page you pinned — when you open it, or automatically for apps where you turned the
  Guide companion on) and the Ko-fi support link.
- Wayfinder needs more access than most apps (accessibility, a keyboard, system commands through the Thor's
  service). What that access is used for, what was reviewed and what was fixed: [SECURITY.md](SECURITY.md).
- Screenshots and screen recordings capture everything shown, including what apps hide from screenshots.

## Building from source

The source is published so anyone can audit it.

- Android Studio (its JBR, JDK 17+) with the Android SDK; the native parts need the NDK (27.2.12479018).
- `./gradlew :app:assembleDebug` — a debug build (use `-Pminify` for a build shrunk like the release).
- Native binaries shipped in `app/src/main/assets/fx/` are built from `fx/`:
  `tools/build_wfpad.sh` (the input layer; `tools/build_wfpad.sh test` runs its unit tests on a Thor) and
  `tools/build_fx.sh` (the speaker widener) — Windows + Git Bash, NDK in the default SDK folder. SHA-256 of the shipped copies:
  - `wfpad` — `deebfd178ec016a077c8480950b4b87f977977aa3743e2bcfa1623f13461127d`
  - `libwfwide.so` — `f64974296927c51c1645b5c56af38504ef5e364d34df7620c07c0ce8a08d6b8a`

  Rebuilt from this source with NDK 27.2.12479018 and those scripts, both come out byte-identical.
- Release builds are signed by the maintainer (`tools/release.ps1`); the key is never in this repository.
- Some code comments refer to the maintainer's private design notes; they aren't needed to build or understand the code.

## How it's made

Wayfinder is designed, tested and maintained by one person. **Most of the code was written with an AI
assistant (Claude, by Anthropic)**, directed and reviewed feature by feature. Every feature was tested on a
real Thor before release, and the code went through dedicated security review passes, also AI-assisted (see
[SECURITY.md](SECURITY.md)). No outside firm audited it — the full source is here so anyone can check it.

## Disclaimer

Wayfinder works around Android's limited support for two screens. Most apps move fine; a few heavy or unusual
apps may restart or refuse to move.

Wayfinder is an independent project, not affiliated with, endorsed or sponsored by AYN or any other company
named here. AYN, Thor and Odin are trademarks of AYN; Nintendo, Xbox, RetroArch, PPSSPP, Dolphin, DuckStation,
GameNative, Cocoon, Spotify, Steam, Shizuku and other names are trademarks of their respective owners, used only
to describe compatibility.

## License

Wayfinder's own code: [PolyForm Strict 1.0.0](LICENSE) (SPDX: `PolyForm-Strict-1.0.0`) — source-available, not
open source. The source is published for **transparency and security auditing**. In short: you may read it,
audit it, and build it as-is for your own personal, non-commercial use; you may **not** share it or builds of
it, change it, or use it commercially. For anything else, ask through the Ko-fi page
below. The Wayfinder name and icon are not licensed.

Not covered by that license: the Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`, Apache 2.0) and
the libraries built into the app — see
[THIRD_PARTY_NOTICES.txt](app/src/main/assets/THIRD_PARTY_NOTICES.txt) (also in the app: Help & status →
Open-source licenses).

Contributions: pull requests aren't accepted (the license doesn't allow changes to the code) — bug reports
and ideas are welcome as issues.

## Support

Wayfinder is made by one person. If it makes your Thor better, you can
[support it on Ko-fi](https://ko-fi.com/thorwayfinder). Questions and bug reports: open an issue.
