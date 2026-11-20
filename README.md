# Discipline OS (KernelSU & EvolutionX Edition)

A minimalist discipline companion tailored for **Motorola Moto G45 5G (`fogos`)** on **EvolutionX** with **KernelSU**.

---

## 1. System Architecture: Stealth Root & Live Notification

```
+------------------------------------------------------------------------+
|                      EvolutionX Android 15 Desktop                     |
|  [Standard EvolutionX Launcher]   [Keyguard / Fingerprint]   [Dialer]  |
+------------------------------------+-----------------------------------+
                                     |
                                     v
+------------------------------------------------------------------------+
|                Discipline OS (Rooted Stealth Architecture)             |
|                                                                        |
|  +------------------------------------------------------------------+  |
|  |             Interactive Missions Notification (Pinned)           |  |
|  |   - Dynamic Header: "🎯 Daily Missions (2/4 Gates Done)"         |  |
|  |   - Expanded: Lists top pending habits & daily tasks             |  |
|  |   - 1-Tap Action: [Open Missions] launches Missions dashboard    |  |
|  +---------------------------------+--------------------------------+  |
|                                    |                                   |
|  +---------------------------------+--------------------------------+  |
|  |             EvolutionX Home Screen Widget & QS Tile              |  |
|  |   - Material Widget on wallpaper showing live checklist          |  |
|  |   - Quick Settings Tile for quick glance & status sync           |  |
|  +---------------------------------+--------------------------------+  |
|                                    |                                   |
|  +---------------------------------+--------------------------------+  |
|  |             KernelSU Root Enforcement (App Freezing)             |  |
|  |   * Monk Mode: pm suspend freezes distracting apps at OS level   |  |
|  |   * Unlocked: All apps instantly unsuspended on gate completion  |  |
|  |   * Zero battery drain, zero screen hijacking, zero fake overlay|  |
|  +------------------------------------------------------------------+  |
+------------------------------------------------------------------------+
```

---

## 2. Safety Invariants & Hard Rules

1. **Emergency Calls (112) Are Unblockable**:
   - The emergency dialer is permanently accessible via a dedicated red button at the bottom of the lock screen and via the device power menu.
   - The system dialer package is explicitly whitelisted in `setLockTaskPackages`.
   - Never put any challenge, prompt, or latency in front of emergency dialing.

2. **Incoming Calls Always Take Precedence**:
   - `SafetyManager` listens for `CALL_STATE_RINGING` and `CALL_STATE_OFFHOOK`.
   - As soon as a call is detected, the lock screen immediately stops LockTask mode and calls `moveTaskToBack(true)`, yielding display control to the telecom in-call UI.

3. **Crash Loop Failsafe (Watchdog)**:
   - If `LockGateActivity` or any component throws uncaught exceptions **3 times within a rolling 10-minute window**, the watchdog automatically disarms gate enforcement.
   - A persistent high-priority notification is posted, and the device unlocks completely.

4. **Banking / UPI Apps Security (Zero Compromise)**:
   - **Do NOT disable Android's native Lock Screen PIN or Biometrics**.
   - Discipline Lock runs **after** or **on top of** the native keyguard (`ACTION_USER_PRESENT`).
   - This ensures hardware Android Keystore backed credentials (used by Google Pay, PhonePe, BHIM, Banking apps) remain fully encrypted and secure.

---

## 3. Target Device: Moto G45 5G (`fogos`)

- **Codename**: `fogos`
- **SoC**: Qualcomm Snapdragon 6s Gen 3 (SM6375 variant)
- **Stock OS**: Android 15
- **Planned Custom ROM**: LineageOS 22.2 (Android 15)
- **Bootloader**: Unlockable via Motorola's Official Bootloader Unlock portal.

### Verify Device Variant Before Doing Any Deep System Work
```bash
adb shell getprop ro.product.name
adb shell getprop ro.product.device
adb shell getprop ro.build.version.release
```
Expected output:
- `ro.product.device`: `fogos`
- `ro.build.version.release`: `15`

---

## 4. Setup & Installation Guide

### Step 1: Build the APK
On your Arch Linux machine:
```bash
cd /home/cybernet/discipline-lock
./gradlew assembleDebug
```
The debug APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Step 2: Install via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 3: Grant Runtime Permissions via ADB
Grant overlay and phone permissions without manual UI prompts:
```bash
# Draw over other apps (SYSTEM_ALERT_WINDOW)
adb shell appops set com.personal.disciplinelock.debug SYSTEM_ALERT_WINDOW allow

# Read phone state (to yield on incoming calls)
adb shell pm grant com.personal.disciplinelock.debug android.permission.READ_PHONE_STATE

# Post notifications
adb shell pm grant com.personal.disciplinelock.debug android.permission.POST_NOTIFICATIONS
```

