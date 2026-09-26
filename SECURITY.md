# Wayfinder 1.0 — security

Wayfinder needs more access than most apps: it's an accessibility service and a keyboard, and it runs
system commands through the AYN Thor's own system service (the same one AYN's "Run script as root"
uses). So before 1.0 its code went through repeated, dedicated security review passes (September 2026;
AI-assisted, like the code — no outside firm), each finding fixed and re-tested on a real Thor. This is
what was checked and what was found.

## What was reviewed
- **Every exported component** (anything another app can call): only the main screen can be opened by
  other apps, and it accepts nothing but a fixed list of page names (plus per-app pages, only for a valid
  package name). The accessibility service and the keyboard can only be bound by Android itself; the two
  library components (Shizuku's provider, AndroidX's profile installer) need system-level permissions.
- **Every string that reaches a system (root) command**: package names, game titles, media titles,
  files, settings values — validated and quoted.
- **The channel between the app and its system helper** (a local socket): both ends check the other's
  identity (user id) and a handshake, so an ordinary app can't impersonate either side or read what goes
  through it (controller events and the Keyboard & mouse keys; Wayfinder Keyboard's typing never uses it).
  On a stock Thor any app can already run commands as root through AYN's service (see Known limits), and
  such a process could pass this check — it gains nothing it didn't already have.
- **Files Wayfinder imports** (shared controller mappings, backups): size caps, strict formats, every
  value range-checked; a shared file can't carry Wayfinder actions, system keys or system shortcuts —
  including shortcuts split across two buttons (if the file holds Ctrl or Alt anywhere, Tab, Space and Esc
  are removed everywhere) — and macros arrive switched off. A full restore only works with a backup made
  by the same installation (signed with a key that never leaves the device); anything else restores
  controller mappings only, through the same filter.
- **The native input layer** (the controller copy games see): its profile parser rejects any
  out-of-range value, and 98 automated checks run on the device.
- **Logging and debug code**: the release build is not debuggable, has no test receivers, and writes no
  debug/info logs from the app; its own warnings don't name apps or keys (logs could reveal which apps you
  use; an Android error message quoted in a rare failure may still contain one). A few root-only entry
  points used to drive the screens (focus, input tools) are part of the app itself, not test code.

## What was found and fixed (highlights)
- A command-injection path through an app-supplied package name (a malicious app could have named
  itself so that a system command ran extra code) — every package name is now validated and quoted.
- The app ↔ helper socket accepted any peer — now identity-checked on both ends.
- An activity-name injection in a system `am start` call — now validated.
- A crafted media title could fake a whole playback session (the Media card then showed, and its "Open"
  button opened, another app) — the separator is now removed from titles before parsing, and only exact,
  validated lines are accepted.
- A shared mapping could assemble a system shortcut from two buttons (Alt on one, Tab on another) — now
  filtered across the whole file.
- Restoring a backup could turn off your own Do not disturb — runtime state is no longer backed up.
- Release warnings that named apps, keys or whole commands — removed.
- Plus many reliability fixes found in the same passes (stuck buttons, overlays, sensors left running).

## Known limits (said plainly)
- The Thor's system service is open to every app on the device — that's AYN's design, not Wayfinder's,
  and Wayfinder doesn't make it wider.
- Wayfinder doesn't need root or Shizuku, but uses them as fallbacks when present: on a rooted Thor your
  root manager may ask once, and if Shizuku runs it asks for its permission (saying no is fine to both).
- The helper's socket has a fixed name. An app that grabs that name first can't read or inject anything
  (the helper refuses it), but it can stop Wayfinder's combos and input features from working — a
  denial of service only. (A per-launch random name is planned.)
- Screenshots and screen recordings capture everything shown, including content apps hide from
  screenshots (passwords, banking), and are saved to your Pictures / Movies folders.
- The Guide page is a small web browser: a DuckDuckGo search for your game, or a page you pinned — when
  you open it, or automatically for apps where you turned the Guide companion on (off by default). It
  runs inside Wayfinder's app (with JavaScript on, and no bridge from web pages into the app).

## Reporting a vulnerability
Please don't open a public issue: use **Report a vulnerability** on this repository's Security tab
(private). Other questions: open an issue.
