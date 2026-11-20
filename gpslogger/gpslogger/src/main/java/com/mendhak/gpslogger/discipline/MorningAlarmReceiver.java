package com.mendhak.gpslogger.discipline;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.NotificationChannelNames;
import com.mendhak.gpslogger.common.slf4j.Logs;
import org.slf4j.Logger;

/**
 * MorningAlarmReceiver
 *
 * Catches alarm clock triggers, system boot events, package upgrades,
 * and user actions from the alarm notification shade (Start Run / Snooze).
 */
public class MorningAlarmReceiver extends BroadcastReceiver {

    private static final Logger LOG = Logs.of(MorningAlarmReceiver.class);

    public static final String ACTION_TRIGGER_ALARM = "com.mendhak.gpslogger.discipline.TRIGGER_ALARM";
    public static final String ACTION_SNOOZE_ALARM = "com.mendhak.gpslogger.discipline.SNOOZE_ALARM";
    public static final String ACTION_START_RUN = "com.mendhak.gpslogger.discipline.START_RUN";
    public static final String EXTRA_FROM_SNOOZE = "extra_from_snooze";

    public static final int REQUEST_CODE_ALARM = 8801;
    public static final int REQUEST_CODE_ACTIVITY = 8802;
    public static final int REQUEST_CODE_ACTION_RUN = 8803;
    public static final int REQUEST_CODE_ACTION_SNOOZE = 8804;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        LOG.info("MorningAlarmReceiver received action: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
            Intent.ACTION_TIME_CHANGED.equals(action) ||
            Intent.ACTION_TIMEZONE_CHANGED.equals(action) ||
            Intent.ACTION_DATE_CHANGED.equals(action) ||
            "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            LOG.info("System boot/time/upgrade event: ensuring unbreakable morning alarm is scheduled.");
            MorningAlarmManager.scheduleAlarm(context);
            return;
        }

        if (ACTION_SNOOZE_ALARM.equals(action)) {
            LOG.info("Notification action: Snooze clicked.");
            if (!MorningAlarmManager.canSnooze(context)) {
                LOG.warn("Snooze rejected: 1-time snooze limit already reached.");
                return;
            }
            int snoozeMins = MorningAlarmManager.getSnoozeMinutes(context);
            long nextTrigger = MorningAlarmManager.scheduleSnooze(context, snoozeMins);
            if (nextTrigger > 0) {
                MorningAlarmManager.postSnoozeNotification(context, nextTrigger, snoozeMins);
            }
            return;
        }

        if (ACTION_START_RUN.equals(action)) {
            LOG.info("Notification action: Start Run clicked.");
            MorningAlarmActivity.dismissNotification(context);
            MorningAlarmActivity.triggerWorkoutStart(context);
            Intent hudIntent = new Intent(context, com.mendhak.gpslogger.GpsMainActivity.class);
            hudIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(hudIntent);
            return;
        }

        if (ACTION_TRIGGER_ALARM.equals(action)) {
            boolean fromSnooze = intent != null && intent.getBooleanExtra(EXTRA_FROM_SNOOZE, false);
            if (!fromSnooze) {
                LOG.info("Fresh daily alarm triggered: resetting snooze count.");
                MorningAlarmManager.resetSnoozeCount(context);
            } else {
                LOG.info("Alarm triggered from snooze. Snooze count: " + MorningAlarmManager.getSnoozeCount(context));
            }
            triggerAlarmWakeup(context);
        }
    }

    public static void triggerImmediately(Context context) {
        Intent intent = new Intent(context, MorningAlarmReceiver.class);
        intent.setAction(ACTION_TRIGGER_ALARM);
        context.sendBroadcast(intent);
    }

    private void triggerAlarmWakeup(Context context) {
        LOG.info("Triggering Morning Alarm Wake-Up sequence!");

        // 1. Wake the CPU with a safe timeout
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "GPSLogger:MorningAlarmWakeLock"
            );
            wakeLock.acquire(30000L); // 30 sec max
        }

        // 2. Reschedule tomorrow's standard alarm so daily routine continues
        MorningAlarmManager.scheduleNextDailyAlarm(context);
        MorningAlarmManager.getInstance(context).triggerAlarm();

        // 3. Build Full Screen Intent to MorningAlarmActivity
        Intent alarmActivityIntent = new Intent(context, MorningAlarmActivity.class);
        alarmActivityIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        );

        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE_ACTIVITY,
                alarmActivityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // 4. Notification Shade quick action buttons
        Intent runIntent = new Intent(context, MorningAlarmReceiver.class);
        runIntent.setAction(ACTION_START_RUN);
        PendingIntent runPi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_ACTION_RUN,
                runIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent snoozeIntent = new Intent(context, MorningAlarmReceiver.class);
        snoozeIntent.setAction(ACTION_SNOOZE_ALARM);
        PendingIntent snoozePi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_ACTION_SNOOZE,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // 5. Post high priority full screen alarm notification
        boolean canSnooze = MorningAlarmManager.canSnooze(context);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationChannelNames.GPSLOGGER_ALARM)
                .setSmallIcon(R.mipmap.gpsloggericon3)
                .setContentTitle(canSnooze ? "⏰ Morning Alarm - Run Mission" : "⏰ FINAL WAKE-UP (NO SNOOZE)")
                .setContentText(canSnooze ? "Wake up! Put on your running shoes. No excuses." : "Snooze limit reached! Time to start your run.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(false)
                .setOngoing(true)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(0, "🏃 START NOW", runPi);

        if (canSnooze) {
            builder.addAction(0, "💤 SNOOZE (15M)", snoozePi);
        }

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NotificationChannelNames.GPSLOGGER_ALARM_NOTIFICATION_ID, builder.build());
        }

        // 6. Launch Alarm Activity directly
        try {
            context.startActivity(alarmActivityIntent);
        } catch (Exception ex) {
            LOG.error("Failed to launch MorningAlarmActivity directly", ex);
        }
    }
}
