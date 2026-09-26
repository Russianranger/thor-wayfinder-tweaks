// wfmap_test — unit tests of the mapping engine (fx/wfmap.h), run ON the Thor:
//   tools/build_wfpad.sh test   → pushes and runs /data/local/tmp/wfmap_test
// A fake pad with the Thor's real capabilities (getevent -p, 2026-09-24).
#include <stdio.h>
#include "wfmap.h"

static int fails = 0, checks = 0;
#define CHECK(cond, ...) do { checks++; if (!(cond)) { fails++; printf("FAIL %s:%d ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static const int KEYS[] = { 0x66, 0x72, 0x73, 0x9e, 0x130, 0x131, 0x132, 0x133, 0x134, 0x135, 0x136, 0x137,
                            0x138, 0x139, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e, 0x220, 0x221, 0x222, 0x223, 0x244 };
static wf_state S; static wf_map M; static wf_out O;

static void pad(int xbox) {
    memset(&S, 0, sizeof S); wf_map_reset(&M);
    S.nk = sizeof KEYS / sizeof *KEYS;
    for (int i = 0; i < S.nk; i++) S.keys[i] = (unsigned short)KEYS[i];
    int ax[] = { 0, 1, 2, 5, 9, 10, 16, 17 };
    S.na = 8;
    for (int i = 0; i < 8; i++) {
        S.axes[i] = (unsigned char)ax[i];
        S.info[ax[i]].minimum = ax[i] >= 16 ? -1 : (ax[i] == 9 || ax[i] == 10) ? 0 : -32767;
        S.info[ax[i]].maximum = ax[i] >= 16 ? 1 : 32767;
    }
    S.xbox = xbox;
    wf_state_rest(&S);
}
/** Send a source event; returns the echo flag. */
static int ev(int type, int code, int value) { int p; return wf_event(&S, type, code, value, &p); }
static int key(int code, int v) { return ev(EV_KEY, code, v); }
static int ab(int code, int v) { return ev(EV_ABS, code, v); }
static void frame(void) { wf_compute(&S, &M, &O); }

