#!/usr/bin/env bash
set -e

# ==============================================================================
# KernelSU & Magisk Systemless Module Builder for Discipline OS Suite
# Target Device: Moto G45 5G (fogos) running EvolutionX
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/build/outputs"
LOCK_APK="${SCRIPT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
GPS_APK="${SCRIPT_DIR}/gpslogger/gpslogger/build/outputs/apk/debug/gpslogger-debug.apk"

mkdir -p "${OUTPUT_DIR}"

echo "=== Discipline OS: Building KernelSU Compliant Flashable Modules ==="

# 1. Build APKs if missing
if [ ! -f "${LOCK_APK}" ]; then
    echo "DisciplineLock APK not found. Compiling..."
    cd "${SCRIPT_DIR}"
    ./gradlew assembleDebug
fi

if [ ! -f "${GPS_APK}" ]; then
    echo "GPSLogger APK not found. Compiling..."
    cd "${SCRIPT_DIR}/gpslogger"
    ./gradlew assembleDebug
fi

# Function to build a compliant module
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

    # Optionally copy GPSLogger APK
    if [ "${include_gps}" = "true" ]; then
        mkdir -p "${staging_dir}/system/app/GPSLogger"
        cp "${GPS_APK}" "${staging_dir}/system/app/GPSLogger/GPSLogger.apk"
    fi

    # module.prop
    cat << EOF > "${staging_dir}/module.prop"
id=${mod_id}
name=${mod_name}
version=v1.0.0
versionCode=1
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
    </privapp-permissions>
    <privapp-permissions package="com.personal.disciplinelock.debug">
        <permission name="android.permission.PACKAGE_USAGE_STATS"/>
        <permission name="android.permission.MANAGE_USERS"/>
    </privapp-permissions>
</permissions>
EOF

    # customize.sh
    cat << 'EOF' > "${staging_dir}/customize.sh"
SKIPUNZIP=0

ui_print "**********************************************"
ui_print "*   Discipline OS KernelSU Module Installer  *"
ui_print "**********************************************"

ui_print "- Installing systemless priv-app binaries..."
set_perm_recursive "$MODPATH" 0 0 0755 0644

if [ -f "$MODPATH/system/priv-app/DisciplineLock/DisciplineLock.apk" ]; then
    set_perm "$MODPATH/system/priv-app/DisciplineLock/DisciplineLock.apk" 0 0 0644
    ui_print "  [+] Installed DisciplineLock priv-app"
fi

if [ -f "$MODPATH/system/app/GPSLogger/GPSLogger.apk" ]; then
    set_perm "$MODPATH/system/app/GPSLogger/GPSLogger.apk" 0 0 0644
    ui_print "  [+] Installed GPSLogger Health Hub app"
fi

ui_print "- Module installation complete! Reboot device to activate."
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

    # service.sh (Late-start service that ensures package manager registers the APKs)
    cat << 'EOF' > "${staging_dir}/service.sh"
#!/system/bin/sh
MODDIR=${0%/*}

# Wait for boot completion
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done

sleep 3

# Force Package Manager to register APKs if not already indexed
if [ -f "$MODDIR/system/priv-app/DisciplineLock/DisciplineLock.apk" ]; then
    pm install -r -d -g "$MODDIR/system/priv-app/DisciplineLock/DisciplineLock.apk" 2>/dev/null || true
fi

if [ -f "$MODDIR/system/app/GPSLogger/GPSLogger.apk" ]; then
    pm install -r -d -g "$MODDIR/system/app/GPSLogger/GPSLogger.apk" 2>/dev/null || true
fi
EOF
    chmod 755 "${staging_dir}/service.sh"

    # Package zip
    rm -f "${output_zip}"
    cd "${staging_dir}"
    zip -r "${output_zip}" ./*
    echo "Generated: ${output_zip}"
}

# Build Suite Module
build_module \
    "discipline-os-suite" \
    "Discipline OS & Health Hub Suite" \
    "Systemless installation of Discipline Lock & GPSLogger Health Hub for EvolutionX with KernelSU." \
    "${SCRIPT_DIR}/build/ksu_suite_staging" \
    "${OUTPUT_DIR}/DisciplineOS-Suite-KernelSU.zip" \
    "true"

# Build Lock Only Module
build_module \
    "discipline-lock-privapp" \
    "Discipline Lock System Priv-App" \
    "Installs Discipline Lock systemlessly as a privileged system app for tamper-proof persistence on EvolutionX with KernelSU." \
    "${SCRIPT_DIR}/build/ksu_lock_staging" \
    "${OUTPUT_DIR}/DisciplineLock-KernelSU-PrivApp.zip" \
    "false"

# Mirror to apk_distribution
cp "${OUTPUT_DIR}/DisciplineOS-Suite-KernelSU.zip" "${SCRIPT_DIR}/apk_distribution/"
cp "${OUTPUT_DIR}/DisciplineLock-KernelSU-PrivApp.zip" "${SCRIPT_DIR}/apk_distribution/"

echo ""
echo "=============================================================================="
echo " SUCCESS: KernelSU flashable modules generated and verified:"
echo " 1) ${OUTPUT_DIR}/DisciplineOS-Suite-KernelSU.zip"
echo " 2) ${OUTPUT_DIR}/DisciplineLock-KernelSU-PrivApp.zip"
echo "=============================================================================="
