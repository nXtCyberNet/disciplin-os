package com.personal.disciplinelock.system

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Controller for interacting with KernelSU and executing root commands safely.
 *
 * Provides:
 * - Root detection (KernelSU / SU binary)
 * - 1-Click granting of all runtime permissions without ADB
 * - App freezing via `pm suspend` / `pm unsuspend`
 * - Anti-tamper USB debugging control
 */
class RootController(private val context: Context) {

    companion object {
        private const val TAG = "RootController"

        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su"
        )
    }

    /**
     * Check if a `su` binary exists on the device.
     */
    fun hasSuBinary(): Boolean {
        for (path in SU_PATHS) {
            if (File(path).exists()) return true
        }
        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(":")) {
            if (File(dir, "su").exists()) return true
        }
        return false
    }

    /**
     * Check if KernelSU is specifically present on the device.
     */
    fun isKernelSuDetected(): Boolean {
        val ksuDir = File("/data/adb/ksu")
        val ksuNodes = listOf(
            File("/sys/fs/selinux/ksu"),
            File("/dev/ksu")
        )
        return ksuDir.exists() || ksuNodes.any { it.exists() }
    }

    /**
     * Test whether root access is granted by running `id`.
     */
    suspend fun isRootAccessGranted(): Boolean = withContext(Dispatchers.IO) {
        val result = executeSu("id")
        result.isSuccess && result.output.contains("uid=0")
    }

    /**
     * Execute a shell command via `su`.
     */
    suspend fun executeSu(command: String): SuResult = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            val text = output.toString().trim()
            if (exitCode == 0) {
                Log.d(TAG, "su command succeeded: '$command'")
                SuResult(isSuccess = true, exitCode = exitCode, output = text)
            } else {
                Log.w(TAG, "su command failed (exit $exitCode): '$command' -> $text")
                SuResult(isSuccess = false, exitCode = exitCode, output = text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception running su command: '$command'", e)
            SuResult(isSuccess = false, exitCode = -1, output = e.message ?: "Execution error")
        }
    }

    /**
     * Automatically grants all required permissions via root.
     * Eliminates the need to use ADB from a computer.
     */
    suspend fun grantAllPermissionsViaRoot(): Result<String> = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val commands = listOf(
            // Runtime permissions
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "pm grant $pkg android.permission.READ_PHONE_STATE",
            "pm grant $pkg android.permission.ACCESS_FINE_LOCATION",
            "pm grant $pkg android.permission.ACCESS_COARSE_LOCATION",
            "pm grant $pkg android.permission.ACCESS_BACKGROUND_LOCATION",
            "pm grant $pkg android.permission.ACTIVITY_RECOGNITION",
            "pm grant $pkg android.permission.READ_MEDIA_AUDIO",
            // AppOps overlay permission
            "appops set $pkg SYSTEM_ALERT_WINDOW allow"
        )

        val fullScript = commands.joinToString(" && ")
        val res = executeSu(fullScript)
        if (res.isSuccess) {
            Log.i(TAG, "All permissions successfully granted via root.")
            Result.success("All permissions successfully granted via KernelSU root!")
        } else {
            Log.e(TAG, "Failed to grant permissions via root: ${res.output}")
            Result.failure(Exception("Root command failed: ${res.output}"))
        }
    }

    /**
     * Suspend an app package using `pm suspend`.
     * Suspended apps are grayed out in EvolutionX launcher and cannot be opened.
     */
    suspend fun suspendPackage(packageName: String): Boolean {
        if (packageName == context.packageName) return false
        val res = executeSu("pm suspend $packageName")
        return res.isSuccess
    }

    /**
     * Unsuspend an app package using `pm unsuspend`.
     */
    suspend fun unsuspendPackage(packageName: String): Boolean {
        val res = executeSu("pm unsuspend $packageName")
        return res.isSuccess
    }

    /**
     * Suspend multiple packages at once.
     */
    suspend fun suspendPackages(packageNames: Collection<String>): Int {
        val valid = packageNames.filter { it.isNotBlank() && it != context.packageName }
        if (valid.isEmpty()) return 0
        val cmd = valid.joinToString(" ") { "pm suspend $it;" }
        val res = executeSu(cmd)
        return if (res.isSuccess) valid.size else 0
    }

    /**
     * Unsuspend multiple packages at once.
     */
    suspend fun unsuspendPackages(packageNames: Collection<String>): Int {
        val valid = packageNames.filter { it.isNotBlank() }
        if (valid.isEmpty()) return 0
        val cmd = valid.joinToString(" ") { "pm unsuspend $it;" }
        val res = executeSu(cmd)
        return if (res.isSuccess) valid.size else 0
    }

    /**
     * Configure USB Debugging anti-tamper.
     * When enabled in Disciplined Mode, prevents bypassing via USB cable.
     */
    suspend fun setUsbDebuggingBlocked(blocked: Boolean): Boolean {
        val cmd = if (blocked) {
            "setprop persist.sys.usb.config none"
        } else {
            "setprop persist.sys.usb.config adb"
        }
        return executeSu(cmd).isSuccess
    }
}

data class SuResult(
    val isSuccess: Boolean,
    val exitCode: Int,
    val output: String
)
