# Release only: no debug / info logging at all (it recorded which app was on each screen, the
# running game, button presses… and on the Thor any app can read the log through AYN's root
# service). Warnings / errors stay — they don't print package names or titles (those are DEBUG-gated).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
