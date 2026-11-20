# Comprehensive Test Plan: Discipline Lock Gate

This test plan verifies the core requirements of Features 1–3 and Safety invariants for Discipline Lock on Moto G45 5G (`fogos`).

---

## Pre-requisites
- Moto G45 5G connected via ADB over USB (`adb devices` confirms device).
- App installed in debug mode (`com.personal.disciplinelock.debug`).
- Permissions granted: `SYSTEM_ALERT_WINDOW`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS`.

---

## Test Cases Matrix

| Case ID | Test Scenario | Procedure | Expected Result | Pass/Fail Criteria |
|:---|:---|:---|:---|:---|
| **TC-01** | **No Network / Airplane Mode** | 1. Enable Airplane mode (`adb shell cmd connectivity airplane-mode enable`).<br>2. Trigger task check via pull-to-refresh. | - App does not hang or crash.<br>- Saves tasks locally in SQLite and displays offline status.<br>- Offline challenge tab loads immediately if needed. | Pass if no crash, UI is responsive, and offline tasks/math problems can be solved. |
| **TC-02** | **API Errors & Rate Limits** | 1. Set invalid Vikunja token in Settings.<br>2. Hit Test Connection / Sync.<br>3. Simulate server error responses. | - Clear feedback: *"Authentication failed (401)"* or error status.<br>- App remains responsive with prompt to use offline emergency challenge.<br>- No uncaught exceptions. | Pass if error is cleanly trapped inside sync state and shown on screen. |
| **TC-03** | **Offline Emergency Math Progression** | 1. Open Offline Fallback tab.<br>2. Solve Question 1 (Arithmetic).<br>3. Solve Question 2 (Combinatorics).<br>4. Solve Question 3 (Probability).<br>5. Enter intentional wrong answer on test. | - Correct answer advances progress (1/3 -> 2/3 -> 3/3).<br>- Wrong answer resets progress back to 0/3 with explanation banner.<br>- Upon 3rd consecutive correct answer: Emergency Window activates for 15 minutes.<br>- Green timer shows countdown; LockTask is suspended. | Pass if exactly 3 in a row triggers 15m unlock window, and wrong answer resets count. |
| **TC-04** | **Reboot / Cold Boot Persistence** | 1. Restart device (`adb reboot`).<br>2. Wait for boot sequence to complete.<br>3. Inspect logcat: `adb logcat -s LockGateService BootReceiver`. | - `BootReceiver` captures `BOOT_COMPLETED`.<br>- `LockGateService` starts in foreground.<br>- Screen-on listener is active.<br>- Turning screen on displays LockGateActivity if tasks pending. | Pass if service auto-starts on boot without requiring manual launcher click. |
| **TC-05** | **Android Safe Mode Bypass Recovery** | 1. Boot device into Safe Mode (Hold Power -> Long-press Restart -> Safe Mode).<br>2. Check app state in Safe Mode. | - Safe Mode boots into clean Android UI.<br>- Third-party device admin apps are dormant.<br>- User can navigate to Settings -> Apps to inspect or uninstall if needed. | Pass if Safe Mode provides 100% reliable hardware recovery. |
| **TC-06** | **Incoming Phone Call Interruption** | 1. Place phone in locked gate state.<br>2. Call the test device from a secondary phone. | - `SafetyManager` detects `CALL_STATE_RINGING`.<br>- LockTask mode immediately calls `stopLockTask()`.<br>- Activity calls `moveTaskToBack(true)`.<br>- System in-call UI appears unhindered.<br>- User can answer and talk normally.<br>- When call ends (`CALL_STATE_IDLE`), gate re-evaluates. | Pass if incoming call UI is NEVER blocked or delayed by lock screen. |
| **TC-07** | **Emergency Call (112) Access** | 1. While lock screen is in active locked state, tap **"EMERGENCY CALL (112)"** button at bottom.<br>2. Check dialer behavior. | - System dialer opens immediately with `112` prefilled.<br>- Zero math puzzles or verification steps.<br>- Call button can be pressed without obstacles. | Pass if emergency dialer opens in under 500ms with zero gate obstruction. |
| **TC-08** | **Watchdog Crash Loop Detection** | 1. Simulate 3 unhandled exceptions within 10 minutes (can be simulated or triggered via test crash intent).<br>2. Observe watchdog state. | - Watchdog detects `CRASH_THRESHOLD >= 3`.<br>- Automatically sets `isDisarmed = true`.<br>- Calls `stopLockTask()`.<br>- High-priority persistent notification posted: *"⚠️ Watchdog Failsafe Disarmed"*.<br>- Gate enforcement completely disabled until user taps "Re-Arm". | Pass if phone unlocks safely without bootlooping or trapping user. |
| **TC-09** | **Screen-On & Unlock Gating** | 1. Turn screen off (`adb shell input keyevent 26`).<br>2. Turn screen on.<br>3. Unlock native Android lock screen. | - `LockGateService` receives `ACTION_SCREEN_ON` and `ACTION_USER_PRESENT`.<br>- If tasks pending and emergency window inactive, `LockGateActivity` launches fullscreen. | Pass if lock gate appears promptly on screen-on. |
