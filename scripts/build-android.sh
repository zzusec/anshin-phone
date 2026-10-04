#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
[ -x "$JAVA_HOME/bin/java" ] || { echo '请设置 JAVA_HOME 指向 JDK 17。' >&2; exit 1; }
[ -d "$ANDROID_HOME/platforms/android-35" ] || { echo '请安装Android SDK platform 35并设置ANDROID_HOME。' >&2; exit 1; }
node "$ROOT/scripts/build-web.mjs"
if [ "${1:-}" = test ]; then TASK=testDebugUnitTest; else TASK=assembleDebug; fi
cd "$ROOT/android"
./gradlew --no-daemon "$TASK"
if [ "$TASK" = assembleDebug ]; then
  mkdir -p "$ROOT/artifacts"
  cp app/build/outputs/apk/debug/app-debug.apk "$ROOT/artifacts/安心手机-debug.apk"
  echo "APK: $ROOT/artifacts/安心手机-debug.apk"
fi
