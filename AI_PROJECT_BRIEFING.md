# PROJECT BRIEFING: DISCIPLINE OS & HEALTH HUB ECOSYSTEM

You are stepping into a project called **Discipline OS**, an anti-distraction, high-integrity personal accountability ecosystem built across Android (Kotlin + Java), Python, and a Tailscale private mesh network.

---

## 1. High-Level Architecture & Philosophy

The system enforces personal discipline by locking down distracting device functionality until daily cognitive and physical gates are cleared. It intentionally decouples responsibilities across three modular systems:

```
+-----------------------------------------------------------------------------------+
|                                 TAILSCALE NETWORK                                 |
|                                                                                   |
|   +--------------------------+                      +-------------------------+   |
|   |   PC / SERVER DISPATCHER |                      |   DISCIPLINE LOCK APP   |   |
|   |   (Python 3, Port 5050)  |                      |   (Kotlin / Jetpack)    |   |
|   |   IP: 100.113.205.122    |                      |   IP: 100.76.223.105    |   |
|   |                          |                      |   Local Webhook: 8080   |   |
|   |  • GitHub PR Poller      |  == HTTP Webhook ==> |                         |   |
|   |  • YouTube Watch Poller  |  (/webhook/mission)  |  • Math Engine Gate     |   |
|   |  • Hack Club AI Proxy    |                      |  • Daily Missions Gate  |   |
|   |  • Web Dashboard & APK   |                      |  • Inbound HTTP Server  |   |
|   +--------------------------+                      +-------------------------+   |
|                                                                  ^                |
|                                                                  | localhost:8080 |
|                                                     +-------------------------+   |
|                                                     |   GPSLOGGER HEALTH HUB  |   |
|                                                     |   (Android Java)        |   |
|                                                     |                         |   |
|                                                     |  • 6 AM Morning Alarm   |   |
|                                                     |  • 15-Min Snooze Loop   |   |
|                                                     |  • Outdoor GPS Workout  |   |
|                                                     |  • Pedometer / Splits   |   |
|                                                     +-------------------------+   |
+-----------------------------------------------------------------------------------+
```

1. **Discipline Lock (`app/`)**: The Gatekeeper. Manages the lock screen, cognitive math quiz, daily obligations list, and runs an embedded local HTTP webhook server on port 8080.
2. **GPSLogger Health Hub (`gpslogger/`)**: The Physical Accountability Hub. Handles outdoor GPS tracking, step counting, live HUD, and the **Morning Alarm (6:00 AM) with a 15-minute Snooze loop**. Once the run starts/finishes, it notifies Discipline Lock via `http://127.0.0.1:8080/webhook/run`.
3. **PC Mission Dispatcher (`dispatcher/`)**: Automated verification engine running on a PC/server. It polls external services (GitHub PRs, YouTube watch history via cookies), uses an LLM (Gemini via Hack Club proxy) to resolve fuzzy mission names to URLs once, caches them, and pings the phone over Tailscale.

---

## 2. Component Details

### A. Discipline Lock Android App (`/discipline-lock/app`)
- **Tech Stack**: Kotlin, Jetpack Compose, Material 3, Coroutines/Flow, SQLite.
- **Package Name**: `com.personal.disciplinelock` (debug build has `.debug` suffix).
- **Core State Machine**:
  - `DisciplineModeManager`: Evaluates gates. If any gate is pending, device stays in disciplined mode. When all gates pass, switches to normal mode until daily reset (default 12:00 AM).
  - Gate 1: **Math Challenge Gate** (`MathEngineManager`).
  - Gate 2: **Daily Missions Gate** (`HabitTaskManager`).
- **Math Engine**:
  - Bundled question bank (`assets/math_questions.json`) containing 50 algorithmic/math problems across NUMBER_THEORY, ALGEBRA, GEOMETRY, COMBINATORICS.
  - Supports MCQ and direct numeric input.
  - Correctly answered questions are **permanently removed** from the pool (persisted in SharedPreferences) so answers cannot be memorized. Pool resets when exhausted.
  - 5-minute quiz timer.
  - **Penalty**: Answering incorrectly triggers a **5-minute phone lockout block**.
