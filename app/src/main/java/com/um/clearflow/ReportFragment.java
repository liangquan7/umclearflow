package com.um.clearflow;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReportFragment extends Fragment {

    private static final Pattern COORD_PATTERN = Pattern.compile("\\(([-\\d.]+),\\s*([-\\d.]+)\\)");

    private ReportViewModel viewModel;
    private Spinner spinnerLocation;
    private Spinner spinnerHazardType;
    private EditText etTitle;
    private EditText etDesc;
    private TextView tvGpsStatus;
    private TextView tvTriageResult;
    private ImageView ivPhotoPreview;
    private LinearLayout reportsListContainer;

    private Bitmap capturedBitmap = null;
    private Double realGpsLat = null;
    private Double realGpsLon = null;
    private CampusRepository.TriageResult latestTriage = null;

    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Bundle extras = result.getData().getExtras();
                    if (extras != null && extras.get("data") instanceof Bitmap) {
                        capturedBitmap = (Bitmap) extras.get("data");
                        ivPhotoPreview.setImageBitmap(capturedBitmap);
                        ivPhotoPreview.setVisibility(View.VISIBLE);
                        // 拍完照片自动触发一次真实 Bitmap HSV 视觉分诊
                        viewModel.performVisionTriage(
                                capturedBitmap,
                                spinnerHazardType.getSelectedItemPosition(),
                                etTitle.getText().toString()
                        );
                    }
                }
            });

    private final ActivityResultLauncher<String> gpsPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    fetchDeviceGpsCoordinates();
                } else {
                    Toast.makeText(getContext(), "GPS permission denied. Using selected campus landmark coordinates.", Toast.LENGTH_SHORT).show();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_report, container, false);
        viewModel = new ViewModelProvider(requireActivity()).get(ReportViewModel.class);

        spinnerLocation = view.findViewById(R.id.spinner_location);
        spinnerHazardType = view.findViewById(R.id.spinner_hazard_type);
        etTitle = view.findViewById(R.id.et_report_title);
        etDesc = view.findViewById(R.id.et_report_desc);
        tvGpsStatus = view.findViewById(R.id.tv_gps_status);
        tvTriageResult = view.findViewById(R.id.tv_triage_result);
        ivPhotoPreview = view.findViewById(R.id.iv_photo_preview);
        reportsListContainer = view.findViewById(R.id.reports_list_container);

        setupSpinners();
        setupButtons(view);
        observeData();

        return view;
    }

    private void setupSpinners() {
        String[] locations = {
                "FCSIT & Central Library (3.1281, 101.6505)",
                "Lingkungan Budi (Pantai Gate) (3.1195, 101.6620)",
                "KK12 Residential College (3.1338, 101.6518)",
                "Dewan Tunku Canselor (DTC) (3.1219, 101.6570)",
                "Faculty of Engineering (3.1185, 101.6560)",
                "Perdanasiswa Complex (KPS) (3.1235, 101.6552)"
        };
        spinnerLocation.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, locations));

        String[] hazardTypes = {
                "Flash Flood",
                "Blocked Drain",
                "Fallen Tree",
                "Slope / Landslide"
        };
        spinnerHazardType.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, hazardTypes));
    }

    private void setupButtons(View view) {
        Button btnGps = view.findViewById(R.id.btn_acquire_gps);
        Button btnPhoto = view.findViewById(R.id.btn_capture_photo);
        Button btnTriage = view.findViewById(R.id.btn_ai_triage);
        Button btnSubmit = view.findViewById(R.id.btn_submit_report);
        Button btnTabAll = view.findViewById(R.id.btn_tab_all_reports);
        Button btnTabMine = view.findViewById(R.id.btn_tab_my_reports);

        btnGps.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                fetchDeviceGpsCoordinates();
            } else {
                gpsPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        });

        btnPhoto.setOnClickListener(v -> {
            Intent takePic = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            try {
                cameraLauncher.launch(takePic);
            } catch (Exception e) {
                Toast.makeText(getContext(), "Camera not available on this device.", Toast.LENGTH_SHORT).show();
            }
        });

        btnTriage.setOnClickListener(v -> viewModel.performVisionTriage(
                capturedBitmap,
                spinnerHazardType.getSelectedItemPosition(),
                etTitle.getText().toString()
        ));

        btnSubmit.setOnClickListener(v -> handleReportSubmission());

        btnTabAll.setOnClickListener(v -> viewModel.setShowOnlyMyReports(false));
        btnTabMine.setOnClickListener(v -> viewModel.setShowOnlyMyReports(true));
    }

    @SuppressLint("MissingPermission")
    private void fetchDeviceGpsCoordinates() {
        LocationManager lm = (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
        Location loc = null;
        if (lm != null) {
            loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) {
                loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        }
        if (loc != null) {
            realGpsLat = loc.getLatitude();
            realGpsLon = loc.getLongitude();
            tvGpsStatus.setText(String.format(Locale.US, "✅ Real GPS locked: (%.5f, %.5f)", realGpsLat, realGpsLon));
        } else {
            realGpsLat = null;
            realGpsLon = null;
            tvGpsStatus.setText("⚠️ Indoor/No GPS signal; fallback to selected landmark coordinates.");
        }
    }

    private void observeData() {
        viewModel.getTriageResult().observe(getViewLifecycleOwner(), res -> {
            if (res != null) {
                latestTriage = res;
                tvTriageResult.setText(String.format(Locale.US,
                        "Severity: %s (Confidence: %.0f%%)\n%s",
                        res.severity, res.confidence * 100, res.note));
            }
        });

        viewModel.getReports().observe(getViewLifecycleOwner(), reports -> refreshReportList());
        viewModel.getUserRole().observe(getViewLifecycleOwner(), role -> refreshReportList());
        viewModel.getShowOnlyMyReports().observe(getViewLifecycleOwner(), onlyMine -> refreshReportList());
    }

    private void handleReportSubmission() {
        String title = etTitle.getText().toString().trim();
        String desc = etDesc.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(getContext(), "Please enter a short hazard title.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (desc.isEmpty()) {
            desc = "Reported via UM-ClearFlow mobile client.";
        }

        String locationStr = spinnerLocation.getSelectedItem().toString();
        String hazardType = spinnerHazardType.getSelectedItem().toString();

        // 修复缺陷4：优先使用真实 GPS 坐标；若未获取 GPS 则正则解析所选地标真实坐标，绝不使用 Math.random()
        double lat = 3.1281, lon = 101.6505;
        if (realGpsLat != null && realGpsLon != null) {
            lat = realGpsLat;
            lon = realGpsLon;
        } else {
            Matcher m = COORD_PATTERN.matcher(locationStr);
            if (m.find()) {
                lat = Double.parseDouble(m.group(1));
                lon = Double.parseDouble(m.group(2));
            }
        }

        if (latestTriage == null) {
            latestTriage = CampusRepository.getInstance().runVisionTriage(
                    capturedBitmap, spinnerHazardType.getSelectedItemPosition(), title);
        }

        String id = "CF-" + (System.currentTimeMillis() % 100000);
        String timeStr = new SimpleDateFormat("Today, HH:mm", Locale.getDefault()).format(new Date());
        String myEmail = CampusRepository.getInstance()
                .getProfileField("siswa_email", "demo.student@siswa.um.edu.my");
        String timeline = timeStr + " • Submitted by " + myEmail
                + (capturedBitmap != null ? " (With Photo Vision Scan)" : " (Text Report)");

        HazardReport newReport = new HazardReport(
                id, title, desc, locationStr, hazardType,
                latestTriage.severity, "Submitted",
                lat, lon, timeStr,
                1, 0, latestTriage.confidence, latestTriage.note,
                myEmail, timeline
        );

        viewModel.submitNewReport(newReport);

        etTitle.setText("");
        etDesc.setText("");
        capturedBitmap = null;
        latestTriage = null;
        ivPhotoPreview.setVisibility(View.GONE);
        tvTriageResult.setText("✅ Report saved to SQLite & synced to Map! Note: Duplicates within 50m auto-merge.");
        Toast.makeText(getContext(), "Hazard report submitted!", Toast.LENGTH_SHORT).show();
    }

    private void refreshReportList() {
        if (reportsListContainer == null || getContext() == null) return;
        reportsListContainer.removeAllViews();

        List<HazardReport> list = viewModel.getReports().getValue();
        if (list == null) return;

        boolean onlyMine = Boolean.TRUE.equals(viewModel.getShowOnlyMyReports().getValue());
        String myEmail = CampusRepository.getInstance()
                .getProfileField("siswa_email", "demo.student@siswa.um.edu.my");
        boolean isAdmin = CampusRepository.getInstance().isAdmin();

        int pad = dpToPx(12);
        int marginBottom = dpToPx(10);

        for (HazardReport r : list) {
            if (onlyMine && !myEmail.equalsIgnoreCase(r.reporterEmail)) {
                continue;
            }

            LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Color.WHITE);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, marginBottom);
            card.setLayoutParams(lp);
            card.setElevation(dpToPx(2));

            TextView tvHeader = new TextView(getContext());
            tvHeader.setText(String.format(Locale.US, "[%s] %s • %s", r.severity, r.title, r.status));
            tvHeader.setTextSize(14f);
            tvHeader.setTextColor("Critical".equalsIgnoreCase(r.severity)
                    ? Color.rgb(185, 28, 28) : Color.rgb(15, 23, 42));
            tvHeader.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(tvHeader);

            TextView tvMeta = new TextView(getContext());
            tvMeta.setText(String.format(Locale.US, "📍 %s (%.4f, %.4f)\n🕒 %s | Reporter: %s",
                    r.locationName, r.latitude, r.longitude, r.timestamp, r.reporterEmail));
            tvMeta.setTextSize(11f);
            tvMeta.setTextColor(Color.rgb(100, 116, 139));
            card.addView(tvMeta);

            TextView tvBody = new TextView(getContext());
            tvBody.setText(r.description + "\n🤖 " + r.aiTriageNote);
            tvBody.setTextSize(12f);
            tvBody.setTextColor(Color.rgb(51, 65, 85));
            tvBody.setPadding(0, dpToPx(4), 0, dpToPx(4));
            card.addView(tvBody);

            // FR 2.3 工单时间线展示
            TextView tvTimeline = new TextView(getContext());
            tvTimeline.setText("📋 Timeline:\n" + r.timeline);
            tvTimeline.setTextSize(11f);
            tvTimeline.setTextColor(Color.rgb(71, 85, 105));
            tvTimeline.setBackgroundColor(Color.rgb(241, 245, 249));
            tvTimeline.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
            card.addView(tvTimeline);

            // 按钮操作行：Upvote (一人一票) + Verify Resolved (满3票解除) + 仅管理员可见的 JPPHB 状态修改
            LinearLayout actionRow = new LinearLayout(getContext());
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setPadding(0, dpToPx(6), 0, 0);

            Button btnUpvote = new Button(getContext());
            boolean alreadyUpvoted = CampusRepository.getInstance().hasUpvoted(r.id);
            btnUpvote.setText(alreadyUpvoted ? "👍 Upvoted (" + r.upvotes + ")" : "👍 Upvote (" + r.upvotes + ")");
            btnUpvote.setEnabled(!alreadyUpvoted);
            btnUpvote.setTextSize(11f);
            btnUpvote.setOnClickListener(v -> viewModel.upvote(r.id));
            actionRow.addView(btnUpvote);

            Button btnResolveVote = new Button(getContext());
            boolean alreadyResolvedVote = CampusRepository.getInstance().hasVotedResolve(r.id);
            btnResolveVote.setText("Resolved".equalsIgnoreCase(r.status)
                    ? "✅ Resolved"
                    : "🌤️ Clear? (" + r.resolveVotes + "/3)");
            btnResolveVote.setEnabled(!alreadyResolvedVote && !"Resolved".equalsIgnoreCase(r.status));
            btnResolveVote.setTextSize(11f);
            btnResolveVote.setOnClickListener(v -> viewModel.voteResolve(r.id));
            actionRow.addView(btnResolveVote);

            // 修复缺陷6：严格检查管理员权限，学生视角彻底隐藏 JPPHB Status 修改按钮
            Button btnAdminStatus = new Button(getContext());
            btnAdminStatus.setText("⚙️ JPPHB Status");
            btnAdminStatus.setTextSize(11f);
            btnAdminStatus.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
            btnAdminStatus.setOnClickListener(v -> showStatusUpdateDialog(r));
            actionRow.addView(btnAdminStatus);

            card.addView(actionRow);
            reportsListContainer.addView(card);
        }
    }

    private void showStatusUpdateDialog(HazardReport report) {
        if (!CampusRepository.getInstance().isAdmin()) {
            Toast.makeText(getContext(), "Admin role required!", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] statuses = {"Submitted", "Verified", "In Progress - JPPHB", "Resolved"};
        new AlertDialog.Builder(requireContext())
                .setTitle("Update Ticket Status (" + report.id + ")")
                .setItems(statuses, (dialog, which) -> viewModel.updateStatus(report.id, statuses[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}