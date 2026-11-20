package com.personal.disciplinelock

import com.personal.disciplinelock.domain.task.DailyTaskItem
import com.personal.disciplinelock.domain.task.HabitItem
import com.personal.disciplinelock.domain.task.MissionVerificationType
import com.personal.disciplinelock.domain.webhook.LocalWebhookServer
import com.personal.disciplinelock.domain.webhook.MissionVerificationEngine
import com.personal.disciplinelock.domain.webhook.VerificationResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

class MissionVerificationTest {

    private var testSocket: ServerSocket? = null
    private var testServerThread: Thread? = null
    private lateinit var verificationEngine: MissionVerificationEngine

    @Before
    fun setUp() {
        verificationEngine = MissionVerificationEngine()
    }

    @After
    fun tearDown() {
        testSocket?.close()
        testServerThread?.interrupt()
    }

    private fun startSimpleServer(responseBody: String, statusCode: Int = 200): String {
        val socket = ServerSocket(0)
        testSocket = socket
        val port = socket.localPort

        testServerThread = thread(isDaemon = true) {
            try {
                while (!socket.isClosed) {
                    val client = socket.accept()
                    thread(isDaemon = true) {
                        client.use { s ->
                            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val writer = PrintWriter(s.getOutputStream(), true)
                            val statusText = if (statusCode == 200) "OK" else if (statusCode == 201) "Created" else "Error"
                            writer.print("HTTP/1.1 $statusCode $statusText\r\n")
                            writer.print("Content-Type: application/json\r\n")
                            writer.print("Content-Length: ${responseBody.toByteArray().size}\r\n")
                            writer.print("Connection: close\r\n\r\n")
                            writer.print(responseBody)
                            writer.flush()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return "http://127.0.0.1:$port/check"
    }

    @Test
    fun testMissionVerificationTypeEnum() {
        assertEquals("Manual Checkbox", MissionVerificationType.MANUAL.displayName)
        assertEquals("PC Backend Webhook (Inbound)", MissionVerificationType.WEBHOOK.displayName)
    }

    @Test
    fun testDailyTaskItemDefaultValues() {
        val task = DailyTaskItem(
            id = 42L,
            title = "Read 30 pages",
            date = "2026-11-17"
        )
        assertFalse(task.isCompleted)
        assertTrue(task.isRecurringEveryday)
        assertEquals(MissionVerificationType.WEBHOOK, task.verificationType)
        assertNull(task.verificationEndpoint)
        assertNull(task.verificationStatus)
    }

    @Test
    fun testHabitItemDefaultValues() {
        val habit = HabitItem(
            id = 101L,
            title = "Morning Mobility",
            category = "Fitness"
        )
        assertFalse(habit.isCompletedToday)
        assertEquals(0, habit.streak)
        assertEquals(MissionVerificationType.WEBHOOK, habit.verificationType)
    }

    @Test
    fun testLocalWebhookServerReceivesTraccarAndMissionPings() {
        var receivedDistance = 0.0
        var verifiedTarget: String? = null

        val server = LocalWebhookServer(
            preferredPort = 8180,
            onRunMetricsReceived = { dist, _, _, _, _, _ ->
                receivedDistance = dist
            },
            onMissionVerified = { target, _ ->
                verifiedTarget = target
                Pair(true, "Mission #$target")
            },
            getMissionsSummary = {
                listOf(Pair("Morning Run", false), Pair("Read", true))
            }
        )

        server.start()
        Thread.sleep(250) // Wait for socket to bind

        val activePort = server.activePort.value

        // 1. Send GPSLogger run query webhook: GET /webhook/run?dist=2500.5&spd=3.1&time=1200
        val runUrl = URL("http://127.0.0.1:$activePort/webhook/run?dist=2500.5&spd=3.1&time=1200")
        val runConn = runUrl.openConnection() as HttpURLConnection
        runConn.requestMethod = "GET"
        val runCode = runConn.responseCode
        assertEquals(200, runCode)
        runConn.disconnect()

        Thread.sleep(150)
        assertEquals(2500.5, receivedDistance, 0.01)

        // 2. Send Mission Completion webhook: POST /webhook/mission/42
        val missionUrl = URL("http://127.0.0.1:$activePort/webhook/mission/42")
        val missionConn = missionUrl.openConnection() as HttpURLConnection
        missionConn.requestMethod = "POST"
        val missionCode = missionConn.responseCode
        assertEquals(200, missionCode)
        missionConn.disconnect()

        Thread.sleep(150)
        assertEquals("42", verifiedTarget)

        // 3. Send Mission Completion webhook by slug: GET /webhook/mission/morning_run
        val slugUrl = URL("http://127.0.0.1:$activePort/webhook/mission/morning_run")
        val slugConn = slugUrl.openConnection() as HttpURLConnection
        slugConn.requestMethod = "GET"
        val slugCode = slugConn.responseCode
        assertEquals(200, slugCode)
        slugConn.disconnect()

        Thread.sleep(150)
        assertEquals("morning_run", verifiedTarget)

        // 4. Query status endpoint: GET /webhook/status
        val statusUrl = URL("http://127.0.0.1:$activePort/webhook/status")
        val statusConn = statusUrl.openConnection() as HttpURLConnection
        assertEquals(200, statusConn.responseCode)
        statusConn.disconnect()

        server.stop()
    }

    @Test
    fun testLocalWebhookServerStepsAndHealthEndpoints() {
        var receivedSteps = 0
        var receivedDistance = 0.0

        val server = LocalWebhookServer(
            preferredPort = 8280,
            onRunMetricsReceived = { dist, _, _, _, _, _ ->
                receivedDistance = dist
            },
            onMissionVerified = { _, _ -> Pair(true, "Mock Mission") },
            onStepsReceived = { steps, source ->
                receivedSteps = steps
                Pair(steps >= 10000, "Walk 10,000 steps")
            },
            getStatusDetailsJson = {
                org.json.JSONObject().apply {
                    put("mockStatus", "verified")
                }
            }
        )

        server.start()
        Thread.sleep(250)
        val port = server.activePort.value

        // 1. Test /webhook/steps POST JSON
        val stepsUrl = URL("http://127.0.0.1:$port/webhook/steps")
        val stepsConn = (stepsUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write("""{"steps": 10500, "source": "DisciplineHealth"}""".toByteArray()) }
        }
        assertEquals(200, stepsConn.responseCode)
        val stepsBody = stepsConn.inputStream.bufferedReader().readText()
        stepsConn.disconnect()
        assertTrue(stepsBody.contains("\"verified\":true"))
        assertEquals(10500, receivedSteps)

        // 2. Test /webhook/health POST JSON
        val healthUrl = URL("http://127.0.0.1:$port/webhook/health")
        val healthConn = (healthUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write("""{"steps": 12000, "distance": 3500.0, "speed": 3.2}""".toByteArray()) }
        }
        assertEquals(200, healthConn.responseCode)
        val healthBody = healthConn.inputStream.bufferedReader().readText()
        healthConn.disconnect()
        assertTrue(healthBody.contains("\"status\":\"success\""))
        assertEquals(12000, receivedSteps)
        assertEquals(3500.0, receivedDistance, 0.01)

        // 3. Test /webhook/status returns custom JSON
        val statusUrl = URL("http://127.0.0.1:$port/webhook/status")
        val statusConn = statusUrl.openConnection() as HttpURLConnection
        assertEquals(200, statusConn.responseCode)
        val statusBody = statusConn.inputStream.bufferedReader().readText()
        statusConn.disconnect()
        assertTrue(statusBody.contains("\"mockStatus\":\"verified\""))

        server.stop()
    }

    @Test
    fun testMorningClockSnoozeFormat() {
        val snapshot = com.personal.disciplinelock.domain.alarm.MorningMissionSnapshot(
            state = com.personal.disciplinelock.domain.alarm.MissionState.SNOOZED_COUNTDOWN,
            snoozeRemainingSeconds = 845L
        )
        // 845 seconds = 14 minutes, 5 seconds
        assertEquals("14:05", snapshot.formattedRemainingSnooze)

        val zeroSnapshot = com.personal.disciplinelock.domain.alarm.MorningMissionSnapshot(
            snoozeRemainingSeconds = 0L
        )
        assertEquals("00:00", zeroSnapshot.formattedRemainingSnooze)
    }
}
