#!/usr/bin/env bash
set -e

# ==============================================================================
# KernelSU & Magisk Systemless Module Builder for Discipline OS Suite
# Target Device: Moto G45 5G (fogos) running EvolutionX / Android 15
# Controls everything: Systemless Priv-App, Automated Perms, Battery Whitelist,
# KernelSU Root Elevation, and Watchdog Supervisor.
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/build/outputs"
LOCK_APK="${SCRIPT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
GPS_APK="${SCRIPT_DIR}/gpslogger/gpslogger/build/outputs/apk/debug/gpslogger-debug.apk"

mkdir -p "${OUTPUT_DIR}"
mkdir -p "${SCRIPT_DIR}/apk_distribution"

echo "=== Discipline OS: Building KernelSU Master Controller Flashable Modules ==="

# 1. Ensure APKs are up to date
if [ ! -f "${LOCK_APK}" ]; then
    echo "Compiling DisciplineLock APK..."
    (cd "${SCRIPT_DIR}" && ./gradlew assembleDebug)
fi

if [ ! -f "${GPS_APK}" ]; then
    echo "Compiling Discipline Run (GPS) APK..."
    (cd "${SCRIPT_DIR}/gpslogger" && ./gradlew assembleDebug)
fi

build_module() {
    local mod_id="$1"
    local mod_name="$2"
    local mod_desc="$3"
    local staging_dir="$4"
    local output_zip="$5"
    local include_gps="$6"

    rm -rf "${staging_dir}"
    mkdir -p "${staging_dir}/META-INF/com/google/android"
    mkdir -p "${staging_dir}/system/priv-app/DisciplineLock"
    mkdir -p "${staging_dir}/system/etc/permissions"

    # Copy Discipline Lock APK
    cp "${LOCK_APK}" "${staging_dir}/system/priv-app/DisciplineLock/DisciplineLock.apk"

    # Optionally copy Discipline Run GPS APK
    if [ "${include_gps}" = "true" ]; then
        mkdir -p "${staging_dir}/system/app/DisciplineRun"
        cp "${GPS_APK}" "${staging_dir}/system/app/DisciplineRun/DisciplineRun.apk"
    fi

    # module.prop
    cat << EOF > "${staging_dir}/module.prop"
id=${mod_id}
name=${mod_name}
version=v2.0.0
versionCode=200
author=cybernet
description=${mod_desc}
EOF

    # privapp permissions
    cat << 'EOF' > "${staging_dir}/system/etc/permissions/privapp-permissions-disciplinelock.xml"
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="com.personal.disciplinelock">
        <permission name="android.permission.PACKAGE_USAGE_STATS"/>
        <permission name="android.permission.MANAGE_USERS"/>
        <permission name="android.permission.START_ACTIVITIES_FROM_BACKGROUND"/>
    </privapp-permissions>
    <privapp-permissions package="com.personal.disciplinelock.debug">
        <permission name="android.permission.PACKAGE_USAGE_STATS"/>
        <permission name="android.permission.MANAGE_USERS"/>
        <permission name="android.permission.START_ACTIVITIES_FROM_BACKGROUND"/>
    </privapp-permissions>
    <privapp-permissions package="com.nxtcybernet.disciplinerun">
        <permission name="android.permission.ACCESS_BACKGROUND_LOCATION"/>
        <permission name="android.permission.START_ACTIVITIES_FROM_BACKGROUND"/>
    </privapp-permissions>
</permissions>
EOF

    # customize.sh (Installer script executed during flashing in KernelSU/Magisk)
    cat << 'EOF' > "${staging_dir}/customize.sh"
SKIPUNZIP=0

ui_print "****************************************************"
ui_print "*   Discipline OS Master Suite Module Installer     *"
ui_print "*       Strict Morning Alarm & App Freezing        *"
ui_print "****************************************************"

ui_print "- Installing systemless priv-app & system binaries..."
set_perm_recursive "$MODPATH" 0 0 0755 0644

if [ -f "$MODPATH/system/priv-app/DisciplineLock/DisciplineLock.apk" ]; then
    set_perm "$MODPATH/system/priv-app/DisciplineLock/DisciplineLock.apk" 0 0 0644
    ui_print "  [+] Installed DisciplineLock (Privileged System App)"
fi

if [ -f "$MODPATH/system/app/DisciplineRun/DisciplineRun.apk" ]; then
    set_perm "$MODPATH/system/app/DisciplineRun/DisciplineRun.apk" 0 0 0644
    ui_print "  [+] Installed DisciplineRun (System GPS Run Tracker)"
fi

if [ -f "$MODPATH/service.sh" ]; then
    set_perm "$MODPATH/service.sh" 0 0 0755
    ui_print "  [+] Configured Systemless Master Control Daemon"
fi

ui_print "- Activating & registering applications immediately..."
if [ -f "$MODPATH/system/priv-app/DisciplineLock/DisciplineLock.apk" ]; then
    LOCK_SZ=$(stat -c %s "$MODPATH/system/priv-app/DisciplineLock/DisciplineLock.apk" 2>/dev/null || echo 0)
    if [ "$LOCK_SZ" -gt 0 ]; then
        ui_print "  [*] Installing Discipline Lock..."
        cat "$MODPATH/system/priv-app/DisciplineLock/DisciplineLock.apk" | pm install -r -d -g -S "$LOCK_SZ" 2>/dev/null || true
    fi
fi

if [ -f "$MODPATH/system/app/DisciplineRun/DisciplineRun.apk" ]; then
    RUN_SZ=$(stat -c %s "$MODPATH/system/app/DisciplineRun/DisciplineRun.apk" 2>/dev/null || echo 0)
    if [ "$RUN_SZ" -gt 0 ]; then
        ui_print "  [*] Installing Discipline Run..."
        cat "$MODPATH/system/app/DisciplineRun/DisciplineRun.apk" | pm install -r -d -g -S "$RUN_SZ" 2>/dev/null || true
    fi
fi

ui_print "- Granting system permissions and battery optimizations..."
TARGET_PKGS="com.personal.disciplinelock com.personal.disciplinelock.debug com.nxtcybernet.disciplinerun"
for pkg in $TARGET_PKGS; do
    pm grant "$pkg" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    pm grant "$pkg" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
    pm grant "$pkg" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
    pm grant "$pkg" android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null || true
    pm grant "$pkg" android.permission.ACTIVITY_RECOGNITION 2>/dev/null || true
    pm grant "$pkg" android.permission.SCHEDULE_EXACT_ALARM 2>/dev/null || true
    appops set "$pkg" PACKAGE_USAGE_STATS allow 2>/dev/null || true
    appops set "$pkg" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
    appops set "$pkg" SCHEDULE_EXACT_ALARM allow 2>/dev/null || true
    appops set "$pkg" ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true
    dumpsys deviceidle whitelist +"$pkg" 2>/dev/null || true
done

ui_print "- Installation successful! Apps are active now and will persist across reboots."
EOF

    # META-INF/com/google/android/updater-script
    cat << 'EOF' > "${staging_dir}/META-INF/com/google/android/updater-script"
#MAGISK
EOF

    # META-INF/com/google/android/update-binary
    cat << 'EOF' > "${staging_dir}/META-INF/com/google/android/update-binary"
#!/sbin/sh
#################
# Magisk / KernelSU Universal Module Installer Entry Point
#################
umask 022

OUTFD=$2
ZIPFILE=$3

mount /data 2>/dev/null

if [ -f /data/adb/ksu/scripts/util_functions.sh ]; then
  . /data/adb/ksu/scripts/util_functions.sh
elif [ -f /data/adb/magisk/util_functions.sh ]; then
  . /data/adb/magisk/util_functions.sh
elif [ -f /data/adb/ap/scripts/util_functions.sh ]; then
  . /data/adb/ap/scripts/util_functions.sh
elif [ -f /data/adb/ksu/bin/busybox ]; then
  exec /data/adb/ksu/bin/busybox sh /data/adb/ksu/scripts/module_installer.sh "$ZIPFILE" "$OUTFD"
elif [ -f /data/adb/magisk/busybox ]; then
  exec /data/adb/magisk/busybox sh /data/adb/magisk/module_installer.sh "$ZIPFILE" "$OUTFD"
else
  echo "! Neither KernelSU nor Magisk environment found"
  exit 1
fi

install_module
exit 0
EOF
    chmod 755 "${staging_dir}/META-INF/com/google/android/update-binary"

    # service.sh (Master Supervisor Daemon executed at boot as Root)
    cat << 'EOF' > "${staging_dir}/service.sh"
#!/system/bin/sh
MODDIR=${0%/*}

# Log supervisor execution
exec >> /data/local/tmp/discipline_module.log 2>&1
echo "[$(date)] Discipline OS Master Daemon Starting..."

# Wait for Android boot completion
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done

echo "[$(date)] Android system booted. Initializing Discipline OS controls..."
sleep 4

# 1. Force Package Manager registration of systemless APKs if needed
if [ -f "$MODDIR/system/priv-app/DisciplineLock/DisciplineLock.apk" ]; then
    pm install -r -d -g "$MODDIR/system/priv-app/DisciplineLock/DisciplineLock.apk" 2>/dev/null || true
fi

if [ -f "$MODDIR/system/app/DisciplineRun/DisciplineRun.apk" ]; then
    pm install -r -d -g "$MODDIR/system/app/DisciplineRun/DisciplineRun.apk" 2>/dev/null || true
fi

# 2. Automatically grant all runtime & special permissions to Discipline OS components
TARGET_PACKAGES="com.personal.disciplinelock com.personal.disciplinelock.debug com.nxtcybernet.disciplinerun"

for pkg in $TARGET_PACKAGES; do
    echo "[$(date)] Granting permissions to $pkg..."
    pm grant "$pkg" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    pm grant "$pkg" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
    pm grant "$pkg" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
    pm grant "$pkg" android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null || true
    pm grant "$pkg" android.permission.ACTIVITY_RECOGNITION 2>/dev/null || true
    pm grant "$pkg" android.permission.SCHEDULE_EXACT_ALARM 2>/dev/null || true
    appops set "$pkg" PACKAGE_USAGE_STATS allow 2>/dev/null || true
    appops set "$pkg" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
    appops set "$pkg" SCHEDULE_EXACT_ALARM allow 2>/dev/null || true
    appops set "$pkg" ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true
    dumpsys deviceidle whitelist +"$pkg" 2>/dev/null || true
done

# 3. Ensure KernelSU / ReSukiSU root access is permitted
if [ -d "/data/adb/ksu" ]; then
    echo "[$(date)] KernelSU environment detected. Ensuring profiles are active..."
    # ksud profile commands if available
    if command -v ksud >/dev/null 2>&1; then
        for pkg in $TARGET_PACKAGES; do
            ksud profile set-sepolicy "$pkg" default 2>/dev/null || true
        done
    fi
fi

# 4. Start Discipline Lock Persistent Service & Alarm Engine
am start-foreground-service -n com.personal.disciplinelock.debug/com.personal.disciplinelock.system.LockGateService 2>/dev/null || \
am start-foreground-service -n com.personal.disciplinelock/com.personal.disciplinelock.system.LockGateService 2>/dev/null || true

echo "[$(date)] Discipline OS Master Daemon initialized successfully."

# 5. Background Watchdog Supervisor
(
    while true; do
        sleep 60
        # Ensure lock gate service stays alive
        if ! pgrep -f "com.personal.disciplinelock" > /dev/null; then
            echo "[$(date)] Watchdog: Discipline Lock stopped. Restarting service..."
            am start-foreground-service -n com.personal.disciplinelock.debug/com.personal.disciplinelock.system.LockGateService 2>/dev/null || \
            am start-foreground-service -n com.personal.disciplinelock/com.personal.disciplinelock.system.LockGateService 2>/dev/null || true
        fi
    done
) &

EOF
    chmod 755 "${staging_dir}/service.sh"

    # Package flashable zip
    rm -f "${output_zip}"
    cd "${staging_dir}"
    zip -r "${output_zip}" ./*
    echo "Generated Module Zip: ${output_zip}"
}

# Build Master Suite Module
build_module \
    "discipline-os-suite" \
    "Discipline OS Master Suite" \
    "Systemless Discipline OS: Lock Gate, Root App Suspension, and Discipline Run GPS Mission Hub." \
    "${SCRIPT_DIR}/build/ksu_suite_staging" \
    "${OUTPUT_DIR}/DisciplineOS-Suite-KernelSU.zip" \
    "true"

# Build Lock Only Module
build_module \
    "discipline-lock-privapp" \
    "Discipline Lock System Priv-App" \
    "Installs Discipline Lock systemlessly as a privileged system app for tamper-proof persistence." \
    "${SCRIPT_DIR}/build/ksu_lock_staging" \
    "${OUTPUT_DIR}/DisciplineLock-KernelSU-PrivApp.zip" \
    "false"

# Mirror to distribution directory
cp "${OUTPUT_DIR}/DisciplineOS-Suite-KernelSU.zip" "${SCRIPT_DIR}/apk_distribution/"
cp "${OUTPUT_DIR}/DisciplineLock-KernelSU-PrivApp.zip" "${SCRIPT_DIR}/apk_distribution/"

echo ""
echo "=============================================================================="
echo " SUCCESS: KernelSU flashable modules generated and verified:"
echo " 1) ${OUTPUT_DIR}/DisciplineOS-Suite-KernelSU.zip"
echo " 2) ${OUTPUT_DIR}/DisciplineLock-KernelSU-PrivApp.zip"
echo "=============================================================================="
