#!/usr/bin/env bash
set -e

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:/opt/android-sdk/cmdline-tools/latest/bin:$PATH"

PKG="com.personal.disciplinelock.debug"
ADMIN_RECEIVER="$PKG/com.personal.disciplinelock.system.DisciplineDeviceAdminReceiver"
ACCESSIBILITY_SERVICE="$PKG/com.personal.disciplinelock.system.AppLockAccessibilityService"
MAIN_ACTIVITY="$PKG/com.personal.disciplinelock.ui.LockGateActivity"
APK="app/build/outputs/apk/debug/app-debug.apk"

echo "=========================================="
echo " DisciplineOS Test & Deployment Script   "
echo "=========================================="

# 1. Check if ADB is ready
echo "[1/7] Waiting for Android device / emulator..."
adb wait-for-device

# 2. Wait for system boot completed
echo "[2/7] Waiting for system boot to complete..."
while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
    sleep 2
done
echo "       System booted successfully!"

# 3. Elevate to root via ADB
echo "[3/7] Restarting ADB with root privileges..."
adb root || true
sleep 1
adb wait-for-device

# 4. Install / Update APK
echo "[4/7] Installing DisciplineOS debug APK ($APK)..."
if [ ! -f "$APK" ]; then
    echo "       Building APK first..."
    ./gradlew assembleDebug
fi
adb install -r -t -g "$APK"

# 5. Provision Device Owner (MDM/Kiosk Mode)
echo "[5/7] Provisioning Device Owner..."
SET_DPM_OUTPUT=$(adb shell dpm set-device-owner "$ADMIN_RECEIVER" 2>&1 || true)
echo "       $SET_DPM_OUTPUT"

# 6. Grant Special & Runtime Permissions
echo "[6/7] Granting permissions and services..."
# Draw over other apps (floating lock overlay)
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
# Enable Accessibility service for real-time app launch interception
adb shell settings put secure enabled_accessibility_services "$ACCESSIBILITY_SERVICE"
adb shell settings put secure accessibility_enabled 1
# Location permissions for GPS morning run verification
adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
adb shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
adb shell pm grant "$PKG" android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null || true
adb shell pm grant "$PKG" android.permission.ACTIVITY_RECOGNITION 2>/dev/null || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

# 7. Launch Lock Gate Screen
echo "[7/7] Launching DisciplineOS Lock Gate..."
adb shell am start -n "$MAIN_ACTIVITY"

echo "=========================================="
echo " DisciplineOS is now live on emulator!    "
echo "=========================================="
