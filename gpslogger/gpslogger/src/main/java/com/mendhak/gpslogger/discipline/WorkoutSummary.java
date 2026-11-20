package com.mendhak.gpslogger.discipline;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Encapsulates the complete post-run workout report, splits, and Discipline Lock status.
 */
public class WorkoutSummary implements Serializable {
    public final double totalDistanceMeters;
    public final long totalDurationMs;
    public final float avgSpeedMps;
    public final float avgPaceMinPerKm;
    public final double targetDistanceMeters;
    public final float targetPaceMinPerKm;
    public final boolean isDistanceMet;
    public final boolean isPaceMatched;
    public final boolean isMissionCompleted;
    public final int stepCount;
    public final int cadenceSpm;
    public final List<KmSplit> splits;
    public final long completionTimestampMs;

    public WorkoutSummary(double totalDistanceMeters, long totalDurationMs, float avgSpeedMps,
                          float avgPaceMinPerKm, double targetDistanceMeters, float targetPaceMinPerKm,
                          int stepCount, int cadenceSpm, List<KmSplit> splits) {
        this.totalDistanceMeters = totalDistanceMeters;
        this.totalDurationMs = totalDurationMs;
        this.avgSpeedMps = avgSpeedMps;
        this.avgPaceMinPerKm = avgPaceMinPerKm;
        this.targetDistanceMeters = targetDistanceMeters;
        this.targetPaceMinPerKm = targetPaceMinPerKm;
        this.stepCount = stepCount;
        this.cadenceSpm = cadenceSpm;
        this.splits = splits != null ? splits : new ArrayList<>();
        this.completionTimestampMs = System.currentTimeMillis();

        this.isDistanceMet = targetDistanceMeters <= 0 || totalDistanceMeters >= (targetDistanceMeters - 10.0);
        this.isPaceMatched = targetPaceMinPerKm <= 0 || inRange(avgPaceMinPerKm, targetPaceMinPerKm);
        this.isMissionCompleted = this.isDistanceMet && this.isPaceMatched;
    }

    private static boolean inRange(float pace, float target) {
        return pace > 0.1f && pace <= target;
    }

    public String getFormattedDistance() {
        return String.format(Locale.getDefault(), "%.2f km", totalDistanceMeters / 1000.0);
    }

    public String getFormattedTargetDistance() {
        return String.format(Locale.getDefault(), "%.2f km", targetDistanceMeters / 1000.0);
    }

    public String getFormattedDuration() {
        long sec = (totalDurationMs / 1000) % 60;
        long min = (totalDurationMs / (1000 * 60)) % 60;
        long hrs = (totalDurationMs / (1000 * 60 * 60));
        if (hrs > 0) {
            return String.format(Locale.getDefault(), "%02d:%02d:%02d", hrs, min, sec);
        }
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec);
    }

    public String getFormattedAvgPace() {
        if (avgPaceMinPerKm <= 0.1f || avgPaceMinPerKm > 60.0f) {
            return "--:-- /km";
        }
        int min = (int) avgPaceMinPerKm;
        int sec = Math.round((avgPaceMinPerKm - min) * 60.0f);
        if (sec >= 60) {
            min++;
            sec = 0;
        }
        return String.format(Locale.getDefault(), "%02d:%02d /km", min, sec);
    }

    public String getFormattedTargetPace() {
        if (targetPaceMinPerKm <= 0.1f || targetPaceMinPerKm > 60.0f) {
            return "--:-- /km";
        }
        int min = (int) targetPaceMinPerKm;
        int sec = Math.round((targetPaceMinPerKm - min) * 60.0f);
        if (sec >= 60) {
            min++;
            sec = 0;
        }
        return String.format(Locale.getDefault(), "%02d:%02d /km", min, sec);
    }

    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("totalDistanceMeters", totalDistanceMeters);
            obj.put("totalDurationMs", totalDurationMs);
            obj.put("avgSpeedMps", avgSpeedMps);
            obj.put("avgPaceMinPerKm", avgPaceMinPerKm);
            obj.put("targetDistanceMeters", targetDistanceMeters);
            obj.put("targetPaceMinPerKm", targetPaceMinPerKm);
            obj.put("stepCount", stepCount);
            obj.put("cadenceSpm", cadenceSpm);
            obj.put("isMissionCompleted", isMissionCompleted);
            obj.put("completionTimestampMs", completionTimestampMs);

            JSONArray arr = new JSONArray();
            for (KmSplit s : splits) {
                JSONObject sObj = new JSONObject();
                sObj.put("splitNumber", s.splitNumber);
                sObj.put("distanceMeters", s.distanceMeters);
                sObj.put("durationMs", s.durationMs);
                sObj.put("paceMinPerKm", s.paceMinPerKm);
                sObj.put("isOnPace", s.isOnPace);
                arr.put(sObj);
            }
            obj.put("splits", arr);
            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static WorkoutSummary fromJson(String json) {
        try {
            if (json == null || json.trim().isEmpty()) return null;
            JSONObject obj = new JSONObject(json);
            double dist = obj.optDouble("totalDistanceMeters", 0.0);
            long dur = obj.optLong("totalDurationMs", 0L);
            float speed = (float) obj.optDouble("avgSpeedMps", 0.0);
            float pace = (float) obj.optDouble("avgPaceMinPerKm", 0.0);
            double tDist = obj.optDouble("targetDistanceMeters", 2000.0);
            float tPace = (float) obj.optDouble("targetPaceMinPerKm", 6.0);
            int steps = obj.optInt("stepCount", 0);
            int cadence = obj.optInt("cadenceSpm", 0);

            List<KmSplit> splits = new ArrayList<>();
            JSONArray arr = obj.optJSONArray("splits");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject sObj = arr.getJSONObject(i);
                    splits.add(new KmSplit(
                            sObj.optInt("splitNumber", i + 1),
                            sObj.optDouble("distanceMeters", 1000.0),
                            sObj.optLong("durationMs", 0L),
                            (float) sObj.optDouble("paceMinPerKm", 0.0),
                            sObj.optBoolean("isOnPace", true)
                    ));
                }
            }
            return new WorkoutSummary(dist, dur, speed, pace, tDist, tPace, steps, cadence, splits);
        } catch (Exception e) {
            return null;
        }
    }
}
