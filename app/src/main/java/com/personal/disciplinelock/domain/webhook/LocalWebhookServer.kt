package com.personal.disciplinelock.domain.webhook

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WebhookRunMetrics(
    val distanceMeters: Double,
    val speedMps: Float,
    val paceMinPerKm: Float,
    val source: String, // "Traccar", "GPSLogger", "Manual / Simulated"
    val timestampMs: Long = System.currentTimeMillis(),
    val rawPayload: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null
)

data class WebhookLogItem(
    val path: String,
    val method: String,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Embedded Lightweight HTTP Webhook Server.
 *
 * Runs locally on the Android device (default port 8080) to receive running metrics from:
 * 1. Traccar (HTTP JSON POST with forward.enable / event.forward.enable)
 * 2. GPSLogger for Android (Log to custom URL with %DIST, %SPD, %TIME)
 * 3. Generic Mission completion webhooks (/webhook/mission/{id})
 */
class LocalWebhookServer(
    private var preferredPort: Int = 8080,
    private var customTailscaleHost: String? = null,
    private val onRunMetricsReceived: (distanceMeters: Double, speedMps: Float?, paceMinPerKm: Float?, isFinished: Boolean, targetDistanceMeters: Double?, targetPaceMinPerKm: Float?) -> Unit,
    private val onMissionVerified: (target: String, source: String) -> Pair<Boolean, String?>,
    private val getMissionsSummary: (() -> List<Pair<String, Boolean>>)? = null,
    private val onStepsReceived: ((steps: Int, source: String) -> Pair<Boolean, String?>)? = null,
    private val getStatusDetailsJson: (() -> JSONObject)? = null,
    private val onMissionAdded: ((title: String, category: String?) -> Pair<Boolean, String?>)? = null
) {

    companion object {
        private const val TAG = "LocalWebhookServer"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private fun resolveLocalIp(): String {
        val custom = customTailscaleHost?.trim()
        if (!custom.isNullOrEmpty()) {
            return custom
        }
        try {
            var tailscaleIp: String? = null
            var localIp: String? = null
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                val name = intf.name.lowercase(Locale.getDefault())
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        // Tailscale CGNAT IP range (100.64.0.0/10) or VPN interface (tun0, tailscale)
                        if (ip.startsWith("100.") || name.contains("tailscale") || name.contains("tun")) {
                            tailscaleIp = ip
                            break
                        }
                        if (localIp == null) {
                            localIp = ip
                        }
                    }
                }
                if (tailscaleIp != null) break
            }
            return tailscaleIp ?: localIp ?: "127.0.0.1"
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private val _serverIp = MutableStateFlow(resolveLocalIp())
    val serverIp: StateFlow<String> = _serverIp.asStateFlow()

    private val _isTailscaleActive = MutableStateFlow(checkIfTailscaleActive(_serverIp.value))
    val isTailscaleActive: StateFlow<Boolean> = _isTailscaleActive.asStateFlow()

    private fun checkIfTailscaleActive(ip: String): Boolean {
        val custom = customTailscaleHost?.trim()
        if (!custom.isNullOrEmpty()) return true
        return ip.startsWith("100.")
    }

    fun setCustomTailscaleHost(host: String?) {
        customTailscaleHost = host?.trim()?.ifEmpty { null }
        val newIp = resolveLocalIp()
        _serverIp.value = newIp
        _isTailscaleActive.value = checkIfTailscaleActive(newIp)
        Log.i(TAG, "Updated custom Tailscale host to '$customTailscaleHost', active server IP: $newIp")
    }

    fun refreshNetworkIp() {
        val newIp = resolveLocalIp()
        _serverIp.value = newIp
        _isTailscaleActive.value = checkIfTailscaleActive(newIp)
    }

    private val _activePort = MutableStateFlow(preferredPort)
    val activePort: StateFlow<Int> = _activePort.asStateFlow()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _latestRunMetrics = MutableStateFlow<WebhookRunMetrics?>(null)
    val latestRunMetrics: StateFlow<WebhookRunMetrics?> = _latestRunMetrics.asStateFlow()

    private val _latestSteps = MutableStateFlow<Int>(0)
    val latestSteps: StateFlow<Int> = _latestSteps.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<WebhookLogItem>>(emptyList())
    val recentLogs: StateFlow<List<WebhookLogItem>> = _recentLogs.asStateFlow()

    fun start() {
        if (_isServerRunning.value) return

        serverJob = scope.launch {
            var port = preferredPort
            var socket: ServerSocket? = null

            // Try binding to port .. port + 15
            for (p in preferredPort..(preferredPort + 15)) {
                try {
                    socket = ServerSocket().apply {
                        reuseAddress = true
                        bind(java.net.InetSocketAddress(p))
                    }
                    port = p
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Port $p busy, attempting next port...")
                }
            }

            if (socket == null) {
                Log.e(TAG, "Failed to bind LocalWebhookServer on ports $preferredPort..${preferredPort + 15}")
                return@launch
            }

            serverSocket = socket
            _serverIp.value = resolveLocalIp()
            _activePort.value = port
            _isServerRunning.value = true
            Log.i(TAG, "🟢 LocalWebhookServer started successfully on port $port")

            while (isActive && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch { handleClient(client) }
                } catch (e: Exception) {
                    if (!socket.isClosed) {
                        Log.e(TAG, "Error accepting client: ${e.message}")
                    }
                }
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
            serverJob?.cancel()
            _isServerRunning.value = false
            Log.i(TAG, "LocalWebhookServer stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}")
        }
    }

    fun restart(newPort: Int = preferredPort) {
        preferredPort = newPort
        stop()
        start()
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(client.getOutputStream())

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val fullPath = parts[1]

            // Read HTTP headers
            var contentLength = 0
            var headerLine: String? = reader.readLine()
            while (!headerLine.isNullOrEmpty()) {
                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                headerLine = reader.readLine()
            }

            // Read payload body if present
            val bodyBuilder = StringBuilder()
            if (contentLength > 0) {
                val charBuf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val read = reader.read(charBuf, readTotal, contentLength - readTotal)
                    if (read == -1) break
                    readTotal += read
                }
                bodyBuilder.append(charBuf, 0, readTotal)
            }
            val body = bodyBuilder.toString()

            val uriPath = fullPath.substringBefore("?")
            val queryString = if (fullPath.contains("?")) fullPath.substringAfter("?") else ""

            Log.d(TAG, "Incoming $method $uriPath (len: $contentLength)")

            val responseJson: String = when {
                uriPath == "/webhook/run" || uriPath == "/webhook/traccar" || uriPath == "/webhook/gpslogger" -> {
                    processRunWebhook(body, queryString)
                }
                uriPath == "/webhook/steps" -> {
                    processStepsWebhook(body, queryString, method)
                }
                uriPath == "/webhook/health" -> {
                    processHealthWebhook(body, queryString, method)
                }
                uriPath == "/webhook/mission/add" || uriPath == "/webhook/missions/add" -> {
                    val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val rawTitle = json.optString("title", json.optString("name", json.optString("mission", "")))
                        .ifBlank {
                            if (queryString.contains("title=")) queryString.substringAfter("title=").substringBefore("&")
                            else if (queryString.isNotBlank() && !queryString.contains("=")) queryString
                            else ""
                        }
                    val decodedTitle = try { java.net.URLDecoder.decode(rawTitle, "UTF-8").trim() } catch (_: Exception) { rawTitle.trim() }
                    if (decodedTitle.isNotBlank()) {
                        val category = json.optString("category", "General")
                        val result = onMissionAdded?.invoke(decodedTitle, category)
                        if (result?.first == true) {
                            addLog(uriPath, method, "Added mission: '$decodedTitle'")
                            """{"status":"success","message":"Mission added to daily obligations","title":"$decodedTitle"}"""
                        } else {
                            addLog(uriPath, method, "Failed to add mission: ${result?.second}")
                            """{"status":"error","message":"${result?.second ?: "Failed to add mission"}"}"""
                        }
                    } else {
                        """{"status":"error","message":"Missing mission title in payload or query (?title=...)"}"""
                    }
                }
                uriPath.startsWith("/webhook/mission/delete") -> {
                    """{"status":"error","message":"Deleting missions is strictly forbidden to preserve discipline integrity"}"""
                }
                uriPath.startsWith("/webhook/mission/") -> {
                    val rawTarget = uriPath.removePrefix("/webhook/mission/").trimEnd('/')
                    val target = try { java.net.URLDecoder.decode(rawTarget, "UTF-8") } catch (_: Exception) { rawTarget }
                    val (verified, matchedTitle) = onMissionVerified(target, "Webhook $method $uriPath")
                    if (verified) {
                        addLog(uriPath, method, "Verified: '$matchedTitle'")
                        """{"status":"success","target":"$target","mission":"$matchedTitle","verified":true}"""
                    } else {
                        addLog(uriPath, method, "No mission matched '$target'")
                        """{"status":"error","message":"No mission matched '$target'"}"""
                    }
                }
                uriPath == "/webhook/mission" -> {
                    val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val id = json.optLong("missionId", json.optLong("id", -1L))
                    val title = json.optString("title", json.optString("name", json.optString("mission", "")))
                    val key = json.optString("key", json.optString("tag", json.optString("slug", "")))
                    val target = when {
                        id > 0 -> id.toString()
                        key.isNotBlank() -> key
                        title.isNotBlank() -> title
                        queryString.isNotBlank() -> {
                            if (queryString.contains("=")) queryString.substringAfter("=") else queryString
                        }
                        else -> ""
                    }
                    if (target.isNotBlank()) {
                        val (verified, matchedTitle) = onMissionVerified(target, "Webhook JSON")
                        if (verified) {
                            addLog(uriPath, method, "Verified: '$matchedTitle'")
                            """{"status":"success","target":"$target","mission":"$matchedTitle","verified":true}"""
                        } else {
                            addLog(uriPath, method, "No mission matched '$target'")
                            """{"status":"error","message":"No mission matched '$target'"}"""
                        }
                    } else {
                        """{"status":"error","message":"Missing missionId, key, or title in JSON payload"}"""
                    }
                }
                uriPath == "/webhook/missions" -> {
                    val summary = getMissionsSummary?.invoke() ?: emptyList()
                    val array = org.json.JSONArray()
                    for ((name, completed) in summary) {
                        val item = JSONObject()
                        item.put("title", name)
                        item.put("completed", completed)
                        array.put(item)
                    }
                    val res = JSONObject()
                    res.put("status", "ok")
                    res.put("serverIp", _serverIp.value)
                    res.put("port", _activePort.value)
                    res.put("missions", array)
                    res.toString()
                }
                uriPath == "/webhook/alarm" || uriPath == "/webhook/target" -> {
                    val statusObj = getStatusDetailsJson?.invoke() ?: JSONObject()
                    val alarmObj = statusObj.optJSONObject("morningAlarm") ?: JSONObject()
                    alarmObj.put("status", "ok")
                    alarmObj.put("serverIp", _serverIp.value)
                    alarmObj.put("port", _activePort.value)
                    alarmObj.toString()
                }
                uriPath == "/webhook/status" || uriPath == "/" -> {
                    val statusObj = getStatusDetailsJson?.invoke() ?: JSONObject()
                    if (!statusObj.has("status")) statusObj.put("status", "active")
                    if (!statusObj.has("server")) statusObj.put("server", "DisciplineOS Webhook Receiver")
                    if (!statusObj.has("ip")) statusObj.put("ip", _serverIp.value)
                    if (!statusObj.has("port")) statusObj.put("port", _activePort.value)
                    if (!statusObj.has("latestSteps")) statusObj.put("latestSteps", _latestSteps.value)
                    if (!statusObj.has("endpoints")) {
                        val epArray = org.json.JSONArray().apply {
                            put("/webhook/status")
                            put("/webhook/health")
                            put("/webhook/steps")
                            put("/webhook/run")
                            put("/webhook/alarm")
                            put("/webhook/target")
                            put("/webhook/mission/{target}")
                            put("/webhook/mission")
                            put("/webhook/missions")
                        }
                        statusObj.put("endpoints", epArray)
                    }
                    statusObj.toString()
                }
                else -> {
                    """{"status":"not_found","path":"$uriPath"}"""
                }
            }

            // Send standard HTTP response
            writer.print("HTTP/1.1 200 OK\r\n")
            writer.print("Content-Type: application/json\r\n")
            writer.print("Access-Control-Allow-Origin: *\r\n")
            writer.print("Content-Length: ${responseJson.toByteArray().size}\r\n")
            writer.print("Connection: close\r\n\r\n")
            writer.print(responseJson)
            writer.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling webhook client: ${e.message}", e)
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /**
     * Parses Traccar and GPSLogger payloads and extracts distance, speed, pace, and completion status.
     */
    private fun processRunWebhook(body: String, queryString: String): String {
        var distanceMeters = 0.0
        var speedMps = 0.0f
        var isFinished = false
        var targetDistanceMeters: Double? = null
        var targetPaceMinPerKm: Float? = null
        var latitude: Double? = null
        var longitude: Double? = null
        var source = "Webhook"

        // 1. Try parsing JSON body (Traccar or GPSLogger JSON mode)
        if (body.isNotBlank()) {
            try {
                val json = JSONObject(body)

                // Traccar: event type "deviceStopped" or "commandResult"
                if (json.has("event")) {
                    source = "Traccar Event"
                    val eventObj = json.optJSONObject("event")
                    val eventType = eventObj?.optString("type", "") ?: ""
                    if (eventType.equals("deviceStopped", ignoreCase = true) ||
                        eventType.equals("deviceOffline", ignoreCase = true)) {
                        isFinished = true
                    }
                }

                // Traccar: position object
                val posObj = json.optJSONObject("position") ?: json
                if (posObj.has("attributes")) {
                    source = "Traccar"
                    val attrs = posObj.optJSONObject("attributes")
                    val totalDist = attrs?.optDouble("totalDistance", 0.0) ?: 0.0
                    val segDist = attrs?.optDouble("distance", 0.0) ?: 0.0
                    distanceMeters = if (totalDist > 0.0) totalDist else segDist
                }

                if (posObj.has("speed")) {
                    speedMps = posObj.optDouble("speed", 0.0).toFloat()
                }

                if (posObj.has("latitude") || posObj.has("lat")) {
                    val lat = posObj.optDouble("latitude", posObj.optDouble("lat", 0.0))
                    val lon = posObj.optDouble("longitude", posObj.optDouble("lon", 0.0))
                    if (lat != 0.0 || lon != 0.0) {
                        latitude = lat
                        longitude = lon
                    }
                }

                // GPSLogger / Discipline Health body variables
                if (json.has("distanceMeters") || json.has("dist") || json.has("distance")) {
                    source = "GPSLogger"
                    distanceMeters = json.optDouble("distanceMeters", json.optDouble("dist", json.optDouble("distance", 0.0)))
                }
                if (json.has("speedMps") || json.has("spd") || json.has("speed")) {
                    speedMps = json.optDouble("speedMps", json.optDouble("spd", json.optDouble("speed", 0.0))).toFloat()
                }
                if (json.has("latitude") || json.has("lat")) {
                    val lat = json.optDouble("latitude", json.optDouble("lat", 0.0))
                    val lon = json.optDouble("longitude", json.optDouble("lon", 0.0))
                    if (lat != 0.0 || lon != 0.0) {
                        latitude = lat
                        longitude = lon
                    }
                }
                if (json.has("isFinished")) {
                    isFinished = json.optBoolean("isFinished", false)
                }
                if (json.has("targetDistanceMeters")) {
                    val td = json.optDouble("targetDistanceMeters", 0.0)
                    if (td > 0.0) targetDistanceMeters = td
                } else if (json.has("targetDist")) {
                    val td = json.optDouble("targetDist", 0.0)
                    if (td > 0.0) targetDistanceMeters = td
                }
                if (json.has("targetPaceMinPerKm")) {
                    val tp = json.optDouble("targetPaceMinPerKm", 0.0).toFloat()
                    if (tp > 0f) targetPaceMinPerKm = tp
                } else if (json.has("targetPace")) {
                    val tp = json.optDouble("targetPace", 0.0).toFloat()
                    if (tp > 0f) targetPaceMinPerKm = tp
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse JSON body: ${e.message}")
            }
        }

        // 2. Parse Query String (GPSLogger URL parameter mode: ?dist=2500&spd=3.2)
        if (queryString.isNotBlank()) {
            try {
                val params = parseQueryString(queryString)
                val distParam = params["dist"] ?: params["distance"] ?: params["%dist"]
                val spdParam = params["spd"] ?: params["speed"] ?: params["%spd"]
                val eventParam = params["event"] ?: params["action"]
                val targetDistParam = params["targetdist"] ?: params["target_dist"] ?: params["targetdistancemeters"]
                val targetPaceParam = params["targetpace"] ?: params["target_pace"] ?: params["targetpaceminperkm"]
                val latParam = params["lat"] ?: params["latitude"]
                val lonParam = params["lon"] ?: params["lng"] ?: params["longitude"]

                if (distParam != null) {
                    source = "GPSLogger URL"
                    val parsed = distParam.toDoubleOrNull() ?: 0.0
                    if (parsed > distanceMeters) distanceMeters = parsed
                }
                if (spdParam != null) {
                    val parsed = spdParam.toFloatOrNull() ?: 0.0f
                    if (parsed > 0f) speedMps = parsed
                }
                if (latParam != null && lonParam != null) {
                    val parsedLat = latParam.toDoubleOrNull()
                    val parsedLon = lonParam.toDoubleOrNull()
                    if (parsedLat != null && parsedLon != null) {
                        latitude = parsedLat
                        longitude = parsedLon
                    }
                }
                if (targetDistParam != null) {
                    val parsed = targetDistParam.toDoubleOrNull() ?: 0.0
                    if (parsed > 0.0) targetDistanceMeters = parsed
                }
                if (targetPaceParam != null) {
                    val parsed = targetPaceParam.toFloatOrNull() ?: 0.0f
                    if (parsed > 0f) targetPaceMinPerKm = parsed
                }
                if (eventParam != null && (eventParam.equals("stop", ignoreCase = true) || eventParam.equals("completed", ignoreCase = true))) {
                    isFinished = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse query string: ${e.message}")
            }
        }

        // If distance is specified in kilometers (e.g. 2.5), convert to meters
        if (distanceMeters > 0 && distanceMeters < 50.0) {
            distanceMeters *= 1000.0
        }
        if (targetDistanceMeters != null && targetDistanceMeters > 0 && targetDistanceMeters < 50.0) {
            targetDistanceMeters *= 1000.0
        }

        // If speed is in km/h (> 25 m/s is sprinting over 90 km/h!), convert to m/s
        if (speedMps > 30.0f) {
            speedMps /= 3.6f
        }

        val paceMinPerKm = if (speedMps > 0.5f) {
            (1000f / speedMps) / 60f
        } else {
            0f
        }

        val metrics = WebhookRunMetrics(
            distanceMeters = distanceMeters,
            speedMps = speedMps,
            paceMinPerKm = paceMinPerKm,
            latitude = latitude,
            longitude = longitude,
            source = source,
            rawPayload = if (body.isNotBlank()) body.take(200) else queryString
        )
        _latestRunMetrics.value = metrics
        val locStr = if (latitude != null && longitude != null) String.format(" @ %.5f, %.5f", latitude, longitude) else ""
        addLog("/webhook/run", "POST", "$source: ${String.format("%.2f km", distanceMeters / 1000.0)}, Pace: ${String.format("%.1f min/km", paceMinPerKm)}$locStr")

        // Dispatch to Morning Mission Manager!
        onRunMetricsReceived(distanceMeters, speedMps, paceMinPerKm, isFinished, targetDistanceMeters, targetPaceMinPerKm)

        return JSONObject().apply {
            put("status", "success")
            put("receivedDistanceMeters", distanceMeters)
            put("formattedDistance", String.format("%.2f km", distanceMeters / 1000.0))
            put("speedMps", speedMps)
            put("paceMinPerKm", paceMinPerKm)
            if (latitude != null) put("latitude", latitude)
            if (longitude != null) put("longitude", longitude)
            put("source", source)
            put("isFinished", isFinished)
        }.toString()
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (param in query.split("&")) {
            val pair = param.split("=")
            if (pair.isNotEmpty()) {
                val key = URLDecoder.decode(pair[0], "UTF-8").lowercase(Locale.getDefault())
                val value = if (pair.size > 1) URLDecoder.decode(pair[1], "UTF-8") else ""
                map[key] = value
            }
        }
        return map
    }

    private fun addLog(path: String, method: String, summary: String) {
        val item = WebhookLogItem(path, method, summary)
        val current = _recentLogs.value.toMutableList()
        current.add(0, item)
        if (current.size > 20) current.removeAt(current.size - 1)
        _recentLogs.value = current
    }

    /**
     * Programmatic trigger for testing / simulating run webhooks directly from the UI.
     */
    fun simulateRunWebhook(distanceMeters: Double = 2500.0, speedMps: Float = 3.2f) {
        val pace = if (speedMps > 0.5f) (1000f / speedMps) / 60f else 5.2f
        val metrics = WebhookRunMetrics(
            distanceMeters = distanceMeters,
            speedMps = speedMps,
            paceMinPerKm = pace,
            source = "Simulated Traccar/GPSLogger",
            rawPayload = "{\"totalDistance\": $distanceMeters, \"speed\": $speedMps}"
        )
        _latestRunMetrics.value = metrics
        addLog("/webhook/run", "TEST", "Simulated Run: ${String.format("%.2f km", distanceMeters / 1000.0)}")
        onRunMetricsReceived(distanceMeters, speedMps, pace, true, null, null)
    }

    /**
     * Programmatic trigger for testing / simulating mission webhooks.
     */
    fun triggerSimulatedMissionWebhook(target: String): Pair<Boolean, String?> {
        val result = onMissionVerified(target, "Simulated Webhook")
        if (result.first) {
            addLog("/webhook/mission/$target", "SIMULATED", "Verified: '${result.second}'")
        } else {
            addLog("/webhook/mission/$target", "SIMULATED", "No mission matched '$target'")
        }
        return result
    }

    fun simulateMissionWebhook(missionId: Long) {
        triggerSimulatedMissionWebhook(missionId.toString())
    }

    /**
     * Programmatic trigger for testing / simulating steps webhooks directly from the UI or tests.
     */
    fun simulateStepsWebhook(steps: Int = 10000): Pair<Boolean, String?> {
        _latestSteps.value = steps
        val (verified, matchedTitle) = onStepsReceived?.invoke(steps, "Simulated Health Companion") ?: Pair(false, null)
        addLog("/webhook/steps", "SIMULATED", "Simulated Steps: %,d steps${if (verified) " (Verified: '$matchedTitle')" else ""}".format(steps))
        return Pair(verified, matchedTitle)
    }

    /**
     * Parses and processes incoming step counts from the Health Companion App.
     */
    private fun processStepsWebhook(body: String, queryString: String, method: String): String {
        var steps = 0
        var source = "Health Companion"

        if (body.isNotBlank()) {
            try {
                val json = JSONObject(body)
                steps = json.optInt("steps", json.optInt("count", json.optInt("stepCount", 0)))
                source = json.optString("source", "Health Companion")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse steps JSON body: ${e.message}")
            }
        }

        if (steps <= 0 && queryString.isNotBlank()) {
            try {
                val params = parseQueryString(queryString)
                steps = params["steps"]?.toIntOrNull() ?: params["count"]?.toIntOrNull() ?: params["stepcount"]?.toIntOrNull() ?: 0
                if (params.containsKey("source")) source = params["source"] ?: source
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse steps query string: ${e.message}")
            }
        }

        if (steps > 0) {
            _latestSteps.value = steps
            val (verified, matchedTitle) = onStepsReceived?.invoke(steps, source) ?: Pair(false, null)
            val summary = "$source: %,d steps${if (verified) " (Verified: '$matchedTitle')" else ""}".format(steps)
            addLog("/webhook/steps", method, summary)
            return JSONObject().apply {
                put("status", "success")
                put("steps", steps)
                put("source", source)
                put("verified", verified)
                if (matchedTitle != null) {
                    put("matchedMission", matchedTitle)
                }
            }.toString()
        } else {
            addLog("/webhook/steps", method, "Invalid or missing steps parameter")
            return JSONObject().apply {
                put("status", "error")
                put("message", "Invalid or missing 'steps' count parameter")
            }.toString()
        }
    }

    /**
     * Combined health telemetry endpoint (/webhook/health) receiving steps, running GPS, heart rate, calories.
     */
    private fun processHealthWebhook(body: String, queryString: String, method: String): String {
        var steps = 0
        var distanceMeters = 0.0
        var speedMps = 0.0f
        var heartRate = 0
        var calories = 0
        var isFinished = false
        var source = "Discipline Health Companion"

        if (body.isNotBlank()) {
            try {
                val json = JSONObject(body)
                steps = json.optInt("steps", json.optInt("stepCount", 0))
                distanceMeters = json.optDouble("distance", json.optDouble("dist", json.optDouble("distanceMeters", 0.0)))
                speedMps = json.optDouble("speed", json.optDouble("spd", 0.0)).toFloat()
                heartRate = json.optInt("heartRate", json.optInt("hr", 0))
                calories = json.optInt("calories", json.optInt("cal", 0))
                isFinished = json.optBoolean("isFinished", json.optBoolean("finished", false))
                source = json.optString("source", "Discipline Health Companion")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse health JSON body: ${e.message}")
            }
        }

        if (queryString.isNotBlank()) {
            try {
                val params = parseQueryString(queryString)
                if (steps <= 0) steps = params["steps"]?.toIntOrNull() ?: 0
                if (distanceMeters <= 0.0) distanceMeters = params["dist"]?.toDoubleOrNull() ?: params["distance"]?.toDoubleOrNull() ?: 0.0
                if (speedMps <= 0f) speedMps = params["spd"]?.toFloatOrNull() ?: params["speed"]?.toFloatOrNull() ?: 0f
                if (heartRate <= 0) heartRate = params["hr"]?.toIntOrNull() ?: 0
                if (calories <= 0) calories = params["cal"]?.toIntOrNull() ?: 0
                if (params.containsKey("source")) source = params["source"] ?: source
                val evt = params["event"] ?: params["action"]
                if (evt.equals("stop", ignoreCase = true) || evt.equals("completed", ignoreCase = true)) {
                    isFinished = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse health query string: ${e.message}")
            }
        }

        var stepVerified = false
        var stepMatchedMission: String? = null
        if (steps > 0) {
            _latestSteps.value = steps
            val res = onStepsReceived?.invoke(steps, source)
            if (res != null) {
                stepVerified = res.first
                stepMatchedMission = res.second
            }
        }

        if (distanceMeters > 0.0) {
            if (distanceMeters > 0 && distanceMeters < 50.0) {
                distanceMeters *= 1000.0
            }
            if (speedMps > 30.0f) {
                speedMps /= 3.6f
            }
            val paceMinPerKm = if (speedMps > 0.5f) (1000f / speedMps) / 60f else 0f
            val metrics = WebhookRunMetrics(
                distanceMeters = distanceMeters,
                speedMps = speedMps,
                paceMinPerKm = paceMinPerKm,
                source = source,
                rawPayload = if (body.isNotBlank()) body.take(200) else queryString
            )
            _latestRunMetrics.value = metrics
            onRunMetricsReceived(distanceMeters, speedMps, paceMinPerKm, isFinished, null, null)
        }

        val summary = "$source sync: %,d steps, %.2f km".format(steps, distanceMeters / 1000.0)
        addLog("/webhook/health", method, summary)

        return JSONObject().apply {
            put("status", "success")
            put("steps", steps)
            put("stepVerified", stepVerified)
            if (stepMatchedMission != null) put("stepMission", stepMatchedMission)
            put("distanceMeters", distanceMeters)
            put("formattedDistance", "%.2f km".format(distanceMeters / 1000.0))
            put("speedMps", speedMps)
            put("heartRate", heartRate)
            put("calories", calories)
            put("isFinished", isFinished)
        }.toString()
    }
}

