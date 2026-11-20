package com.mendhak.gpslogger.discipline;

import android.location.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * Calculates kilometer splits from recorded GPS route points.
 */
public class SplitCalculator {

    public static List<KmSplit> calculateSplits(List<RouteMapView.RoutePoint> points, float targetPaceMinPerKm) {
        List<KmSplit> splits = new ArrayList<>();
        if (points == null || points.size() < 2) {
            return splits;
        }

        double splitTargetMeters = 1000.0;
        double currentSplitDistance = 0.0;
        long currentSplitStartTime = points.get(0).timestampMs;
        int splitNum = 1;

        float[] distResults = new float[1];

        for (int i = 1; i < points.size(); i++) {
            RouteMapView.RoutePoint prev = points.get(i - 1);
            RouteMapView.RoutePoint curr = points.get(i);

            Location.distanceBetween(prev.lat, prev.lng, curr.lat, curr.lng, distResults);
            float stepDist = distResults[0];
            currentSplitDistance += stepDist;

            if (currentSplitDistance >= splitTargetMeters) {
                long durationMs = Math.max(1000L, curr.timestampMs - currentSplitStartTime);
                float speedMps = (float) (currentSplitDistance / (durationMs / 1000.0));
                float paceMinPerKm = speedMps > 0.1f ? (1000.0f / speedMps) / 60.0f : 0.0f;
                boolean onPace = targetPaceMinPerKm <= 0 || (paceMinPerKm > 0.1f && paceMinPerKm <= targetPaceMinPerKm);

                splits.add(new KmSplit(splitNum++, currentSplitDistance, durationMs, paceMinPerKm, onPace));

                currentSplitDistance = 0.0;
                currentSplitStartTime = curr.timestampMs;
            }
        }

        // Remaining partial split
        if (currentSplitDistance > 50.0) {
            RouteMapView.RoutePoint last = points.get(points.size() - 1);
            long durationMs = Math.max(1000L, last.timestampMs - currentSplitStartTime);
            float speedMps = (float) (currentSplitDistance / (durationMs / 1000.0));
            float paceMinPerKm = speedMps > 0.1f ? (1000.0f / speedMps) / 60.0f : 0.0f;
            boolean onPace = targetPaceMinPerKm <= 0 || (paceMinPerKm > 0.1f && paceMinPerKm <= targetPaceMinPerKm);

            splits.add(new KmSplit(splitNum, currentSplitDistance, durationMs, paceMinPerKm, onPace));
        }

        return splits;
    }
}
