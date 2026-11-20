package com.mendhak.gpslogger.discipline;

import java.io.Serializable;
import java.util.Locale;

/**
 * Represents a 1-kilometer (or partial remaining) split during a workout.
 */
public class KmSplit implements Serializable {
    public final int splitNumber;
    public final double distanceMeters;
    public final long durationMs;
    public final float paceMinPerKm;
    public final boolean isOnPace;

    public KmSplit(int splitNumber, double distanceMeters, long durationMs, float paceMinPerKm, boolean isOnPace) {
        this.splitNumber = splitNumber;
        this.distanceMeters = distanceMeters;
        this.durationMs = durationMs;
        this.paceMinPerKm = paceMinPerKm;
        this.isOnPace = isOnPace;
    }

    public String getFormattedPace() {
        if (paceMinPerKm <= 0.1f || paceMinPerKm > 60.0f) {
            return "--:-- /km";
        }
        int min = (int) paceMinPerKm;
        int sec = Math.round((paceMinPerKm - min) * 60.0f);
        if (sec >= 60) {
            min++;
            sec = 0;
        }
        return String.format(Locale.getDefault(), "%02d:%02d /km", min, sec);
    }

    public String getFormattedDuration() {
        long sec = (durationMs / 1000) % 60;
        long min = (durationMs / (1000 * 60));
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec);
    }

    public String getFormattedDistance() {
        return String.format(Locale.getDefault(), "%.2f km", distanceMeters / 1000.0);
    }
}
