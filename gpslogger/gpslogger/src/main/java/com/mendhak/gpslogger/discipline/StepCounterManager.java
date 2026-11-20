package com.mendhak.gpslogger.discipline;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;

/**
 * StepCounterManager
 *
 * Tracks daily/session steps and live cadence (steps per minute) using Android's
 * Sensor.TYPE_STEP_COUNTER and Sensor.TYPE_STEP_DETECTOR hardware sensors.
 */
public class StepCounterManager implements SensorEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(StepCounterManager.class);
    private static volatile StepCounterManager instance;

    private final Context appContext;
    private final SensorManager sensorManager;
    private final Sensor stepCounterSensor;
    private final Sensor stepDetectorSensor;

    private boolean isTracking = false;
    private int initialHardwareStepCount = -1;
    private int currentSessionSteps = 0;
    private int liveCadence = 0;

    // Rolling window for calculating real-time cadence (last 15 seconds)
    private static final long CADENCE_WINDOW_MS = 15000L;
    private final LinkedList<Long> recentStepTimestamps = new LinkedList<>();

    private StepListener listener;

    public interface StepListener {
        void onStepCountUpdated(int sessionSteps, int cadenceSpm);
    }

    private StepCounterManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            this.stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            this.stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        } else {
            this.stepCounterSensor = null;
            this.stepDetectorSensor = null;
        }
    }

    public static StepCounterManager getInstance(Context context) {
        if (instance == null) {
            synchronized (StepCounterManager.class) {
                if (instance == null) {
                    instance = new StepCounterManager(context);
                }
            }
        }
        return instance;
    }

    public void setListener(StepListener listener) {
        this.listener = listener;
    }

    public synchronized void startTracking() {
        if (isTracking) return;
        isTracking = true;
        currentSessionSteps = 0;
        initialHardwareStepCount = -1;
        liveCadence = 0;
        recentStepTimestamps.clear();

        if (sensorManager != null) {
            if (stepCounterSensor != null) {
                sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_UI);
                LOG.info("Registered Sensor.TYPE_STEP_COUNTER");
            }
            if (stepDetectorSensor != null) {
                sensorManager.registerListener(this, stepDetectorSensor, SensorManager.SENSOR_DELAY_UI);
                LOG.info("Registered Sensor.TYPE_STEP_DETECTOR");
            }
        }
    }

    public synchronized void stopTracking() {
        if (!isTracking) return;
        isTracking = false;
        liveCadence = 0;
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    public synchronized void reset() {
        currentSessionSteps = 0;
        initialHardwareStepCount = -1;
        liveCadence = 0;
        recentStepTimestamps.clear();
        notifyListener();
    }

    public synchronized int getSessionSteps() {
        return currentSessionSteps;
    }

    public synchronized int getLiveCadence() {
        return liveCadence;
    }

    public boolean isSensorAvailable() {
        return stepCounterSensor != null || stepDetectorSensor != null;
    }

    @Override
    public synchronized void onSensorChanged(SensorEvent event) {
        if (!isTracking) return;

        long now = SystemClock.elapsedRealtime();

        if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            int totalSteps = (int) event.values[0];
            if (initialHardwareStepCount < 0) {
                initialHardwareStepCount = totalSteps;
            }
            int newSteps = totalSteps - initialHardwareStepCount;
            if (newSteps >= currentSessionSteps) {
                currentSessionSteps = newSteps;
            }
        } else if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            if (stepCounterSensor == null) {
                // If hardware step counter isn't present, accumulate individual detected steps
                currentSessionSteps++;
            }
            recordStepForCadence(now);
        }

        // Keep cadence window fresh
        recordStepForCadence(now);
        calculateCadence(now);
        notifyListener();
    }

    private void recordStepForCadence(long now) {
        recentStepTimestamps.addLast(now);
        while (!recentStepTimestamps.isEmpty() && (now - recentStepTimestamps.getFirst()) > CADENCE_WINDOW_MS) {
            recentStepTimestamps.removeFirst();
        }
    }

    private void calculateCadence(long now) {
        while (!recentStepTimestamps.isEmpty() && (now - recentStepTimestamps.getFirst()) > CADENCE_WINDOW_MS) {
            recentStepTimestamps.removeFirst();
        }
        int count = recentStepTimestamps.size();
        if (count > 1) {
            long window = now - recentStepTimestamps.getFirst();
            if (window > 1000L) {
                float minutes = window / 60000.0f;
                liveCadence = Math.round(count / minutes);
            }
        } else {
            liveCadence = 0;
        }
    }

    private void notifyListener() {
        if (listener != null) {
            listener.onStepCountUpdated(currentSessionSteps, liveCadence);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No-op
    }
}
