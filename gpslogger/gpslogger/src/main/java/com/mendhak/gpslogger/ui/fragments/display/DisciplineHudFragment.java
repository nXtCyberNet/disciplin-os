package com.mendhak.gpslogger.ui.fragments.display;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;

import com.mendhak.gpslogger.GpsMainActivity;
import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.EventBusHook;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.common.Session;
import com.mendhak.gpslogger.common.events.CommandEvents;
import com.mendhak.gpslogger.common.events.ServiceEvents;
import com.mendhak.gpslogger.discipline.AlarmRingingDialog;
import com.mendhak.gpslogger.discipline.AlarmRingtoneDialog;
import com.mendhak.gpslogger.discipline.AlarmTonePlayer;
import com.mendhak.gpslogger.discipline.DisciplineVoiceCoach;
import com.mendhak.gpslogger.discipline.DisciplineWebhookClient;
import com.mendhak.gpslogger.discipline.KmSplit;
import com.mendhak.gpslogger.discipline.MorningAlarmManager;
import com.mendhak.gpslogger.discipline.RouteMapView;
import com.mendhak.gpslogger.discipline.SplitCalculator;
import com.mendhak.gpslogger.discipline.StepCounterManager;
import com.mendhak.gpslogger.discipline.WorkoutDatabaseHelper;
import com.mendhak.gpslogger.discipline.WorkoutSummary;
import com.mendhak.gpslogger.discipline.WorkoutSummaryDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.greenrobot.event.EventBus;

/**
 * Health Hub / Discipline Workout Screen
 *
 * Implements the design from Image 1 and Image 2:
 * - Pre-workout: Objective first (Distance Target, Target pace), Last Pace, Last Distance, Route Preview, Start Workout.
 * - Active workout: Live duration, Distance, Pace Zones (ahead/on pace/slow), Speed, Cadence, Live Kilometer Splits.
 * - Automatic workout detection: Movement prompt when running without active session; stationary penalty when stopped.
 * - Automatic workout verification: Verifies directly to Discipline OS upon target completion.
 */
