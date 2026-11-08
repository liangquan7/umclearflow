package com.um.clearflow;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

public class WeatherAiFragment extends Fragment {

    private TextView tvWeatherStatus, tvAiChatResponse;
    private WeatherAiViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_weather_ai, container, false);

        tvWeatherStatus = view.findViewById(R.id.tvWeatherStatus);
        tvAiChatResponse = view.findViewById(R.id.tvAiChatResponse);

        viewModel = new ViewModelProvider(this).get(WeatherAiViewModel.class);

        // Observers
        viewModel.getWeatherState().observe(getViewLifecycleOwner(), state -> {
            if (state != null) {
                if (state.isLoading) {
                    tvWeatherStatus.setText(state.weatherText);
                } else if (state.errorText != null) {
                    tvWeatherStatus.setText(state.errorText);
                } else if (state.weatherText != null) {
                    tvWeatherStatus.setText(state.weatherText);
                }
            }
        });

        viewModel.getAiResponse().observe(getViewLifecycleOwner(), response -> {
            if (response != null) {
                tvAiChatResponse.setText(response);
            }
        });

        view.findViewById(R.id.btnRefreshWeather).setOnClickListener(v -> viewModel.fetchOpenMeteoWeather());
        view.findViewById(R.id.btnPrompt1).setOnClickListener(v -> viewModel.selectPrompt(1));
        view.findViewById(R.id.btnPrompt2).setOnClickListener(v -> viewModel.selectPrompt(2));

        return view;
    }
}