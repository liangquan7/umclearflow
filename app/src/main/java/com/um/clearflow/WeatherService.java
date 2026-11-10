package com.um.clearflow;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Open-Meteo client used by BOTH the Weather tab and the background worker, so there is one parser and one set of
 * thresholds. The request uses fixed campus coordinates, never the device location.
 * Blocking: call from a background thread only.
 */
public final class WeatherService {
    private WeatherService() {}

    static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=3.1245&longitude=101.6525"
            + "&current=temperature_2m,precipitation,wind_speed_10m"
            + "&hourly=precipitation,precipitation_probability"
            + "&forecast_hours=6&timezone=Asia%2FKuala_Lumpur";

    public static WeatherSnapshot fetch() throws IOException, JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL(FORECAST_URL).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Open-Meteo HTTP " + code);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            return parse(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    /** Pure function, unit-tested on the JVM. */
    public static WeatherSnapshot parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject current = root.getJSONObject("current");
        double temp = current.optDouble("temperature_2m", 28.0);
        double rain = current.optDouble("precipitation", 0.0);
        double wind = current.optDouble("wind_speed_10m", 5.0);

        JSONObject hourly = root.getJSONObject("hourly");
        JSONArray rainArr = hourly.getJSONArray("precipitation");
        JSONArray probArr = hourly.getJSONArray("precipitation_probability");

        double next3h = 0.0;
        double next6h = 0.0;
        int maxProb = 0;
        for (int i = 0; i < rainArr.length(); i++) {
            double r = rainArr.optDouble(i, 0.0);
            int p = probArr.optInt(i, 0);
            if (i < 3) next3h += r;
            next6h += r;
            if (p > maxProb) maxProb = p;
        }
        return new WeatherSnapshot(temp, rain, wind, next3h, next6h, maxProb);
    }

    /** Short SOP line shown on the Weather tab. */
    public static String buildResilienceTip(double tempC, double rainMm, double next3hMm, double windKmh) {
        if (rainMm >= 5.0 || next3hMm >= 15.0) {
            return "Monsoon Squall SOP — Avoid low-lying Lingkungan Budi & Jalan Ilmu culverts; take KPS elevated covered walkway.";
        }
        if (windKmh >= 24.0) {
            return "Thunderstorm & Gust SOP — Watch for falling branches along KK12 & Pantai Gate; shelter inside concrete buildings.";
        }
        if (tempC >= 33.0) {
            return "Extreme Heat & Haze SOP — Hydrate at KPS water stations, minimize outdoor walking 12:00-15:00, wear N95 if API > 100.";
        }
        return "Clear/Moderate Weather — Report blocked storm grates early via Report tab to prevent flash ponding.";
    }
}
