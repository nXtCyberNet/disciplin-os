package com.mendhak.gpslogger.discipline;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;

import com.mendhak.gpslogger.common.slf4j.Logs;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * SQLite Database Helper for persistent Workout History, Personal Bests,
 * Weekly Distance, and Consistency Streak tracking.
 */
public class WorkoutDatabaseHelper extends SQLiteOpenHelper {

    private static final Logger LOG = Logs.of(WorkoutDatabaseHelper.class);
    private static final String DATABASE_NAME = "discipline_workouts.db";
    private static final int DATABASE_VERSION = 1;

    private static final String TABLE_WORKOUTS = "workouts";
    private static final String COL_ID = "id";
    private static final String COL_TIMESTAMP = "timestamp";
    private static final String COL_DISTANCE_METERS = "distance_meters";
    private static final String COL_DURATION_SECONDS = "duration_seconds";
    private static final String COL_AVG_PACE_MIN_KM = "avg_pace_min_km";
    private static final String COL_TARGET_DIST_METERS = "target_dist_meters";
    private static final String COL_TARGET_PACE_MIN_KM = "target_pace_min_km";
    private static final String COL_STEPS = "steps";
    private static final String COL_CADENCE = "cadence";
    private static final String COL_VERIFIED = "verified";
    private static final String COL_SPLITS_JSON = "splits_json";

    private static WorkoutDatabaseHelper instance;