- **Inbound Webhook Server (`LocalWebhookServer.kt`)**:
  - Lightweight raw socket HTTP server running on port `8080`.
  - Auto-resolves Tailscale IP (`100.x.x.x`).
  - Endpoints:
    - `GET /webhook/missions` -> returns JSON array of all daily tasks and completion states.
    - `POST /webhook/mission/{target}` or `GET /webhook/mission/{target}` -> verifies and marks a mission completed.
    - `POST /webhook/mission/add` -> dynamically registers new missions from the dispatcher.
    - `POST /webhook/mission/delete` -> **rejected with error 400** to preserve discipline integrity (missions cannot be deleted remotely).
    - `POST /webhook/run` or `/webhook/gpslogger` -> receives run metrics (`distanceMeters`, `paceMinPerKm`) from GPSLogger.
- **Purged Bloat**:
  - All legacy alarms, sirens, geofencing safe zones, internal GPS loops, notes tables, and app whitelists were completely removed to keep this app lean, crash-free, and battery-friendly.
  - **Testing Status**: Currently compiled as a standard non-Device Owner app so it is 100% uninstallable via Android Settings during testing.

### B. GPSLogger Health Hub (`/discipline-lock/gpslogger`)
- **Tech Stack**: Java, Android SDK, OpenStreetMap/Canvas Route Rendering.
- **Package Name**: `com.mendhak.gpslogger`.
- **Purpose**: Battery-optimized background GPS logging and workout HUD.
- **Discipline HUD (`DisciplineHudFragment.java`)**:
  - Displays real-time GPS coordinates, speed, pace, kilometer splits, step count (`StepCounterManager`), and mini route map.
  - Contains `DisciplineWebhookClient.java` which automatically pings `http://127.0.0.1:8080/webhook/run` during and at the end of runs.
- **Morning Alarm Mission (Current Development Priority)**:
  - Alarm scheduled for **6:00 AM** (configurable) using `AlarmManager.setExactAndAllowWhileIdle()`.
  - Loud alarm ringtone + vibration loop with full-screen intent (`FLAG_SHOW_WHEN_LOCKED`, `FLAG_TURN_SCREEN_ON`).
  - **[Snooze (+15 Min)]** button -> silences alarm and reschedules for `now + 15 minutes`.
  - **[Start Run / Wake Up]** button -> silences alarm and directly triggers `requestToggleLogging()` to start GPS workout tracking immediately ("just run if not running simple").

### C. PC Mission Dispatcher (`/discipline-lock/dispatcher`)
- **Tech Stack**: Python 3, `http.server`, `urllib`, `requests`.
- **Dashboard**: Running at `http://localhost:5050` (and `http://100.113.205.122:5050` on Tailscale).
- **Core Modules**:
  - `main.py`: Background polling worker (runs every 5 minutes by default).
  - `github_poller.py`: Checks GitHub events/PR endpoints for user `nXtCyberNet` on target repositories (e.g. `k8gb`) to auto-verify "Submit PR" missions.
  - `youtube_poller.py`: Reads Chrome session cookies (`SID`, `HSID`, `SSID`, `APISID`, `SAPISID`) to poll `myactivity.google.com` watch history for required video tracking.
  - `matcher.py`: Calls Hack Club AI Proxy (`https://ai.hackclub.com/proxy/v1/chat/completions` using `google/gemini-4-hydrogen`) to resolve natural language mission titles to target URLs/IDs. Calls LLM **only once**, stores mapping in `mission_cache.json`.
  - `web_server.py`: Serves the HTML dark-mode dashboard and hosts the APK download route (`/download/DisciplineLock.apk`).

---

## 3. Network Configuration & Device Details

- **Tailscale Mesh**:
  - PC Tailscale IP: `100.113.205.122`
  - Phone Tailscale IP: `100.76.223.105` (Hostname: `moto.tail93aa09.ts.net`, Motorola device)
- **Port Allocations**:
  - `5050`: PC Dispatcher Web Dashboard & APK Download.
  - `8080`: Phone Discipline Lock Webhook Receiver.
- **Direct APK Download**:
  - `http://100.113.205.122:5050/download/DisciplineLock.apk`

---

## 4. Current State & Next Tasks

1. **Discipline Lock App**:
   - Cleanly compiles with `./gradlew assembleDebug`.
   - Verified on emulator: Math Quiz operates with full question bank, timer, and lockout penalty; Webhook receiver is active; Settings menu configures daily reset and quiz parameters.
   - Tested and verified without Device Owner restrictions.
2. **GPSLogger Health Hub**:
   - Compiles cleanly with `./gradlew compileDebugSources`.
   - Existing Discipline HUD communicates with Discipline Lock port 8080.
   - **Immediate Next Task**: Finalize the morning alarm service and UI inside `gpslogger`:
     - Schedule daily 6:00 AM alarm.
     - 15-minute Snooze button logic.
     - Auto-launch workout logging upon dismissal/wake-up.
