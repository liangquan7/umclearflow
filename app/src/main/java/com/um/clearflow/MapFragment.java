package com.um.clearflow;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
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
    private Button btnAll;
    private Button btnCritical;

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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext().getApplicationContext();
        SharedPreferences osmPrefs = ctx.getSharedPreferences(ctx.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        Configuration.getInstance().load(ctx, osmPrefs);
        Configuration.getInstance().setUserAgentValue(ctx.getPackageName());

        View view = inflater.inflate(R.layout.fragment_map, container, false);

        // 绑定在 requireActivity() 作用域，切换 Tab 不丢失地图筛选与路由状态
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
        // Restore selections from the activity-scoped ViewModel (survives rotation / view recreation)
        Integer savedO = viewModel.getOriginIndex().getValue();
        Integer savedD = viewModel.getDestinationIndex().getValue();
        if (savedO != null && savedO >= 0 && savedO < originNames.length) spinnerOrigin.setSelection(savedO, false);
        if (savedD != null && savedD >= 0 && savedD < destNames.size()) spinnerDestination.setSelection(savedD, false);

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
        btnAll = view.findViewById(R.id.btn_filter_all);
        btnCritical = view.findViewById(R.id.btn_filter_critical);
        CheckBox cbShelters = view.findViewById(R.id.cb_show_shelters);
        CheckBox cbRoute = view.findViewById(R.id.cb_evac_route);
        // Sync checkbox state with ViewModel before attaching listeners
        cbShelters.setChecked(Boolean.TRUE.equals(viewModel.getSheltersVisible().getValue()));
        cbRoute.setChecked(Boolean.TRUE.equals(viewModel.getRouteEnabled().getValue()));

        btnAll.setOnClickListener(v -> viewModel.setFilter("All"));
        btnCritical.setOnClickListener(v -> viewModel.setFilter("Critical"));

        cbShelters.setOnCheckedChangeListener((buttonView, isChecked) ->
                viewModel.setSheltersVisible(isChecked));

        cbRoute.setOnCheckedChangeListener((buttonView, isChecked) -> {
            layoutRouteSelector.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            viewModel.setRouteEnabled(isChecked);
        });
    }

    private void observeViewModel() {
        viewModel.getReports().observe(getViewLifecycleOwner(), reports -> renderMapOverlays());
        viewModel.getSelectedFilter().observe(getViewLifecycleOwner(), filter -> {
            updateFilterButtonsHighlight(filter);
            renderMapOverlays();
        });
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

    private void updateFilterButtonsHighlight(String filter) {
        boolean criticalOnly = "Critical".equalsIgnoreCase(filter);
        if (btnAll != null && btnCritical != null) {
            btnAll.setAlpha(criticalOnly ? 0.55f : 1.0f);
            btnCritical.setAlpha(criticalOnly ? 1.0f : 0.55f);
        }
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

        // 1. 渲染分级热力风险圈与隐患 Marker (FR 4.1)
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

        // 3. 动态计算避障逃生路线 (FR 4.2: 基于起终点几何中点与正交偏移生成候选走廊 + 10米加密碰撞检测)
        if (showRoute) {
            drawDynamicSafeRoute(allReports);
        }

        tvMapSummary.setText(String.format(Locale.US,
                "Visible Active Reports: %d (%d Critical) • Shelters: %s",
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

        double midLat = (start.getLatitude() + end.getLatitude()) / 2.0;
        double midLon = (start.getLongitude() + end.getLongitude()) / 2.0;
        double dLat = end.getLatitude() - start.getLatitude();
        double dLon = end.getLongitude() - start.getLongitude();
        double len = Math.hypot(dLat, dLon);
        // 构造垂直于起止点连线的单位法向量（用于生成左右两侧高地绕行走廊，约偏移 180~240 米）
        double normLat = len > 1e-5 ? -dLon / len : 0.0020;
        double normLon = len > 1e-5 ?  dLat / len : 0.0020;

        List<List<GeoPoint>> candidateRoutes = new ArrayList<>();
        // 候选 0：起终点直连路径
        candidateRoutes.add(Arrays.asList(start, new GeoPoint(midLat, midLon), end));
        // 候选 1：向北/西侧高地偏移绕行 (~200m)
        candidateRoutes.add(Arrays.asList(
                start,
                new GeoPoint(midLat + normLat * 0.0020, midLon + normLon * 0.0020),
                end
        ));
        // 候选 2：向南/东侧外环偏移绕行 (~220m)
        candidateRoutes.add(Arrays.asList(
                start,
                new GeoPoint(midLat - normLat * 0.0022, midLon - normLon * 0.0022),
                end
        ));

        List<GeoPoint> chosenRoute = null;
        int chosenIndex = -1;
        String blockedReason = "";

        for (int i = 0; i < candidateRoutes.size(); i++) {
            List<GeoPoint> densified = densifyRoute(candidateRoutes.get(i), 10.0);
            HazardReport hit = findIntersectingHazard(densified, allReports, i > 0, start, end);
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
                    .getProfileField("mobility_pref", "Walking (Pedestrian / Covered Walkway)");
            String modeNote = mobilityPref.contains("Bus") ? " [Shuttle Bus Stop: KPS High-Ground]"
                    : (mobilityPref.contains("Driving") ? " [Vehicle Ring Road]"
                    : (mobilityPref.contains("Cycling") ? " [Micro-mobility Lane]" : " [Covered Walkway]"));
            mobilityPref = mobilityPref + modeNote;
            if (chosenIndex == 0) {
                tvRouteStatus.setText("Illustrative safe corridor estimate only; it does not confirm the path is safe or accessible. Check conditions before moving. ("
                        + mobilityPref + ") -> " + targetShelter.name);
            } else {
                tvRouteStatus.setText("A reported hazard intersects the direct estimate [" + blockedReason
                        + "]. Alternate corridor #" + chosenIndex + " is illustrative only; verify route conditions and accessibility before moving.");
            }
        } else {
            // 所有候选路径均被未解决隐患封锁：明确告警，绝不硬画假安全线
            routeLine.setPoints(candidateRoutes.get(0));
            routeLine.getOutlinePaint().setColor(Color.rgb(220, 38, 38));
            routeLine.getOutlinePaint().setStrokeWidth(8f);
            mapView.getOverlays().add(routeLine);
            tvRouteStatus.setText("🚨 No candidate line avoids locally recorded active hazards. Follow current official UM instructions; if you are in immediate danger, use SOS.");
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

    private HazardReport findIntersectingHazard(List<GeoPoint> denseRoute, List<HazardReport> hazards,
                                                boolean isDetourCandidate, GeoPoint start, GeoPoint end) {
        for (HazardReport h : hazards) {
            if ("Resolved".equalsIgnoreCase(h.status)) continue;
            GeoPoint hp = new GeoPoint(h.latitude, h.longitude);
            double radius = "Critical".equalsIgnoreCase(h.severity) ? 95.0 : 55.0;
            // If a hazard was pinned to the exact departure/arrival landmark coordinate,
            // Candidate 0 (direct route) flags it, while detour candidates (Candidate 1/2)
            // represent departing the building via a side/covered walkway to bypass that gate hazard.
            boolean pinnedAtTerminal = isDetourCandidate
                    && (hp.distanceToAsDouble(start) < radius || hp.distanceToAsDouble(end) < radius);
            for (GeoPoint p : denseRoute) {
                if (pinnedAtTerminal
                        && (p.distanceToAsDouble(start) < radius + 15.0 || p.distanceToAsDouble(end) < radius + 15.0)) {
                    continue;
                }
                if (p.distanceToAsDouble(hp) < radius) {
                    return h;
                }
            }
        }
        return null;
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

    @Override
    public void onDestroyView() {
        // Release osmdroid tile threads / bitmaps to avoid leaking the destroyed view
        if (mapView != null) {
            mapView.getOverlays().clear();
            mapView.onDetach();
            mapView = null;
        }
        super.onDestroyView();
    }
}
