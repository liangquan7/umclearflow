package com.um.clearflow;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WeatherAiViewModel extends ViewModel {

    public static class WeatherUiState {
        public final boolean isLoading;
        public final String weatherText;
        public final String errorText;

        public WeatherUiState(boolean isLoading, String weatherText, String errorText) {
            this.isLoading = isLoading;
            this.weatherText = weatherText;
            this.errorText = errorText;
        }
    }

    private final MutableLiveData<WeatherUiState> weatherStateLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> aiResponseLiveData = new MutableLiveData<>();
    private final OkHttpClient httpClient = new OkHttpClient();

    public WeatherAiViewModel() {
        fetchOpenMeteoWeather();
    }

    public LiveData<WeatherUiState> getWeatherState() {
        return weatherStateLiveData;
    }

    public LiveData<String> getAiResponse() {
        return aiResponseLiveData;
    }

    public void fetchOpenMeteoWeather() {
        weatherStateLiveData.setValue(new WeatherUiState(true, "Connecting to Open-Meteo API for UM Campus (3.1209, 101.6538)...", null));

        String url = "https://api.open-meteo.com/v1/forecast?latitude=3.1209&longitude=101.6538&current=temperature_2m,rain,weather_code,wind_speed_10m";
        Request request = new Request.Builder().url(url).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                weatherStateLiveData.postValue(new WeatherUiState(
                        false,
                        null,
                        "⚠️ Weather API unavailable (No active network connection).\nCheck network status or try again."
                ));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String jsonStr = response.body().string();
                        JSONObject obj = new JSONObject(jsonStr);
                        JSONObject current = obj.getJSONObject("current");
                        double temp = current.getDouble("temperature_2m");
                        double rain = current.getDouble("rain");
                        double wind = current.getDouble("wind_speed_10m");

                        String risk = rain > 5.0 ? "HIGH (Flash Flood Warning)" : (rain > 1.0 ? "MODERATE (Watch Drains)" : "LOW (Normal)");

                        String statusText = "✅ Open-Meteo Live Data (UM Campus):\n" +
                                "• Temperature: " + temp + "°C\n" +
                                "• Rainfall: " + rain + " mm\n" +
                                "• Wind Speed: " + wind + " km/h\n" +
                                "• Flood Risk Assessment: " + risk;

                        weatherStateLiveData.postValue(new WeatherUiState(false, statusText, null));
                    } catch (Exception e) {
                        weatherStateLiveData.postValue(new WeatherUiState(false, null, "⚠️ Error parsing weather data."));
                    }
                } else {
                    weatherStateLiveData.postValue(new WeatherUiState(false, null, "⚠️ Weather API returned error code: " + response.code()));
                }
            }
        });
    }

    public void selectPrompt(int promptId) {
        if (promptId == 1) {
            aiResponseLiveData.setValue("🤖 UM Emergency Assistant:\n" +
                    "Q: What should I do during a sudden flash flood near FCSIT?\n\n" +
                    "A: 1. Move immediately to higher ground (e.g., FCSIT Upper Floors or DTC).\n" +
                    "2. Avoid walking or driving through moving water.\n" +
                    "3. Call UM Security at 03-7967 7070 if trapped.");
        } else if (promptId == 2) {
            aiResponseLiveData.setValue("🤖 UM Emergency Assistant:\n" +
                    "Q: How to report a fallen tree blocking Jalan Ilmu?\n\n" +
                    "A: Use the 'Report & AI' tab in this app to submit details and location.\n" +
                    "It will tag the severity and alert JPPHB and Pejabat Keselamatan.");
        }
    }
}