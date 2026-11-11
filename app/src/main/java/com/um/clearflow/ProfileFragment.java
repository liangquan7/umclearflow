package com.um.clearflow;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ProfileFragment extends Fragment {

    private ProfileViewModel viewModel;
    private TextView tvAuthStatus;
    private EditText etEmail;
    private EditText etName;
    private EditText etMatric;
    private Spinner spinnerFaculty;
    private Spinner spinnerCollege;
    private EditText etEmergencyName;
    private EditText etEmergencyPhone;
    private Spinner spinnerMobility;

    private TextView tvRoleStatus;
    private Button btnToggleAdmin;
    private Button btnExportCsv;
    private LinearLayout layoutAdminBroadcast;
    private EditText etBroadcastMsg;
    private TextView tvHotspotRanking;
    private TextView tvPrivacyInfo;
    private MaterialSwitch swBgAlerts;
    private TextView tvBgAlertStatus;

    private final String[] faculties = {
            "Faculty of Computer Science & IT (FCSIT)",
            "Faculty of Engineering",
            "Faculty of Science",
            "Faculty of Arts & Social Sciences",
            "Faculty of Business & Economics"
    };

    private final String[] colleges = {
            "KK12 (Kolej Kediaman Raja Dr. Nazrin Shah)",
            "KK8 (Kolej Kediaman Kinabalu)",
            "KK1 (Kolej Kediaman Tuanku Abdul Rahman)",
            "KK2 (Kolej Kediaman Tuanku Bahiyah)",
            "KK3 (Kolej Kediaman Tuanku Kurshiah)",
            "KK4 (Kolej Kediaman Bestari)",
            "KK5 (Kolej Kediaman Dayasari)",
            "KK6 (Kolej Kediaman Ibnu Sina)",
            "KK7 (Kolej Kediaman Za'ba)",
            "KK9 (Kolej Kediaman Syed Putra)",
            "KK10 (Kolej Kediaman Tun Ahmad Zaidi)",
            "KK11 (Kolej Kediaman Ungku Aziz)",
            "KK13 (Kolej Kediaman Antarabangsa)",
            "Off-Campus / Pantai Hillpark / Bangsar South"
    };

    private final String[] mobilityOptions = {
            "Walking (Pedestrian / Covered Walkway)",
            "Wheelchair / Barrier-Free Ramp Only",
            "Cycling (Bicycle / Micro-mobility)",
            "UM Shuttle Bus (Kampus Bus AB / C)",
            "Driving (Car / Motorcycle)"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_profile, container, false);
        viewModel = new ViewModelProvider(requireActivity()).get(ProfileViewModel.class);

        tvAuthStatus = view.findViewById(R.id.tv_auth_status);
        etEmail = view.findViewById(R.id.et_siswa_email);
        etName = view.findViewById(R.id.et_student_name);
        etMatric = view.findViewById(R.id.et_matric_id);
        spinnerFaculty = view.findViewById(R.id.spinner_faculty);
        spinnerCollege = view.findViewById(R.id.spinner_college);
        etEmergencyName = view.findViewById(R.id.et_emergency_name);
        etEmergencyPhone = view.findViewById(R.id.et_emergency_phone);
        spinnerMobility = view.findViewById(R.id.spinner_mobility);

        tvRoleStatus = view.findViewById(R.id.tv_role_status);
        btnToggleAdmin = view.findViewById(R.id.btn_toggle_admin);
        layoutAdminBroadcast = view.findViewById(R.id.layout_admin_broadcast);
        etBroadcastMsg = view.findViewById(R.id.et_broadcast_msg);
        tvHotspotRanking = view.findViewById(R.id.tv_hotspot_ranking);

        setupSpinners();
        loadSavedProfileFields();
        setupActions(view);
        tvPrivacyInfo = view.findViewById(R.id.privacy_info_text);
        Button btnSyncNow = view.findViewById(R.id.btn_sync_now);
        btnSyncNow.setOnClickListener(v -> {
            viewModel.syncNow();
            Toast.makeText(getContext(), "Syncing with the ClearFlow server…", Toast.LENGTH_SHORT).show();
        });
        viewModel.getSyncStatus().observe(getViewLifecycleOwner(), s -> tvPrivacyInfo.setText(
                s + "\nReports are saved on this device first and then synced to the ClearFlow server. "
                        + "JPPHB dispatch is not connected."));
        setupBackgroundAlerts(view);
        observeViewModel();

        return view;
    }

    private void setupSpinners() {
        spinnerFaculty.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, faculties));
        spinnerCollege.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, colleges));
        spinnerMobility.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, mobilityOptions));
    }

    private void loadSavedProfileFields() {
        CampusRepository repo = CampusRepository.getInstance();
        // 默认使用脱敏演示学生信息，不硬编码个人真实隐私数据
        etEmail.setText(repo.getProfileField("siswa_email", "demo.student@siswa.um.edu.my"));
        etName.setText(repo.getProfileField("student_name", "Ahmad Danial (Demo Student)"));
        etMatric.setText(repo.getProfileField("matric_id", "S2104921"));
        etEmergencyName.setText(repo.getProfileField("emergency_name", "Campus Security / Guardian"));
        etEmergencyPhone.setText(repo.getProfileField("emergency_phone", "0379677070"));

        setSpinnerToValue(spinnerFaculty, faculties, repo.getProfileField("faculty", faculties[0]));
        setSpinnerToValue(spinnerCollege, colleges, repo.getProfileField("college", colleges[0]));
        setSpinnerToValue(spinnerMobility, mobilityOptions, repo.getProfileField("mobility_pref", mobilityOptions[0]));
    }

    private void setSpinnerToValue(Spinner spinner, String[] items, String target) {
        for (int i = 0; i < items.length; i++) {
            if (items[i].equalsIgnoreCase(target)) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private void setupActions(View view) {
        Button btnSaveProfile = view.findViewById(R.id.btn_save_profile);
        Button btnPublishBroadcast = view.findViewById(R.id.btn_publish_broadcast);
        Button btnClearBroadcast = view.findViewById(R.id.btn_clear_broadcast);
        btnExportCsv = view.findViewById(R.id.btn_export_csv);

        // FR 1.1 & 1.2: 校验 @siswa.um.edu.my 域名并持久化个人档案
        btnSaveProfile.setOnClickListener(v -> {
            String email = etEmail.getText().toString().trim().toLowerCase(Locale.ROOT);
            String name = etName.getText().toString().trim();
            String matric = etMatric.getText().toString().trim();
            String emName = etEmergencyName.getText().toString().trim();
            String emPhone = etEmergencyPhone.getText().toString().trim();

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()
                    || !email.endsWith("@siswa.um.edu.my")) {
                tvAuthStatus.setText("❌ Domain Error: Must use an official @siswa.um.edu.my email!");
                Toast.makeText(getContext(), "Invalid domain! Please use @siswa.um.edu.my", Toast.LENGTH_LONG).show();
                return;
            }
            if (name.isEmpty() || matric.isEmpty()) {
                Toast.makeText(getContext(), "Please fill in your Name and Matric ID.", Toast.LENGTH_SHORT).show();
                return;
            }

            CampusRepository.getInstance().saveUserProfile(
                    email, name, matric,
                    spinnerFaculty.getSelectedItem().toString(),
                    spinnerCollege.getSelectedItem().toString(),
                    emName, emPhone,
                    spinnerMobility.getSelectedItem().toString()
            );

            tvAuthStatus.setText("✅ Profile saved; email domain checked locally (not independently verified): "
                    + email + " (" + matric + ")");
            Toast.makeText(getContext(), "Profile, Emergency Contact & Mobility Preferences saved!", Toast.LENGTH_SHORT).show();
        });

        // FR 1.3: 管理员角色切换必须验证 PIN 码 (默认 UM2026)
        btnToggleAdmin.setOnClickListener(v -> {
            if (CampusRepository.getInstance().isAdmin()) {
                viewModel.switchToStudent();
                Toast.makeText(getContext(), "Switched back to Student / Staff role.", Toast.LENGTH_SHORT).show();
            } else {
                showAdminPinDialog();
            }
        });

        btnPublishBroadcast.setOnClickListener(v -> {
            String msg = etBroadcastMsg.getText().toString().trim();
            if (msg.isEmpty()) {
                Toast.makeText(getContext(), "Please enter a broadcast message.", Toast.LENGTH_SHORT).show();
                return;
            }
            viewModel.sendBroadcast(msg);
            Toast.makeText(getContext(), "Local demo notice saved and shown on this device only.", Toast.LENGTH_SHORT).show();
        });

        btnClearBroadcast.setOnClickListener(v -> {
            etBroadcastMsg.setText("");
            viewModel.sendBroadcast("");
            Toast.makeText(getContext(), "Broadcast banner cleared.", Toast.LENGTH_SHORT).show();
        });

        // FR 5.2: 导出并分享真实 CSV 报告
        btnExportCsv.setOnClickListener(v -> {
            if (!CampusRepository.getInstance().isAdmin()) {
                Toast.makeText(getContext(), "Admin role required.", Toast.LENGTH_SHORT).show();
                return;
            }
            String csvData = viewModel.exportCsvContent();
            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("text/csv");
            sendIntent.putExtra(Intent.EXTRA_SUBJECT, "UM-ClearFlow Hazard Report Export (CSV)");
            sendIntent.putExtra(Intent.EXTRA_TEXT, csvData);
            try {
                java.io.File exportDir = new java.io.File(requireContext().getCacheDir(), "exports");
                if (!exportDir.exists()) exportDir.mkdirs();
                java.io.File csvFile = new java.io.File(exportDir, "um_clearflow_reports.csv");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(csvFile)) {
                    fos.write(csvData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        requireContext(), requireContext().getPackageName() + ".fileprovider", csvFile);
                sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
                sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            startActivity(Intent.createChooser(sendIntent, "Export CSV Report via..."));
        });
    }

    private void showAdminPinDialog() {
        EditText inputPin = new EditText(requireContext());
        inputPin.setHint("Enter JPPHB staff authorization PIN");
        inputPin.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        new AlertDialog.Builder(requireContext())
                .setTitle("🔐 JPPHB Admin Verification")
                .setMessage("Enter staff authorization code to unlock ticket status updates and campus broadcast:")
                .setView(inputPin)
                .setPositiveButton("Verify", (dialog, which) -> {
                    String pin = inputPin.getText().toString();
                    if (viewModel.verifyAdminPin(pin)) {
                        Toast.makeText(getContext(), "Demo admin view unlocked on this device only.", Toast.LENGTH_SHORT).show();
                    } else if (CampusRepository.getInstance().getPinLockoutRemainingMs() > 0) {
                        long sec = (CampusRepository.getInstance().getPinLockoutRemainingMs() + 999) / 1000;
                        Toast.makeText(getContext(), "Too many wrong PINs. Try again in " + sec + "s.", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(getContext(), "❌ Invalid Admin PIN! Access Denied.", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void observeViewModel() {
        viewModel.getUserRole().observe(getViewLifecycleOwner(), role -> {
            boolean isAdmin = "JPPHB Maintenance Admin".equals(role);
            tvRoleStatus.setText("Current Role: " + role);
            btnToggleAdmin.setText(isAdmin
                    ? "🔓 Logout Admin (Return to Student View)"
                    : "🔐 Authenticate JPPHB Admin");
            layoutAdminBroadcast.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
            if (btnExportCsv != null) {
                btnExportCsv.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
            }
        });

        viewModel.getReports().observe(getViewLifecycleOwner(), reports -> {
            tvHotspotRanking.setText(viewModel.getHotspotStats());
        });
    }

    private void setupBackgroundAlerts(View view) {
        swBgAlerts = view.findViewById(R.id.sw_bg_alerts);
        tvBgAlertStatus = view.findViewById(R.id.tv_bg_alert_status);
        Button btnRunNow = view.findViewById(R.id.btn_bg_check_now);
        Button btnSimulate = view.findViewById(R.id.btn_bg_simulate);
        final Context app = requireContext().getApplicationContext();
        final AlertPrefs prefs = new AlertPrefs(app);

        swBgAlerts.setChecked(prefs.isEnabled());
        swBgAlerts.setOnCheckedChangeListener((b, on) -> {
            prefs.setEnabled(on);
            WeatherAlertScheduler.apply(app);
            refreshBackgroundAlertStatus();
        });
        btnRunNow.setOnClickListener(v -> {
            WeatherAlertScheduler.runNow(app, false);
            Toast.makeText(getContext(), "Background check queued; the result appears below in a few seconds.",
                    Toast.LENGTH_SHORT).show();
            tvBgAlertStatus.postDelayed(this::refreshBackgroundAlertStatus, 4000);
        });
        if (BuildConfig.DEBUG) {
            btnSimulate.setOnClickListener(v -> {
                WeatherAlertScheduler.runNow(app, true);
                Toast.makeText(getContext(), "Simulated squall queued. Press Home: the notification comes from the background worker.",
                        Toast.LENGTH_LONG).show();
                tvBgAlertStatus.postDelayed(this::refreshBackgroundAlertStatus, 4000);
            });
        } else {
            btnSimulate.setVisibility(View.GONE);
        }
        refreshBackgroundAlertStatus();
    }

    private void refreshBackgroundAlertStatus() {
        if (tvBgAlertStatus == null || !isAdded()) return;
        AlertPrefs prefs = new AlertPrefs(requireContext());
        StringBuilder sb = new StringBuilder();
        if (!androidx.core.app.NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
            sb.append("⚠️ Notifications are blocked for this app, so alerts cannot be shown. Enable them in system settings.\n");
        }
        if (!prefs.isEnabled()) {
            sb.append("Background checks are off.");
        } else if (prefs.lastCheckMs() == 0L) {
            sb.append("No background check has run yet (the first one runs within about 15 minutes).");
        } else {
            sb.append("Last check ")
                    .append(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(prefs.lastCheckMs())))
                    .append(" — ").append(prefs.lastSummary());
        }
        tvBgAlertStatus.setText(sb.toString());
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshBackgroundAlertStatus();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {   // tabs are shown/hidden, not recreated
        super.onHiddenChanged(hidden);
        if (!hidden) refreshBackgroundAlertStatus();
    }
}
