package com.um.clearflow;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
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
import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import java.util.List;

public class ReportFragment extends Fragment {

    private ImageView ivHazardPreview;
    private TextView tvAiDiagnosisBox;
    private EditText etHazardTitle;
    private Spinner spCampusLocation, spHazardType;
    private CheckBox cbSimulateOffline;
    private LinearLayout llReportsContainer;

    private ReportViewModel viewModel;

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Bundle extras = result.getData().getExtras();
                    if (extras != null && extras.get("data") != null) {
                        Bitmap imageBitmap = (Bitmap) extras.get("data");
                        ivHazardPreview.setImageBitmap(imageBitmap);
                        if (viewModel != null) {
                            viewModel.runTriage(spHazardType.getSelectedItemPosition(), etHazardTitle.getText().toString());
                        }
                    }
                }
            }
    );

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_report, container, false);

        ivHazardPreview = view.findViewById(R.id.ivHazardPreview);
        tvAiDiagnosisBox = view.findViewById(R.id.tvAiDiagnosisBox);
        etHazardTitle = view.findViewById(R.id.etHazardTitle);
        spCampusLocation = view.findViewById(R.id.spCampusLocation);
        spHazardType = view.findViewById(R.id.spHazardType);
        cbSimulateOffline = view.findViewById(R.id.cbSimulateOffline);
        llReportsContainer = view.findViewById(R.id.llReportsContainer);

        setupSpinners();

        viewModel = new ViewModelProvider(this).get(ReportViewModel.class);

        // Observers
        viewModel.getTriageResult().observe(getViewLifecycleOwner(), triage -> {
            if (triage != null) {
                String boxText = "🤖 Risk Assessment:\n" +
                        "• Category Severity: " + triage.severity.toUpperCase() + " (Confidence: " + (int)(triage.confidence * 100) + "%)\n" +
                        "• " + triage.note + "\n" +
                        "• Dispatch Target: JPPHB & UM Security";
                tvAiDiagnosisBox.setText(boxText);
            }
        });

        viewModel.getReports().observe(getViewLifecycleOwner(), reports -> refreshReportList(reports));

        // Camera / Demo photo button
        view.findViewById(R.id.btnCapturePhoto).setOnClickListener(v -> {
            try {
                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cameraLauncher.launch(takePictureIntent);
            } catch (Exception e) {
                ivHazardPreview.setImageResource(android.R.drawable.ic_dialog_alert);
                Toast.makeText(requireContext(), getString(R.string.toast_sample_image_loaded), Toast.LENGTH_SHORT).show();
                viewModel.runTriage(spHazardType.getSelectedItemPosition(), etHazardTitle.getText().toString());
            }
        });

        // Run Risk Triage button
        view.findViewById(R.id.btnRunAiTriage).setOnClickListener(v ->
                viewModel.runTriage(spHazardType.getSelectedItemPosition(), etHazardTitle.getText().toString())
        );

        // Submit Report button
        view.findViewById(R.id.btnSubmitReport).setOnClickListener(v -> {
            String title = etHazardTitle.getText().toString().trim();
            String locationStr = spCampusLocation.getSelectedItem().toString();
            String typeStr = spHazardType.getSelectedItem().toString();
            boolean isOfflineMode = cbSimulateOffline.isChecked();

            viewModel.submitReport(title, locationStr, typeStr, isOfflineMode);
            etHazardTitle.setText("");

            if (isOfflineMode) {
                Toast.makeText(requireContext(), getString(R.string.toast_saved_offline), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(requireContext(), getString(R.string.toast_published), Toast.LENGTH_SHORT).show();
            }
        });

        return view;
    }

    private void setupSpinners() {
        String[] umLocations = {
                "FCSIT Block A & B Walkway (3.1281, 101.6505)",
                "Jalan Lingkungan Budi - Near KL Gate (3.1285, 101.6582)",
                "Jalan Ilmu - Near KK8 Shuttle Stop (3.1268, 101.6462)",
                "Dewan Tunku Canselor (DTC) Lowland (3.1209, 101.6538)",
                "KK12 / Raja Dr. Nazrin Shah Drain (3.1230, 101.6435)",
                "Universiti LRT Gate Entrance Road (3.1165, 101.6602)"
        };
        spCampusLocation.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, umLocations));

        String[] hazardTypes = {
                getString(R.string.category_blocked_drain),
                getString(R.string.category_flash_flood),
                getString(R.string.category_fallen_tree)
        };
        spHazardType.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, hazardTypes));
    }

    private void refreshReportList(List<HazardReport> reports) {
        if (llReportsContainer == null || reports == null) return;
        llReportsContainer.removeAllViews();

        for (HazardReport r : reports) {
            CardView card = new CardView(requireContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, 20);
            card.setLayoutParams(params);
            card.setRadius(24f);
            card.setCardElevation(6f);
            card.setCardBackgroundColor(Color.WHITE);

            LinearLayout inner = new LinearLayout(requireContext());
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setPadding(32, 28, 32, 28);

            TextView tvTitle = new TextView(requireContext());
            tvTitle.setText("[" + r.severity.toUpperCase() + "] " + r.title + " (" + r.id + ")");
            tvTitle.setTextSize(15f);
            tvTitle.setTextColor(r.severity.equalsIgnoreCase("Critical") ? Color.parseColor("#C62828") : Color.parseColor("#002B5B"));
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);

            TextView tvMeta = new TextView(requireContext());
            tvMeta.setText(getString(R.string.hazard_detail_location, r.campusLocation) + "\n" +
                    getString(R.string.hazard_detail_ai, r.aiAnalysis) + "\n" +
                    getString(R.string.hazard_detail_status, r.status) + " | Verified: " + r.upvotes + " | " + r.timestamp);
            tvMeta.setTextSize(12f);
            tvMeta.setTextColor(Color.parseColor("#444444"));
            tvMeta.setPadding(0, 10, 0, 14);

            LinearLayout btnRow = new LinearLayout(requireContext());
            btnRow.setOrientation(LinearLayout.HORIZONTAL);

            Button btnUpvote = new Button(requireContext());
            btnUpvote.setText("👍 Still Active (" + r.upvotes + ")");
            btnUpvote.setTextSize(11f);
            btnUpvote.setOnClickListener(v -> viewModel.upvoteReport(r.id));

            Button btnUpdateStatus = new Button(requireContext());
            btnUpdateStatus.setText("🛠️ JPPHB Status");
            btnUpdateStatus.setTextSize(11f);
            btnUpdateStatus.setOnClickListener(v -> showStatusUpdateDialog(r));

            btnRow.addView(btnUpvote);
            btnRow.addView(btnUpdateStatus);

            inner.addView(tvTitle);
            inner.addView(tvMeta);
            inner.addView(btnRow);
            card.addView(inner);
            llReportsContainer.addView(card);
        }
    }

    private void showStatusUpdateDialog(HazardReport r) {
        String[] statuses = {
                getString(R.string.status_pending),
                getString(R.string.status_in_progress),
                getString(R.string.status_resolved),
                getString(R.string.status_sync_offline)
        };
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.status_update_dialog_title, r.id))
                .setItems(statuses, (d, which) -> {
                    if (which == 3) {
                        viewModel.updateReportStatus(r.id, getString(R.string.status_pending) + " (Synced)", true);
                    } else {
                        viewModel.updateReportStatus(r.id, statuses[which], r.isSynced);
                    }
                })
                .show();
    }
}