#!/bin/bash
# Build wfpad (fx/wfpad.c) — the input layer's native passthrough — as a 64-bit Android
# executable into the app's assets (copied to the app's files and run as root by the helper).
set -euo pipefail
cd "$(dirname "$0")/.."

# The pure mapping tests also run on a Linux host; no Android SDK or device is needed.
if [[ "${1:-}" == host-test ]]; then
  test_dir="$(mktemp -d)"
  trap 'rm -rf "$test_dir"' EXIT
  for test_source in fx/*_test.c; do
    test_name="$(basename "$test_source" .c)"
    "${HOST_CC:-cc}" -std=c11 -D_POSIX_C_SOURCE=200809L -O2 -Wall -Wextra -Werror \
      -o "$test_dir/$test_name" "$test_source" -lm
    "$test_dir/$test_name"
  done
  exit 0
fi

if [[ "${1:-}" != '' && "${1:-}" != test ]]; then
  echo 'Usage: tools/build_wfpad.sh [host-test|test]' >&2
  exit 2
fi

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${LOCALAPPDATA:-}/Android/Sdk}}"
wayfinder_ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$sdk_root/ndk/${ANDROID_NDK_VERSION:-27.2.12479018}}}"
case "$(uname -s)" in
  Linux*) ndk_host=linux-x86_64; exe_suffix='' ;;
  Darwin*) ndk_host=darwin-x86_64; exe_suffix='' ;;
  MINGW*|MSYS*|CYGWIN*) ndk_host=windows-x86_64; exe_suffix=.exe ;;
  *) echo 'Unsupported NDK host platform' >&2; exit 2 ;;
esac
wayfinder_cc="$wayfinder_ndk/toolchains/llvm/prebuilt/$ndk_host/bin/clang$exe_suffix"
if [[ ! -x "$wayfinder_cc" ]]; then
  echo "NDK compiler not found: $wayfinder_cc" >&2
  echo 'Set ANDROID_NDK_HOME or install NDK 27.2.12479018 in ANDROID_HOME.' >&2
  exit 1
fi
mkdir -p app/src/main/assets/fx
"$wayfinder_cc" --target=aarch64-linux-android30 -O2 -Wall -Wextra -fPIE -pie -o app/src/main/assets/fx/wfpad fx/wfpad.c -Wl,-z,max-page-size=16384
ls -la app/src/main/assets/fx/wfpad
# tools/build_wfpad.sh test → the mapping engine's unit tests, run ON the Thor (fx/wfmap_test.c)
if [[ "${1:-}" == test ]]; then
  "$wayfinder_cc" --target=aarch64-linux-android30 -O2 -Wall -Wextra -fPIE -pie -o tools/wfmap_test fx/wfmap_test.c -lm
  wayfinder_adb="${ADB:-$sdk_root/platform-tools/adb$exe_suffix}"
  test_path="$PWD/tools/wfmap_test"
  if command -v cygpath >/dev/null 2>&1; then test_path="$(cygpath -w "$test_path")"; fi
  MSYS_NO_PATHCONV=1 "$wayfinder_adb" push "$test_path" /data/local/tmp/wfmap_test >/dev/null
  MSYS_NO_PATHCONV=1 "$wayfinder_adb" shell 'chmod 755 /data/local/tmp/wfmap_test; /data/local/tmp/wfmap_test' </dev/null
fi
