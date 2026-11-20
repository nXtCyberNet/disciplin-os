package com.mendhak.gpslogger.discipline;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class DisciplineWebhookClientTest {

    @Test
    public void testLiveRunStreamJsonPayload() throws Exception {
        double distanceMeters = 1500.0;
        float speedMps = 3.1f;
        float paceMinPerKm = 5.4f;
        boolean isFinished = false;

        JSONObject json = new JSONObject();
        json.put("distanceMeters", distanceMeters);
        json.put("speedMps", (double) speedMps);
        json.put("paceMinPerKm", (double) paceMinPerKm);
        json.put("targetDistanceMeters", 2000.0);
        json.put("targetPaceMinPerKm", 6.0);
        json.put("isFinished", isFinished);

        assertEquals(1500.0, json.getDouble("distanceMeters"), 0.001);
        assertEquals(3.1, json.getDouble("speedMps"), 0.001);
        assertEquals(5.4, json.getDouble("paceMinPerKm"), 0.001);
        assertEquals(2000.0, json.getDouble("targetDistanceMeters"), 0.001);
        assertEquals(6.0, json.getDouble("targetPaceMinPerKm"), 0.001);
        assertFalse(json.getBoolean("isFinished"));
    }

    @Test
    public void testRunFinishedJsonPayload() throws Exception {
        double distanceMeters = 2050.0;
        float speedMps = 0.0f;
        boolean isFinished = true;

        JSONObject json = new JSONObject();
        json.put("distanceMeters", distanceMeters);
        json.put("speedMps", (double) speedMps);
        json.put("targetDistanceMeters", 2000.0);
        json.put("targetPaceMinPerKm", 6.0);
        json.put("isFinished", isFinished);

        assertEquals(2050.0, json.getDouble("distanceMeters"), 0.001);
        assertEquals(0.0, json.getDouble("speedMps"), 0.001);
        assertEquals(2000.0, json.getDouble("targetDistanceMeters"), 0.001);
        assertEquals(6.0, json.getDouble("targetPaceMinPerKm"), 0.001);
        assertTrue(json.getBoolean("isFinished"));
    }

    @Test
    public void testMissionEndpoints() {
        String baseUrl = "http://127.0.0.1:8080";
        String missionId = "1";
        String missionSlug = "morning-mobility";

        String endpoint1 = baseUrl + "/webhook/mission/" + missionId;
        String endpoint2 = baseUrl + "/webhook/mission/" + missionSlug;
        String healthEndpoint = baseUrl + "/webhook/status";

        assertEquals("http://127.0.0.1:8080/webhook/mission/1", endpoint1);
        assertEquals("http://127.0.0.1:8080/webhook/mission/morning-mobility", endpoint2);
        assertEquals("http://127.0.0.1:8080/webhook/status", healthEndpoint);
    }
}
