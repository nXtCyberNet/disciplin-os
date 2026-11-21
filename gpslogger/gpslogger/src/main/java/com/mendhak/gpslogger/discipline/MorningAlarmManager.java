package com.mendhak.gpslogger.discipline;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import androidx.core.app.NotificationCompat;
import com.mendhak.gpslogger.GpsMainActivity;
import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.NotificationChannelNames;
import com.mendhak.gpslogger.common.slf4j.Logs;
import org.slf4j.Logger;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * MorningAlarmManager
 *
 * Central engine for the Discipline Morning Run Alarm mission.
 * Features:
 * - Daily wake-up schedule (synced with Discipline Lock or default 06:00 AM).
 * - Exact alarm scheduling with setAlarmClock() for Doze-bypass reliability.
 * - 15-minute snooze loop support.
 * - Stationary penalty detection (outside movement stops > 45s).
 * - Target pace & distance enforcement: if target pace did not match on finish,
 *   the alarm DOES NOT STOP and restarts ringing until pace is matched!
 * - Alarm volume mute control (defaults muted for quiet emulator testing).
 */
public class MorningAlarmManager {

    private static final Logger LOG = Logs.of(MorningAlarmManager.class);
    private static volatile MorningAlarmManager instance;

    public static final String PREF_ALARM_ENABLED = "discipline_morning_alarm_enabled";
    public static final String PREF_ALARM_HOUR = "discipline_morning_alarm_hour";
    public static final String PREF_ALARM_MINUTE = "discipline_morning_alarm_minute";
    public static final String PREF_SNOOZE_MINUTES = "discipline_morning_snooze_minutes";
    public static final String PREF_NEXT_TRIGGER_MS = "discipline_morning_next_trigger_ms";
    public static final String PREF_ALARM_MUTED = "discipline_morning_alarm_muted";
    public static final String PREF_STATIONARY_GRACE_SEC = "discipline_morning_stationary_grace_sec";
    public static final String PREF_SNOOZE_COUNT = "discipline_morning_snooze_count";
    public static final int MAX_SNOOZE_COUNT = 1;

    public static final int DEFAULT_HOUR = 6;
    public static final int DEFAULT_MINUTE = 0;
    public static final int DEFAULT_SNOOZE_MINUTES = 15;
    public static final int DEFAULT_STATIONARY_GRACE_SEC = 45;

    public enum AlarmState {
        IDLE,
        RINGING,
        SNOOZED,
        RUNNING_MISSION,
        COMPLETED
    }

    public interface AlarmStateListener {
        void onAlarmStateChanged(AlarmState state, String statusMessage);
        void onCountdownTick(long millisUntilFinished);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final DisciplineVoiceCoach voiceCoach;

    private AlarmState currentState = AlarmState.IDLE;
    private AlarmStateListener listener;
    private final AlarmTonePlayer tonePlayer = AlarmTonePlayer.getInstance();
    private CountDownTimer snoozeTimer;
    private long snoozeRemainingMs = 0L;

    // Stationary penalty tracking
    private long stationaryStartTimeMs = 0L;
    private boolean isStationarySirenSounding = false;
    private boolean isStationaryWarningSpoken = false;

    private MorningAlarmManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(this.context);
        this.voiceCoach = DisciplineVoiceCoach.getInstance(this.context);
    }

    public static MorningAlarmManager getInstance(Context context) {
        if (instance == null) {
            synchronized (MorningAlarmManager.class) {
                if (instance == null) {
                    instance = new MorningAlarmManager(context);
                }
            }
        }
        return instance;
    }

    public static SharedPreferences getPrefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public void setListener(AlarmStateListener listener) {
        this.listener = listener;
        if (listener != null) {
            listener.onAlarmStateChanged(currentState, getStateDescription());
        }
    }

    public AlarmState getCurrentState() {
        return currentState;
    }

    // Static helper accessors for compatibility
    public static boolean isAlarmEnabled(Context context) {
        return true; // Strict discipline: Alarm cannot be turned off!
    }

    public static void setAlarmEnabled(Context context, boolean enabled) {
        // Strict discipline: Alarm cannot be turned off! Always armed.
        getPrefs(context).edit().putBoolean(PREF_ALARM_ENABLED, true).apply();
        scheduleAlarm(context);
    }

    public static int getAlarmHour(Context context) {
        return getPrefs(context).getInt(PREF_ALARM_HOUR, DEFAULT_HOUR);
    }

    public static int getAlarmMinute(Context context) {
        return getPrefs(context).getInt(PREF_ALARM_MINUTE, DEFAULT_MINUTE);
    }

