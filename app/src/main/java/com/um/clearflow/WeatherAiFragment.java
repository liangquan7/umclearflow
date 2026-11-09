package com.um.clearflow;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

public class WeatherAiFragment extends Fragment {

    private WeatherAiViewModel viewModel;
    private TextView tvWeatherLive;
    private TextView tvRiskMatrix;
    private TextView tvChatHistory;
    private EditText etChatInput;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_weather_ai, container, false);
        viewModel = new ViewModelProvider(requireActivity()).get(WeatherAiViewModel.class);

        tvWeatherLive = view.findViewById(R.id.tv_weather_live);
        tvRiskMatrix = view.findViewById(R.id.tv_risk_matrix);
        tvChatHistory = view.findViewById(R.id.tv_chat_history);
        etChatInput = view.findViewById(R.id.et_chat_input);

        Button btnRefresh = view.findViewById(R.id.btn_refresh_weather);
        Button btnSimSquall = view.findViewById(R.id.btn_simulate_squall);
        if (!BuildConfig.DEBUG) {
            btnSimSquall.setVisibility(View.GONE);
        }
        Button btnPromptShelter = view.findViewById(R.id.btn_prompt_shelter);
        Button btnPromptRoute = view.findViewById(R.id.btn_prompt_route);
        Button btnPromptSos = view.findViewById(R.id.btn_prompt_sos);
        Button btnSendChat = view.findViewById(R.id.btn_send_chat);

        btnRefresh.setOnClickListener(v -> viewModel.fetchLiveWeatherAndForecast(false));
        btnSimSquall.setOnClickListener(v -> viewModel.fetchLiveWeatherAndForecast(true));

        btnPromptShelter.setOnClickListener(v -> {
            if (!viewModel.sendUserChatPrompt("Where are the nearest flood evacuation shelters?")) {
                Toast.makeText(getContext(), "Please wait for the current reply...", Toast.LENGTH_SHORT).show();
            }
        });
        btnPromptRoute.setOnClickListener(v -> {
            if (!viewModel.sendUserChatPrompt("Is the route from Pantai Gate to FCSIT safe right now?")) {
                Toast.makeText(getContext(), "Please wait for the current reply...", Toast.LENGTH_SHORT).show();
            }
        });
        btnPromptSos.setOnClickListener(v -> {
            if (!viewModel.sendUserChatPrompt("What are the emergency SOS contact numbers?")) {
                Toast.makeText(getContext(), "Please wait for the current reply...", Toast.LENGTH_SHORT).show();
            }
        });

        btnSendChat.setOnClickListener(v -> {
            String msg = etChatInput.getText().toString().trim();
            if (!msg.isEmpty()) {
                if (viewModel.sendUserChatPrompt(msg)) {
                    etChatInput.setText("");
                } else {
                    Toast.makeText(getContext(), "Please wait for the current reply...", Toast.LENGTH_SHORT).show();
                }
            }
        });

        viewModel.getWeatherSummary().observe(getViewLifecycleOwner(), text -> tvWeatherLive.setText(text));
        viewModel.getRiskMatrix().observe(getViewLifecycleOwner(), text -> tvRiskMatrix.setText(text));
        viewModel.getChatHistory().observe(getViewLifecycleOwner(), text -> tvChatHistory.setText(text));

        // 监听工单列表变化，实时重算区域风险评分矩阵（解决冷启动异步加载与新增/核销工单后风险分不刷新的问题）
        CampusRepository.getInstance().getReportsLiveData().observe(
                getViewLifecycleOwner(), reports -> viewModel.recomputeRiskFromLatestReports());

        // 首次进入拉取一次真实 Open-Meteo 6 小时预报
        viewModel.fetchLiveWeatherAndForecast(false);

        return view;
    }
}
