#!/bin/bash
# Build Wayfinder's native audio effect (fx/wfwide.c) for the Thor's 64-bit audio HAL and
# drop it into the app's assets (installed at runtime by AudioFx.kt). Needs the NDK (r27c).
set -e
cd "$(dirname "$0")/.."
NDK="$LOCALAPPDATA/Android/Sdk/ndk/27.2.12479018"
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
"$CC" --target=aarch64-linux-android33 -shared -fPIC -O2 -Wall -Wextra -fvisibility=hidden \
  -o app/src/main/assets/fx/libwfwide.so fx/wfwide.c -llog -lm -Wl,--build-id=sha1 -Wl,-z,max-page-size=16384
"$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe" -D --defined-only app/src/main/assets/fx/libwfwide.so | grep -E " AELI$"
ls -la app/src/main/assets/fx/libwfwide.so
