# Wayfinder test — analog direction mappings

The test APK is named `wayfinder-test.apk` in this fork's [releases](https://github.com/Russianranger/thor-wayfinder-tweaks/releases).
It installs as **Wayfinder Test** (`app.wayfinder.test`), separately from the original Wayfinder.
Turn off the original Wayfinder accessibility service before enabling the test app's service;
only one should manage the Thor's controller at a time. The test app needs its own setup permissions.

## Set up a mapping

1. Open **Game controls** for the app/game you want to configure.
2. Tap an arrow around either stick in the controller drawing, or the **Left stick / Right stick** summary underneath it.
3. Select **Up**, **Down**, **Left**, or **Right** and choose a **Keyboard key** or **Mouse movement**.
4. Repeat for other directions. **WASD**, **Arrow keys**, and **Mouse** apply a preset to the selected stick.

Each of the eight physical stick directions can have a different output, including a different cursor direction.
L3/R3 clicks remain separate controls in the center of each drawn stick.
**Original analog** restores that direction's normal controller output; **Nothing** disables it.
**Reset direction** and **Reset stick** restore normal analog behavior. Changes save to the selected app/game profile.

Keyboard keys remain held while the stick is deflected, including two keys for diagonals.
Cursor movement continues while held, with speed proportional to stick deflection, and stops at center.
Mapped directions replace their original analog output; unassigned directions keep passing through.
Mappings follow the physical stick before the existing swap/invert options. Existing stick shaping/deadzone settings apply first.
After a profile change or Home/Back shortcut, return a held stick to center before using its mapping again.

## Thor test checklist

- Left stick **WASD**: test each direction, diagonals, quick reversals, and release to center.
- Right stick **Mouse**: test slow/full deflection, diagonals, holding still off-center, and stopping at center.
- Change one direction to a different key or opposite cursor direction; check its summary and map highlight.
- Map two directions to the same key; releasing one must not release the other held direction.
- Check L3/R3 clicks still perform their own bindings.
- Leave some directions on **Original analog** and check they retain normal controller movement.
- Hold a mapped direction while switching app/profile, opening Home/Back shortcuts, or turning the screen off;
  verify no key or cursor movement remains stuck. Recenter before resuming.
- Restart the app and export/import the controls profile; verify all eight saved assignments survive.
- Reset a direction, a whole stick, and the whole profile; verify analog behavior returns.

The automated build runs native mapping regression checks and JVM stick-motion tests and rebuilds the Android input helper from source.
Physical Thor input and UI behavior still require device testing. The debug signing key is reused through the CI cache;
if that cache is removed, a later test APK may require backing up settings and reinstalling the test app.