    public static void setAlarmTime(Context context, int hour, int minute) {
        getPrefs(context).edit()
                .putInt(PREF_ALARM_HOUR, hour)
                .putInt(PREF_ALARM_MINUTE, minute)
                .apply();
        if (isAlarmEnabled(context)) {
            scheduleAlarm(context);
        }
    }

    public static int getSnoozeMinutes(Context context) {
        return getPrefs(context).getInt(PREF_SNOOZE_MINUTES, DEFAULT_SNOOZE_MINUTES);
    }

    public static void setSnoozeMinutes(Context context, int minutes) {
        getPrefs(context).edit().putInt(PREF_SNOOZE_MINUTES, minutes).apply();
    }

    public static long getNextAlarmTimestamp(Context context) {
        return getPrefs(context).getLong(PREF_NEXT_TRIGGER_MS, 0L);
    }

    // Instance accessors
    public int getAlarmHour() {
        return getAlarmHour(context);
    }

    public void setAlarmHour(int hour) {
        setAlarmTime(context, hour, getAlarmMinute());
    }

    public int getAlarmMinute() {
        return getAlarmMinute(context);
    }

    public void setAlarmMinute(int minute) {
        setAlarmTime(context, getAlarmHour(), minute);
    }

    public int getSnoozeDurationMinutes() {
        return getSnoozeMinutes(context);
    }

    public void setSnoozeDurationMinutes(int minutes) {
        setSnoozeMinutes(context, minutes);
    }

    public int getStationaryGraceSeconds() {
        return prefs.getInt(PREF_STATIONARY_GRACE_SEC, DEFAULT_STATIONARY_GRACE_SEC);
    }

    public void setStationaryGraceSeconds(int seconds) {
        prefs.edit().putInt(PREF_STATIONARY_GRACE_SEC, Math.max(10, seconds)).apply();
    }

    public boolean isMuted() {
        return false; // Strict discipline: Alarm sound cannot be turned off or muted!
    }

    public void setMuted(boolean muted) {
        // Strict discipline: Alarm sound cannot be muted or turned off!
        prefs.edit().putBoolean(PREF_ALARM_MUTED, false).apply();
    }

    public String getFormattedAlarmTime() {
        int h = getAlarmHour();
        int m = getAlarmMinute();
        String amPm = h < 12 ? "AM" : "PM";
        int displayH = (h == 0) ? 12 : (h > 12 ? h - 12 : h);
        return String.format(Locale.getDefault(), "%02d:%02d %s", displayH, m, amPm);
    }

