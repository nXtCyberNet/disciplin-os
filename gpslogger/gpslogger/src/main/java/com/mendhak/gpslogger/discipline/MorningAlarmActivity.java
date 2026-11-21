package com.mendhak.gpslogger.discipline;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.mendhak.gpslogger.GpsLoggingService;
import com.mendhak.gpslogger.GpsMainActivity;
import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.NotificationChannelNames;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.common.Session;
import com.mendhak.gpslogger.common.events.CommandEvents;
import com.mendhak.gpslogger.common.slf4j.Logs;

import org.slf4j.Logger;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.greenrobot.event.EventBus;

/**
 * MorningAlarmActivity
 *
 * Full-screen wakeup activity that bursts through the lock screen,
 * sounds the morning alarm ringtone, vibrates, and gives two direct choices:
 *
 * 1. [🏃 START RUN & WAKE UP] -> Silences alarm, auto-starts GPS logging,
 *    submits morning-run webhook to Discipline Lock, and launches Health Hub HUD.
 * 2. [💤 SNOOZE (15 MIN)] -> Silences alarm and schedules an exact 15-minute re-ring.
 */
public class MorningAlarmActivity extends AppCompatActivity {

    private static final Logger LOG = Logs.of(MorningAlarmActivity.class);

    private TextView txtTime;
    private TextView txtAmPm;
    private TextView txtDate;
    private TextView txtTarget;
    private Button btnStartRun;
    private Button btnSnooze;

    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private boolean isHandled = false;

    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            updateClockDisplay();
            clockHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        configureWindowForLockscreen();

        setContentView(R.layout.activity_morning_alarm);

        bindViews();
        setupActions();
        startAlarmAudioAndVibration();