int main(void) {
    // ── passthrough ───────────────────────────────────────────────
    pad(0); key(WF_A, 1); ab(WF_LX, 20000); ab(WF_GAS, 30000); ab(WF_HY, -1); frame();
    CHECK(O.key[WF_A] && !O.key[WF_B], "A passes");
    CHECK(O.abs[WF_LX] == 20000 && O.abs[WF_GAS] == 30000 && O.abs[WF_HY] == -1, "axes pass");
    key(WF_A, 0); frame();
    CHECK(!O.key[WF_A], "A released");

    // ── face layout ──────────────────────────────────────────────
    pad(0); M.layout = 'x'; key(WF_A, 1); frame();                  // printed A, Xbox wanted
    CHECK(O.key[WF_B] && !O.key[WF_A], "Nintendo pad + Xbox layout: printed A → B code");
    pad(1); key(WF_B, 1); frame();                                  // Xbox pad: code B = printed A
    CHECK(O.key[WF_B], "Xbox pad, follow AYN: unchanged");
    M.layout = 'n'; frame();
    CHECK(O.key[WF_A] && !O.key[WF_B], "Xbox pad + Nintendo layout: printed A → A code");
    pad(1); M.layout = 'x'; key(WF_Y, 1); frame();                  // Xbox pad code Y = printed X
    CHECK(O.key[WF_Y], "Xbox pad + Xbox layout: unchanged");

    // ── remaps ───────────────────────────────────────────────────
    pad(0); M.keymap[WF_A] = WF_B; M.keymap[WF_B] = WF_NONE; key(WF_A, 1); key(WF_B, 1); frame();
    CHECK(O.key[WF_B] && !O.key[WF_A], "A → B, B → none");
    pad(0); M.keymap[0x13b] = WF_HAT_UP; key(0x13b, 1); frame();
    CHECK(O.abs[WF_HY] == -1 && !O.key[0x13b], "Start → D-pad up");
    pad(0); M.keymap[WF_L2] = WF_R2; key(WF_L2, 1); ab(WF_BRAKE, 12345); frame();
    CHECK(O.key[WF_R2] && O.abs[WF_GAS] == 12345 && O.abs[WF_BRAKE] == 0, "L2 → R2 carries the analog");
    pad(0); M.keymap[WF_L2] = WF_A; key(WF_L2, 1); ab(WF_BRAKE, 30000); frame();
    CHECK(O.key[WF_A] && O.abs[WF_BRAKE] == 0, "L2 → A drops the analog");
    pad(0); M.keymap[WF_X] = WF_R2; key(WF_X, 1); frame();
    CHECK(O.key[WF_R2] && O.abs[WF_GAS] == 32767, "X → R2 = full press");
    pad(0); M.trig_digital = 1; key(WF_R2, 1); ab(WF_GAS, 9000); frame();
    CHECK(O.abs[WF_GAS] == 32767, "digital triggers");
    pad(0); M.swap_sticks = 1; M.inv_ry = 1; ab(WF_LX, 100); ab(WF_LY, 200); frame();
    CHECK(O.abs[WF_RX] == 100 && O.abs[WF_RY] == -200 && O.abs[WF_LX] == 0, "swap sticks + invert right Y");
    pad(0); M.dpad_ls = 1; ab(WF_HX, 1); ab(WF_LY, -30000); frame();
    CHECK(O.abs[WF_LX] == 32767 && O.abs[WF_HY] == -1 && O.abs[WF_HX] == 0, "D-pad ↔ left stick");
    pad(0); M.keymap[WF_A] = WF_B; key(WF_A, 1); frame();
    wf_map_reset(&M); frame();                                      // profile switch mid-press
    CHECK(O.key[WF_A] && !O.key[WF_B], "profile switch: old output released, no stuck B");

    // ── Wayfinder outputs (keyboard key / action) ────────────────
    pad(0); M.keymap[WF_X] = WF_EXT; key(WF_X, 1); frame();
    CHECK(!O.key[WF_X] && wf_ext_event(&S, &M, WF_X, 1), "X → keyboard: nothing for the game, press to Wayfinder");
    CHECK(wf_ext_event(&S, &M, WF_X, 0) && !wf_ext_event(&S, &M, WF_A, 1), "release too; unmapped buttons not");
    pad(0); M.keymap[WF_X] = WF_EXT; key(WF_HOME, 1); key(WF_X, 1);
    CHECK(!wf_ext_event(&S, &M, WF_X, 1), "during Home: the press is the combo's, not the key's");
    key(WF_HOME, 0);
    CHECK(wf_ext_event(&S, &M, WF_X, 0), "its release still goes out (harmless)");
    pad(0); M.keymap[WF_L2] = WF_EXT; key(WF_L2, 1); ab(WF_BRAKE, 30000); frame();
    CHECK(O.abs[WF_BRAKE] == 0 && !O.key[WF_L2], "L2 → keyboard drops its analog too");
    CHECK(wf_parse(&M, &S, "k0x133=65534") == 0 && M.keymap[WF_X] == WF_EXT, "parse ext");

    // ── how it fires: toggle / turbo ─────────────────────────────
    pad(0); M.fire[WF_A] = 2;
    key(WF_A, 1); wf_fire_edge(&S, &M, WF_A, 1); frame(); CHECK(O.key[WF_A], "toggle: press → on");
    key(WF_A, 0); frame(); CHECK(O.key[WF_A], "toggle: released, still on");
    key(WF_A, 1); wf_fire_edge(&S, &M, WF_A, 1); key(WF_A, 0); frame(); CHECK(!O.key[WF_A], "toggle: second press → off");
    key(WF_A, 1); wf_fire_edge(&S, &M, WF_A, 1); key(WF_A, 0); key(WF_HOME, 1); frame();
    CHECK(!O.key[WF_A], "toggle: released while Home is held");
    key(WF_HOME, 0); frame(); CHECK(O.key[WF_A], "toggle: back on after Home");
    pad(0); M.fire[WF_B] = 1; M.turbo_hz = 10; S.now_ms = 1000;
    key(WF_B, 1); wf_fire_edge(&S, &M, WF_B, 1); frame(); CHECK(O.key[WF_B] && wf_turbo_active(&S, &M), "turbo: on at the press");
    S.now_ms = 1060; frame(); CHECK(!O.key[WF_B], "turbo: off after half a period (50 ms at 10/s)");
    S.now_ms = 1110; frame(); CHECK(O.key[WF_B], "turbo: on again");
    key(WF_B, 0); frame(); CHECK(!O.key[WF_B] && !wf_turbo_active(&S, &M), "turbo: stops on release");
    pad(0); M.fire[WF_X] = 1; M.keymap[WF_X] = WF_Y; S.now_ms = 0; key(WF_X, 1); wf_fire_edge(&S, &M, WF_X, 1); frame();
    CHECK(O.key[WF_Y], "turbo on a remapped button fires its target");
    CHECK(wf_parse(&M, &S, "f0x130=2 f0x131=1 tr=15") == 0 && M.fire[WF_A] == 2 && M.fire[WF_B] == 1 && M.turbo_hz == 15, "parse fire");
    CHECK(wf_parse(&M, &S, "f0x66=1") != 0 && wf_parse(&M, &S, "f0x130=3") != 0 && wf_parse(&M, &S, "tr=99") != 0, "fire refusals");

    // ── virtual presses (phase 3) ────────────────────────────────
    pad(0);
    CHECK(wf_vpress(&S, WF_A, 1) == 0, "vpress A accepted"); frame(); CHECK(O.key[WF_A], "vpress A → A");
    CHECK(wf_vpress(&S, WF_HOME, 1) == -1 && wf_vpress(&S, 0x2f0, 1) == -1 && wf_vpress(&S, WF_A, 2) == -1, "vpress refuses Home, unknown codes, bad values");
    wf_vpress(&S, WF_HAT_LEFT, 1); wf_vpress(&S, WF_R2, 1); frame();
    CHECK(O.abs[WF_HX] == -1 && O.key[WF_R2] && O.abs[WF_GAS] == 32767, "vpress D-pad left + R2 full pull");
    key(WF_HOME, 1); frame(); CHECK(!O.key[WF_A] && O.abs[WF_HX] == 0 && O.abs[WF_GAS] == 0, "vpress silent while Home is held");
    key(WF_HOME, 0); frame(); CHECK(O.key[WF_A], "vpress back after the gate");
    wf_vpress(&S, 0, 0); frame(); CHECK(!O.key[WF_A] && !O.key[WF_R2] && O.abs[WF_HX] == 0, "p 0 0 releases all");
    { wf_map x; wf_map_reset(&x); x.layout = 'x'; wf_vpress(&S, WF_A, 1); wf_compute(&S, &x, &O);
      CHECK(O.key[WF_B] && !O.key[WF_A], "vpress follows the face layout (Xbox)"); wf_vpress(&S, 0, 0); }

    // ── gyro as a stick ──────────────────────────────────────────
    pad(0); S.gyro_stick = 2; S.gyro_x = 12000; S.gyro_y = -5000; ab(WF_RX, 1000); frame();
    CHECK(O.abs[WF_RX] == 13000 && O.abs[WF_RY] == -5000 && O.abs[WF_LX] == 0, "gyro adds to the right stick");
    S.gyro_x = 40000; frame(); CHECK(O.abs[WF_RX] == 32767, "gyro + stick clamps");
    S.gyro_stick = 1; S.gyro_x = -3000; S.gyro_y = 0; frame(); CHECK(O.abs[WF_LX] == -3000 && O.abs[WF_RX] == 1000, "gyro on the left stick");
    key(WF_HOME, 1); frame(); CHECK(O.abs[WF_LX] == 0, "no gyro while Home is held");

    // ── Home / Back gate ─────────────────────────────────────────
    pad(0); key(WF_HOME, 1); frame();
    CHECK(O.key[WF_HOME], "Home itself reaches Android");
    CHECK(key(0x137, 1) == 1, "R1 during Home is echoed"); frame();
    CHECK(!O.key[0x137], "Home + R1: R1 withheld");
    CHECK(ab(WF_GAS, 32000) == 1, "R2 analog echoed"); frame();
    CHECK(O.abs[WF_GAS] == 0, "Home + R2: analog withheld (no brightness leak)");
    CHECK(ab(WF_RY, -32000) == 1, "right stick echoed"); frame();
    CHECK(O.abs[WF_RY] == 0, "Home + right stick flick: stick withheld");
    key(WF_HOME, 0); frame();
    CHECK(!O.key[WF_HOME] && !O.key[0x137], "Home up, R1 still held → still silent");
    CHECK(O.abs[WF_GAS] == 0 && O.abs[WF_RY] == 0, "consumed trigger/stick stay silent after Home");
    CHECK(ab(WF_RY, -32000) == 1, "consumed stick keeps echoing");
    CHECK(key(0x137, 0) == 1, "R1 release echoed");
    ab(WF_GAS, 0); ab(WF_RY, 0); frame();
    key(0x137, 1); ab(WF_GAS, 20000); frame();
    CHECK(O.key[0x137] && O.abs[WF_GAS] == 20000, "after release: R1 / R2 normal again");

    pad(0); key(WF_R2, 1); ab(WF_GAS, 32767); ab(WF_LX, 30000); frame();    // driving
    key(WF_HOME, 1); frame();
    CHECK(!O.key[WF_R2] && O.abs[WF_GAS] == 0 && O.abs[WF_LX] == 0, "Home while driving: game sees all released");
    key(WF_HOME, 0); frame();
    CHECK(O.key[WF_R2] && O.abs[WF_GAS] == 32767 && O.abs[WF_LX] == 30000, "Home released: held-before controls come back");

    pad(0); key(WF_HOME, 1); key(0x137, 1); key(0x137, 0); key(WF_HOME, 0); frame();   // Home + R1 tap
    key(0x137, 1); frame();
    CHECK(O.key[0x137], "a combo tapped inside one Home hold doesn't stay consumed");

    pad(0); key(WF_BACK, 1); key(WF_A, 1); frame();
    CHECK(O.key[WF_BACK] && !O.key[WF_A], "Back gates too");

    pad(0); key(WF_A, 1); key(WF_HOME, 1); key(WF_A, 0); key(WF_A, 1); key(WF_HOME, 0); frame();
    CHECK(!O.key[WF_A], "released and re-pressed during Home → consumed");

    // ── profile parsing ──────────────────────────────────────────
    pad(0);
    CHECK(wf_parse(&M, &S, "L=x k0x130=0x131 k0x131=65535 k0x13b=0x220 sw=1 td=1") == 0 &&
          M.layout == 'x' && M.keymap[WF_A] == WF_B && M.keymap[WF_B] == WF_NONE && M.swap_sticks && M.trig_digital, "parse ok");
    wf_map before = M;
    CHECK(wf_parse(&M, &S, "k0x66=0x130") != 0, "Home can't be remapped");
    CHECK(wf_parse(&M, &S, "k0x130=0x66") != 0, "nothing can become Home");
    CHECK(wf_parse(&M, &S, "k0x130=0x2") != 0, "unknown target refused");
    CHECK(wf_parse(&M, &S, "L=q") != 0 && wf_parse(&M, &S, "sw=2") != 0 && wf_parse(&M, &S, "zz=1") != 0, "bad tokens refused");
    CHECK(!memcmp(&M, &before, sizeof M), "a refused line leaves the map untouched");
    CHECK(wf_parse(&M, &S, "") == 0 && M.layout == 0 && M.keymap[WF_A] == 0, "empty line = no remap");

    // ── round 8: stick shape + trigger range ──────────────────────
    pad(0); M.dz[0] = 10; ab(WF_LX, 2000); ab(WF_LY, -1500); frame();               // ~7.6 % drift
    CHECK(O.abs[WF_LX] == 0 && O.abs[WF_LY] == 0, "drift inside the deadzone -> centre");
    ab(WF_LX, 32767); ab(WF_LY, 0); frame();
    CHECK(O.abs[WF_LX] == 32767, "full push still full (%d)", O.abs[WF_LX]);
    ab(WF_LX, 16384); frame();                                                     // 50 % -> (50-10)/90 = 44 %
    CHECK(O.abs[WF_LX] > 14000 && O.abs[WF_LX] < 15000, "rescaled after the deadzone (%d)", O.abs[WF_LX]);
    pad(0); M.oz[1] = 80; ab(WF_RX, 26214); frame();                               // 80 % = full
    CHECK(O.abs[WF_RX] == 32767, "outer: 80 %% reaches full (%d)", O.abs[WF_RX]);
    pad(0); M.cv[0] = 1; ab(WF_LX, 16384); frame();                                 // precise: 50 % -> 25 %
    CHECK(O.abs[WF_LX] > 8000 && O.abs[WF_LX] < 8400, "precise centre (%d)", O.abs[WF_LX]);
    pad(0); M.cv[0] = 2; ab(WF_LX, 8192); frame();                                  // fast: 25 % -> 50 %
    CHECK(O.abs[WF_LX] > 16200 && O.abs[WF_LX] < 16600, "fast (%d)", O.abs[WF_LX]);
    pad(0); M.dz[0] = 10; M.swap_sticks = 1; ab(WF_LX, 2000); frame();              // the PHYSICAL left stick
    CHECK(O.abs[WF_RX] == 0, "deadzone follows the physical stick through a swap (%d)", O.abs[WF_RX]);
    pad(0); M.tlo = 10; M.thi = 80; ab(WF_GAS, 2000); frame();                      // 6 % -> nothing
    CHECK(O.abs[WF_GAS] == 0, "trigger below its start -> 0 (%d)", O.abs[WF_GAS]);
    ab(WF_GAS, 27000); frame();                                                    // 82 % -> full
    CHECK(O.abs[WF_GAS] == 32767, "trigger past its end -> full (%d)", O.abs[WF_GAS]);
    ab(WF_GAS, 14745); frame();                                                    // 45 % -> 50 %
    CHECK(O.abs[WF_GAS] > 16000 && O.abs[WF_GAS] < 16800, "trigger rescaled (%d)", O.abs[WF_GAS]);
    pad(0);
    CHECK(wf_parse(&M, &S, "dzl=8 dzr=12 ozl=90 cvr=2 tlo=5 thi=95") == 0 && M.dz[0] == 8 && M.dz[1] == 12 &&
          M.oz[0] == 90 && M.cv[1] == 2 && M.tlo == 5 && M.thi == 95, "parse shape");
    CHECK(wf_parse(&M, &S, "dzl=31") != 0 && wf_parse(&M, &S, "ozl=60") != 0 && wf_parse(&M, &S, "cvl=3") != 0 &&
          wf_parse(&M, &S, "tlo=51") != 0 && wf_parse(&M, &S, "thi=49") != 0, "shape out of range refused");

    // ── hold-to-shift (§6l) ────────────────────────────────────────
    pad(0); M.shift = 0x13a; S.shift = 0x13a; S.now_ms = 1000;
    CHECK(key(0x13a, 1) == 1, "the shift button is echoed to Wayfinder");
    frame(); CHECK(!O.key[0x13a], "held shift: not sent to the game");
    CHECK(key(WF_A, 1) == 1, "a press while shift is held is echoed"); ab(WF_LX, 20000); frame();
    CHECK(!O.key[WF_A] && O.abs[WF_LX] == 0, "nothing reaches the game while shift is held");
    key(WF_A, 0); ab(WF_LX, 0); S.now_ms = 1200; key(0x13a, 0); frame();
    CHECK(!O.key[0x13a] && S.shift_tap_until == 0, "shift used for a combo: no tap on release");
    key(WF_A, 1); frame(); CHECK(O.key[WF_A], "after shift: A reaches the game again"); key(WF_A, 0);
    S.now_ms = 2000; key(0x13a, 1); S.now_ms = 2150; key(0x13a, 0); frame();
    CHECK(O.key[0x13a], "shift alone: a tap reaches the game on release");
    S.now_ms = 2250; frame(); CHECK(!O.key[0x13a], "the tap ends after 60 ms");
    pad(0); M.shift = 0x13a; S.shift = 0x13a; M.keymap[0x13a] = WF_B; S.now_ms = 10; key(0x13a, 1); key(0x13a, 0); frame();
    CHECK(O.key[WF_B] && !O.key[0x13a], "the tap follows the shift button's remap");
    pad(0); key(WF_A, 1); M.shift = 0x13a; S.shift = 0x13a; key(0x13a, 1); frame();
    CHECK(!O.key[WF_A], "held before shift: paused while shift is held (like Home)");
    key(0x13a, 0); S.now_ms = 1000000; frame(); CHECK(O.key[WF_A], "...and back when shift is let go");
    pad(0);
    CHECK(wf_parse(&M, &S, "sh=0x13a") == 0 && M.shift == 0x13a, "parse sh");
    CHECK(wf_parse(&M, &S, "sh=0x66") != 0 && wf_parse(&M, &S, "sh=0x220") != 0 && wf_parse(&M, &S, "sh=0x2") != 0,
          "sh: Home, the D-pad or an unknown key refused");
    CHECK(wf_parse(&M, &S, "sh=0") == 0 && M.shift == 0, "sh=0 = none");
    pad(0); M.shift = 0x13a; S.shift = 0x13a; key(0x13a, 1); wf_vpress(&S, WF_A, 1); frame();
    CHECK(O.key[WF_A], "a Shift-layer button press (virtual) reaches the game while shift is held");
    pad(0); key(WF_HOME, 1); wf_vpress(&S, WF_A, 1); frame();
    CHECK(!O.key[WF_A], "virtual presses stay silent while Home is held");

    printf("%s: %d checks, %d failed\n", fails ? "FAILED" : "OK", checks, fails);
    return fails != 0;
}
