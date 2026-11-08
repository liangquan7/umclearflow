package com.um.clearflow;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
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

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MapFragment extends Fragment {

    private MapView mapView;
    private MapViewModel viewModel;
    private TextView tvMapSummary;
    private TextView tvBroadcastBanner;
    private TextView tvRouteStatus;
    private LinearLayout layoutRouteSelector;
    private Spinner spinnerOrigin;
    private Spinner spinnerDestination;

    private final String[] originNames = {
            "Pantai Gate / Lingkungan Budi",
            "FCSIT & Central Library",
            "KK12 Residential College",
            "Faculty of Engineering"
    };
    private final GeoPoint[] originPoints = {
            new GeoPoint(3.1190, 101.6622),
            new GeoPoint(3.1281, 101.6505),
            new GeoPoint(3.1338, 101.6518),
            new GeoPoint(3.1185, 101.6560)
    };

    private final ActivityResultLauncher<String> sosLocationPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    shareRealLiveLocation();
                } else {
                    Toast.makeText(getContext(), "Location permission is required to share live GPS.", Toast.LENGTH_LONG).show();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext().getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        Configuration.getInstance().setUserAgentValue(ctx.getPackageName());

        View view = inflater.inflate(R.layout.fragment_map, container, false);

        // 修复缺陷1：绑定在 requireActivity() 作用域，切换 Tab 不丢失地图筛选与路由状态
        viewModel = new ViewModelProvider(requireActivity()).get(MapViewModel.class);

        tvMapSummary = view.findViewById(R.id.tv_map_summary);
        tvBroadcastBanner = view.findViewById(R.id.tv_broadcast_banner);
        tvRouteStatus = view.findViewById(R.id.tv_route_status);
        layoutRouteSelector = view.findViewById(R.id.layout_route_selector);
        spinnerOrigin = view.findViewById(R.id.spinner_origin);
        spinnerDestination = view.findViewById(R.id.spinner_destination);
        mapView = view.findViewById(R.id.osm_map);

        setupMap();
        setupSpinners();
        setupControls(view);
        observeViewModel();

        return view;
    }

    private void setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(15.6);
        mapView.getController().setCenter(new GeoPoint(3.1245, 101.6555));
    }

    private void setupSpinners() {
        ArrayAdapter<String> originAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, originNames);
        spinnerOrigin.setAdapter(originAdapter);

        List<CampusRepository.ShelterInfo> shelters = CampusRepository.getInstance().getCampusShelters();
        List<String> destNames = new ArrayList<>();
        for (CampusRepository.ShelterInfo s : shelters) {
            destNames.add(s.name);
        }
        ArrayAdapter<String> destAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, destNames);
        spinnerDestination.setAdapter(destAdapter);

        AdapterView.OnItemSelectedListener routeChangeListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                viewModel.setOriginIndex(spinnerOrigin.getSelectedItemPosition());
                viewModel.setDestinationIndex(spinnerDestination.getSelectedItemPosition());
                renderMapOverlays();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        };
        spinnerOrigin.setOnItemSelectedListener(routeChangeListener);
        spinnerDestination.setOnItemSelectedListener(routeChangeListener);
    }

    private void setupControls(View view) {
        Button btnAll = view.findViewById(R.id.btn_filter_all);
        Button btnCritical = view.findViewById(R.id.btn_filter_critical);
        CheckBox cbShelters = view.findViewById(R.id.cb_show_shelters);
        CheckBox cbRoute = view.findViewById(R.id.cb_evac_route);
        Button btnSos = view.findViewById(R.id.btn_sos);

        btnAll.setOnClickListener(v -> viewModel.setFilter("All"));
        btnCritical.setOnClickListener(v -> viewModel.setFilter("Critical"));

        cbShelters.setOnCheckedChangeListener((buttonView, isChecked) ->
                viewModel.setSheltersVisible(isChecked));

        cbRoute.setOnCheckedChangeListener((buttonView, isChecked) -> {
            layoutRouteSelector.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            viewModel.setRouteEnabled(isChecked);
        });

        btnSos.setOnClickListener(v -> showSosDialog());
    }

    private void observeViewModel() {
        viewModel.getReports().observe(getViewLifecycleOwner(), reports -> renderMapOverlays());
        viewModel.getSelectedFilter().observe(getViewLifecycleOwner(), filter -> renderMapOverlays());
        viewModel.getSheltersVisible().observe(getViewLifecycleOwner(), visible -> renderMapOverlays());
        viewModel.getRouteEnabled().observe(getViewLifecycleOwner(), enabled -> {
            layoutRouteSelector.setVisibility(Boolean.TRUE.equals(enabled) ? View.VISIBLE : View.GONE);
            renderMapOverlays();
        });
        viewModel.getBroadcastBanner().observe(getViewLifecycleOwner(), msg -> {
            if (msg != null && !msg.trim().isEmpty()) {
                tvBroadcastBanner.setText("📢 ADMIN ALERT: " + msg);
                tvBroadcastBanner.setVisibility(View.VISIBLE);
            } else {
                tvBroadcastBanner.setVisibility(View.GONE);
            }
        });
    }

    private void renderMapOverlays() {
        if (mapView == null) return;
        mapView.getOverlays().clear();

        List<HazardReport> allReports = viewModel.getReports().getValue();
        if (allReports == null) allReports = new ArrayList<>();

        String filter = viewModel.getSelectedFilter().getValue();
        boolean showShelters = Boolean.TRUE.equals(viewModel.getSheltersVisible().getValue());
        boolean showRoute = Boolean.TRUE.equals(viewModel.getRouteEnabled().getValue());

        int activeCount = 0;
        int criticalCount = 0;

        // 1. 渲染分级热力風險圈与隐患 Marker (FR 4.1)
        for (HazardReport r : allReports) {
            if ("Resolved".equalsIgnoreCase(r.status)) continue;
            if ("Critical".equalsIgnoreCase(filter) && !"Critical".equalsIgnoreCase(r.severity)) {
                continue;
            }
            activeCount++;
            boolean isCritical = "Critical".equalsIgnoreCase(r.severity);
            if (isCritical) criticalCount++;

            GeoPoint pt = new GeoPoint(r.latitude, r.longitude);

            // 外圈热力扩散光晕 (Heatmap Thermal Halo)
            Polygon outerHalo = new Polygon();
            outerHalo.setPoints(Polygon.pointsAsCircle(pt, isCritical ? 145.0 : 85.0));
            outerHalo.getFillPaint().setColor(isCritical
                    ? Color.argb(45, 220, 38, 38)
                    : Color.argb(35, 245, 158, 11));
            outerHalo.getOutlinePaint().setColor(Color.TRANSPARENT);
            mapView.getOverlays().add(outerHalo);

            // 内圈核心封锁半径
            Polygon coreCircle = new Polygon();
            coreCircle.setPoints(Polygon.pointsAsCircle(pt, isCritical ? 95.0 : 55.0));
            coreCircle.getFillPaint().setColor(isCritical
                    ? Color.argb(85, 220, 38, 38)
                    : Color.argb(75, 245, 158, 11));
            coreCircle.getOutlinePaint().setColor(isCritical
                    ? Color.rgb(185, 28, 28)
                    : Color.rgb(217, 119, 6));
            coreCircle.getOutlinePaint().setStrokeWidth(3f);
            mapView.getOverlays().add(coreCircle);

            Marker marker = new Marker(mapView);
            marker.setPosition(pt);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setTitle("[" + r.severity.toUpperCase(Locale.ROOT) + "] " + r.title);
            marker.setSnippet("Type: " + r.hazardType + " | Status: " + r.status
                    + " | 👍 " + r.upvotes + " | ✅ Clear Votes: " + r.resolveVotes + "/3\n"
                    + r.aiTriageNote);
            mapView.getOverlays().add(marker);
        }

        // 2. 渲染校园官方避难所图层 (FR 4.1)
        if (showShelters) {
            for (CampusRepository.ShelterInfo shelter : CampusRepository.getInstance().getCampusShelters()) {
                Marker sm = new Marker(mapView);
                sm.setPosition(new GeoPoint(shelter.lat, shelter.lon));
                sm.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
                sm.setTitle("🏛️ [EVACUATION SHELTER] " + shelter.name);
                sm.setSnippet(shelter.description);
                sm.setIcon(ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_dialog_info));
                mapView.getOverlays().add(sm);
            }
        }

        // 3. 动态计算避障逃生路线 (FR 4.2: 10米线段加密 + 碰撞检测)
        if (showRoute) {
            drawDynamicSafeRoute(allReports);
        }

        tvMapSummary.setText(String.format(Locale.US,
                "Active Hazards: %d (%d Critical) • Shelters: %s",
                activeCount, criticalCount, showShelters ? "ON" : "OFF"));

        mapView.invalidate();
    }

    private void drawDynamicSafeRoute(List<HazardReport> allReports) {
        int oIdx = spinnerOrigin.getSelectedItemPosition();
        int dIdx = spinnerDestination.getSelectedItemPosition();
        if (oIdx < 0 || oIdx >= originPoints.length) oIdx = 0;

        List<CampusRepository.ShelterInfo> shelters = CampusRepository.getInstance().getCampusShelters();
        if (dIdx < 0 || dIdx >= shelters.size()) dIdx = 0;

        GeoPoint start = originPoints[oIdx];
        CampusRepository.ShelterInfo targetShelter = shelters.get(dIdx);
        GeoPoint end = new GeoPoint(targetShelter.lat, targetShelter.lon);

        // 生成 3 条候选校园走廊路径：1. 谷地直达线  2. 西北高地绕行线  3. 东侧环路绕行线
        List<List<GeoPoint>> candidateRoutes = new ArrayList<>();
        // 候选1：直接谷地路径 (经过 Lingkungan Budi 主干道)
        candidateRoutes.add(Arrays.asList(
                start,
                new GeoPoint((start.getLatitude() + 3.1195) / 2, (start.getLongitude() + 101.6620) / 2),
                new GeoPoint(3.1205, 101.6595),
                end
        ));
        // 候选2：经中央高地走廊绕行 (避开谷地低洼积水区)
        candidateRoutes.add(Arrays.asList(
                start,
                new GeoPoint(3.1248, 101.6538),
                new GeoPoint(3.1238, 101.6562),
                end
        ));
        // 候选3：经外环高架步道绕行
        candidateRoutes.add(Arrays.asList(
                start,
                new GeoPoint(3.1270, 101.6585),
                new GeoPoint(3.1245, 101.6598),
                end
        ));

        List<GeoPoint> chosenRoute = null;
        int chosenIndex = -1;
        String blockedReason = "";

        for (int i = 0; i < candidateRoutes.size(); i++) {
            List<GeoPoint> densified = densifyRoute(candidateRoutes.get(i), 10.0);
            HazardReport hit = findIntersectingHazard(densified, allReports);
            if (hit == null) {
                chosenRoute = candidateRoutes.get(i);
                chosenIndex = i;
                break;
            } else if (i == 0) {
                blockedReason = hit.title + " (" + hit.hazardType + ")";
            }
        }

        Polyline routeLine = new Polyline();
        if (chosenRoute != null) {
            routeLine.setPoints(chosenRoute);
            routeLine.getOutlinePaint().setColor(Color.rgb(22, 163, 74)); // 安全绿色
            routeLine.getOutlinePaint().setStrokeWidth(11f);
            mapView.getOverlays().add(routeLine);

            String mobilityPref = CampusRepository.getInstance()
                    .getProfileField("mobility_pref", "Standard Walking");
            if (chosenIndex == 0) {
                tvRouteStatus.setText("✅ Direct route is clear (" + mobilityPref + ") -> " + targetShelter.name);
            } else {
                tvRouteStatus.setText("⚠️ Direct path blocked by [" + blockedReason
                        + "]. Auto-rerouted via Elevated Detour #" + chosenIndex + " (" + mobilityPref + ").");
            }
        } else {
            // 所有候选路径均被未解决隐患封锁：明确告警，绝不硬画假安全线
            routeLine.setPoints(candidateRoutes.get(0));
            routeLine.getOutlinePaint().setColor(Color.rgb(220, 38, 38));
            routeLine.getOutlinePaint().setStrokeWidth(8f);
            mapView.getOverlays().add(routeLine);
            tvRouteStatus.setText("🚨 WARNING: All candidate routes intersect active hazard zones! "
                    + "Please shelter-in-place on upper floors or tap SOS.");
        }
    }

    // 将稀疏折线按 stepMeters (10米) 加密采样，防止跨过隐患圆圈漏判
    private List<GeoPoint> densifyRoute(List<GeoPoint> waypoints, double stepMeters) {
        List<GeoPoint> dense = new ArrayList<>();
        for (int i = 0; i < waypoints.size() - 1; i++) {
            GeoPoint a = waypoints.get(i);
            GeoPoint b = waypoints.get(i + 1);
            double dist = a.distanceToAsDouble(b);
            int steps = Math.max(1, (int) Math.ceil(dist / stepMeters));
            for (int s = 0; s < steps; s++) {
                double ratio = (double) s / steps;
                double lat = a.getLatitude() + ratio * (b.getLatitude() - a.getLatitude());
                double lon = a.getLongitude() + ratio * (b.getLongitude() - a.getLongitude());
                dense.add(new GeoPoint(lat, lon));
            }
        }
        if (!waypoints.isEmpty()) {
            dense.add(waypoints.get(waypoints.size() - 1));
        }
        return dense;
    }

    private HazardReport findIntersectingHazard(List<GeoPoint> denseRoute, List<HazardReport> hazards) {
        for (HazardReport h : hazards) {
            if ("Resolved".equalsIgnoreCase(h.status)) continue;
            GeoPoint hp = new GeoPoint(h.latitude, h.longitude);
            double radius = "Critical".equalsIgnoreCase(h.severity) ? 95.0 : 55.0;
            for (GeoPoint p : denseRoute) {
                if (p.distanceToAsDouble(hp) < radius) {
                    return h;
                }
            }
        }
        return null;
    }

    // FR 4.3: 真实 SOS 紧急面板（分享真实 GPS 链接 / 拨打 999 / 拨打 UM 辅警）
    private void showSosDialog() {
        String emergencyContact = CampusRepository.getInstance()
                .getProfileField("emergency_phone", "Not Set");
        String[] options = {
                "📍 Share Live GPS Link (WhatsApp / SMS / Share)",
                "🚓 Call UM Auxiliary Police (03-7967 7070)",
                "🚑 Call Malaysia Emergency Services (999)",
                "📞 Call My Emergency Contact (" + emergencyContact + ")"
        };

        new AlertDialog.Builder(requireContext())
                .setTitle("🚨 UM-ClearFlow Emergency SOS")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        checkPermissionAndShareGps();
                    } else if (which == 1) {
                        dialPhoneNumber("0379677070");
                    } else if (which == 2) {
                        dialPhoneNumber("999");
                    } else if (which == 3) {
                        if ("Not Set".equals(emergencyContact) || emergencyContact.trim().isEmpty()) {
                            Toast.makeText(getContext(), "Please set an emergency contact in Profile tab first.", Toast.LENGTH_SHORT).show();
                        } else {
                            dialPhoneNumber(emergencyContact);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkPermissionAndShareGps() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            shareRealLiveLocation();
        } else {
            sosLocationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    @SuppressLint("MissingPermission")
    private void shareRealLiveLocation() {
        LocationManager lm = (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
        Location loc = null;
        if (lm != null) {
            loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) {
                loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        }

        String studentName = CampusRepository.getInstance().getProfileField("student_name", "UM Student");
        String college = CampusRepository.getInstance().getProfileField("college", "UM Campus");

        String body;
        if (loc != null) {
            String mapsLink = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f",
                    loc.getLatitude(), loc.getLongitude());
            body = "[UM-ClearFlow SOS] Emergency assistance needed!\n"
                    + "Sender: " + studentName + " (" + college + ")\n"
                    + "Live GPS Location: " + mapsLink;
        } else {
            GeoPoint mapCenter = (GeoPoint) mapView.getMapCenter();
            String fallbackLink = String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f",
                    mapCenter.getLatitude(), mapCenter.getLongitude());
            body = "[UM-ClearFlow SOS] Emergency assistance needed!\n"
                    + "Sender: " + studentName + " (" + college + ")\n"
                    + " GPS Signal unavailable indoors; Last Map Position: " + fallbackLink;
        }

        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("text/plain");
        sendIntent.putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(sendIntent, "Share SOS Location via..."));
    }

    private void dialPhoneNumber(String phone) {
        Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone));
        startActivity(dial);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
    }
}