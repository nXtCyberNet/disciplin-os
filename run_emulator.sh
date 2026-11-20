#!/usr/bin/env bash
set -e

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:/opt/android-sdk/cmdline-tools/latest/bin:$PATH"

# Force xcb platform on Linux/Wayland for Qt emulator window compatibility
export QT_QPA_PLATFORM="${QT_QPA_PLATFORM:-xcb}"

echo "=========================================="
echo " Starting Android Emulator: test_fogos "
echo "=========================================="
echo "SDK: $ANDROID_HOME"
echo "AVD: test_fogos"
echo "=========================================="

exec "$ANDROID_HOME/emulator/emulator" -avd test_fogos -writable-system -accel on -gpu auto "$@"
