package com.personal.disciplinelock.domain.whitelist

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.personal.disciplinelock.data.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class WhitelistAppItem(
    val name: String,
    val packageName: String,
    val isWhitelisted: Boolean,
    val isSystemApp: Boolean,
    val icon: ImageBitmap? = null
)

class AppsWhitelistManager(
    private val context: Context,
    private val securePreferences: SecurePreferences
) {
    companion object {
        private const val TAG = "AppsWhitelistManager"
    }

    private val packageManager: PackageManager = context.packageManager

    private val _whitelistedPackages = MutableStateFlow<Set<String>>(securePreferences.whitelistedPackages)
    val whitelistedPackages: StateFlow<Set<String>> = _whitelistedPackages.asStateFlow()

    private val _installedApps = MutableStateFlow<List<WhitelistAppItem>>(emptyList())
    val installedApps: StateFlow<List<WhitelistAppItem>> = _installedApps.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    suspend fun refreshInstalledApps() {
        _isLoading.value = true
        withContext(Dispatchers.IO) {
            try {
                val currentWhitelist = _whitelistedPackages.value
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
                val appMap = mutableMapOf<String, WhitelistAppItem>()

                for (info in resolveInfos) {
                    val pkgName = info.activityInfo.packageName
                    if (pkgName == context.packageName) continue // Skip Discipline OS itself

                    val label = try {
                        info.loadLabel(packageManager).toString()
                    } catch (_: Exception) {
                        pkgName
                    }

                    val isSystem = (info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val iconBitmap = try {
                        val drawable = info.loadIcon(packageManager)
                        drawableToImageBitmap(drawable)
                    } catch (_: Exception) {
                        null
                    }

                    appMap[pkgName] = WhitelistAppItem(
                        name = label,
                        packageName = pkgName,
                        isWhitelisted = currentWhitelist.contains(pkgName),
                        isSystemApp = isSystem,
                        icon = iconBitmap
                    )
                }

                // Sort: Whitelisted first, then alphabetical
                val sorted = appMap.values.sortedWith(
                    compareByDescending<WhitelistAppItem> { it.isWhitelisted }
                        .thenBy { it.name.lowercase() }
                )

                _installedApps.value = sorted
            } catch (e: Exception) {
                Log.e(TAG, "Error querying installed apps: ${e.message}", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addToWhitelist(packageName: String) {
        val updated = _whitelistedPackages.value.toMutableSet()
        updated.add(packageName)
        saveWhitelist(updated)
    }

    fun removeFromWhitelist(packageName: String) {
        val updated = _whitelistedPackages.value.toMutableSet()
        updated.remove(packageName)
        saveWhitelist(updated)
    }

    fun toggleWhitelist(packageName: String) {
        val updated = _whitelistedPackages.value.toMutableSet()
        if (updated.contains(packageName)) {
            updated.remove(packageName)
        } else {
            updated.add(packageName)
        }
        saveWhitelist(updated)
    }

    fun isWhitelisted(packageName: String): Boolean {
        return _whitelistedPackages.value.contains(packageName)
    }

    fun launchApp(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                Log.w(TAG, "No launch intent found for $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName: ${e.message}", e)
            false
        }
    }

    private fun saveWhitelist(set: Set<String>) {
        securePreferences.whitelistedPackages = set
        _whitelistedPackages.value = set
        // Update isWhitelisted flags in loaded apps list
        _installedApps.value = _installedApps.value.map { item ->
            item.copy(isWhitelisted = set.contains(item.packageName))
        }
    }

    private fun drawableToImageBitmap(drawable: Drawable): ImageBitmap? {
        return try {
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
}
