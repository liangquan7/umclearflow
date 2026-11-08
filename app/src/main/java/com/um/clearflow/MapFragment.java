package com.um.clearflow;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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
import java.util.List;

public class MapFragment extends Fragment {

    private MapView osmMap;
    private TextView tvRouteInfo;
    private Button btnSafeRoute;

    private MapViewModel viewModel;

    // Universiti Malaya (UM) campus coordinates
    private final GeoPoint UM_CENTER = new GeoPoint(3.1245, 101.6525);
    private final GeoPoint FCSIT_POINT = new GeoPoint(3.1281, 101.6505);
    private final GeoPoint KK8_POINT = new GeoPoint(3.1268, 101.6462);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Configuration.getInstance().load(requireContext(), requireContext().getSharedPreferences("osmdroid", Context.MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue(requireContext().getPackageName());

        View view = inflater.inflate(R.layout.fragment_map, container, false);
        osmMap = view.findViewById(R.id.osmMap);
        tvRouteInfo = view.findViewById(R.id.tvRouteInfo);
        btnSafeRoute = view.findViewById(R.id.btnSafeRoute);

        setupUmMap();

        viewModel = new ViewModelProvider(this).get(MapViewModel.class);

        // Observers
        viewModel.getReports().observe(getViewLifecycleOwner(), reports -> renderMapOverlays());
        viewModel.getFilter().observe(getViewLifecycleOwner(), filter -> renderMapOverlays());
        viewModel.isSafeRouteActive().observe(getViewLifecycleOwner(), active -> {
            if (active != null && active) {
                tvRouteInfo.setText(getString(R.string.route_info_active));
                btnSafeRoute.setText(getString(R.string.btn_safe_route_clear));
            } else {
                tvRouteInfo.setText(getString(R.string.route_info_default));
                btnSafeRoute.setText(getString(R.string.btn_safe_route_plan));
            }
            renderMapOverlays();
        });

        // Event listeners -> Send user actions to ViewModel
        view.findViewById(R.id.btnFilterAll).setOnClickListener(v -> viewModel.setFilter("ALL"));
        view.findViewById(R.id.btnFilterFlood).setOnClickListener(v -> viewModel.setFilter("Flash Flood"));
        view.findViewById(R.id.btnFilterTree).setOnClickListener(v -> viewModel.setFilter("Fallen Tree"));
        view.findViewById(R.id.btnFilterDrain).setOnClickListener(v -> viewModel.setFilter("Blocked Drain"));

        btnSafeRoute.setOnClickListener(v -> viewModel.toggleSafeRoute());

        view.findViewById(R.id.btnRecenterUm).setOnClickListener(v -> {
            osmMap.getController().animateTo(UM_CENTER);
            osmMap.getController().setZoom(16.2);
        });

        return view;
    }

    private void setupUmMap() {
        osmMap.setTileSource(TileSourceFactory.MAPNIK);
        osmMap.setMultiTouchControls(true);
        osmMap.getController().setZoom(16.2);
        osmMap.getController().setCenter(UM_CENTER);
    }

    private void renderMapOverlays() {
        if (viewModel == null || osmMap == null) return;
        osmMap.getOverlays().clear();

        List<HazardReport> reports = viewModel.getReports().getValue();
        String currentFilter = viewModel.getFilter().getValue();
        Boolean showingSafeRoute = viewModel.isSafeRouteActive().getValue();
        if (currentFilter == null) currentFilter = "ALL";
        if (showingSafeRoute == null) showingSafeRoute = false;

        if (reports != null) {
            for (HazardReport r : reports) {
                if (!currentFilter.equals("ALL") && !r.hazardType.equalsIgnoreCase(currentFilter)) {
                    continue;
                }

                GeoPoint point = new GeoPoint(r.latitude, r.longitude);

                // Hazard risk zone circle
                Polygon riskCircle = new Polygon();
                riskCircle.setPoints(Polygon.pointsAsCircle(point, "Critical".equalsIgnoreCase(r.severity) ? 95.0 : 55.0));
                if ("Critical".equalsIgnoreCase(r.severity)) {
                    riskCircle.getFillPaint().setColor(Color.argb(70, 211, 47, 47));
                    riskCircle.getOutlinePaint().setColor(Color.RED);
                } else {
                    riskCircle.getFillPaint().setColor(Color.argb(55, 255, 152, 0));
                    riskCircle.getOutlinePaint().setColor(Color.rgb(230, 81, 0));
                }
                riskCircle.getOutlinePaint().setStrokeWidth(3f);
                osmMap.getOverlays().add(riskCircle);

                // Marker
                Marker marker = new Marker(osmMap);
                marker.setPosition(point);
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                marker.setTitle("[" + r.severity.toUpperCase() + "] " + r.title);
                marker.setSnippet(r.campusLocation + "\n" + r.aiAnalysis);

                marker.setOnMarkerClickListener((m, mapView) -> {
                    showHazardDetailPopup(r);
                    return true;
                });
                osmMap.getOverlays().add(marker);
            }
        }

        // Safe detour route overlay
        if (showingSafeRoute) {
            Polyline safeLine = new Polyline();
            List<GeoPoint> routePoints = new ArrayList<>();
            routePoints.add(FCSIT_POINT);
            routePoints.add(new GeoPoint(3.1255, 101.6512));
            routePoints.add(new GeoPoint(3.1248, 101.6480));
            routePoints.add(KK8_POINT);
            safeLine.setPoints(routePoints);
            safeLine.getOutlinePaint().setColor(Color.parseColor("#2E7D32"));
            safeLine.getOutlinePaint().setStrokeWidth(14f);
            osmMap.getOverlays().add(safeLine);
        }

        osmMap.invalidate();
    }

    private void showHazardDetailPopup(HazardReport r) {
        String msg = getString(R.string.hazard_detail_location, r.campusLocation) + "\n" +
                getString(R.string.hazard_detail_type, r.hazardType, r.severity) + "\n" +
                getString(R.string.hazard_detail_ai, r.aiAnalysis) + "\n" +
                getString(R.string.hazard_detail_status, r.status) + "\n" +
                getString(R.string.hazard_detail_upvotes, r.upvotes);

        new AlertDialog.Builder(requireContext())
                .setTitle(r.title)
                .setMessage(msg)
                .setPositiveButton(getString(R.string.hazard_confirm_active), (d, w) -> viewModel.upvoteReport(r.id))
                .setNegativeButton(getString(R.string.btn_close), null)
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (osmMap != null) osmMap.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (osmMap != null) osmMap.onPause();
    }
}