package com.personal.disciplinelock.domain.webhook

import android.util.Log
import com.personal.disciplinelock.domain.task.MissionVerificationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed class VerificationResult {
    data class Success(val message: String, val statusCode: Int, val timestamp: Long = System.currentTimeMillis()) : VerificationResult()
    data class Failure(val errorMessage: String, val statusCode: Int? = null) : VerificationResult()
}

/**
 * Engine that verifies missions using outgoing HTTP GET / POST API requests.
 */
class MissionVerificationEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "MissionVerifier"
    }

    suspend fun verifyMission(
        missionId: Long,
        type: MissionVerificationType,
        endpointUrl: String,
        expectedResponse: String? = null
    ): VerificationResult = withContext(Dispatchers.IO) {
        if (endpointUrl.isBlank()) {
            return@withContext VerificationResult.Failure("API Endpoint URL cannot be empty.")
        }

        try {
            val requestBuilder = Request.Builder().url(endpointUrl)

            val request = requestBuilder.get().build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext VerificationResult.Failure("HTTP request failed with status $code", code)
                }

                // Check expected string/code if provided
                val expected = expectedResponse?.trim()
                if (!expected.isNullOrBlank()) {
                    if (expected.equals(code.toString(), ignoreCase = true) ||
                        responseBody.contains(expected, ignoreCase = true)) {
                        return@withContext VerificationResult.Success("Verified: Response matched '$expected'", code)
                    } else {
                        return@withContext VerificationResult.Failure("HTTP $code received, but expected '$expected' was not found in response", code)
                    }
                }

                // Default success on HTTP 200..299
                VerificationResult.Success("Verified successfully (HTTP $code)", code)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing API verification: ${e.message}", e)
            VerificationResult.Failure("Network error: ${e.localizedMessage ?: e.message}")
        }
    }
}
