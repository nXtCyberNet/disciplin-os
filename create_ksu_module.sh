#!/usr/bin/env bash
set -e

# ==============================================================================
# KernelSU & Magisk Systemless Module Builder for Discipline Lock
# Target Device: Moto G45 5G (fogos) running EvolutionX
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build/ksu_module"
OUTPUT_DIR="${SCRIPT_DIR}/build/outputs"
APK_PATH="${SCRIPT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
MODULE_ZIP="${OUTPUT_DIR}/DisciplineLock-KernelSU-PrivApp.zip"

echo "=== Discipline Lock: Building KernelSU Priv-App Systemless Module ==="

# 1. Ensure latest APK is built
if [ ! -f "${APK_PATH}" ]; then
    echo "APK not found at ${APK_PATH}. Compiling debug APK..."
    cd "${SCRIPT_DIR}"
    ./gradlew assembleDebug
fi

# 2. Clean and prepare module staging directory
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}/system/priv-app/DisciplineLock"
mkdir -p "${BUILD_DIR}/system/etc/permissions"
mkdir -p "${OUTPUT_DIR}"

# 3. Copy APK
echo "Copying APK to system/priv-app/DisciplineLock/DisciplineLock.apk..."
cp "${APK_PATH}" "${BUILD_DIR}/system/priv-app/DisciplineLock/DisciplineLock.apk"

# 4. Generate module.prop
cat << 'EOF' > "${BUILD_DIR}/module.prop"
id=discipline-lock-privapp
name=Discipline Lock System Priv-App
version=v1.0.0
versionCode=1
author=cybernet
description=Installs Discipline Lock systemlessly as a privileged system app for tamper-proof persistence on EvolutionX with KernelSU.
EOF

# 5. Generate privapp permissions whitelist xml
cat << 'EOF' > "${BUILD_DIR}/system/etc/permissions/privapp-permissions-disciplinelock.xml"
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

# 6. Package into flashable zip
echo "Packaging flashable zip module..."
rm -f "${MODULE_ZIP}"
cd "${BUILD_DIR}"
zip -r "${MODULE_ZIP}" ./*

echo ""
echo "=============================================================================="
echo " SUCCESS: KernelSU module generated at:"
echo " ${MODULE_ZIP}"
echo "=============================================================================="
echo "To install on your Moto G45 5G:"
echo " 1. Open the KernelSU Manager app on EvolutionX"
echo " 2. Tap 'Modules' -> 'Install' and select ${MODULE_ZIP}"
echo " 3. Reboot the phone"
echo " Discipline Lock is now an un-killable system priv-app!"
echo "=============================================================================="
