package com.um.clearflow;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class WeatherAiFragment extends Fragment {

    private WeatherAiViewModel viewModel;
    private TextView tvWeatherLive;
    private TextView tvRiskMatrix;
    private TextView tvChatHistory;
    private TextView tvBgAlertStatus;
    private CheckBox cbBgWeatherAlerts;
    private EditText etChatInput;
    private AlertPrefs alertPrefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_weather_ai, container, false);
        viewModel = new ViewModelProvider(requireActivity()).get(WeatherAiViewModel.class);
        alertPrefs = new AlertPrefs(requireContext());

        tvWeatherLive = view.findViewById(R.id.tv_weather_live);
        tvRiskMatrix = view.findViewById(R.id.tv_risk_matrix);
        tvChatHistory = view.findViewById(R.id.tv_chat_history);
        tvBgAlertStatus = view.findViewById(R.id.tv_bg_alert_status);
        cbBgWeatherAlerts = view.findViewById(R.id.cb_bg_weather_alerts);
        etChatInput = view.findViewById(R.id.et_chat_input);

        cbBgWeatherAlerts.setChecked(alertPrefs.isEnabled());
        refreshBgAlertStatusText();
        cbBgWeatherAlerts.setOnCheckedChangeListener((buttonView, isChecked) -> {
            alertPrefs.setEnabled(isChecked);
            WeatherAlertScheduler.apply(requireContext());
            if (isChecked) {
                WeatherAlertScheduler.runNow(requireContext(), false);
            }
            refreshBgAlertStatusText();
        });

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

        viewModel.getWeatherSummary().observe(getViewLifecycleOwner(), text -> {
            tvWeatherLive.setText(text);
            refreshBgAlertStatusText();
        });
        viewModel.getRiskMatrix().observe(getViewLifecycleOwner(), text -> tvRiskMatrix.setText(text));
        viewModel.getChatHistory().observe(getViewLifecycleOwner(), text -> tvChatHistory.setText(text));

        // 监听工单列表变化，实时重算区域风险评分矩阵（解决冷启动异步加载与新增/核销工单后风险分不刷新的问题）
        CampusRepository.getInstance().getReportsLiveData().observe(
                getViewLifecycleOwner(), reports -> viewModel.recomputeRiskFromLatestReports());

        // 首次进入拉取一次真实 Open-Meteo 6 小时预报
        viewModel.fetchLiveWeatherAndForecast(false);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshBgAlertStatusText();
    }

    private void refreshBgAlertStatusText() {
        if (tvBgAlertStatus == null || alertPrefs == null) return;
        if (!alertPrefs.isEnabled()) {
            tvBgAlertStatus.setText("Background weather checks paused by user.");
            return;
        }
        long lastMs = alertPrefs.lastCheckMs();
        String summary = alertPrefs.lastSummary();
        if (lastMs <= 0L) {
            tvBgAlertStatus.setText("Background check: scheduled (~15 min) • " + summary);
        } else {
            String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(lastMs));
            tvBgAlertStatus.setText("Last check " + timeStr + " • " + summary);
        }
    }
}