### Step 4: Test in Safe (Non-Device Owner) Mode First
Open the app from the launcher or via ADB:
```bash
adb shell am start -n com.personal.disciplinelock.debug/com.personal.disciplinelock.ui.LockGateActivity
```
- Verify the UI displays the orange badge: **"DEV/TEST MODE"**.
- In test mode, you can freely exit via the Home button if needed.
- Test the **Offline Emergency Fallback**: solve 3 math questions in a row. Verify the green banner shows the 15-minute emergency timer.
- Configure your **Vikunja API Token & Server URL** in the Settings tab.

---

## 5. Device Owner Provisioning (When Ready for Full Lock)

> [!IMPORTANT]
> Android strictly forbids setting Device Owner if any accounts exist on the device (Google, WhatsApp, Telegram, email accounts, etc.).

### Option A: Clean Setup (Recommended)
1. Factory reset device or perform after a fresh flash.
2. Complete setup wizard **WITHOUT adding any Google account or Wi-Fi login**.
3. Enable Developer Options & USB Debugging.
4. Install the app via ADB.
5. Set Device Owner:
```bash
adb shell dpm set-device-owner com.personal.disciplinelock.debug/com.personal.disciplinelock.system.DisciplineDeviceAdminReceiver
```
You should see:
```text
Success: Device owner set to package ComponentInfo{com.personal.disciplinelock.debug/com.personal.disciplinelock.system.DisciplineDeviceAdminReceiver}
```
6. Add your Google accounts and apps back.

### Option B: Without Factory Reset (Remove Accounts)
1. Go to **Settings -> Passwords & Accounts**.
2. Remove all accounts temporarily.
3. Run the `dpm set-device-owner` command above.
4. Log back into your accounts.

---

## 6. Emergency Recovery & Disaster Protocol

If you ever need to disable or remove the lock during development, use these failsafe mechanisms:

### Recovery Method 1: ADB Shell (Phone is reachable over USB)
```bash
# 1. Force stop the lock activity and background service
adb shell am force-stop com.personal.disciplinelock.debug

# 2. Trip watchdog failsafe directly via SharedPreferences or clear app data
adb shell pm clear com.personal.disciplinelock.debug

# 3. Remove Device Owner (if Device Owner was set)
adb shell dpm remove-active-admin com.personal.disciplinelock.debug/com.personal.disciplinelock.system.DisciplineDeviceAdminReceiver

# 4. Uninstall the package
adb shell pm uninstall com.personal.disciplinelock.debug
```

### Recovery Method 2: Boot into Android Safe Mode
Safe Mode disables all third-party Device Admin apps and services:
1. Hold the **Power Button** until the Power menu appears.
2. Long-press **Restart** or **Power Off** until the "Reboot to Safe Mode" dialog appears.
3. Tap **OK**.
4. In Safe Mode, third-party apps cannot start lock task mode or boot receivers. Go to **Settings -> Apps** and uninstall Discipline Lock.

### Recovery Method 3: Fastboot Stock Recovery (Moto G45 5G `fogos`)
Since your bootloader is unlocked, you can always flash stock Motorola firmware via fastboot:
1. Download stock Motorola firmware for `fogos` (via Lenovo RSA - Rescue and Smart Assistant tool or Motorola mirrors).
2. Reboot into Fastboot:
   ```bash
   adb reboot bootloader
   ```
   Or hold `Volume Down + Power` when powered off.
3. Verify device:
   ```bash
   fastboot devices
   ```
4. Erase userdata to restore clean factory state:
   ```bash
   fastboot erase userdata
   fastboot reboot
   ```

---

## 7. EvolutionX & KernelSU Superpowers (Moto G45 5G)

With **EvolutionX** and **KernelSU** installed on `fogos`, Discipline Lock leverages direct kernel root features:

### A. One-Tap Root Permission Grant
No ADB cable needed. Open Settings -> **KernelSU & EvolutionX Superpowers** -> tap **"Grant All Permissions via KernelSU"**. It will automatically grant notifications, system overlays, location, audio, and enable the accessibility service in 1 second.

### B. Native OS App Freezing (`pm suspend`)
When enabled in Settings, entering Monk / Disciplined Mode automatically freezes non-whitelisted apps (`pm suspend <pkg>`). In EvolutionX Launcher, icons are grayed out and cannot be launched.

### C. Anti-Tamper USB Lock
Prevents bypassing lock sessions by connecting a USB cable to a computer (`setprop persist.sys.usb.config none`). Automatically restored when all daily missions are cleared.

### D. EvolutionX Quick Settings Tile
Pull down the notification shade, tap the pencil icon (Edit), and add the **Discipline OS** tile to your quick toggles to monitor gate progress and quick-launch the dashboard.

### E. Flash as a System Priv-App (Immune to Safe Mode)
Build the flashable KernelSU zip:
```bash
./create_ksu_module.sh
```
Install `/home/cybernet/discipline-lock/build/outputs/DisciplineLock-KernelSU-PrivApp.zip` directly in KernelSU Manager -> Modules.

