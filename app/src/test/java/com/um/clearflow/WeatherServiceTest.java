package com.um.clearflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

/** Needs testImplementation("org.json:json:20240303") (already added for SyncClientTest). */
public class WeatherServiceTest {

    @Test
    public void parsesCurrentConditionsAndSumsTheForecastWindows() throws Exception {
        String json = "{\"current\":{\"temperature_2m\":27.3,\"precipitation\":2.1,\"wind_speed_10m\":12.0},"
                + "\"hourly\":{\"precipitation\":[1.0,2.0,3.0,4.0,5.0,6.0],"
                + "\"precipitation_probability\":[10,20,90,40,30,20]}}";
        WeatherSnapshot s = WeatherService.parse(json);
        assertEquals(27.3, s.tempC, 1e-9);
        assertEquals(2.1, s.currentRainMm, 1e-9);
        assertEquals(12.0, s.windKmh, 1e-9);
        assertEquals("first 3 hours", 6.0, s.next3hRainMm, 1e-9);
        assertEquals("all 6 hours", 21.0, s.next6hRainMm, 1e-9);
        assertEquals(90, s.maxProbPct);
    }

    @Test
    public void missingValuesFallBackToSafeDefaults() throws Exception {
        WeatherSnapshot s = WeatherService.parse(
                "{\"current\":{},\"hourly\":{\"precipitation\":[],\"precipitation_probability\":[]}}");
        assertEquals(0.0, s.currentRainMm, 1e-9);
        assertEquals(0.0, s.next3hRainMm, 1e-9);
        assertEquals(0, s.maxProbPct);
        assertTrue("defaults must not trigger alerts", AlertEngine.evaluate(s).isEmpty());
    }

    @Test(expected = JSONException.class)
    public void malformedResponseIsReportedNotSwallowed() throws Exception {
        WeatherService.parse("{\"unexpected\": true}");
    }

    @Test
    public void simulatedSquallExceedsTheSevereRainThreshold() {
        WeatherSnapshot s = WeatherSnapshot.simulatedSquall();
        assertEquals(AlertEngine.LEVEL_SEVERE, AlertEngine.evaluate(s).get(0).level);
    }
}
