package com.mendhak.gpslogger.discipline;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import com.mendhak.gpslogger.common.Session;
import okhttp3.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * DisciplineWebhookClient
 *
 * Dispatches live workout metrics and mission verifications to the Discipline Lock
 * local HTTP receiver on port 8080 (or custom configured address).
 */
public class DisciplineWebhookClient {

    private static final Logger LOG = LoggerFactory.getLogger(DisciplineWebhookClient.class);
    private static volatile DisciplineWebhookClient instance;

    public static final String PREF_DISCIPLINE_SERVER_URL = "PREF_DISCIPLINE_SERVER_URL";
    public static final String PREF_TARGET_DISTANCE_METERS = "PREF_TARGET_DISTANCE_METERS";
    public static final String PREF_TARGET_PACE_MIN_PER_KM = "PREF_TARGET_PACE_MIN_PER_KM";
    public static final String PREF_LAST_RUN_SUMMARY_JSON = "PREF_LAST_RUN_SUMMARY_JSON";
    public static final String DEFAULT_SERVER_URL = "http://127.0.0.1:8080";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final Context appContext;
    private final SharedPreferences prefs;
    private final OkHttpClient httpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> streamTask;
    private boolean isWorkoutStreaming = false;
    private double lastReportedDistanceMeters = 0.0;
    private float lastReportedSpeedMps = 0.0f;
    private float lastReportedPaceMinPerKm = 0.0f;
    private double currentLatitude = 0.0;
    private double currentLongitude = 0.0;

    // Status listener for UI
    private SyncStatusListener statusListener;

    public interface SyncStatusListener {
        void onSyncStatusChanged(boolean isConnected, String message);
        default void onTargetsUpdated(double targetDistanceMeters, float targetPaceMinPerKm) {}
    }

    public interface WebhookCallback {
        void onSuccess(String responseBody);
        void onFailure(String errorMessage);
    }