    /**
     * Schedules the next exact morning alarm using Android AlarmManager.
     */
    public static void scheduleAlarm(Context context) {
        if (!isAlarmEnabled(context)) {
            LOG.info("Morning alarm is disabled. Skipping schedule.");
            return;
        }

        int hour = getAlarmHour(context);
        int minute = getAlarmMinute(context);

        Calendar calendar = Calendar.getInstance();
        long now = System.currentTimeMillis();
        calendar.setTimeInMillis(now);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        if (calendar.getTimeInMillis() <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        long triggerAtMs = calendar.getTimeInMillis();
        setAlarmExact(context, triggerAtMs);
    }

    public static void scheduleNextDailyAlarm(Context context) {
        if (!isAlarmEnabled(context)) {
            return;
        }
        int hour = getAlarmHour(context);
        int minute = getAlarmMinute(context);

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        long triggerAtMs = calendar.getTimeInMillis();
        setAlarmExact(context, triggerAtMs);
    }

    public static int getSnoozeCount(Context context) {
        return getPrefs(context).getInt(PREF_SNOOZE_COUNT, 0);
    }

    public static void incrementSnoozeCount(Context context) {
        int count = getSnoozeCount(context);
        getPrefs(context).edit().putInt(PREF_SNOOZE_COUNT, count + 1).apply();
        LOG.info("Snooze count incremented to " + (count + 1) + "/" + MAX_SNOOZE_COUNT);
    }

    public static void resetSnoozeCount(Context context) {
        getPrefs(context).edit().putInt(PREF_SNOOZE_COUNT, 0).apply();
        LOG.info("Morning Alarm snooze count reset to 0.");
    }

    public static boolean canSnooze(Context context) {
        return getSnoozeCount(context) < MAX_SNOOZE_COUNT;
    }

    public static long scheduleSnooze(Context context, int minutes) {
        if (!canSnooze(context)) {
            LOG.warn("Cannot snooze: Maximum snooze limit (" + MAX_SNOOZE_COUNT + ") reached!");
            return -1L;
        }
        incrementSnoozeCount(context);
        long triggerAtMs = System.currentTimeMillis() + (minutes * 60 * 1000L);
        setAlarmExact(context, triggerAtMs, true);
        LOG.info("Alarm snoozed (" + getSnoozeCount(context) + "/" + MAX_SNOOZE_COUNT + " used) for " + minutes + " minutes until " + new Date(triggerAtMs));
        return triggerAtMs;
    }

    public static void postSnoozeNotification(Context context, long nextTriggerMs, int snoozeMins) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        String timeStr = sdf.format(new Date(nextTriggerMs));

        Intent contentIntent = new Intent(context, GpsMainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(
                context,
                MorningAlarmReceiver.REQUEST_CODE_ACTIVITY,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent runIntent = new Intent(context, MorningAlarmReceiver.class);
        runIntent.setAction(MorningAlarmReceiver.ACTION_START_RUN);
        PendingIntent runPi = PendingIntent.getBroadcast(
                context,
                MorningAlarmReceiver.REQUEST_CODE_ACTION_RUN,
                runIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationChannelNames.GPSLOGGER_ALARM)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle("⏰ Alarm Snoozed (" + snoozeMins + "m)")
                .setContentText("Next alarm at " + timeStr + ". Put on your running shoes!")
                .setSubText("Discipline Mission")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(contentPi)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(0, "🏃 START NOW", runPi);

        try {
            nm.notify(NotificationChannelNames.GPSLOGGER_ALARM_NOTIFICATION_ID, builder.build());
            LOG.info("Posted Snooze countdown notification until " + timeStr);
        } catch (Exception ex) {
            LOG.warn("Failed to post snooze notification: " + ex.getMessage());
        }
    }

    public static void scheduleTestAlarm(Context context, int delaySeconds) {
        long triggerAtMs = System.currentTimeMillis() + (delaySeconds * 1000L);
        setAlarmExact(context, triggerAtMs);
        LOG.info("Test alarm scheduled in " + delaySeconds + " seconds.");
    }

    private static void setAlarmExact(Context context, long triggerAtMs) {
        setAlarmExact(context, triggerAtMs, false);
    }

    private static void setAlarmExact(Context context, long triggerAtMs, boolean isSnooze) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        getPrefs(context).edit().putLong(PREF_NEXT_TRIGGER_MS, triggerAtMs).apply();

        Intent intent = new Intent(context, MorningAlarmReceiver.class);
        intent.setAction(MorningAlarmReceiver.ACTION_TRIGGER_ALARM);
        intent.putExtra(MorningAlarmReceiver.EXTRA_FROM_SNOOZE, isSnooze);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                MorningAlarmReceiver.REQUEST_CODE_ALARM,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent showIntent = new Intent(context, GpsMainActivity.class);
        PendingIntent showPi = PendingIntent.getActivity(
                context,
                MorningAlarmReceiver.REQUEST_CODE_ALARM,
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(triggerAtMs, showPi);
        try {
            am.setAlarmClock(info, pi);
            LOG.info("Morning Alarm registered via setAlarmClock for " + new Date(triggerAtMs).toString());
        } catch (SecurityException se) {
            LOG.warn("Cannot setAlarmClock due to security exception, fallback to setExactAndAllowWhileIdle", se);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
                }
            } catch (Exception ex) {
                LOG.error("Failed to set alarm via fallback", ex);
                try {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
                } catch (Exception e) {
                    LOG.error("Complete failure setting alarm", e);
                }
            }
        }
    }

    public static void cancelAlarm(Context context) {
        // Strict discipline: Alarm cannot be cancelled or turned off!
        // Keep armed and reschedule to guarantee it is unbreakable.
        LOG.warn("cancelAlarm ignored: Morning Alarm cannot be cancelled or turned off!");
        scheduleAlarm(context);
    }

    public static void rescheduleIfEnabled(Context context) {
        if (isAlarmEnabled(context)) {
            scheduleAlarm(context);
        }
    }

    public static String getFormattedTime(int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        return sdf.format(cal.getTime());
    }

    public static String getFormattedNextAlarm(Context context) {
        if (!isAlarmEnabled(context)) {
            return "Alarm Disabled";
        }
        long next = getNextAlarmTimestamp(context);
        if (next <= 0) {
            return "Alarm Disabled";
        }
        long diff = next - System.currentTimeMillis();
        if (diff <= 0) {
            return "Alarm Due";
        }

        SimpleDateFormat sdf = new SimpleDateFormat("EEE, hh:mm a", Locale.getDefault());
        String timeStr = sdf.format(new Date(next));

        long hours = diff / (3600 * 1000L);
        long mins = (diff % (3600 * 1000L)) / (60 * 1000L);
        if (hours > 0) {
            return String.format(Locale.getDefault(), "In %dh %dm (%s)", hours, mins, timeStr);
        } else if (mins > 0) {
            return String.format(Locale.getDefault(), "In %dm (%s)", mins, timeStr);
        } else {
            return String.format(Locale.getDefault(), "In <1m (%s)", timeStr);
        }
    }

