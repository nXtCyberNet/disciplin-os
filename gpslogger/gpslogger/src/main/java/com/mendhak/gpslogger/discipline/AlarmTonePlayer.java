package com.mendhak.gpslogger.discipline;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.mendhak.gpslogger.common.slf4j.Logs;
import org.slf4j.Logger;

/**
 * High-performance, multi-tone alarm audio player.
 * Supports:
 * 1. 🚨 Emergency Siren (dynamic frequency modulation sweep)
 * 2. ⏰ Digital Beep (classic 4-burst alarm clock)
 * 3. 🎺 Military Reveille Bugle (military wake-up fanfare)
 * 4. 🔔 System Default Alarm (Android system alarm)
 * 5. 📱 Custom / Device Picked Ringtone (any URI chosen by user)
 */
public class AlarmTonePlayer {

    private static final Logger LOG = Logs.of(AlarmTonePlayer.class);

    public static final String PREF_ALARM_TONE_ID = "discipline_alarm_tone_id";
    public static final String PREF_ALARM_TONE_NAME = "discipline_alarm_tone_name";
    public static final String PREF_ALARM_TONE_URI = "discipline_alarm_tone_uri";

    public static final String TONE_SIREN = "siren";
    public static final String TONE_DIGITAL_BEEP = "digital_beep";
    public static final String TONE_MILITARY_BUGLE = "military_bugle";
    public static final String TONE_SYSTEM_ALARM = "system_alarm";
    public static final String TONE_CUSTOM_URI = "custom_uri";

    public static final String DEFAULT_TONE = TONE_SIREN;

    private static volatile AlarmTonePlayer instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Thread syntheticPlaybackThread;
    private volatile boolean isSyntheticPlaying = false;
    private AudioTrack activeAudioTrack;
    private MediaPlayer activeMediaPlayer;
    private boolean isPlaying = false;

    public static AlarmTonePlayer getInstance() {
        if (instance == null) {
            synchronized (AlarmTonePlayer.class) {
                if (instance == null) {
                    instance = new AlarmTonePlayer();
                }
            }
        }
        return instance;
    }

    private AlarmTonePlayer() {}

    public static String getSelectedToneId(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        return prefs.getString(PREF_ALARM_TONE_ID, DEFAULT_TONE);
    }

    public static String getSelectedToneName(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        String id = prefs.getString(PREF_ALARM_TONE_ID, DEFAULT_TONE);
        switch (id) {
            case TONE_SIREN:
                return "🚨 Emergency Siren";
            case TONE_DIGITAL_BEEP:
                return "⏰ Digital Beep";
            case TONE_MILITARY_BUGLE:
                return "🎺 Military Bugle";
            case TONE_SYSTEM_ALARM:
                return "🔔 System Alarm";
            case TONE_CUSTOM_URI:
                return prefs.getString(PREF_ALARM_TONE_NAME, "📂 Custom Tune");
            default:
                return "🚨 Emergency Siren";
        }
    }