    private DisciplineWebhookClient(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    public static DisciplineWebhookClient getInstance(Context context) {
        if (instance == null) {
            synchronized (DisciplineWebhookClient.class) {
                if (instance == null) {
                    instance = new DisciplineWebhookClient(context);
                }
            }
        }
        return instance;
    }

    public void setStatusListener(SyncStatusListener listener) {
        this.statusListener = listener;
    }

    public String getServerBaseUrl() {
        return prefs.getString(PREF_DISCIPLINE_SERVER_URL, DEFAULT_SERVER_URL).replaceAll("/+$", "");
    }

    public void setServerBaseUrl(String url) {
        if (url != null && !url.trim().isEmpty()) {
            prefs.edit().putString(PREF_DISCIPLINE_SERVER_URL, url.trim().replaceAll("/+$", "")).apply();
            checkHealth(null);
        }
    }

    public double getTargetDistanceMeters() {
        return (double) prefs.getFloat(PREF_TARGET_DISTANCE_METERS, 2000.0f);
    }

    public void setTargetDistanceMeters(double meters) {
        prefs.edit().putFloat(PREF_TARGET_DISTANCE_METERS, (float) meters).apply();
        notifyTargetsUpdated();
    }

    public float getTargetPaceMinPerKm() {
        return prefs.getFloat(PREF_TARGET_PACE_MIN_PER_KM, 6.0f);
    }

    public void setTargetPaceMinPerKm(float minPerKm) {
        prefs.edit().putFloat(PREF_TARGET_PACE_MIN_PER_KM, minPerKm).apply();
        notifyTargetsUpdated();
    }

    public void updateTargets(double meters, float minPerKm) {
        prefs.edit()
                .putFloat(PREF_TARGET_DISTANCE_METERS, (float) meters)
                .putFloat(PREF_TARGET_PACE_MIN_PER_KM, minPerKm)
                .apply();
        notifyTargetsUpdated();
    }

    public double getLastReportedDistanceMeters() {
        return lastReportedDistanceMeters;
    }

    private void notifyTargetsUpdated() {
        if (statusListener != null) {
            final double td = getTargetDistanceMeters();
            final float tp = getTargetPaceMinPerKm();
            mainHandler.post(() -> statusListener.onTargetsUpdated(td, tp));
        }
    }

    /**
     * Checks if Discipline Lock receiver is active on GET /webhook/status
     */
    public void checkHealth(final WebhookCallback callback) {
        String url = getServerBaseUrl() + "/webhook/status";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                LOG.warn("Discipline Lock healthcheck failed: {}", e.getMessage());
                notifyStatus(false, "Unreachable: " + e.getMessage());
                if (callback != null) {
                    mainHandler.post(() -> callback.onFailure(e.getMessage()));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String body = response.body() != null ? response.body().string() : "";
                final boolean success = response.isSuccessful();
                if (success && !body.isEmpty()) {
                    try {
                        JSONObject json = new JSONObject(body);
                        if (json.has("morningAlarm")) {
                            JSONObject morningAlarm = json.getJSONObject("morningAlarm");
                            boolean targetsChanged = false;
                            if (morningAlarm.has("targetDistanceMeters")) {
                                double td = morningAlarm.optDouble("targetDistanceMeters", 0.0);
                                if (td > 0.0) {
                                    prefs.edit().putFloat(PREF_TARGET_DISTANCE_METERS, (float) td).apply();
                                    targetsChanged = true;
                                }
                            }
                            if (morningAlarm.has("targetPaceMinPerKm")) {
                                double tp = morningAlarm.optDouble("targetPaceMinPerKm", 0.0);
                                if (tp > 0.0) {
                                    prefs.edit().putFloat(PREF_TARGET_PACE_MIN_PER_KM, (float) tp).apply();
                                    targetsChanged = true;
                                }
                            }
                            if (morningAlarm.has("alarmHour") && morningAlarm.has("alarmMinute")) {
                                int h = morningAlarm.optInt("alarmHour", 6);
                                int m = morningAlarm.optInt("alarmMinute", 0);
                                MorningAlarmManager.getInstance(appContext).setAlarmHour(h);
                                MorningAlarmManager.getInstance(appContext).setAlarmMinute(m);
                            }
                            if (morningAlarm.has("snoozeDurationMinutes")) {
                                int sn = morningAlarm.optInt("snoozeDurationMinutes", 15);
                                MorningAlarmManager.getInstance(appContext).setSnoozeDurationMinutes(sn);
                            }
                            if (morningAlarm.has("stationaryGraceSeconds")) {
                                int st = morningAlarm.optInt("stationaryGraceSeconds", 45);
                                MorningAlarmManager.getInstance(appContext).setStationaryGraceSeconds(st);
                            }
                            if (targetsChanged) {
                                notifyTargetsUpdated();
                            }
                        }
                    } catch (Exception e) {
                        LOG.warn("Error parsing morningAlarm targets from status: {}", e.getMessage());
                    }
                }

                notifyStatus(success, success ? "Linked to Discipline Lock" : "HTTP " + response.code());
                if (callback != null) {
                    mainHandler.post(() -> {
                        if (success) {
                            callback.onSuccess(body);
                        } else {
                            callback.onFailure("HTTP " + response.code());
                        }
                    });
                }
            }
        });
    }

    public void updateCurrentCoordinates(double lat, double lng) {
        this.currentLatitude = lat;
        this.currentLongitude = lng;
    }

    public double getCurrentLatitude() {
        return currentLatitude;
    }

    public double getCurrentLongitude() {
        return currentLongitude;
    }

    /**
     * Sends a single run metric update: POST /webhook/run
     */
    public void sendRunMetrics(double distanceMeters, float speedMps, float paceMinPerKm, boolean isFinished, final WebhookCallback callback) {
        try {
            JSONObject json = new JSONObject();
            json.put("distanceMeters", distanceMeters);
            json.put("distance", distanceMeters);
            json.put("speedMps", (double) speedMps);
            json.put("speed", (double) speedMps);
            json.put("paceMinPerKm", (double) paceMinPerKm);
            json.put("targetDistanceMeters", getTargetDistanceMeters());
            json.put("targetPaceMinPerKm", (double) getTargetPaceMinPerKm());
            json.put("isFinished", isFinished);
            if (currentLatitude != 0.0 || currentLongitude != 0.0) {
                json.put("latitude", currentLatitude);
                json.put("longitude", currentLongitude);
                json.put("lat", currentLatitude);
                json.put("lon", currentLongitude);
            }

            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, json.toString());
            String url = getServerBaseUrl() + "/webhook/run";

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    LOG.warn("Failed to dispatch run metric to {}: {}", url, e.getMessage());
                    notifyStatus(false, "Sync error: " + e.getMessage());
                    if (callback != null) {
                        mainHandler.post(() -> callback.onFailure(e.getMessage()));
                    }
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    final String respStr = response.body() != null ? response.body().string() : "";
                    final boolean ok = response.isSuccessful();
                    notifyStatus(ok, ok ? "Linked to Discipline Lock" : "HTTP " + response.code());
                    if (callback != null) {
                        mainHandler.post(() -> {
                            if (ok) callback.onSuccess(respStr);
                            else callback.onFailure("HTTP " + response.code());
                        });
                    }
                }
            });
        } catch (Exception e) {
            LOG.error("Error creating run webhook payload", e);
            if (callback != null) {
                callback.onFailure(e.getMessage());
            }
        }
    }

    /**
     * Starts interval dispatch loop (every 3 seconds) while workout is running.
     */
    public synchronized void startWorkoutStreaming() {
        if (isWorkoutStreaming) return;
        isWorkoutStreaming = true;
        lastReportedDistanceMeters = 0.0;
        lastReportedSpeedMps = 0.0f;
        lastReportedPaceMinPerKm = 0.0f;

        // Immediately ping health and send initial zero run metric
        checkHealth(null);
        sendRunMetrics(0.0, 0.0f, 0.0f, false, null);

        // Schedule periodic stream every 3 seconds
        streamTask = scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                if (!isWorkoutStreaming) return;
                try {
                    Session session = Session.getInstance();
                    if (session.getCurrentLocationInfo() != null) {
                        currentLatitude = session.getCurrentLocationInfo().getLatitude();
                        currentLongitude = session.getCurrentLocationInfo().getLongitude();
                    }
                    double distanceMeters = session.getTotalTravelled();
                    float speedMps = 0.0f;
                    if (session.getCurrentLocationInfo() != null && session.getCurrentLocationInfo().hasSpeed()) {
                        speedMps = session.getCurrentLocationInfo().getSpeed();
                    }
                    float paceMinPerKm = speedMps > 0.5f ? (1000.0f / speedMps) / 60.0f : 0.0f;

                    if (distanceMeters > lastReportedDistanceMeters) {
                        lastReportedDistanceMeters = distanceMeters;
                    }
                    if (speedMps > 0.0f) {
                        lastReportedSpeedMps = speedMps;
                        lastReportedPaceMinPerKm = paceMinPerKm;
                    }

                    sendRunMetrics(distanceMeters, speedMps, paceMinPerKm, false, null);
                } catch (Exception e) {
                    LOG.error("Periodic webhook stream error", e);
                }
            }
        }, 3, 3, TimeUnit.SECONDS);

        LOG.info("Started periodic Discipline Lock webhook streaming.");
    }

    /**
     * Stops interval dispatch loop and sends final isFinished: true payload.
     */
    public synchronized void stopWorkoutStreaming() {
        if (!isWorkoutStreaming) return;
        isWorkoutStreaming = false;

        if (streamTask != null) {
            streamTask.cancel(false);
            streamTask = null;
        }

        // Final payload preserving max distance and last active pace
        Session session = Session.getInstance();
        double currentTravelled = session.getTotalTravelled();
        double finalDistance = Math.max(lastReportedDistanceMeters, currentTravelled);
        sendRunMetrics(finalDistance, lastReportedSpeedMps, lastReportedPaceMinPerKm, true, null);
        LOG.info("Dispatched final run finished webhook (distance: {}m, speed: {}m/s, pace: {}/km)",
                finalDistance, lastReportedSpeedMps, lastReportedPaceMinPerKm);
    }

    /**
     * Verify mission by ID or Slug: POST /webhook/mission/{target}
     */
    public void verifyMission(String target, final WebhookCallback callback) {
        if (target == null || target.trim().isEmpty()) {
            if (callback != null) callback.onFailure("Target ID or Slug cannot be empty.");
            return;
        }

        String safeTarget = target.trim().replaceAll("^/+", "");
        String url = getServerBaseUrl() + "/webhook/mission/" + safeTarget;

        RequestBody emptyBody = RequestBody.create(JSON_MEDIA_TYPE, "{}");
        Request request = new Request.Builder()
                .url(url)
                .post(emptyBody)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                LOG.error("Failed to verify mission {}: {}", safeTarget, e.getMessage());
                notifyStatus(false, "Sync error: " + e.getMessage());
                if (callback != null) {
                    mainHandler.post(() -> callback.onFailure(e.getMessage()));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String respStr = response.body() != null ? response.body().string() : "";
                final boolean ok = response.isSuccessful();
                notifyStatus(ok, ok ? "Linked to Discipline Lock" : "HTTP " + response.code());
                if (callback != null) {
                    mainHandler.post(() -> {
                        if (ok) callback.onSuccess(respStr);
                        else callback.onFailure("HTTP " + response.code() + ": " + respStr);
                    });
                }
            }
        });
    }

    public void saveLastWorkoutSummary(WorkoutSummary summary) {
        if (summary == null) return;
        prefs.edit().putString(PREF_LAST_RUN_SUMMARY_JSON, summary.toJson()).apply();
    }

    public WorkoutSummary getLastWorkoutSummary() {
        String json = prefs.getString(PREF_LAST_RUN_SUMMARY_JSON, null);
        return WorkoutSummary.fromJson(json);
    }

    private void notifyStatus(final boolean isConnected, final String msg) {
        if (statusListener != null) {
            mainHandler.post(() -> statusListener.onSyncStatusChanged(isConnected, msg));
        }
    }
}
