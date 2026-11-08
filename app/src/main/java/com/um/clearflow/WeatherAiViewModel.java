package com.um.clearflow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class WeatherAiViewModel extends ViewModel {

    private final CampusRepository repository = CampusRepository.getInstance();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private long lastWeatherAlertAt = 0L;
    private static final long ALERT_COOLDOWN_MS = 30 * 60 * 1000L;

    private boolean shouldSendWeatherAlert() {
        long now = System.currentTimeMillis();
        if (now - lastWeatherAlertAt < ALERT_COOLDOWN_MS) return false;
        lastWeatherAlertAt = now;
        return true;
    }

    private final MutableLiveData<String> weatherSummaryLiveData = new MutableLiveData<>(
            "Fetching live Open-Meteo 6-hour forecast for Universiti Malaya (3.1245, 101.6525)...");
    private final MutableLiveData<String> riskMatrixLiveData = new MutableLiveData<>(
            "Calculating Forecast × Historical Flood Weight fusion score...");
    private final MutableLiveData<String> chatHistoryLiveData = new MutableLiveData<>(
            "🤖 UM-ClearFlow Assistant: Hello! Ask me about flood-safe routes, nearest shelters, active hazards, or monsoon SOPs.\n" +
                    "⚠️ Disclaimer: In life-threatening situations, always call 999 or UM Auxiliary Police (03-7967 7070) first.");

    public LiveData<String> getWeatherSummary() {
        return weatherSummaryLiveData;
    }

    public LiveData<String> getRiskMatrix() {
        return riskMatrixLiveData;
    }

    public LiveData<String> getChatHistory() {
        return chatHistoryLiveData;
    }

    // FR 3.2: 请求 Open-Meteo 当前气象 + 未来 6 小时逐时降雨与概率
    public void fetchLiveWeatherAndForecast(boolean simulateSquallForDemo) {
        executor.execute(() -> {
            if (simulateSquallForDemo) {
                double simCurrent = 14.5;
                double simNext3h = 38.0;
                repository.updateWeatherMetrics(simCurrent, simNext3h);

                weatherSummaryLiveData.postValue(String.format(Locale.US,
                        "🌧️ [SIMULATED MONSOON SQUALL - DEMO MODE]\n" +
                                "• Current Rainfall: %.1f mm/h | Temp: 25.4°C | Wind: 32.0 km/h\n" +
                                "• Next 3-Hour Cumulative Forecast: %.1f mm (95%% Probability)\n" +
                                "• Threshold Note: Demo Alert Threshold >= 15.0 mm/3h exceeded!",
                        simCurrent, simNext3h));

                computeZoneRiskFusion(simCurrent, simNext3h, 95);
                repository.sendLocalAlertNotification(
                        "[DEMO] Extreme Weather Warning (FR 5.3)",
                        "Simulated: forecast 3h rain 38.0mm. This is a demo alert, not a real warning."
                );
                return;
            }

            try {
                String urlStr = "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=3.1245&longitude=101.6525"
                        + "&current=temperature_2m,precipitation,wind_speed_10m"
                        + "&hourly=precipitation,precipitation_probability"
                        + "&forecast_hours=6&timezone=Asia%2FKuala_Lumpur";

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject root = new JSONObject(sb.toString());
                JSONObject current = root.getJSONObject("current");
                double temp = current.optDouble("temperature_2m", 28.0);
                double currentRain = current.optDouble("precipitation", 0.0);
                double wind = current.optDouble("wind_speed_10m", 5.0);

                JSONObject hourly = root.getJSONObject("hourly");
                JSONArray rainArr = hourly.getJSONArray("precipitation");
                JSONArray probArr = hourly.getJSONArray("precipitation_probability");

                double next3hRain = 0.0;
                double next6hRain = 0.0;
                int maxProb = 0;

                for (int i = 0; i < rainArr.length(); i++) {
                    double r = rainArr.optDouble(i, 0.0);
                    int p = probArr.optInt(i, 0);
                    if (i < 3) next3hRain += r;
                    next6hRain += r;
                    if (p > maxProb) maxProb = p;
                }

                repository.updateWeatherMetrics(currentRain, next3hRain);

                String summary = String.format(Locale.US,
                        "⛅ Live Open-Meteo Telemetry (UM Campus 3.1245°N, 101.6525°E)\n" +
                                "• Current: %.1f°C | Rain: %.1f mm | Wind: %.1f km/h\n" +
                                "• Next 3h Forecast Sum: %.1f mm | Next 6h Sum: %.1f mm\n" +
                                "• Max Rain Probability (6h): %d%% (Alert Threshold: 15.0 mm/3h)",
                        temp, currentRain, wind, next3hRain, next6hRain, maxProb);
                weatherSummaryLiveData.postValue(summary);

                computeZoneRiskFusion(currentRain, next3hRain, maxProb);

                if ((currentRain >= 5.0 || next3hRain >= 15.0) && shouldSendWeatherAlert()) {
                    repository.sendLocalAlertNotification(
                            "🌧️ UM Flood Forecast Alert (FR 3.2/5.3)",
                            String.format(Locale.US, "Next 3h forecast: %.1f mm (%d%% prob). Check safe detour routes.", next3hRain, maxProb)
                    );
                }
            } catch (Exception e) {
                double fallbackRain = repository.getLatestRainfallMm();
                weatherSummaryLiveData.postValue(
                        "⚠️ Offline / Network Timeout reaching Open-Meteo.\n" +
                                "Using cached rainfall (" + fallbackRain + " mm) & SQLite hazard weights for risk estimation.");
                computeZoneRiskFusion(fallbackRain, fallbackRain * 2.0, 50);
            }
        });
    }

    // FR 3.2: 降雨预报 × 区域历史低洼权重 × 当前活跃上报数 融合评分
    private void computeZoneRiskFusion(double currentRain, double next3hRain, int maxProb) {
        List<HazardReport> activeReports = repository.getReportsLiveData().getValue();
        int pantaiActive = 0, fcsitActive = 0, kk12Active = 0;

        if (activeReports != null) {
            for (HazardReport r : activeReports) {
                if ("Resolved".equalsIgnoreCase(r.status)) continue;
                String loc = r.locationName != null ? r.locationName : "";
                int weight = "Critical".equalsIgnoreCase(r.severity) ? 2 : 1;
                if (loc.contains("Pantai") || loc.contains("Budi")) pantaiActive += weight;
                else if (loc.contains("FCSIT") || loc.contains("Library")) fcsitActive += weight;
                else if (loc.contains("KK12")) kk12Active += weight;
            }
        }

        // 公式：RiskScore = min(100, (CurrentRain*2.2 + Next3hRain*1.4) * ZoneTopoWeight + ActiveHazardPenalty * 14)
        double baseRainFactor = currentRain * 2.2 + next3hRain * 1.4 + (maxProb * 0.12);
        int pantaiScore = (int) Math.min(100, Math.round(baseRainFactor * 1.45 + pantaiActive * 15));
        int fcsitScore = (int) Math.min(100, Math.round(baseRainFactor * 1.15 + fcsitActive * 14));
        int kk12Score = (int) Math.min(100, Math.round(baseRainFactor * 1.05 + kk12Active * 12));

        String matrixText = String.format(Locale.US,
                "📊 Topographic × Forecast Risk Fusion (Formula: RainFactor × TopoWeight + ActiveReports):\n" +
                        "1. Lingkungan Budi / Pantai Gate (Weight 1.45x): %d/100 [%s]\n" +
                        "2. FCSIT & Central Library Culvert (Weight 1.15x): %d/100 [%s]\n" +
                        "3. KK12 Residential Corridor (Weight 1.05x): %d/100 [%s]",
                pantaiScore, levelFromScore(pantaiScore),
                fcsitScore, levelFromScore(fcsitScore),
                kk12Score, levelFromScore(kk12Score));

        riskMatrixLiveData.postValue(matrixText);
    }

    private String levelFromScore(int score) {
        if (score >= 70) return "CRITICAL RISK 🔴";
        if (score >= 40) return "MODERATE RISK 🟠";
        return "LOW RISK 🟢";
    }

    // FR 3.3: 真实用户提问输入 -> 优先请求后端 FastAPI LLM (/api/v1/chat)，离线/无后端时自动使用本地 RAG 状态引擎
    public void sendUserChatPrompt(String userPrompt) {
        if (userPrompt == null || userPrompt.trim().isEmpty()) return;
        final String cleanPrompt = userPrompt.trim();

        String currentHistory = chatHistoryLiveData.getValue();
        final String updatedWithUser = (currentHistory != null ? currentHistory + "\n\n" : "")
                + "🧑 You: " + cleanPrompt;
        chatHistoryLiveData.setValue(updatedWithUser + "\n🤖 Assistant: Thinking...");

        executor.execute(() -> {
            String reply = tryBackendChatOrContextualFallback(cleanPrompt);
            chatHistoryLiveData.postValue(updatedWithUser + "\n🤖 Assistant: " + reply);
        });
    }

    private String tryBackendChatOrContextualFallback(String prompt) {
        // 1. 尝试调用本地 FastAPI 后端 (http://10.0.2.2:8000/api/v1/chat)，绝不在 APK 内硬编码任何第三方 API Key
        try {
            URL url = new URL(CampusRepository.API_BASE_URL + "api/v1/chat");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3500);
            conn.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("prompt", prompt);
            body.put("rainfall_mm", repository.getLatestRainfallMm());
            body.put("forecast_3h_mm", repository.getForecast3hRainMm());

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder resp = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) resp.append(line);
                br.close();
                JSONObject json = new JSONObject(resp.toString());
                if (json.has("reply")) {
                    return json.getString("reply")
                            + "\n⚠️ [Emergency Priority: Dial 999 or UM Auxiliary Police 03-7967 7070 if trapped]";
                }
            }
        } catch (Exception ignored) {
            // 本地 FastAPI 未启动时，无缝使用端侧实时 SQLite 上下文引擎回答
        }

        // 2. 端侧实时数据感知问答引擎（读取当前 SQLite 隐患、降雨预报、避难所与用户出行偏好）
        String lower = prompt.toLowerCase(Locale.ROOT);
        List<HazardReport> reports = repository.getReportsLiveData().getValue();
        int activeCount = 0;
        StringBuilder activeLocations = new StringBuilder();
        if (reports != null) {
            for (HazardReport r : reports) {
                if (!"Resolved".equalsIgnoreCase(r.status)) {
                    activeCount++;
                    activeLocations.append("• [").append(r.severity).append("] ")
                            .append(r.title).append(" at ").append(r.locationName).append("\n");
                }
            }
        }

        String mobility = repository.getProfileField("mobility_pref", "Standard Walking");
        double rain = repository.getLatestRainfallMm();
        double forecast3h = repository.getForecast3hRainMm();

        StringBuilder answer = new StringBuilder();
        if (lower.contains("shelter") || lower.contains("evacuat") || lower.contains("where to go") || lower.contains("避难")) {
            answer.append("Based on your mobility preference (").append(mobility).append("), here are the official UM Evacuation Shelters:\n");
            for (CampusRepository.ShelterInfo s : repository.getCampusShelters()) {
                answer.append("🏛️ ").append(s.name).append(" — ").append(s.description).append("\n");
            }
            answer.append("Tip: Enable 'Safe Route' on the Map tab to compute an elevated detour avoiding flooded zones.");
        } else if (lower.contains("route") || lower.contains("safe") || lower.contains("fcsit") || lower.contains("pantai") || lower.contains("kk12")) {
            answer.append("Live Route & Hazard Assessment (Current rain: ").append(rain)
                    .append(" mm, 3h forecast: ").append(forecast3h).append(" mm):\n");
            if (activeCount == 0) {
                answer.append("All campus corridors are currently clear.");
            } else {
                answer.append("There are ").append(activeCount).append(" active hazards right now:\n")
                        .append(activeLocations)
                        .append("Recommendation: Avoid Lingkungan Budi valley road; take the Perdanasiswa (KPS) elevated walkway to DTC Shelter.");
            }
        } else if (lower.contains("sos") || lower.contains("help") || lower.contains("trapped") || lower.contains("phone") || lower.contains("contact")) {
            answer.append("🚨 Immediate Emergency Contacts:\n")
                    .append("• Malaysia Emergency Services: 999\n")
                    .append("• UM Auxiliary Police (24/7 Control Room): 03-7967 7070\n")
                    .append("• Saved Personal Emergency Contact: ")
                    .append(repository.getProfileField("emergency_name", "None"))
                    .append(" (").append(repository.getProfileField("emergency_phone", "Not set")).append(")\n")
                    .append("Use the red 'SOS 999 / GPS' button on the Map tab to share your live Google Maps coordinates.");
        } else {
            answer.append("Live Campus Status Summary:\n")
                    .append("• Active Unresolved Hazards in SQLite: ").append(activeCount).append("\n")
                    .append("• Current Rainfall: ").append(rain).append(" mm | Next 3h Forecast: ").append(forecast3h).append(" mm\n")
                    .append("• Mobility Mode: ").append(mobility).append("\n")
                    .append("You can ask me about 'nearest shelters', 'safe route from Pantai Gate', or 'emergency numbers'.");
        }

        answer.append("\n⚠️ Disclaimer: In any life-threatening emergency, prioritize dialing 999 or UM Auxiliary Police (03-7967 7070).");
        return answer.toString();
    }
}