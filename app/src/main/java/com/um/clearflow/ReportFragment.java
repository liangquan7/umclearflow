package com.um.clearflow;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.location.Location;
import android.location.LocationManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.location.LocationManagerCompat;
import androidx.core.os.CancellationSignal;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReportFragment extends Fragment {

    private static final Pattern COORD_PATTERN = Pattern.compile("\\(([-\\d.]+),\\s*([-\\d.]+)\\)");
    private static final String NO_HAZARD = "None";
    private static final long MAX_REPORT_LOCATION_AGE_MS = 10 * 60 * 1000L;
    private static final long LIVE_GPS_TIMEOUT_MS = 6000L;
    private static final String CAMERA_DIR = "camera";
    private static final String PENDING_PHOTO = "pending_report.jpg";
    private static final int MAX_PHOTO_DIMENSION = 1280;

    private ReportViewModel viewModel;
    private Spinner spinnerLocation;
    private Spinner spinnerHazardType;
    private EditText etTitle;
    private EditText etDesc;
    private TextView tvGpsStatus;
    private TextView tvTriageResult;
    private ImageView ivPhotoPreview;
    private RecyclerView reportsListContainer;
    private ReportAdapter reportAdapter;
    private Button btnSubmit;
    private Button btnPhoto;
    private Button btnTriage;
    private Button btnTabAll;
    private Button btnTabMine;
    private boolean submitAfterTriage = false;
    private long latestTriageRequestId = 0L;
    private long pendingSubmitRequestId = 0L;
    private long inFlightTriageRequestId = 0L;
    private String inFlightTriageInputKey = null;
    private String latestTriageInputKey = null;

    private Bitmap capturedBitmap = null;
    private Double realGpsLat = null;
    private Double realGpsLon = null;
    private CampusRepository.TriageResult latestTriage = null;
    private CancellationSignal gpsCancelSignal = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Full-resolution capture through FileProvider. The old ACTION_IMAGE_CAPTURE "data" extra is only a
     * ~160x120 thumbnail, far too small for photo triage or for a useful report photo (FR 2.1 / 3.1).
     * A fixed file name is used so the callback still works after the fragment is re-created.
     */
    private final ActivityResultLauncher<Uri> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                Context ctx = getContext();
                if (ctx == null) return;
                File photoFile = new File(new File(ctx.getCacheDir(), CAMERA_DIR), PENDING_PHOTO);
                if (Boolean.TRUE.equals(success) && photoFile.exists()) {
                    Bitmap bmp = decodeCapturedPhoto(photoFile, MAX_PHOTO_DIMENSION);
                    if (bmp != null && ivPhotoPreview != null) {
                        capturedBitmap = bmp;
                        viewModel.setCapturedPhoto(bmp);
                        ivPhotoPreview.setImageBitmap(bmp);
                        ivPhotoPreview.setVisibility(View.VISIBLE);
                        requestTriage(false);
                    } else {
                        Toast.makeText(ctx, "Could not read the captured photo.", Toast.LENGTH_SHORT).show();
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                photoFile.delete();
            });

    private final ActivityResultLauncher<String[]> gpsPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                        || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
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

        reportAdapter = new ReportAdapter(new ReportAdapter.Actions() {
            @Override
            public void onUpvote(HazardReport r) {
                viewModel.upvote(r.id);
            }

            @Override
            public void onVoteResolve(HazardReport r) {
                viewModel.voteResolve(r.id);
            }

            @Override
            public void onAdminStatus(HazardReport r) {
                showStatusUpdateDialog(r);
            }
        });
        reportsListContainer.setLayoutManager(new LinearLayoutManager(requireContext()));
        reportsListContainer.setAdapter(reportAdapter);

        capturedBitmap = viewModel.getCapturedPhoto();
        if (capturedBitmap != null) {
            ivPhotoPreview.setImageBitmap(capturedBitmap);
            ivPhotoPreview.setVisibility(View.VISIBLE);
        }

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
        spinnerLocation.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;   // skip the automatic callback at init
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (first) { first = false; return; }
                realGpsLat = null;
                realGpsLon = null;
                tvGpsStatus.setText("Using landmark coordinates");
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupButtons(View view) {
        Button btnGps = view.findViewById(R.id.btn_acquire_gps);
        btnPhoto = view.findViewById(R.id.btn_capture_photo);
        btnTriage = view.findViewById(R.id.btn_ai_triage);
        btnSubmit = view.findViewById(R.id.btn_submit_report);
        btnTabAll = view.findViewById(R.id.btn_tab_all_reports);
        btnTabMine = view.findViewById(R.id.btn_tab_my_reports);

        btnGps.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                fetchDeviceGpsCoordinates();
            } else {
                gpsPermLauncher.launch(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION});
            }
        });

        btnPhoto.setOnClickListener(v -> launchCamera());

        btnTriage.setOnClickListener(v -> requestTriage(false));

        btnSubmit.setOnClickListener(v -> handleReportSubmission());

        btnTabAll.setOnClickListener(v -> viewModel.setShowOnlyMyReports(false));
        btnTabMine.setOnClickListener(v -> viewModel.setShowOnlyMyReports(true));
    }

    private void launchCamera() {
        try {
            Context ctx = requireContext();
            File dir = new File(ctx.getCacheDir(), CAMERA_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                Toast.makeText(ctx, "Cannot prepare camera storage.", Toast.LENGTH_SHORT).show();
                return;
            }
            File photoFile = new File(dir, PENDING_PHOTO);
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", photoFile);
            takePictureLauncher.launch(uri);
        } catch (Exception e) {
            Toast.makeText(getContext(), "Camera not available on this device.", Toast.LENGTH_SHORT).show();
        }
    }

    /** Down-sampled decode + EXIF rotation so the preview, triage upload and saved JPEG are upright and small. */
    @Nullable
    private static Bitmap decodeCapturedPhoto(File file, int maxDimension) {
        String path = file.getAbsolutePath();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(path, opts);
        if (bmp == null) return null;

        int rotation = 0;
        try {
            int orientation = new ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (Exception ignored) {}
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            bmp = rotated;
        }
        return bmp;
    }

    @SuppressLint("MissingPermission")
    private void fetchDeviceGpsCoordinates() {
        LocationManager lm = (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
        Location loc = null;
        if (lm != null) {
            for (String p : new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (loc == null || l.getTime() > loc.getTime())) loc = l;
                } catch (SecurityException | IllegalArgumentException ignored) {}
            }
        }
        boolean staleFix = loc != null
                && (System.currentTimeMillis() - loc.getTime() > MAX_REPORT_LOCATION_AGE_MS);
        if (loc != null && !staleFix) {
            applyGpsFix(loc, false);
            return;
        }

        if (lm != null) {
            String activeProvider = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    if (lm.isProviderEnabled(p)) {
                        activeProvider = p;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            if (activeProvider != null) {
                if (gpsCancelSignal != null) {
                    gpsCancelSignal.cancel();
                }
                final CancellationSignal signal = new CancellationSignal();
                gpsCancelSignal = signal;
                final boolean wasStale = staleFix;
                tvGpsStatus.setText("📡 Acquiring live GPS fix...");

                Runnable timeoutTask = () -> {
                    if (!signal.isCanceled()) {
                        signal.cancel();
                        if (isAdded() && tvGpsStatus != null) {
                            realGpsLat = null;
                            realGpsLon = null;
                            tvGpsStatus.setText(wasStale
                                    ? "⚠️ Location fix is over 10 minutes old & live GPS timed out; using landmark coordinates."
                                    : "⚠️ Indoor/No live GPS signal; fallback to selected landmark coordinates.");
                        }
                    }
                };
                mainHandler.postDelayed(timeoutTask, LIVE_GPS_TIMEOUT_MS);

                try {
                    LocationManagerCompat.getCurrentLocation(
                            lm,
                            activeProvider,
                            signal,
                            ContextCompat.getMainExecutor(requireContext()),
                            freshLocation -> {
                                mainHandler.removeCallbacks(timeoutTask);
                                if (!isAdded() || tvGpsStatus == null) return;
                                if (freshLocation != null) {
                                    applyGpsFix(freshLocation, true);
                                } else {
                                    realGpsLat = null;
                                    realGpsLon = null;
                                    tvGpsStatus.setText(wasStale
                                            ? "⚠️ Location fix is over 10 minutes old; using selected landmark coordinates."
                                            : "⚠️ Indoor/No GPS signal; fallback to selected landmark coordinates.");
                                }
                            }
                    );
                    return;
                } catch (SecurityException | IllegalArgumentException ignored) {
                    mainHandler.removeCallbacks(timeoutTask);
                }
            }
        }

        realGpsLat = null;
        realGpsLon = null;
        tvGpsStatus.setText(staleFix
                ? "⚠️ Location fix is over 10 minutes old; using selected landmark coordinates."
                : "⚠️ Indoor/No GPS signal; fallback to selected landmark coordinates.");
    }

    private void applyGpsFix(@NonNull Location loc, boolean isLiveFix) {
        realGpsLat = loc.getLatitude();
        realGpsLon = loc.getLongitude();
        long ageSeconds = Math.max(0L, (System.currentTimeMillis() - loc.getTime()) / 1000L);
        if (isLiveFix) {
            tvGpsStatus.setText(String.format(Locale.US,
                    "✅ Live GPS fix acquired: (%.5f, %.5f)", realGpsLat, realGpsLon));
        } else {
            tvGpsStatus.setText(String.format(Locale.US,
                    "✅ Last known location (%ds old): (%.5f, %.5f)", ageSeconds, realGpsLat, realGpsLon));
        }
    }

    private void observeData() {
        viewModel.getTriageResult().observe(getViewLifecycleOwner(), res -> {
            if (res != null && res.requestId == inFlightTriageRequestId) {
                inFlightTriageRequestId = 0L;
                inFlightTriageInputKey = null;
            }
            if (res == null || res.requestId != latestTriageRequestId) return;
            if (!res.inputKey.equals(currentTriageInputKey())) {
                latestTriage = null;
                latestTriageInputKey = null;
                tvTriageResult.setText("Form changed during analysis. Review the form and submit again.");
                if (submitAfterTriage && pendingSubmitRequestId == res.requestId) {
                    finishPendingSubmission();
                    Toast.makeText(getContext(), "Form changed; please submit again.", Toast.LENGTH_SHORT).show();
                }
                return;
            }

            latestTriage = res.result;
            latestTriageInputKey = res.inputKey;
            if (res.result != null) {
                String chosen = spinnerHazardType.getSelectedItem().toString();
                String detected = res.result.detectedType;
                String typeLine;
                if (NO_HAZARD.equals(detected)) {
                    typeLine = "No clear hazard detected in the photo.";
                } else if (detected.equals(chosen)) {
                    typeLine = "Suggested type: " + detected;
                } else {
                    typeLine = "Suggested type: " + detected + " (you selected: " + chosen + ")";
                }
                tvTriageResult.setText(String.format(Locale.US,
                        "%s\nSeverity: %s (Confidence: %.0f%%)\n%s",
                        typeLine, res.result.severity, res.result.confidence * 100, res.result.note));
                if (submitAfterTriage && pendingSubmitRequestId == res.requestId) {
                    finishPendingSubmission();
                    handleReportSubmission();
                }
            }
        });

        viewModel.getReports().observe(getViewLifecycleOwner(), reports -> refreshReportList());
        viewModel.getUserRole().observe(getViewLifecycleOwner(), role -> refreshReportList());
        viewModel.getShowOnlyMyReports().observe(getViewLifecycleOwner(), onlyMine -> {
            updateTabFilterHighlight(Boolean.TRUE.equals(onlyMine));
            refreshReportList();
        });
    }

    private void updateTabFilterHighlight(boolean onlyMine) {
        if (btnTabAll != null && btnTabMine != null) {
            btnTabAll.setAlpha(onlyMine ? 0.55f : 1.0f);
            btnTabMine.setAlpha(onlyMine ? 1.0f : 0.55f);
        }
    }

    private String currentTriageInputKey() {
        String title = etTitle.getText().toString().trim();
        int hazardType = spinnerHazardType.getSelectedItemPosition();
        int photoIdentity = capturedBitmap != null ? System.identityHashCode(capturedBitmap) : 0;
        return title.length() + ":" + title + "|" + hazardType + "|" + photoIdentity;
    }

    private void requestTriage(boolean submitWhenComplete) {
        String inputKey = currentTriageInputKey();
        if (inFlightTriageRequestId != 0L && inputKey.equals(inFlightTriageInputKey)) {
            if (submitWhenComplete) {
                submitAfterTriage = true;
                pendingSubmitRequestId = inFlightTriageRequestId;
                btnSubmit.setEnabled(false);
                if (btnPhoto != null) btnPhoto.setEnabled(false);
                if (btnTriage != null) btnTriage.setEnabled(false);
            }
            return;
        }
        latestTriage = null;
        latestTriageInputKey = null;
        long requestId = viewModel.performVisionTriage(
                capturedBitmap,
                spinnerHazardType.getSelectedItemPosition(),
                etTitle.getText().toString().trim(),
                inputKey);
        latestTriageRequestId = requestId;
        inFlightTriageRequestId = requestId;
        inFlightTriageInputKey = inputKey;
        if (submitWhenComplete) {
            submitAfterTriage = true;
            pendingSubmitRequestId = requestId;
            btnSubmit.setEnabled(false);
            if (btnPhoto != null) btnPhoto.setEnabled(false);
            if (btnTriage != null) btnTriage.setEnabled(false);
        }
        tvTriageResult.setText("Analyzing the current form in the background…");
    }

    private void finishPendingSubmission() {
        submitAfterTriage = false;
        pendingSubmitRequestId = 0L;
        if (btnSubmit != null) btnSubmit.setEnabled(true);
        if (btnPhoto != null) btnPhoto.setEnabled(true);
        if (btnTriage != null) btnTriage.setEnabled(true);
    }

    private void handleReportSubmission() {
        String title = etTitle.getText().toString().trim();
        String descInput = etDesc.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(getContext(), "Please enter a short hazard title.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String desc = descInput.isEmpty() ? "Reported via UM-ClearFlow mobile client." : descInput;

        final String locationStr = spinnerLocation.getSelectedItem().toString();
        final String selectedType = spinnerHazardType.getSelectedItem().toString();

        // Prefer real GPS; otherwise parse the chosen landmark's coordinates. Never random.
        double latTmp = 3.1281, lonTmp = 101.6505;
        if (realGpsLat != null && realGpsLon != null) {
            latTmp = realGpsLat;
            lonTmp = realGpsLon;
        } else {
            Matcher m = COORD_PATTERN.matcher(locationStr);
            if (m.find()) {
                latTmp = Double.parseDouble(m.group(1));
                lonTmp = Double.parseDouble(m.group(2));
            }
        }
        final double lat = latTmp, lon = lonTmp;

        String currentInputKey = currentTriageInputKey();
        if (latestTriage == null || !currentInputKey.equals(latestTriageInputKey)) {
            requestTriage(true);
            return;
        }

        final CampusRepository.TriageResult tr = latestTriage;
        final String detected = tr.detectedType != null ? tr.detectedType : selectedType;

        if (NO_HAZARD.equals(detected)) {
            // The model sees no hazard: do not silently file a hazard report
            new AlertDialog.Builder(requireContext())
                    .setTitle("No clear hazard detected")
                    .setMessage("The photo does not appear to show a hazard. Retake the photo, or save the report "
                            + "anyway as \"" + selectedType + "\" with Low severity?")
                    .setPositiveButton("Save anyway", (d, w) ->
                            saveReport(title, desc, locationStr, selectedType, lat, lon, tr, "Low",
                                    "user choice, AI saw no hazard"))
                    .setNegativeButton("Retake photo", null)
                    .show();
        } else if (!detected.equals(selectedType)) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Hazard type differs")
                    .setMessage("You selected \"" + selectedType + "\" but the photo scan suggests \""
                            + detected + "\". Which should be saved? (Nearby reports of the same type are merged.)")
                    .setPositiveButton("Use suggestion", (d, w) ->
                            saveReport(title, desc, locationStr, detected, lat, lon, tr, tr.severity,
                                    "AI suggestion; user chose " + selectedType))
                    .setNegativeButton("Keep mine", (d, w) ->
                            saveReport(title, desc, locationStr, selectedType, lat, lon, tr, tr.severity,
                                    "user choice; AI suggested " + detected))
                    .setNeutralButton("Cancel", null)
                    .show();
        } else {
            saveReport(title, desc, locationStr, selectedType, lat, lon, tr, tr.severity, "user and AI agree");
        }
    }

    private void saveReport(String title, String desc, String locationStr, String hazardType,
                            double lat, double lon, CampusRepository.TriageResult tr,
                            String severity, String typeSource) {
        String id = "CF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String timeStr = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(new Date());
        String myEmail = CampusRepository.getInstance()
                .getProfileField("siswa_email", "demo.student@siswa.um.edu.my");
        String timeline = timeStr + " • Submitted"
                + (capturedBitmap != null ? " (With Photo Estimate)" : " (Text Report)")
                + "\n" + timeStr + " • Category: " + hazardType + " (" + typeSource + ")";

        String photoUri = "";
        if (capturedBitmap != null) {
            photoUri = CampusRepository.getInstance().saveReportPhotoToInternalStorage(id, capturedBitmap);
        }

        HazardReport newReport = new HazardReport(
                id, title, desc, locationStr, hazardType,
                severity, "Submitted",
                lat, lon, timeStr,
                1, 0, tr.confidence, tr.note,
                myEmail, timeline, photoUri
        );
        viewModel.submitNewReport(newReport);

        etTitle.setText("");
        etDesc.setText("");
        capturedBitmap = null;
        viewModel.setCapturedPhoto(null);
        latestTriage = null;
        ivPhotoPreview.setVisibility(View.GONE);
        tvTriageResult.setText("Report saved on this device and queued for sync to the ClearFlow server. "
                + "JPPHB dispatch is not connected. Nearby reports of the same type (<50 m) are merged.");
        realGpsLat = null;
        realGpsLon = null;
        tvGpsStatus.setText("Using landmark coordinates");
        viewModel.clearTriage();
        Toast.makeText(getContext(), "Report saved; syncing…", Toast.LENGTH_SHORT).show();
    }

    private void refreshReportList() {
        if (reportAdapter == null) return;
        List<HazardReport> list = viewModel.getReports().getValue();
        if (list == null) return;

        boolean onlyMine = Boolean.TRUE.equals(viewModel.getShowOnlyMyReports().getValue());
        String myEmail = CampusRepository.getInstance()
                .getProfileField("siswa_email", "demo.student@siswa.um.edu.my");

        List<HazardReport> visible = new ArrayList<>(list.size());
        for (HazardReport r : list) {
            if (!onlyMine || myEmail.equalsIgnoreCase(r.reporterEmail)) visible.add(r);
        }
        reportAdapter.setAdmin(CampusRepository.getInstance().isAdmin());
        reportAdapter.submitList(visible);
    }

    private void showStatusUpdateDialog(HazardReport report) {
        if (!CampusRepository.getInstance().isAdmin()) {
            Toast.makeText(getContext(), "Admin role required!", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] statuses = {"Submitted", "Verified", "In Progress - JPPHB", "Resolved"};
        new AlertDialog.Builder(requireContext())
                .setTitle("Update Local Demo Status (" + report.id + ")")
                .setItems(statuses, (dialog, which) -> viewModel.updateStatus(report.id, statuses[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onDestroyView() {
        if (gpsCancelSignal != null) {
            gpsCancelSignal.cancel();
            gpsCancelSignal = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }
}