    /**
     * Triggers the alarm immediately (for test runs or when AlarmManager fires).
     */
    public void triggerAlarm() {
        mainHandler.post(() -> {
            currentState = AlarmState.RINGING;
            stationaryStartTimeMs = 0L;
            isStationarySirenSounding = false;
            isStationaryWarningSpoken = false;

            if (snoozeTimer != null) {
                snoozeTimer.cancel();
                snoozeTimer = null;
            }

            startSirenSound();

            DisciplineWebhookClient client = DisciplineWebhookClient.getInstance(context);
            voiceCoach.announceAlarmTriggered(client.getTargetDistanceMeters(), client.getTargetPaceMinPerKm());

            notifyStateChanged("🔔 ALARM RINGING! Time to run!");
            LOG.info("Morning Alarm triggered! State -> RINGING");
        });
    }

    /**
     * Snooze action: silences siren for 15 minutes.
     */
    public void snoozeAlarm() {
        mainHandler.post(() -> {
            stopSirenSound();
            currentState = AlarmState.SNOOZED;

            final int snoozeMinutes = getSnoozeDurationMinutes();
            voiceCoach.announceSnooze(snoozeMinutes);

            long nextTrigger = scheduleSnooze(context, snoozeMinutes);
            if (nextTrigger > 0) {
                postSnoozeNotification(context, nextTrigger, snoozeMinutes);
            }

            long totalSnoozeMs = snoozeMinutes * 60 * 1000L;
            snoozeRemainingMs = totalSnoozeMs;

            if (snoozeTimer != null) {
                snoozeTimer.cancel();
            }

            snoozeTimer = new CountDownTimer(totalSnoozeMs, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    snoozeRemainingMs = millisUntilFinished;
                    if (listener != null) {
                        listener.onCountdownTick(millisUntilFinished);
                    }
                }

                @Override
                public void onFinish() {
                    LOG.info("Snooze countdown finished. Restarting alarm!");
                    triggerAlarm();
                }
            }.start();

            notifyStateChanged(String.format(Locale.getDefault(), "💤 Snoozed (%d min)", snoozeMinutes));
            LOG.info("Morning Alarm snoozed for {} minutes", snoozeMinutes);
        });
    }

    /**
     * Runner started workout: silences siren and activates running mission monitor.
     */
    public void onWorkoutStarted() {
        mainHandler.post(() -> {
            stopSirenSound();
            if (snoozeTimer != null) {
                snoozeTimer.cancel();
                snoozeTimer = null;
            }

            currentState = AlarmState.RUNNING_MISSION;
            stationaryStartTimeMs = 0L;
            isStationarySirenSounding = false;
            isStationaryWarningSpoken = false;

            DisciplineWebhookClient client = DisciplineWebhookClient.getInstance(context);
            voiceCoach.announceWorkoutStart(client.getTargetDistanceMeters(), client.getTargetPaceMinPerKm());

            notifyStateChanged("🏃 Mission Active: Running");
            LOG.info("Workout started. State -> RUNNING_MISSION");
        });
    }

    /**
     * Evaluates location and speed during workout to enforce movement and stationary grace period.
     */
    public void onLocationUpdate(Location loc) {
        if (currentState != AlarmState.RUNNING_MISSION) {
            return;
        }

        float speedMps = 0.0f;
        if (loc != null && loc.hasSpeed()) {
            speedMps = loc.getSpeed();
        }

        long now = System.currentTimeMillis();

        if (speedMps < 0.5f) {
            if (stationaryStartTimeMs == 0L) {
                stationaryStartTimeMs = now;
                isStationaryWarningSpoken = false;
            }

            long stationaryElapsedSec = (now - stationaryStartTimeMs) / 1000L;
            int graceSec = getStationaryGraceSeconds();
            int remainingSec = (int) Math.max(0, graceSec - stationaryElapsedSec);

            if (stationaryElapsedSec >= (graceSec - 15) && !isStationaryWarningSpoken && remainingSec > 0) {
                isStationaryWarningSpoken = true;
                voiceCoach.announceStationaryWarning(remainingSec);
            }

            if (stationaryElapsedSec >= graceSec && !isStationarySirenSounding) {
                isStationarySirenSounding = true;
                startSirenSound();
                notifyStateChanged("⚠️ Stationary Penalty! Siren Active!");
                LOG.warn("Stationary grace period exceeded ({}s). Siren ringing!", graceSec);
            }
        } else {
            if (isStationarySirenSounding) {
                stopSirenSound();
                isStationarySirenSounding = false;
                notifyStateChanged("🏃 Movement resumed! Keep running!");
                LOG.info("Runner moving again ({} m/s). Siren stopped.", speedMps);
            }
            stationaryStartTimeMs = 0L;
            isStationaryWarningSpoken = false;
        }
    }

    /**
     * Checks workout completion against target distance and target pace.
     * Enforces user rule: If target pace is NOT matched, the alarm does not stop and restarts!
     *
     * @return true if mission fully completed and pace matched, false if pace failed and alarm restarted.
     */
    public boolean checkWorkoutFinished(double finalDistanceMeters, float avgPaceMinPerKm) {
        DisciplineWebhookClient client = DisciplineWebhookClient.getInstance(context);
        double targetDistance = client.getTargetDistanceMeters();
        float targetPace = client.getTargetPaceMinPerKm();

        LOG.info("Evaluating workout completion: distance={}/{}m, pace={}/{}",
                finalDistanceMeters, targetDistance, avgPaceMinPerKm, targetPace);

        boolean distanceMet = finalDistanceMeters >= (targetDistance * 0.95); // 95% tolerance
        boolean paceMet = avgPaceMinPerKm > 0.1f && avgPaceMinPerKm <= targetPace;

        if (distanceMet && paceMet) {
            stopSirenSound();
            resetSnoozeCount(context);
            currentState = AlarmState.COMPLETED;
            voiceCoach.announceMissionCompleted(finalDistanceMeters, avgPaceMinPerKm);
            notifyStateChanged("✅ Mission Accomplished! Alarm Disarmed!");

            client.verifyMission("morning-run", null);
            client.verifyMission("run", null);

            LOG.info("Mission SUCCESS: Targets met! Alarm disarmed.");
            return true;
        } else {
            currentState = AlarmState.RINGING;
            startSirenSound();

            if (!paceMet && distanceMet) {
                voiceCoach.announceTargetPaceNotMatched(avgPaceMinPerKm, targetPace);
                notifyStateChanged(String.format(Locale.getDefault(),
                        "🔴 Target Pace Failed (%.1f > %.1f)! Alarm Restarting!", avgPaceMinPerKm, targetPace));
            } else {
                voiceCoach.announcePaceWarning(avgPaceMinPerKm, targetPace);
                notifyStateChanged(String.format(Locale.getDefault(),
                        "⚠️ Distance/Pace not matched (%.2f km, %.1f min/km)! Alarm Restarting!",
                        finalDistanceMeters / 1000.0, avgPaceMinPerKm));
            }

            LOG.warn("Pace/Distance NOT matched! Alarm restarting until pace matches.");
            return false;
        }
    }

    public static void enforceMaxAlarmVolume(Context ctx) {
        if (ctx == null) return;
        try {
            android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM);
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, maxVol, 0);
            }
        } catch (Exception ex) {
            LOG.warn("Failed to set max alarm volume: " + ex.getMessage());
        }
    }

    private synchronized void startSirenSound() {
        enforceMaxAlarmVolume(context);
        tonePlayer.play(context, false, true);
        LOG.info("Alarm audio playback started via AlarmTonePlayer at maximum volume.");
    }

    public synchronized void stopSirenSound() {
        tonePlayer.stop();
        LOG.info("Alarm audio playback stopped via AlarmTonePlayer.");
    }

    public void resetToIdle() {
        stopSirenSound();
        if (snoozeTimer != null) {
            snoozeTimer.cancel();
            snoozeTimer = null;
        }
        currentState = AlarmState.IDLE;
        notifyStateChanged(getStateDescription());
    }

    private void notifyStateChanged(String message) {
        if (listener != null) {
            mainHandler.post(() -> listener.onAlarmStateChanged(currentState, message));
        }
    }

    public String getStateDescription() {
        switch (currentState) {
            case RINGING:
                return "🔔 ALARM RINGING! Time to run!";
            case SNOOZED:
                return String.format(Locale.getDefault(), "💤 Snoozed (%d:%02d left)",
                        (snoozeRemainingMs / 1000) / 60, (snoozeRemainingMs / 1000) % 60);
            case RUNNING_MISSION:
                return "🏃 Mission Active: Running";
            case COMPLETED:
                return "✅ Mission Completed! Alarm Disarmed";
            case IDLE:
            default:
                return String.format(Locale.getDefault(), "⏰ Alarm set for %s", getFormattedAlarmTime());
        }
    }
}