        updateClockDisplay();
        clockHandler.postDelayed(clockRunnable, 1000);
    }

    private void configureWindowForLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF121212);
            getWindow().setNavigationBarColor(0xFF121212);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
    }

    private void bindViews() {
        txtTime = findViewById(R.id.alarm_txt_time);
        txtAmPm = findViewById(R.id.alarm_txt_ampm);
        txtDate = findViewById(R.id.alarm_txt_date);
        txtTarget = findViewById(R.id.alarm_txt_target);
        btnStartRun = findViewById(R.id.alarm_btn_start_run);
        btnSnooze = findViewById(R.id.alarm_btn_snooze);

        boolean canSnooze = MorningAlarmManager.canSnooze(this);
        if (btnSnooze != null) {
            if (!canSnooze) {
                btnSnooze.setEnabled(false);
                btnSnooze.setAlpha(0.4f);
                btnSnooze.setText("🚫 NO MORE SNOOZES (MAX 1)");
            } else {
                btnSnooze.setEnabled(true);
                btnSnooze.setAlpha(1.0f);
                btnSnooze.setText("💤 SNOOZE (15 MIN)");
            }
        }

        if (txtTarget != null) {
            try {
                DisciplineWebhookClient client = DisciplineWebhookClient.getInstance(this);
                double distKm = client.getTargetDistanceMeters() / 1000.0;
                float pace = client.getTargetPaceMinPerKm();
                if (distKm > 0) {
                    int paceMin = (int) pace;
                    int paceSec = (int) ((pace - paceMin) * 60);
                    txtTarget.setText(String.format(Locale.getDefault(), "🏃 Target: %.1f km • Max Pace: %d:%02d/km", distKm, paceMin, paceSec));
                }
            } catch (Exception ex) {
                LOG.warn("Could not bind target text: " + ex.getMessage());
            }
        }
    }

    private void setupActions() {
        btnStartRun.setOnClickListener(v -> onStartRunClicked());
        btnSnooze.setOnClickListener(v -> onSnoozeClicked());
    }

    private void updateClockDisplay() {
        Date now = new Date();
        if (txtTime != null) {
            txtTime.setText(new SimpleDateFormat("hh:mm:ss", Locale.getDefault()).format(now));
        }
        if (txtAmPm != null) {
            txtAmPm.setText(new SimpleDateFormat("a", Locale.getDefault()).format(now));
        }
        if (txtDate != null) {
            txtDate.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now));
        }
    }

    private synchronized void startAlarmAudioAndVibration() {
        AlarmTonePlayer.getInstance().play(this, MorningAlarmManager.getInstance(this).isMuted(), true);

        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 800, 500, 800, 500, 1000};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception ex) {
            LOG.error("Failed to start alarm vibration", ex);
        }
    }

    private synchronized void stopAlarmAudioAndVibration() {
        AlarmTonePlayer.getInstance().stop();

        try {
            if (vibrator != null) {
                vibrator.cancel();
                vibrator = null;
            }
        } catch (Exception ex) {
            LOG.error("Error stopping vibrator", ex);
        }
    }

    private void onStartRunClicked() {
        if (isHandled) return;
        isHandled = true;

        LOG.info("Morning Run started from alarm screen!");
        stopAlarmAudioAndVibration();
        dismissNotification(this);

        // Auto start GPS workout and dispatch webhook
        triggerWorkoutStart(this);

        // Request keyguard dismissal if locked so HUD opens cleanly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                km.requestDismissKeyguard(this, null);
            }
        }

        // Launch HUD screen directly
        Intent hudIntent = new Intent(this, GpsMainActivity.class);
        hudIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(hudIntent);

        Toast.makeText(this, "🏃 Morning Run Started! Tracking GPS.", Toast.LENGTH_LONG).show();
        finish();
    }

    private void onSnoozeClicked() {
        if (isHandled) return;

        if (!MorningAlarmManager.canSnooze(this)) {
            Toast.makeText(this, "🚫 Snooze limit reached (Max 1)! You must start your run now.", Toast.LENGTH_LONG).show();
            return;
        }

        isHandled = true;
        LOG.info("Alarm snoozed from alarm screen!");
        stopAlarmAudioAndVibration();
        dismissNotification(this);

        try {
            MorningAlarmManager.getInstance(this).snoozeAlarm();
        } catch (Exception ex) {
            LOG.error("Error invoking snoozeAlarm", ex);
        }

        finish();
    }

    public static void triggerWorkoutStart(Context context) {
        Session session = Session.getInstance();
        if (!session.isStarted()) {
            LOG.info("Auto-starting GPSLoggingService from Morning Alarm!");
            PreferenceHelper.getInstance().setMinimumLoggingInterval(1);
            Intent serviceIntent = new Intent(context, GpsLoggingService.class);
            ContextCompat.startForegroundService(context, serviceIntent);
            EventBus.getDefault().post(new CommandEvents.RequestStartStop(true));
        }

        // Inform Discipline Lock (port 8080) of morning-run completion/start
        DisciplineWebhookClient.getInstance(context).verifyMission("morning-run", new DisciplineWebhookClient.WebhookCallback() {
            @Override
            public void onSuccess(String responseBody) {
                LOG.info("Successfully synced morning-run mission with Discipline Lock: " + responseBody);
            }

            @Override
            public void onFailure(String errorMessage) {
                LOG.warn("Failed to sync morning-run with Discipline Lock: " + errorMessage);
            }
        });
    }

    public static void dismissNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NotificationChannelNames.GPSLOGGER_ALARM_NOTIFICATION_ID);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            MorningAlarmManager.enforceMaxAlarmVolume(this);
            Toast.makeText(this, "⚠️ Alarm volume cannot be turned down!", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // Strict discipline: Alarm cannot be turned off by Back key!
        Toast.makeText(this, "⚠️ Alarm cannot be turned off! Choose Snooze or Start Run.", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        clockHandler.removeCallbacksAndMessages(null);
        stopAlarmAudioAndVibration();
        super.onDestroy();
    }
}