    public static synchronized WorkoutDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new WorkoutDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private WorkoutDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_WORKOUTS + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_TIMESTAMP + " INTEGER NOT NULL, "
                + COL_DISTANCE_METERS + " REAL NOT NULL, "
                + COL_DURATION_SECONDS + " INTEGER NOT NULL, "
                + COL_AVG_PACE_MIN_KM + " REAL NOT NULL, "
                + COL_TARGET_DIST_METERS + " REAL NOT NULL, "
                + COL_TARGET_PACE_MIN_KM + " REAL NOT NULL, "
                + COL_STEPS + " INTEGER DEFAULT 0, "
                + COL_CADENCE + " INTEGER DEFAULT 0, "
                + COL_VERIFIED + " INTEGER DEFAULT 0, "
                + COL_SPLITS_JSON + " TEXT"
                + ");";
        db.execSQL(createTable);
        LOG.info("Created workouts database table");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_WORKOUTS);
        onCreate(db);
    }

    public static class WorkoutRecord {
        public long id;
        public long timestamp;
        public double distanceMeters;
        public long durationSeconds;
        public float avgPaceMinPerKm;
        public double targetDistanceMeters;
        public float targetPaceMinPerKm;
        public int steps;
        public int cadence;
        public boolean verified;
        public List<KmSplit> splits = new ArrayList<>();

        public String getFormattedDate() {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault());
            return sdf.format(new java.util.Date(timestamp));
        }

        public String getFormattedDistance() {
            return String.format(Locale.getDefault(), "%.2f km", distanceMeters / 1000.0);
        }

        public String getFormattedDuration() {
            long min = durationSeconds / 60;
            long sec = durationSeconds % 60;
            return String.format(Locale.getDefault(), "%02d:%02d", min, sec);
        }

        public String getFormattedPace() {
            if (avgPaceMinPerKm <= 0.1f || Float.isInfinite(avgPaceMinPerKm)) return "--:-- /km";
            int min = (int) avgPaceMinPerKm;
            int sec = Math.round((avgPaceMinPerKm - min) * 60f);
            if (sec >= 60) { min++; sec = 0; }
            return String.format(Locale.getDefault(), "%d:%02d /km", min, sec);
        }
    }

    public static class PersonalBests {
        public float fastestSplitPace = 0f;
        public double longestDistanceMeters = 0.0;
        public float bestAvgPace = 0f;
    }

    /**
     * Inserts a completed workout into SQLite.
     */
    public synchronized long insertWorkout(WorkoutSummary summary, boolean verified) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_TIMESTAMP, System.currentTimeMillis());
        cv.put(COL_DISTANCE_METERS, summary.totalDistanceMeters);
        cv.put(COL_DURATION_SECONDS, summary.totalDurationMs / 1000L);
        cv.put(COL_AVG_PACE_MIN_KM, summary.avgPaceMinPerKm);
        cv.put(COL_TARGET_DIST_METERS, summary.targetDistanceMeters);
        cv.put(COL_TARGET_PACE_MIN_KM, summary.targetPaceMinPerKm);
        cv.put(COL_STEPS, summary.stepCount);
        cv.put(COL_CADENCE, summary.cadenceSpm);
        cv.put(COL_VERIFIED, verified ? 1 : 0);

        JSONArray arr = new JSONArray();
        if (summary.splits != null) {
            for (KmSplit split : summary.splits) {
                JSONObject obj = new JSONObject();
                try {
                    obj.put("splitNumber", split.splitNumber);
                    obj.put("distanceMeters", split.distanceMeters);
                    obj.put("durationMs", split.durationMs);
                    obj.put("paceMinPerKm", split.paceMinPerKm);
                    obj.put("isOnPace", split.isOnPace);
                    arr.put(obj);
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        cv.put(COL_SPLITS_JSON, arr.toString());

        long rowId = db.insert(TABLE_WORKOUTS, null, cv);
        LOG.info("Inserted workout rowId: {}, dist: {}m, verified: {}", rowId, summary.totalDistanceMeters, verified);
        return rowId;
    }

    /**
     * Gets the most recent workout (for LAST PACE and LAST DISTANCE).
     */
    public synchronized WorkoutRecord getLastWorkout() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_WORKOUTS, null, null, null, null, null, COL_TIMESTAMP + " DESC", "1");
        WorkoutRecord record = null;
        if (cursor != null && cursor.moveToFirst()) {
            record = cursorToRecord(cursor);
            cursor.close();
        }
        return record;
    }

    /**
     * Gets all workouts in reverse chronological order.
     */
    public synchronized List<WorkoutRecord> getAllWorkouts() {
        List<WorkoutRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_WORKOUTS, null, null, null, null, null, COL_TIMESTAMP + " DESC");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                list.add(cursorToRecord(cursor));
            }
            cursor.close();
        }
        return list;
    }

    /**
     * Total distance in kilometers completed over the past 7 days.
     */
    public synchronized double getWeeklyDistanceKm() {
        long sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000L);
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT SUM(" + COL_DISTANCE_METERS + ") FROM " + TABLE_WORKOUTS
                + " WHERE " + COL_TIMESTAMP + " >= ?", new String[]{String.valueOf(sevenDaysAgo)});
        double meters = 0.0;
        if (cursor != null && cursor.moveToFirst()) {
            meters = cursor.getDouble(0);
            cursor.close();
        }
        return meters / 1000.0;
    }

    /**
     * Personal Bests calculation across all recorded workouts.
     */
    public synchronized PersonalBests getPersonalBests() {
        PersonalBests pb = new PersonalBests();
        List<WorkoutRecord> all = getAllWorkouts();
        for (WorkoutRecord r : all) {
            if (r.distanceMeters > pb.longestDistanceMeters) {
                pb.longestDistanceMeters = r.distanceMeters;
            }
            if (r.avgPaceMinPerKm > 0.1f && (pb.bestAvgPace == 0f || r.avgPaceMinPerKm < pb.bestAvgPace)) {
                pb.bestAvgPace = r.avgPaceMinPerKm;
            }
            for (KmSplit s : r.splits) {
                if (s.paceMinPerKm > 0.1f && (pb.fastestSplitPace == 0f || s.paceMinPerKm < pb.fastestSplitPace)) {
                    pb.fastestSplitPace = s.paceMinPerKm;
                }
            }
        }
        return pb;
    }

    /**
     * Calculate active workout streak (consecutive days with completed workouts).
     */
    public synchronized int getCurrentStreakDays() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT " + COL_TIMESTAMP + " FROM " + TABLE_WORKOUTS + " ORDER BY " + COL_TIMESTAMP + " DESC", null);
        if (cursor == null) return 0;

        List<Long> timestamps = new ArrayList<>();
        while (cursor.moveToNext()) {
            timestamps.add(cursor.getLong(0));
        }
        cursor.close();
        if (timestamps.isEmpty()) return 0;

        int streak = 0;
        Calendar checkCal = Calendar.getInstance();
        normalizeDay(checkCal);

        // Check if there's a workout today or yesterday
        Calendar workoutCal = Calendar.getInstance();
        boolean hasWorkoutToday = false;

        for (long ts : timestamps) {
            workoutCal.setTimeInMillis(ts);
            normalizeDay(workoutCal);

            if (workoutCal.equals(checkCal)) {
                hasWorkoutToday = true;
                break;
            }
        }

        if (!hasWorkoutToday) {
            // Check yesterday
            checkCal.add(Calendar.DAY_OF_YEAR, -1);
            boolean hasYesterday = false;
            for (long ts : timestamps) {
                workoutCal.setTimeInMillis(ts);
                normalizeDay(workoutCal);
                if (workoutCal.equals(checkCal)) {
                    hasYesterday = true;
                    break;
                }
            }
            if (!hasYesterday) return 0;
        }

        // Count streak backwards
        while (true) {
            boolean found = false;
            for (long ts : timestamps) {
                workoutCal.setTimeInMillis(ts);
                normalizeDay(workoutCal);
                if (workoutCal.equals(checkCal)) {
                    found = true;
                    break;
                }
            }
            if (found) {
                streak++;
                checkCal.add(Calendar.DAY_OF_YEAR, -1);
            } else {
                break;
            }
        }
        return streak;
    }

    private void normalizeDay(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private WorkoutRecord cursorToRecord(Cursor cursor) {
        WorkoutRecord r = new WorkoutRecord();
        r.id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID));
        r.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP));
        r.distanceMeters = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_DISTANCE_METERS));
        r.durationSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DURATION_SECONDS));
        r.avgPaceMinPerKm = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_AVG_PACE_MIN_KM));
        r.targetDistanceMeters = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_TARGET_DIST_METERS));
        r.targetPaceMinPerKm = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_TARGET_PACE_MIN_KM));
        r.steps = cursor.getInt(cursor.getColumnIndexOrThrow(COL_STEPS));
        r.cadence = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CADENCE));
        r.verified = cursor.getInt(cursor.getColumnIndexOrThrow(COL_VERIFIED)) == 1;

        String splitsJson = cursor.getString(cursor.getColumnIndexOrThrow(COL_SPLITS_JSON));
        if (splitsJson != null && !splitsJson.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(splitsJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    int splitNum = obj.optInt("splitNumber", obj.optInt("kmIndex", i + 1));
                    double dist = obj.optDouble("distanceMeters", 1000.0);
                    long durMs = obj.has("durationMs") ? obj.optLong("durationMs", 0) : (obj.optLong("durationSeconds", 0) * 1000L);
                    float pace = (float) obj.optDouble("paceMinPerKm", 0.0);
                    boolean onPace = obj.optBoolean("isOnPace", obj.optBoolean("withinTarget", true));
                    KmSplit s = new KmSplit(splitNum, dist, durMs, pace, onPace);
                    r.splits.add(s);
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return r;
    }
}
