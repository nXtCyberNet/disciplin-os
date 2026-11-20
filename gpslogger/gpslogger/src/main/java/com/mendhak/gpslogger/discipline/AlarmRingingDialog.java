package com.mendhak.gpslogger.discipline;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.mendhak.gpslogger.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Un-dismissable Ringing Alarm Popup Dialog.
 *
 * Enforces the user requirement:
 * "for alarm the popup should open at the time untill not click to snooz it will not stop,
 *  and there should be no ways to turn the alarm off"
 */
public class AlarmRingingDialog {

    private static AlertDialog activeDialog;

    public interface WorkoutActionCallback {
        void onStartWorkout();
    }

    public static synchronized void show(Activity activity, MorningAlarmManager alarmManager, WorkoutActionCallback callback) {
        if (activity == null || activity.isFinishing()) return;

        // If already showing, don't recreate
        if (activeDialog != null && activeDialog.isShowing()) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_alarm_ringing, null);
        builder.setView(view);

        builder.setCancelable(false);

        activeDialog = builder.create();

        if (activeDialog.getWindow() != null) {
            activeDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        activeDialog.setCanceledOnTouchOutside(false);

        // Block hardware Back button and volume lowering: user CANNOT exit or silence!
        activeDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                Toast.makeText(activity, "⚠️ Alarm cannot be turned off! Choose Snooze or Start Workout.", Toast.LENGTH_SHORT).show();
                return true;
            }
            if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                 keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                 keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) && event.getAction() == KeyEvent.ACTION_DOWN) {
                MorningAlarmManager.enforceMaxAlarmVolume(activity);
                Toast.makeText(activity, "⚠️ Alarm volume cannot be turned down!", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });

        TextView txtTime = view.findViewById(R.id.ringing_txt_time);
        TextView txtTarget = view.findViewById(R.id.ringing_txt_target);
        Button btnStartWorkout = view.findViewById(R.id.ringing_btn_start_workout);
        Button btnSnooze = view.findViewById(R.id.ringing_btn_snooze);

        if (txtTime != null) {
            txtTime.setText(new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date()));
        }

        DisciplineWebhookClient client = DisciplineWebhookClient.getInstance(activity);
        if (txtTarget != null) {
            txtTarget.setText(String.format(Locale.getDefault(),
                    "🎯 TARGET: %.2f km @ < %.1f min/km",
                    client.getTargetDistanceMeters() / 1000.0,
                    client.getTargetPaceMinPerKm()));
        }

        if (btnSnooze != null) {
            int snoozeMins = alarmManager != null ? alarmManager.getSnoozeDurationMinutes() : 15;
            btnSnooze.setText(String.format(Locale.getDefault(), "💤 SNOOZE (%d MIN)", snoozeMins));
            btnSnooze.setOnClickListener(v -> {
                dismissIfShowing();
                if (alarmManager != null) {
                    alarmManager.snoozeAlarm();
                }
            });
        }

        if (btnStartWorkout != null) {
            btnStartWorkout.setOnClickListener(v -> {
                dismissIfShowing();
                if (callback != null) {
                    callback.onStartWorkout();
                }
            });
        }

        activeDialog.show();
    }

    public static synchronized void dismissIfShowing() {
        if (activeDialog != null) {
            try {
                if (activeDialog.isShowing()) {
                    activeDialog.dismiss();
                }
            } catch (Exception e) {
                // ignore
            }
            activeDialog = null;
        }
    }

    public static synchronized boolean isShowing() {
        return activeDialog != null && activeDialog.isShowing();
    }
}