public class DisciplineHudFragment extends GenericViewFragment implements
        StepCounterManager.StepListener,
        DisciplineWebhookClient.SyncStatusListener,
        MorningAlarmManager.AlarmStateListener {

    private Session session = Session.getInstance();
    private StepCounterManager stepManager;
    private DisciplineWebhookClient webhookClient;
    private DisciplineVoiceCoach voiceCoach;
    private MorningAlarmManager alarmManager;

    // Header & Status
    private LinearLayout pillContainer;
    private TextView txtSyncStatus;
    private TextView txtGpsReadyBadge;
    private TextView txtGpsStatus;
    private ImageView btnSettingsGear;

    // Morning Alarm Card
    private LinearLayout cardMorningAlarm;
    private TextView txtAlarmHeader;
    private TextView txtAlarmStatus;
    private TextView txtAlarmSubstatus;
    private Button btnVoiceToggle;
    private Button btnAlarmTune;
    private LinearLayout layoutAlarmActions;
    private Button btnAlarmSnooze;

    // Containers: Pre-workout vs Active
    private LinearLayout layoutPreWorkout;
    private LinearLayout layoutActiveWorkout;

    // Pre-workout Views (Image 1)
    private LinearLayout rowTargetDistance;
    private TextView txtTargetNumber;
    private TextView txtTargetUnit;
    private TextView txtTargetPaceSubtitle;
    private TextView txtLastPace;
    private TextView txtLastDistance;
    private RouteMapView routeMapView;
    private Button btnMapExpand;
    private Button btnWorkout;
    private TextView btnRunHistoryLink;
    private TextView btnReturnDiscipline;

    // Active Workout Views
    private TextView txtDuration;
    private TextView txtDistance;
    private TextView txtDistanceUnit;
    private TextView txtTargetDistance;
    private TextView txtPace;
    private TextView txtPaceMin;
    private TextView txtTargetPace;
    private TextView txtPaceBadge;
    private View meterAhead;
    private View meterWithin;
    private View meterSlow;
    private TextView txtSpeed;
    private TextView txtSpeedKmh;
    private TextView txtSteps;
    private TextView txtCadence;
    private LinearLayout layoutSplitsList;
    private TextView txtSplitsEmpty;
    private Button btnWorkoutFinish;
    private TextView txtAutoVerifyNotice;

    // State Tracking
    private boolean isMapExpanded = false;
    private LocationListener preWorkoutLocationListener;
    private Location lastPreWorkoutLocation;
    private long lastPreWorkoutTimestamp = 0;
    private Location lastLocationForSpeed;
    private long lastLocationTimeForSpeed = 0;
    private int consecutiveRunningReadings = 0;
    private boolean isMovementPromptShowing = false;
    private boolean hasAutoVerified = false;
    private long lastSplitRecordedKm = 0;
    private long lastSplitTimestamp = 0;
    private final List<KmSplit> activeSplits = new ArrayList<>();

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            updateDurationTimer();
            checkAutoVerification();
            if (session.isStarted()) {
                timerHandler.postDelayed(this, 1000);
            }
        }
    };

    public DisciplineHudFragment() {
    }

    public static DisciplineHudFragment newInstance() {
        return new DisciplineHudFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_discipline_hud, container, false);

        stepManager = StepCounterManager.getInstance(requireContext());
        webhookClient = DisciplineWebhookClient.getInstance(requireContext());
        voiceCoach = DisciplineVoiceCoach.getInstance(requireContext());
        alarmManager = MorningAlarmManager.getInstance(requireContext());

        bindViews(view);
        setupListeners();

        updatePreWorkoutStats();
        updateDisplayMode(session.isStarted());
        updateTargetsDisplay();
        updateAlarmStatusCard();
        updateTuneButton();
        updateVoiceButton();

        if (session.hasValidLocation()) {
            displayLocationInfo(session.getCurrentLocationInfo());
        }
        updateDurationTimer();

        return view;
    }

    private void bindViews(View v) {
        pillContainer = v.findViewById(R.id.hud_pill_container);
        txtSyncStatus = v.findViewById(R.id.hud_txt_sync_status);
        txtGpsReadyBadge = v.findViewById(R.id.hud_txt_gps_ready_badge);
        txtGpsStatus = v.findViewById(R.id.hud_txt_gps_status);
        btnSettingsGear = v.findViewById(R.id.hud_btn_settings_gear);

        cardMorningAlarm = v.findViewById(R.id.hud_card_morning_alarm);
        txtAlarmHeader = v.findViewById(R.id.hud_txt_alarm_header);
        txtAlarmStatus = v.findViewById(R.id.hud_txt_alarm_status);
        txtAlarmSubstatus = v.findViewById(R.id.hud_txt_alarm_substatus);
        btnAlarmTune = v.findViewById(R.id.hud_btn_alarm_tune);
        btnVoiceToggle = v.findViewById(R.id.hud_btn_voice_toggle);
        layoutAlarmActions = v.findViewById(R.id.hud_layout_alarm_actions);
        btnAlarmSnooze = v.findViewById(R.id.hud_btn_alarm_snooze);

        layoutPreWorkout = v.findViewById(R.id.hud_layout_pre_workout);
        layoutActiveWorkout = v.findViewById(R.id.hud_layout_active_workout);

        rowTargetDistance = v.findViewById(R.id.hud_row_target_distance);
        txtTargetNumber = v.findViewById(R.id.hud_txt_target_number);
        txtTargetUnit = v.findViewById(R.id.hud_txt_target_unit);
        txtTargetPaceSubtitle = v.findViewById(R.id.hud_txt_target_pace_subtitle);
        txtLastPace = v.findViewById(R.id.hud_txt_last_pace);
        txtLastDistance = v.findViewById(R.id.hud_txt_last_distance);
        routeMapView = v.findViewById(R.id.hud_map_view);
        btnMapExpand = v.findViewById(R.id.hud_btn_map_expand);
        btnWorkout = v.findViewById(R.id.hud_btn_workout);
        btnRunHistoryLink = v.findViewById(R.id.hud_btn_run_history_link);
        btnReturnDiscipline = v.findViewById(R.id.hud_btn_return_discipline);

        txtDuration = v.findViewById(R.id.hud_txt_duration);
        txtDistance = v.findViewById(R.id.hud_txt_distance);
        txtDistanceUnit = v.findViewById(R.id.hud_txt_distance_unit);
        txtTargetDistance = v.findViewById(R.id.hud_txt_target_distance);
        txtPace = v.findViewById(R.id.hud_txt_pace);
        txtPaceMin = v.findViewById(R.id.hud_txt_pace_min);
        txtTargetPace = v.findViewById(R.id.hud_txt_target_pace);
        txtPaceBadge = v.findViewById(R.id.hud_txt_pace_badge);
        meterAhead = v.findViewById(R.id.hud_meter_ahead);
        meterWithin = v.findViewById(R.id.hud_meter_within);
        meterSlow = v.findViewById(R.id.hud_meter_slow);
        txtSpeed = v.findViewById(R.id.hud_txt_speed);
        txtSpeedKmh = v.findViewById(R.id.hud_txt_speed_kmh);
        txtSteps = v.findViewById(R.id.hud_txt_steps);
        txtCadence = v.findViewById(R.id.hud_txt_cadence);
        layoutSplitsList = v.findViewById(R.id.hud_layout_splits_list);
        txtSplitsEmpty = v.findViewById(R.id.hud_txt_splits_empty);
        btnWorkoutFinish = v.findViewById(R.id.hud_btn_workout_finish);
        txtAutoVerifyNotice = v.findViewById(R.id.hud_txt_auto_verify_notice);
    }

    private void setupListeners() {
        // Morning Alarm controls
        if (btnAlarmTune != null) {
            btnAlarmTune.setOnClickListener(v -> showRingtoneSelectionDialog());
        }

        if (btnAlarmSnooze != null) {
            btnAlarmSnooze.setOnClickListener(v -> alarmManager.snoozeAlarm());
        }
        if (btnVoiceToggle != null) {
            btnVoiceToggle.setOnClickListener(v -> {
                boolean next = !voiceCoach.isVoiceEnabled();
                voiceCoach.setVoiceEnabled(next);
                updateVoiceButton();
                Toast.makeText(getContext(), next ? "Voice Coach: ON" : "Voice Coach: MUTED", Toast.LENGTH_SHORT).show();
            });
        }

        // Start Workout Button
        if (btnWorkout != null) {
            btnWorkout.setOnClickListener(v -> startWorkoutSession());
        }

        // Finish Workout Button
        if (btnWorkoutFinish != null) {
            btnWorkoutFinish.setOnClickListener(v -> finishWorkoutSession(false));
        }

        // Target row click: customize distance & pace
        if (rowTargetDistance != null) {
            rowTargetDistance.setOnClickListener(v -> showAdjustTargetsDialog());
        }

        // Map Expand
        if (btnMapExpand != null) {
            btnMapExpand.setOnClickListener(v -> toggleMapExpansion());
        }

        // Settings Gear Icon
        if (btnSettingsGear != null) {
            btnSettingsGear.setOnClickListener(v -> {
                if (getActivity() instanceof GpsMainActivity) {
                    ((GpsMainActivity) getActivity()).toggleDrawer();
                }
            });
        }

        // Bottom Links
        if (btnRunHistoryLink != null) {
            btnRunHistoryLink.setOnClickListener(v -> {
                if (getActivity() instanceof GpsMainActivity) {
                    ((GpsMainActivity) getActivity()).selectTab(3); // Switch to History
                }
            });
        }
        if (btnReturnDiscipline != null) {
            btnReturnDiscipline.setOnClickListener(v -> returnToDisciplineLock());
        }

        // Sync Pill Container: tap to check health
        if (pillContainer != null) {
            pillContainer.setOnClickListener(v -> {
                txtSyncStatus.setText(R.string.hud_status_checking);
                updatePillColor(0);
                webhookClient.checkHealth(new DisciplineWebhookClient.WebhookCallback() {
                    @Override
                    public void onSuccess(String responseBody) {
                        Toast.makeText(getContext(), "Discipline Lock connection active!", Toast.LENGTH_SHORT).show();
                        updateTargetsDisplay();
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        Toast.makeText(getContext(), "Discipline Lock unreachable: " + errorMessage, Toast.LENGTH_SHORT).show();
                    }
                });
            });
        }
    }

    private void startWorkoutSession() {
        if (!session.isStarted()) {
            hasAutoVerified = false;
            lastSplitRecordedKm = 0;
            lastSplitTimestamp = System.currentTimeMillis();
            activeSplits.clear();
            if (layoutSplitsList != null) {
                layoutSplitsList.removeAllViews();
            }

            PreferenceHelper.getInstance().setMinimumLoggingInterval(1);
            PreferenceHelper.getInstance().setMinimumDistanceInMeters(0);
            alarmManager.onWorkoutStarted();
            requestToggleLogging();

            updateDisplayMode(true);
            stepManager.startTracking();
            webhookClient.startWorkoutStreaming();
            timerHandler.post(timerRunnable);
        } else {
            alarmManager.onWorkoutStarted();
        }
    }

    private void finishWorkoutSession(boolean wasAutoVerified) {
        if (session.isStarted()) {
            requestToggleLogging();
            stepManager.stopTracking();
            webhookClient.stopWorkoutStreaming();
            timerHandler.removeCallbacks(timerRunnable);
            updateDisplayMode(false);
            showPostRunSummary(wasAutoVerified);
        }
    }

    private void updateDisplayMode(boolean isRunning) {
        if (layoutPreWorkout != null) {
            layoutPreWorkout.setVisibility(isRunning ? View.GONE : View.VISIBLE);
        }
        if (layoutActiveWorkout != null) {
            layoutActiveWorkout.setVisibility(isRunning ? View.VISIBLE : View.GONE);
        }
        updatePreWorkoutStats();
    }

    private void updatePreWorkoutStats() {
        if (getContext() == null) return;
        WorkoutDatabaseHelper db = WorkoutDatabaseHelper.getInstance(requireContext());
        WorkoutDatabaseHelper.WorkoutRecord last = db.getLastWorkout();

        if (txtLastPace != null) {
            txtLastPace.setText(last != null ? last.getFormattedPace() : "--:-- /km");
        }
        if (txtLastDistance != null) {
            txtLastDistance.setText(last != null ? last.getFormattedDistance() : "-- km");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (routeMapView != null) {
            routeMapView.onResume();
        }
        stepManager.setListener(this);
        webhookClient.setStatusListener(this);
        if (alarmManager != null) {
            alarmManager.setListener(this);
            updateAlarmStatusCard();
            if (alarmManager.getCurrentState() == MorningAlarmManager.AlarmState.RINGING) {
                AlarmRingingDialog.show(getActivity(), alarmManager, this::startWorkoutSession);
            }
        }
        updateTuneButton();
        updateVoiceButton();
        initPreWorkoutGps();
        updatePreWorkoutStats();

        if (session.isStarted()) {
            updateDisplayMode(true);
            stepManager.startTracking();
            webhookClient.startWorkoutStreaming();
            timerHandler.post(timerRunnable);
        } else {
            updateDisplayMode(false);
            webhookClient.checkHealth(null);
        }
    }

    @Override
    public void onPause() {
        if (alarmManager != null) {
            alarmManager.setListener(null);
        }
        stopPreWorkoutGps();
        if (routeMapView != null) {
            routeMapView.onPause();
        }
        timerHandler.removeCallbacks(timerRunnable);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        AlarmRingingDialog.dismissIfShowing();
        stopPreWorkoutGps();
        stepManager.setListener(null);
        webhookClient.setStatusListener(null);
        if (alarmManager != null) {
            alarmManager.setListener(null);
        }
        timerHandler.removeCallbacks(timerRunnable);
        super.onDestroy();
    }

    @Override
    public void onStepCountUpdated(int sessionSteps, int cadenceSpm) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (txtSteps != null) txtSteps.setText(String.format(Locale.getDefault(), "%,d", sessionSteps));
            if (txtCadence != null) txtCadence.setText(cadenceSpm + " spm");
        });
    }

    @Override
    public void onSyncStatusChanged(boolean isConnected, String message) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (txtSyncStatus != null) {
                txtSyncStatus.setText(isConnected ? "🟢 Linked" : "🔴 Disconnected");
            }
            updatePillColor(isConnected ? 1 : 2);
            updateTargetsDisplay();
        });
    }

    @EventBusHook
    public void onEventMainThread(ServiceEvents.LocationUpdate locationUpdate) {
        if (locationUpdate != null && locationUpdate.location != null) {
            displayLocationInfo(locationUpdate.location);
        }
    }

    @EventBusHook
    public void onEventMainThread(ServiceEvents.LoggingStatus loggingStatus) {
        if (loggingStatus != null) {
            updateDisplayMode(loggingStatus.loggingStarted);
        }
    }

    protected void displayLocationInfo(Location loc) {
        if (loc == null || getActivity() == null) return;

        // 1. Update Map View
        if (routeMapView != null) {
            routeMapView.setCurrentLocation(loc);
        }

        // 2. Metrics & Pace Zones
        double distanceMeters = session.getTotalTravelled();
        float speedMps = loc.hasSpeed() && loc.getSpeed() > 0 ? loc.getSpeed() : 0.0f;
        if (speedMps <= 0.0f && lastLocationForSpeed != null) {
            long nowTime = loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis();
            long dtMs = nowTime - lastLocationTimeForSpeed;
            if (dtMs > 100 && dtMs < 10000) {
                speedMps = lastLocationForSpeed.distanceTo(loc) / (dtMs / 1000.0f);
            }
        }
        lastLocationForSpeed = loc;
        lastLocationTimeForSpeed = loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis();

        float paceMinPerKm = speedMps > 0.1f ? (1000.0f / speedMps) / 60.0f : 0.0f;
        float targetPace = webhookClient.getTargetPaceMinPerKm();

        // 3. Automatic Workout Detection: Prompt if user starts running without active session
        if (!session.isStarted()) {
            if (speedMps >= 1.8f) { // ~6.5 km/h running movement
                consecutiveRunningReadings++;
                if (consecutiveRunningReadings >= 3 && !isMovementPromptShowing) {
                    promptUserToStartWorkout(speedMps);
                }
            } else {
                consecutiveRunningReadings = 0;
            }
        }

        // 4. Update Alarm Manager Stationary Monitor
        if (alarmManager != null) {
            alarmManager.onLocationUpdate(loc);
        }

        // 5. Active Workout Pace Zones & Live Kilometer Split Tracking
        if (session.isStarted()) {
            updatePaceZones(speedMps, paceMinPerKm, targetPace);
            checkKilometerSplitCrossing(distanceMeters, targetPace);
        }

        // 6. UI Updates
        updateGpsCoordinatesDisplay(loc);
        updateTargetsDisplay();

        if (txtDistance != null) {
            txtDistance.setText(String.format(Locale.US, "%.2f", distanceMeters / 1000.0));
        }
        if (txtSpeed != null) {
            txtSpeed.setText(String.format(Locale.US, "%.1f m/s", speedMps));
        }
        if (txtSpeedKmh != null) {
            txtSpeedKmh.setText(String.format(Locale.US, "%.1f km/h", speedMps * 3.6f));
        }
        if (txtPace != null) {
            if (paceMinPerKm > 0.1f && !Float.isInfinite(paceMinPerKm)) {
                int pMin = (int) paceMinPerKm;
                int pSec = Math.round((paceMinPerKm - pMin) * 60.0f);
                if (pSec >= 60) { pMin++; pSec = 0; }
                txtPace.setText(String.format(Locale.US, "%d:%02d", pMin, pSec));
            } else {
                txtPace.setText("--:--");
            }
        }
    }

    /**
     * Feature 1: Automatic Workout Detection.
     * Prompts the runner to confirm starting the workout.
     */
    private void promptUserToStartWorkout(float speedMps) {
        if (getActivity() == null || isMovementPromptShowing) return;
        isMovementPromptShowing = true;
        consecutiveRunningReadings = 0;

        new AlertDialog.Builder(requireContext())
                .setTitle("🏃 Running Movement Detected!")
                .setMessage(String.format(Locale.getDefault(),
                        "You appear to be running (%.1f km/h). Would you like to start your morning workout?",
                        speedMps * 3.6f))
                .setPositiveButton("Start Workout", (dialog, which) -> {
                    isMovementPromptShowing = false;
                    startWorkoutSession();
                })
                .setNegativeButton("Dismiss", (dialog, which) -> {
                    isMovementPromptShowing = false;
                    dialog.dismiss();
                })
                .setOnCancelListener(dialog -> isMovementPromptShowing = false)
                .show();
    }

    /**
     * Feature 2: Pace Zones (Ahead / On Pace / Slow).
     */
    private void updatePaceZones(float speedMps, float currentPace, float targetPace) {
        if (txtPaceBadge == null) return;

        if (speedMps < 0.5f) {
            txtPaceBadge.setText("⚪ Stopped");
            txtPaceBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.hud_text_secondary));
            setMeterHighlight(0);
            return;
        }

        if (currentPace < targetPace - 0.4f) {
            // Zone 1: Fast / Ahead
            txtPaceBadge.setText(String.format(Locale.US, "⚡ Fast (%.1f /km)", currentPace));
            txtPaceBadge.setTextColor(Color.parseColor("#38BDF8"));
            setMeterHighlight(1);
        } else if (currentPace <= targetPace + 0.2f) {
            // Zone 2: Within Target / On Pace
            txtPaceBadge.setText(String.format(Locale.US, "🟢 On Pace (%.1f /km)", currentPace));
            txtPaceBadge.setTextColor(Color.parseColor("#10B981"));
            setMeterHighlight(2);
        } else {
            // Zone 3: Below Target / Slow
            txtPaceBadge.setText(String.format(Locale.US, "🔴 Slow (%.1f /km)", currentPace));
            txtPaceBadge.setTextColor(Color.parseColor("#EF4444"));
            setMeterHighlight(3);
            voiceCoach.announcePaceWarning(currentPace, targetPace);
        }
    }

    private void setMeterHighlight(int zone) {
        if (meterAhead != null) meterAhead.setAlpha(zone == 1 ? 1.0f : 0.25f);
        if (meterWithin != null) meterWithin.setAlpha(zone == 2 ? 1.0f : 0.25f);
        if (meterSlow != null) meterSlow.setAlpha(zone == 3 ? 1.0f : 0.25f);
    }

    /**
     * Feature 3: Real-Time Kilometer Split Tracking.
     */
    private void checkKilometerSplitCrossing(double distanceMeters, float targetPace) {
        long currentKm = (long) (distanceMeters / 1000.0);
        if (currentKm > lastSplitRecordedKm && currentKm >= 1) {
            long now = System.currentTimeMillis();
            long splitDurationSec = Math.max(1, (now - lastSplitTimestamp) / 1000L);
            lastSplitTimestamp = now;
            lastSplitRecordedKm = currentKm;

            float splitPaceMinKm = (splitDurationSec / 60.0f);
            boolean onPace = splitPaceMinKm <= targetPace;
            KmSplit split = new KmSplit((int) currentKm, 1000.0, splitDurationSec * 1000L, splitPaceMinKm, onPace);
            activeSplits.add(split);

            // Announce via TTS Voice Coach
            voiceCoach.announceSplit((int) currentKm, splitPaceMinKm, onPace);

            // Dynamically add split item view into HUD
            if (layoutSplitsList != null && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (txtSplitsEmpty != null) txtSplitsEmpty.setVisibility(View.GONE);

                    TextView splitView = new TextView(requireContext());
                    int min = (int) splitPaceMinKm;
                    int sec = Math.round((splitPaceMinKm - min) * 60f);
                    if (sec >= 60) { min++; sec = 0; }

                    splitView.setText(String.format(Locale.getDefault(),
                            "Km %d • %d:%02d /km • %02d:%02d  %s",
                            currentKm, min, sec, splitDurationSec / 60, splitDurationSec % 60,
                            onPace ? "🟢 On Pace" : "🔴 Slower"));
                    splitView.setTextColor(onPace ? Color.parseColor("#10B981") : Color.parseColor("#F87171"));
                    splitView.setTextSize(12f);
                    splitView.setPadding(0, 4, 0, 4);
                    layoutSplitsList.addView(splitView, 0);
                });
            }
        }
    }

    /**
     * Feature 4: Automatic Workout Verification upon target achievement.
     */
    private void checkAutoVerification() {
        if (!session.isStarted() || hasAutoVerified) return;

        double currentDist = session.getTotalTravelled();
        double targetDist = webhookClient.getTargetDistanceMeters();
        float targetPace = webhookClient.getTargetPaceMinPerKm();

        long durationMs = Math.max(1000L, System.currentTimeMillis() - session.getStartTimeStamp());
        float speedMps = (float) (currentDist / (durationMs / 1000.0));
        float avgPace = speedMps > 0.1f ? (1000.0f / speedMps) / 60.0f : 0.0f;

        if (currentDist >= targetDist && avgPace > 0.1f && avgPace <= targetPace) {
            hasAutoVerified = true;
            finishWorkoutSession(true);
        }
    }

    private void showPostRunSummary(boolean wasAutoVerified) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            double finalDistance = Math.max(session.getTotalTravelled(), webhookClient.getLastReportedDistanceMeters());
            long durationMs = Math.max(1000L, System.currentTimeMillis() - session.getStartTimeStamp());
            double durationSec = durationMs / 1000.0;
            float speedMps = durationSec > 0 ? (float) (finalDistance / durationSec) : 0.0f;
            float pace = speedMps > 0.1f ? (1000.0f / speedMps) / 60.0f : 0.0f;
            float targetPace = webhookClient.getTargetPaceMinPerKm();
            double targetDist = webhookClient.getTargetDistanceMeters();

            List<KmSplit> splits = new ArrayList<>(activeSplits);
            if (splits.isEmpty() && routeMapView != null) {
                splits = SplitCalculator.calculateSplits(routeMapView.getPoints(), targetPace);
            }

            WorkoutSummary summary = new WorkoutSummary(
                    finalDistance, durationMs, speedMps, pace, targetDist, targetPace,
                    stepManager.getSessionSteps(), stepManager.getLiveCadence(), splits
            );
            webhookClient.saveLastWorkoutSummary(summary);

            // Enforce pace verification & alarm disarming
            boolean completed = alarmManager.checkWorkoutFinished(finalDistance, pace);

            // Persist to WorkoutDatabaseHelper
            WorkoutDatabaseHelper.getInstance(requireContext()).insertWorkout(summary, completed);
            updatePreWorkoutStats();

            WorkoutSummaryDialog.show(getContext(), summary);
        });
    }

    private void updateTargetsDisplay() {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            double targetMeters = webhookClient.getTargetDistanceMeters();
            float targetPace = webhookClient.getTargetPaceMinPerKm();

            // Image 1 Target section
            if (txtTargetNumber != null) {
                txtTargetNumber.setText(String.format(Locale.US, "%.2f", targetMeters / 1000.0));
            }
            if (txtTargetPaceSubtitle != null) {
                int tMin = (int) targetPace;
                int tSec = Math.round((targetPace - tMin) * 60.0f);
                if (tSec >= 60) { tMin++; tSec = 0; }
                txtTargetPaceSubtitle.setText(String.format(Locale.getDefault(), "Target pace below %d:%02d /km", tMin, tSec));
            }

            // Active workout target indicators
            double currentMeters = session.getTotalTravelled();
            double remainingMeters = Math.max(0, targetMeters - currentMeters);
            if (txtTargetDistance != null) {
                txtTargetDistance.setText(String.format(Locale.getDefault(),
                        "🎯 Target: %.2f km (%.2f km left)", targetMeters / 1000.0, remainingMeters / 1000.0));
            }
            if (txtTargetPace != null) {
                int tMin = (int) targetPace;
                int tSec = Math.round((targetPace - tMin) * 60.0f);
                if (tSec >= 60) { tMin++; tSec = 0; }
                txtTargetPace.setText(String.format(Locale.getDefault(), "🎯 Target: < %d:%02d /km", tMin, tSec));
            }
            if (txtAutoVerifyNotice != null) {
                txtAutoVerifyNotice.setText(String.format(Locale.getDefault(),
                        "✅ Workout automatically verifies upon reaching %.2f km under target pace", targetMeters / 1000.0));
            }
        });
    }

    private void updateDurationTimer() {
        if (txtDuration == null) return;
        if (!session.isStarted()) {
            txtDuration.setText("⏱ 00:00:00");
            return;
        }

        long elapsedMs = Math.max(0, System.currentTimeMillis() - session.getStartTimeStamp());
        long seconds = (elapsedMs / 1000) % 60;
        long minutes = (elapsedMs / (1000 * 60)) % 60;
        long hours = (elapsedMs / (1000 * 60 * 60));

        txtDuration.setText(String.format(Locale.getDefault(), "⏱ %02d:%02d:%02d", hours, minutes, seconds));
    }

    private void updateGpsCoordinatesDisplay(Location loc) {
        if (loc == null || getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (txtGpsReadyBadge != null) {
                boolean hasFix = loc.hasAccuracy() && loc.getAccuracy() <= 30.0f;
                txtGpsReadyBadge.setText(hasFix ? "GPS READY" : "SEARCHING GPS");
                txtGpsReadyBadge.setTextColor(hasFix ? Color.parseColor("#34D399") : Color.parseColor("#FBBF24"));
            }
            if (txtGpsStatus != null) {
                int sats = session.getVisibleSatelliteCount();
                String satStr = sats > 0 ? (sats + " satellites • ") : "";
                String accStr = loc.hasAccuracy() ? String.format(Locale.getDefault(), "±%.0f m", loc.getAccuracy()) : "±5 m";
                txtGpsStatus.setText(satStr + accStr);
            }
        });
    }

    private void toggleMapExpansion() {
        if (routeMapView == null || btnMapExpand == null) return;
        isMapExpanded = !isMapExpanded;
        ViewGroup.LayoutParams params = routeMapView.getLayoutParams();
        params.height = dpToPx(isMapExpanded ? 320 : 180);
        routeMapView.setLayoutParams(params);
        btnMapExpand.setText(isMapExpanded ? "Collapse ↙" : "Expand ↗");
    }

    private void showAdjustTargetsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Workout Objective Targets");

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12));

        TextView lblDist = new TextView(requireContext());
        lblDist.setText("Target Distance (km)");
        lblDist.setTextColor(Color.DKGRAY);
        lblDist.setTextSize(13);
        layout.addView(lblDist);

        final EditText inputDist = new EditText(requireContext());
        inputDist.setText(String.format(Locale.US, "%.2f", webhookClient.getTargetDistanceMeters() / 1000.0));
        inputDist.setTextColor(Color.BLACK);
        inputDist.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(inputDist);

        TextView lblPace = new TextView(requireContext());
        lblPace.setText("Target Pace (min/km, e.g. 6.0)");
        lblPace.setTextColor(Color.DKGRAY);
        lblPace.setTextSize(13);
        lblPace.setPadding(0, dpToPx(8), 0, 0);
        layout.addView(lblPace);

        final EditText inputPace = new EditText(requireContext());
        inputPace.setText(String.format(Locale.US, "%.1f", webhookClient.getTargetPaceMinPerKm()));
        inputPace.setTextColor(Color.BLACK);
        inputPace.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(inputPace);

        TextView lblNotice = new TextView(requireContext());
        lblNotice.setText("⚠️ If workout finishes slower than target pace, alarm will not stop and restarts!");
        lblNotice.setTextColor(ContextCompat.getColor(requireContext(), R.color.hud_accent_red));
        lblNotice.setTextSize(11);
        lblNotice.setPadding(0, dpToPx(8), 0, 0);
        layout.addView(lblNotice);

        builder.setView(layout);
        builder.setPositiveButton("Save & Apply", (dialog, which) -> {
            try {
                double km = Double.parseDouble(inputDist.getText().toString().trim());
                float pace = Float.parseFloat(inputPace.getText().toString().trim());
                if (km > 0 && pace > 0) {
                    webhookClient.updateTargets(km * 1000.0, pace);
                    updateTargetsDisplay();
                    Toast.makeText(getContext(), String.format(Locale.getDefault(),
                            "Targets saved: %.2f km @ %.1f min/km", km, pace), Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(getContext(), "Invalid numbers entered.", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void initPreWorkoutGps() {
        if (getContext() == null) return;
        try {
            LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                Location lastKnown = null;
                try {
                    lastKnown = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (lastKnown == null) {
                        lastKnown = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    }
                } catch (SecurityException se) {
                    // permission
                }
                if (lastKnown != null) {
                    if (routeMapView != null) routeMapView.setCurrentLocation(lastKnown);
                    if (webhookClient != null) webhookClient.updateCurrentCoordinates(lastKnown.getLatitude(), lastKnown.getLongitude());
                    updateGpsCoordinatesDisplay(lastKnown);
                }

                if (preWorkoutLocationListener == null) {
                    preWorkoutLocationListener = new LocationListener() {
                        @Override
                        public void onLocationChanged(@NonNull Location location) {
                            if (routeMapView != null) routeMapView.setCurrentLocation(location);
                            if (webhookClient != null) webhookClient.updateCurrentCoordinates(location.getLatitude(), location.getLongitude());
                            updateGpsCoordinatesDisplay(location);

                            // Automatic workout detection check while pre-workout
                            if (!session.isStarted()) {
                                float spd = location.hasSpeed() && location.getSpeed() > 0 ? location.getSpeed() : 0f;
                                if (spd <= 0f && lastPreWorkoutLocation != null) {
                                    long nowTime = location.getTime() > 0 ? location.getTime() : System.currentTimeMillis();
                                    long dtMs = nowTime - lastPreWorkoutTimestamp;
                                    if (dtMs > 200 && dtMs < 10000) {
                                        spd = lastPreWorkoutLocation.distanceTo(location) / (dtMs / 1000.0f);
                                    }
                                }
                                lastPreWorkoutLocation = location;
                                lastPreWorkoutTimestamp = location.getTime() > 0 ? location.getTime() : System.currentTimeMillis();

                                if (spd >= 1.8f) {
                                    consecutiveRunningReadings++;
                                    if (consecutiveRunningReadings >= 3 && !isMovementPromptShowing) {
                                        promptUserToStartWorkout(spd);
                                    }
                                } else {
                                    consecutiveRunningReadings = 0;
                                }
                            }
                        }

                        @Override
                        public void onStatusChanged(String provider, int status, Bundle extras) {}
                        @Override
                        public void onProviderEnabled(@NonNull String provider) {}
                        @Override
                        public void onProviderDisabled(@NonNull String provider) {}
                    };
                }

                try {
                    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, preWorkoutLocationListener);
                    } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1.0f, preWorkoutLocationListener);
                    }
                } catch (SecurityException se) {
                    // permission
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private void stopPreWorkoutGps() {
        if (preWorkoutLocationListener != null && getContext() != null) {
            try {
                LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) lm.removeUpdates(preWorkoutLocationListener);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    @Override
    public void onAlarmStateChanged(MorningAlarmManager.AlarmState state, String statusMessage) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            Context ctx = getContext();
            if (ctx == null || !isAdded()) return;

            if (state == MorningAlarmManager.AlarmState.RINGING) {
                AlarmRingingDialog.show(getActivity(), alarmManager, this::startWorkoutSession);
            } else {
                AlarmRingingDialog.dismissIfShowing();
            }

            if (txtAlarmStatus != null) {
                txtAlarmStatus.setText(statusMessage);
            }
            if (txtAlarmSubstatus != null && txtAlarmHeader != null) {
                if (state == MorningAlarmManager.AlarmState.RINGING) {
                    txtAlarmSubstatus.setText("⚠️ Ringing! Tap 'START WORKOUT' to silence and begin mission!");
                    txtAlarmHeader.setTextColor(ContextCompat.getColor(ctx, R.color.hud_accent_red));
                    if (btnAlarmSnooze != null) btnAlarmSnooze.setVisibility(View.VISIBLE);
                } else if (state == MorningAlarmManager.AlarmState.SNOOZED) {
                    txtAlarmSubstatus.setText("💤 Snooze active. Alarm will ring again when timer ends.");
                    txtAlarmHeader.setTextColor(Color.parseColor("#E3B341"));
                    if (btnAlarmSnooze != null) btnAlarmSnooze.setVisibility(View.GONE);
                } else if (state == MorningAlarmManager.AlarmState.RUNNING_MISSION) {
                    txtAlarmSubstatus.setText("🏃 Workout active. Keep moving to prevent stationary penalty!");
                    txtAlarmHeader.setTextColor(ContextCompat.getColor(ctx, R.color.hud_accent_green));
                    if (btnAlarmSnooze != null) btnAlarmSnooze.setVisibility(View.GONE);
                } else if (state == MorningAlarmManager.AlarmState.COMPLETED) {
                    txtAlarmSubstatus.setText("🏆 Target distance & pace achieved! Alarm disarmed.");
                    txtAlarmHeader.setTextColor(ContextCompat.getColor(ctx, R.color.hud_accent_green));
                    if (btnAlarmSnooze != null) btnAlarmSnooze.setVisibility(View.GONE);
                } else {
                    txtAlarmSubstatus.setText("If pace does not match on finish, alarm will not stop and restarts!");
                    txtAlarmHeader.setTextColor(Color.parseColor("#F78166"));
                    if (btnAlarmSnooze != null) btnAlarmSnooze.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    @Override
    public void onCountdownTick(long millisUntilFinished) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (getContext() == null || !isAdded()) return;
            if (txtAlarmStatus != null) {
                long sec = (millisUntilFinished / 1000) % 60;
                long min = (millisUntilFinished / 1000) / 60;
                txtAlarmStatus.setText(String.format(Locale.getDefault(), "💤 Snoozed (%02d:%02d left)", min, sec));
            }
        });
    }

    private void updateAlarmStatusCard() {
        if (alarmManager == null || txtAlarmStatus == null) return;
        txtAlarmStatus.setText(alarmManager.getStateDescription());
    }

    private void updateTuneButton() {
        if (btnAlarmTune == null || getContext() == null) return;
        String toneName = AlarmTonePlayer.getSelectedToneName(requireContext());
        btnAlarmTune.setText("🎵 " + toneName);
    }

    private void showRingtoneSelectionDialog() {
        if (getActivity() == null) return;
        AlarmRingtoneDialog.show(getActivity(), (toneId, toneName) -> {
            updateTuneButton();
        });
    }

    private void updateVoiceButton() {
        if (btnVoiceToggle == null || voiceCoach == null || !isAdded()) return;
        boolean enabled = voiceCoach.isVoiceEnabled();
        btnVoiceToggle.setText(enabled ? "🔊 Voice" : "🔇 Voice Off");
        btnVoiceToggle.setTextColor(ContextCompat.getColor(requireContext(),
                enabled ? R.color.hud_accent_green : R.color.hud_text_secondary));
    }

    private void updatePillColor(int state) {
        if (pillContainer == null || !isAdded()) return;
        GradientDrawable bg = (GradientDrawable) pillContainer.getBackground();
        if (bg == null) return;
        switch (state) {
            case 1:
                bg.setColor(ContextCompat.getColor(requireContext(), R.color.hud_pill_bg_connected));
                bg.setStroke(dpToPx(1), ContextCompat.getColor(requireContext(), R.color.hud_pill_stroke_connected));
                txtSyncStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.hud_pill_text_connected));
                break;
            case 2:
                bg.setColor(ContextCompat.getColor(requireContext(), R.color.hud_pill_bg_disconnected));
                bg.setStroke(dpToPx(1), ContextCompat.getColor(requireContext(), R.color.hud_pill_stroke_disconnected));
                txtSyncStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.hud_pill_text_disconnected));
                break;
            default:
                bg.setColor(ContextCompat.getColor(requireContext(), R.color.hud_pill_bg_checking));
                bg.setStroke(dpToPx(1), ContextCompat.getColor(requireContext(), R.color.hud_pill_stroke_checking));
                txtSyncStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.hud_pill_text_checking));
                break;
        }
    }

    private void returnToDisciplineLock() {
        if (getContext() == null) return;
        try {
            PackageManager pm = requireContext().getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage("com.personal.disciplinelock.debug");
            if (intent == null) intent = pm.getLaunchIntentForPackage("com.personal.disciplinelock");
            if (intent == null) {
                intent = new Intent();
                intent.setComponent(new ComponentName("com.personal.disciplinelock.debug", "com.personal.disciplinelock.ui.LockGateActivity"));
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent();
                fallback.setComponent(new ComponentName("com.personal.disciplinelock", "com.personal.disciplinelock.ui.LockGateActivity"));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(fallback);
            } catch (Exception ex) {
                Toast.makeText(getContext(), "Failed to open Discipline Lock: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
