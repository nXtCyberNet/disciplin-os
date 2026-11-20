package com.mendhak.gpslogger.discipline;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import androidx.preference.PreferenceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * DisciplineVoiceCoach
 *
 * Real-time Android Text-To-Speech (TTS) engine providing talking workout data,
 * pace feedback, stationary warnings, and morning alarm announcements.
 */
public class DisciplineVoiceCoach implements TextToSpeech.OnInitListener {

    private static final Logger LOG = LoggerFactory.getLogger(DisciplineVoiceCoach.class);
    private static volatile DisciplineVoiceCoach instance;

    public static final String PREF_VOICE_COACH_ENABLED = "PREF_VOICE_COACH_ENABLED";

    private final Context context;
    private final SharedPreferences prefs;
    private TextToSpeech tts;
    private boolean isInitialized = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastPaceWarningTimeMs = 0L;

    private DisciplineVoiceCoach(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(this.context);
        initTts();
    }

    public static DisciplineVoiceCoach getInstance(Context context) {
        if (instance == null) {
            synchronized (DisciplineVoiceCoach.class) {
                if (instance == null) {
                    instance = new DisciplineVoiceCoach(context);
                }
            }
        }
        return instance;
    }

    private void initTts() {
        try {
            tts = new TextToSpeech(context, this);
        } catch (Exception e) {
            LOG.warn("Failed to initialize TextToSpeech: {}", e.getMessage());
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            int result = tts.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                LOG.warn("US English not supported on device TTS, using default locale");
                tts.setLanguage(Locale.getDefault());
            }
            tts.setSpeechRate(1.05f);
            tts.setPitch(1.0f);
            isInitialized = true;
            LOG.info("DisciplineVoiceCoach TTS initialized successfully.");
        } else {
            LOG.warn("DisciplineVoiceCoach TTS initialization failed with status: {}", status);
        }
    }

    public boolean isVoiceEnabled() {
        return prefs.getBoolean(PREF_VOICE_COACH_ENABLED, true);
    }

    public void setVoiceEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_VOICE_COACH_ENABLED, enabled).apply();
    }

    public void speak(final String message, final boolean flush) {
        if (!isVoiceEnabled()) {
            return;
        }

        mainHandler.post(() -> {
            if (!isInitialized || tts == null) {
                LOG.debug("TTS not ready yet, skipping utterance: {}", message);
                return;
            }
            try {
                int queueMode = flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
                tts.speak(message, queueMode, null, "DISCIPLINE_COACH_" + System.currentTimeMillis());
                LOG.info("Voice Coach spoken: {}", message);
            } catch (Exception e) {
                LOG.warn("Error speaking utterance: {}", e.getMessage());
            }
        });
    }

    public void announceAlarmTriggered(double targetDistMeters, float targetPaceMinPerKm) {
        double km = targetDistMeters / 1000.0;
        int pMin = (int) targetPaceMinPerKm;
        int pSec = Math.round((targetPaceMinPerKm - pMin) * 60.0f);
        String text = String.format(Locale.US,
                "Morning alarm! Wake up! Today's mission is %.1f kilometers under %d minutes %02d seconds per kilometer. Tap Start Running to begin!",
                km, pMin, pSec);
        speak(text, true);
    }

    public void announceWorkoutStart(double targetDistMeters, float targetPaceMinPerKm) {
        double km = targetDistMeters / 1000.0;
        int pMin = (int) targetPaceMinPerKm;
        int pSec = Math.round((targetPaceMinPerKm - pMin) * 60.0f);
        String text = String.format(Locale.US,
                "Morning mission started. Target distance is %.2f kilometers at pace under %d minutes %02d seconds per kilometer. Let's run!",
                km, pMin, pSec);
        speak(text, true);
    }

    public void announceSplit(int splitNumber, float splitPaceMinPerKm, boolean onPace) {
        int pMin = (int) splitPaceMinPerKm;
        int pSec = Math.round((splitPaceMinPerKm - pMin) * 60.0f);
        String encouragement = onPace ? "Great job, you are on pace!" : "Pace is behind target. Pick up your speed!";
        String text = String.format(Locale.US,
                "Kilometer %d complete. Split pace: %d minutes %02d seconds per kilometer. %s",
                splitNumber, pMin, pSec, encouragement);
        speak(text, false);
    }

    public void announcePaceWarning(float currentPaceMinPerKm, float targetPaceMinPerKm) {
        long now = System.currentTimeMillis();
        // Throttle pace warnings to at most once every 30 seconds
        if (now - lastPaceWarningTimeMs < 30000L) {
            return;
        }
        lastPaceWarningTimeMs = now;

        int curMin = (int) currentPaceMinPerKm;
        int curSec = Math.round((currentPaceMinPerKm - curMin) * 60.0f);
        int tgtMin = (int) targetPaceMinPerKm;
        int tgtSec = Math.round((targetPaceMinPerKm - tgtMin) * 60.0f);

        String text = String.format(Locale.US,
                "Pace warning. Current pace is %d minutes %02d seconds, slower than target of %d minutes %02d seconds per kilometer. Accelerate!",
                curMin, curSec, tgtMin, tgtSec);
        speak(text, true);
    }

    public void announceStationaryWarning(int secondsRemaining) {
        String text = String.format(Locale.US,
                "Warning! Movement stopped outside safe zone. Resume running in %d seconds or alarm siren will sound!",
                secondsRemaining);
        speak(text, true);
    }

    public void announceTargetPaceNotMatched(float avgPaceMinPerKm, float targetPaceMinPerKm) {
        int avgMin = (int) avgPaceMinPerKm;
        int avgSec = Math.round((avgPaceMinPerKm - avgMin) * 60.0f);
        int tgtMin = (int) targetPaceMinPerKm;
        int tgtSec = Math.round((targetPaceMinPerKm - tgtMin) * 60.0f);

        String text = String.format(Locale.US,
                "Target distance reached, but your pace was %d minutes %02d seconds per kilometer, slower than required %d minutes %02d seconds! Morning alarm will not stop and is restarting! Keep running to match target pace!",
                avgMin, avgSec, tgtMin, tgtSec);
        speak(text, true);
    }

    public void announceMissionCompleted(double distanceMeters, float avgPaceMinPerKm) {
        double km = distanceMeters / 1000.0;
        int avgMin = (int) avgPaceMinPerKm;
        int avgSec = Math.round((avgPaceMinPerKm - avgMin) * 60.0f);
        String text = String.format(Locale.US,
                "Outstanding! Morning run mission completed! You ran %.2f kilometers at %d minutes %02d seconds per kilometer. Target pace matched. Alarm disarmed!",
                km, avgMin, avgSec);
        speak(text, true);
    }

    public void announceSnooze(int minutes) {
        String text = String.format(Locale.US,
                "Morning alarm snoozed for %d minutes. Alarm will ring again at end of snooze.",
                minutes);
        speak(text, true);
    }

    public void shutdown() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception e) {
                // ignore
            }
            tts = null;
            isInitialized = false;
        }
    }
}