    public static void saveSelectedTone(Context context, String toneId, String toneName, String toneUri) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        prefs.edit()
                .putString(PREF_ALARM_TONE_ID, toneId)
                .putString(PREF_ALARM_TONE_NAME, toneName)
                .putString(PREF_ALARM_TONE_URI, toneUri != null ? toneUri : "")
                .apply();
        LOG.info("Saved alarm ringtone: id={}, name={}, uri={}", toneId, toneName, toneUri);
    }

    public synchronized void play(Context context, boolean isMuted, boolean looping) {
        // Strict discipline: Alarm cannot be muted or turned off! Always audible at max volume.
        MorningAlarmManager.enforceMaxAlarmVolume(context);

        stop();

        String toneId = getSelectedToneId(context);
        LOG.info("Starting alarm audio playback with tone: {}", toneId);

        if (TONE_SYSTEM_ALARM.equals(toneId) || TONE_CUSTOM_URI.equals(toneId)) {
            playMediaTone(context, toneId, looping);
        } else {
            playSyntheticTone(toneId, looping);
        }
        isPlaying = true;
    }

    public synchronized void playPreview(Context context, String toneId, String customUriString, int durationMs) {
        stop();
        LOG.info("Playing ringtone preview for: {}", toneId);

        if (TONE_SYSTEM_ALARM.equals(toneId) || TONE_CUSTOM_URI.equals(toneId)) {
            playMediaToneCustom(context, toneId, customUriString, true);
        } else {
            playSyntheticTone(toneId, true);
        }
        isPlaying = true;

        mainHandler.postDelayed(this::stop, Math.max(1000, durationMs));
    }

    public synchronized void stop() {
        isPlaying = false;
        isSyntheticPlaying = false;

        if (syntheticPlaybackThread != null) {
            syntheticPlaybackThread.interrupt();
            syntheticPlaybackThread = null;
        }

        if (activeAudioTrack != null) {
            try {
                activeAudioTrack.pause();
                activeAudioTrack.flush();
                activeAudioTrack.stop();
                activeAudioTrack.release();
            } catch (Exception e) {
                // ignore
            }
            activeAudioTrack = null;
        }

        if (activeMediaPlayer != null) {
            try {
                if (activeMediaPlayer.isPlaying()) {
                    activeMediaPlayer.stop();
                }
                activeMediaPlayer.release();
            } catch (Exception e) {
                // ignore
            }
            activeMediaPlayer = null;
        }
    }

    public synchronized boolean isPlaying() {
        return isPlaying;
    }

    private void playMediaTone(Context context, String toneId, boolean looping) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        String uriStr = prefs.getString(PREF_ALARM_TONE_URI, "");
        playMediaToneCustom(context, toneId, uriStr, looping);
    }

    private void playMediaToneCustom(Context context, String toneId, String uriStr, boolean looping) {
        try {
            Uri toneUri = null;
            if (TONE_CUSTOM_URI.equals(toneId) && uriStr != null && !uriStr.isEmpty()) {
                toneUri = Uri.parse(uriStr);
            }
            if (toneUri == null) {
                toneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            }
            if (toneUri == null) {
                toneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            if (toneUri == null) {
                toneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }

            if (toneUri == null) {
                // Fallback to synthetic siren
                playSyntheticTone(TONE_SIREN, looping);
                return;
            }

            activeMediaPlayer = new MediaPlayer();
            activeMediaPlayer.setDataSource(context, toneUri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                activeMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            } else {
                activeMediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
            }
            activeMediaPlayer.setLooping(looping);
            activeMediaPlayer.prepare();
            activeMediaPlayer.start();
        } catch (Exception e) {
            LOG.warn("Failed to play media ringtone, falling back to siren: {}", e.getMessage());
            playSyntheticTone(TONE_SIREN, looping);
        }
    }

    private void playSyntheticTone(String toneId, boolean looping) {
        isSyntheticPlaying = true;
        syntheticPlaybackThread = new Thread(() -> {
            int sampleRate = 22050;
            int bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            if (bufferSize < 2048) bufferSize = 2048;

            AudioTrack track = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ALARM)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build())
                            .setBufferSizeInBytes(bufferSize)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                } else {
                    track = new AudioTrack(
                            AudioManager.STREAM_ALARM,
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize,
                            AudioTrack.MODE_STREAM
                    );
                }

                synchronized (AlarmTonePlayer.this) {
                    activeAudioTrack = track;
                }

                track.play();

                short[] buffer = new short[512];
                double phase = 0.0;
                long sampleIndex = 0;

                while (isSyntheticPlaying) {
                    for (int i = 0; i < buffer.length; i++) {
                        double currentFreq;
                        double amp = 28000.0;

                        if (TONE_DIGITAL_BEEP.equals(toneId)) {
                            // 4 rapid beeps (each 80ms on, 40ms off) followed by 400ms pause
                            long cycleSamples = (long) (sampleRate * 0.90); // ~900ms cycle
                            long posInCycle = sampleIndex % cycleSamples;
                            long burstLen = (long) (sampleRate * 0.12); // 120ms per beep slot

                            if (posInCycle < burstLen * 4) {
                                long withinSlot = posInCycle % burstLen;
                                if (withinSlot < (sampleRate * 0.08)) {
                                    currentFreq = 1200.0; // 1200 Hz tone
                                } else {
                                    currentFreq = 0.0;
                                    amp = 0.0;
                                }
                            } else {
                                currentFreq = 0.0;
                                amp = 0.0;
                            }
                        } else if (TONE_MILITARY_BUGLE.equals(toneId)) {
                            // Military Reveille fanfare notes: C4 (261Hz), G4 (392Hz), C5 (523Hz), E5 (659Hz)
                            long noteLen = (long) (sampleRate * 0.22); // 220ms per note
                            int noteIndex = (int) ((sampleIndex / noteLen) % 8);
                            double[] reveilleNotes = {261.63, 329.63, 392.00, 523.25, 392.00, 523.25, 392.00, 329.63};
                            currentFreq = reveilleNotes[noteIndex];
                        } else {
                            // TONE_SIREN: continuous frequency sweep from 700 Hz to 1700 Hz
                            double sweepRate = 1.6; // 1.6 Hz modulation
                            double t = (double) sampleIndex / (double) sampleRate;
                            currentFreq = 700.0 + 1000.0 * (0.5 * (1.0 + Math.sin(2.0 * Math.PI * sweepRate * t)));
                        }

                        if (amp > 0 && currentFreq > 0) {
                            phase += (2.0 * Math.PI * currentFreq) / sampleRate;
                            if (phase > 2.0 * Math.PI) {
                                phase -= 2.0 * Math.PI;
                            }
                            buffer[i] = (short) (Math.sin(phase) * amp);
                        } else {
                            buffer[i] = 0;
                        }
                        sampleIndex++;
                    }

                    if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        track.write(buffer, 0, buffer.length);
                    } else {
                        break;
                    }

                    if (!looping && sampleIndex >= (sampleRate * 3)) {
                        break;
                    }
                }
            } catch (Exception e) {
                LOG.warn("Synthetic audio playback error: {}", e.getMessage());
            } finally {
                if (track != null) {
                    try {
                        track.stop();
                        track.release();
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        }, "AlarmSyntheticPlaybackThread");

        syntheticPlaybackThread.start();
    }
}
