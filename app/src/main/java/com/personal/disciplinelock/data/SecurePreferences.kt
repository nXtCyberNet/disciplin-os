package com.personal.disciplinelock.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePreferences(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize EncryptedSharedPreferences; falling back to private SharedPreferences to prevent lockout crash", e)
        context.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "SecurePreferences"
        private const val SECURE_PREFS_FILE = "discipline_secure_prefs"
        private const val FALLBACK_PREFS_FILE = "discipline_fallback_prefs"

        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_LLM_PROVIDER = "llm_provider"
        private const val KEY_GATE_ENABLED = "gate_enabled"
        private const val KEY_LOCK_TASK_ENABLED = "lock_task_mode_enabled"
        private const val KEY_MATH_ENGINE_ENABLED = "math_engine_enabled"
        private const val KEY_MATH_NUM_QUESTIONS = "math_num_questions"
        private const val KEY_MATH_TIME_LIMIT_SECONDS = "math_time_limit_seconds"
        private const val KEY_DISCIPLINED_COLOR_HEX = "disciplined_color_hex"
        const val DEFAULT_DISCIPLINED_COLOR_HEX = "#FCF5F0" // Sage & Linen
        private const val KEY_WEBHOOK_PORT = "key_webhook_port"
        private const val KEY_CUSTOM_TAILSCALE_HOST = "custom_tailscale_host"
        private const val KEY_ALWAYS_ON_TAILSCALE_ENABLED = "always_on_tailscale_enabled"
        private const val KEY_ROOT_FREEZING_ENABLED = "root_freezing_enabled"
        private const val KEY_ANTI_TAMPER_ADB_ENABLED = "anti_tamper_adb_enabled"
        private const val KEY_DAILY_RESET_HOUR = "daily_reset_hour"
        private const val KEY_DAILY_RESET_MINUTE = "daily_reset_minute"
        private const val KEY_ALARM_VOLUME_PERCENT = "alarm_volume_percent"
        private const val KEY_WHITELISTED_PACKAGES = "whitelisted_packages"
        private const val KEY_MORNING_ALARM_ENABLED = "morning_alarm_enabled"
        private const val KEY_MORNING_ALARM_HOUR = "morning_alarm_hour"
        private const val KEY_MORNING_ALARM_MINUTE = "morning_alarm_minute"
        private const val KEY_MORNING_TARGET_DISTANCE_METERS = "morning_target_distance_meters"
        private const val KEY_MORNING_TARGET_PACE_MIN_PER_KM = "morning_target_pace_min_per_km"
        private const val KEY_MORNING_SNOOZE_MINUTES = "morning_snooze_minutes"
        private const val KEY_MORNING_STATIONARY_GRACE_SECONDS = "morning_stationary_grace_seconds"
        private const val KEY_LAST_DAILY_RESET_DATE = "last_daily_reset_date"
        val DEFAULT_WHITELIST = setOf(
            "com.google.android.dialer",
            "com.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.settings",
            "com.nxtcybernet.disciplinerun",
            "com.mendhak.gpslogger",
            "com.mendhak.gpslogger.debug"
        )
    }

    var whitelistedPackages: Set<String>
        get() = prefs.getStringSet(KEY_WHITELISTED_PACKAGES, DEFAULT_WHITELIST) ?: DEFAULT_WHITELIST
        set(value) = prefs.edit().putStringSet(KEY_WHITELISTED_PACKAGES, value).apply()

    var alarmVolumePercent: Int
        get() = prefs.getInt(KEY_ALARM_VOLUME_PERCENT, 0)
        set(value) = prefs.edit().putInt(KEY_ALARM_VOLUME_PERCENT, value.coerceIn(0, 100)).apply()

    var dailyResetHour: Int
        get() = prefs.getInt(KEY_DAILY_RESET_HOUR, 0)
        set(value) = prefs.edit().putInt(KEY_DAILY_RESET_HOUR, value.coerceIn(0, 23)).apply()

    var dailyResetMinute: Int
        get() = prefs.getInt(KEY_DAILY_RESET_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_DAILY_RESET_MINUTE, value.coerceIn(0, 59)).apply()

    var lastDailyResetDate: String?
        get() = prefs.getString(KEY_LAST_DAILY_RESET_DATE, null)
        set(value) = prefs.edit().putString(KEY_LAST_DAILY_RESET_DATE, value).apply()

    val formattedDailyResetTime: String
        get() {
            val h = dailyResetHour
            val m = dailyResetMinute
            val amPm = if (h < 12) "AM" else "PM"
            val displayH = when {
                h == 0 -> 12
                h > 12 -> h - 12
                else -> h
            }
            return String.format(java.util.Locale.getDefault(), "%02d:%02d %s", displayH, m, amPm)
        }

    var llmApiKey: String?
        get() = prefs.getString(KEY_LLM_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_LLM_API_KEY, value).apply()

    var llmProvider: String
        get() = prefs.getString(KEY_LLM_PROVIDER, "gemini") ?: "gemini"
        set(value) = prefs.edit().putString(KEY_LLM_PROVIDER, value).apply()

    var isGateEnabled: Boolean
        get() = prefs.getBoolean(KEY_GATE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GATE_ENABLED, value).apply()

    var isLockTaskModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_TASK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_TASK_ENABLED, value).apply()

    var isMathEngineEnabled: Boolean
        get() = prefs.getBoolean(KEY_MATH_ENGINE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MATH_ENGINE_ENABLED, value).apply()

    var mathNumQuestions: Int
        get() = prefs.getInt(KEY_MATH_NUM_QUESTIONS, 5)
        set(value) = prefs.edit().putInt(KEY_MATH_NUM_QUESTIONS, value.coerceIn(1, 20)).apply()

    var mathTimeLimitSeconds: Int
        get() = prefs.getInt(KEY_MATH_TIME_LIMIT_SECONDS, 300)
        set(value) = prefs.edit().putInt(KEY_MATH_TIME_LIMIT_SECONDS, value.coerceIn(60, 1800)).apply()

    var disciplinedModeColorHex: String
        get() = prefs.getString(KEY_DISCIPLINED_COLOR_HEX, DEFAULT_DISCIPLINED_COLOR_HEX) ?: DEFAULT_DISCIPLINED_COLOR_HEX
        set(value) = prefs.edit().putString(KEY_DISCIPLINED_COLOR_HEX, value).apply()

    var webhookPort: Int
        get() = prefs.getInt(KEY_WEBHOOK_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_WEBHOOK_PORT, value.coerceIn(1024, 65535)).apply()

    var customTailscaleHost: String?
        get() = prefs.getString(KEY_CUSTOM_TAILSCALE_HOST, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_TAILSCALE_HOST, value?.trim()).apply()

    var isAlwaysOnTailscaleEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_ON_TAILSCALE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ALWAYS_ON_TAILSCALE_ENABLED, value).apply()

    var isRootFreezingEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROOT_FREEZING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ROOT_FREEZING_ENABLED, value).apply()

    var isAntiTamperAdbEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANTI_TAMPER_ADB_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ANTI_TAMPER_ADB_ENABLED, value).apply()

    var isMorningAlarmEnabled: Boolean
        get() = true // Strict discipline: Morning alarm cannot be turned off!
        set(_) = prefs.edit().putBoolean(KEY_MORNING_ALARM_ENABLED, true).apply()

    var morningAlarmHour: Int
        get() = prefs.getInt(KEY_MORNING_ALARM_HOUR, 6)
        set(value) = prefs.edit().putInt(KEY_MORNING_ALARM_HOUR, value.coerceIn(0, 23)).apply()

    var morningAlarmMinute: Int
        get() = prefs.getInt(KEY_MORNING_ALARM_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_MORNING_ALARM_MINUTE, value.coerceIn(0, 59)).apply()

    var morningTargetDistanceMeters: Double
        get() = prefs.getFloat(KEY_MORNING_TARGET_DISTANCE_METERS, 2000.0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_MORNING_TARGET_DISTANCE_METERS, value.toFloat()).apply()

    var morningTargetPaceMinPerKm: Float
        get() = prefs.getFloat(KEY_MORNING_TARGET_PACE_MIN_PER_KM, 6.0f)
        set(value) = prefs.edit().putFloat(KEY_MORNING_TARGET_PACE_MIN_PER_KM, value).apply()

    var morningSnoozeMinutes: Int
        get() = prefs.getInt(KEY_MORNING_SNOOZE_MINUTES, 15)
        set(value) = prefs.edit().putInt(KEY_MORNING_SNOOZE_MINUTES, value.coerceIn(1, 60)).apply()

    var morningStationaryGraceSeconds: Int
        get() = prefs.getInt(KEY_MORNING_STATIONARY_GRACE_SECONDS, 45)
        set(value) = prefs.edit().putInt(KEY_MORNING_STATIONARY_GRACE_SECONDS, value.coerceIn(10, 300)).apply()
}
