package com.um.clearflow;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.core.location.LocationManagerCompat;
import androidx.core.os.CancellationSignal;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapFragment extends Fragment {

    /**
     * Public OSM-Germany routing endpoints (foot / bike / car profiles), HTTPS.
     * Fair-use demo server: for production host your own OSRM/Valhalla instance.
     */
    private static final String ROUTING_BASE = "https://routing.openstreetmap.de/";
    private static final int MY_LOCATION_INDEX = 4;   // last entry of originNames
    private static final long MAX_FIX_AGE_MS = 2 * 60 * 1000L;
    private static final long LOCATE_TIMEOUT_MS = 8000L;

    // Route result states
    private static final int ROUTE_SAFE_ROAD = 0;
    private static final int ROUTE_DETOUR_ROAD = 1;
    private static final int ROUTE_STRAIGHT_ESTIMATE = 2;
    private static final int ROUTE_BLOCKED = 3;

    private static class RoadRoute {
        final List<GeoPoint> points;
        final double distanceM;
        final double durationS;

        RoadRoute(List<GeoPoint> points, double distanceM, double durationS) {
            this.points = points;
            this.distanceM = distanceM;
            this.durationS = durationS;
        }
    }

    private static class RouteResult {
        final List<GeoPoint> points;
        final int state;
        final String note;      // blocked-hazard description or terminal-hazard remark
        final double distanceM; // <0 = unknown
        final double durationS;

        RouteResult(List<GeoPoint> points, int state, String note, double distanceM, double durationS) {
            this.points = points;
            this.state = state;
            this.note = note;
            this.distanceM = distanceM;
            this.durationS = durationS;
        }
    }

    private MapView mapView;
    private MapViewModel viewModel;
    private TextView tvMapSummary;
    private TextView tvBroadcastBanner;
    private TextView tvRouteStatus;
    private LinearLayout layoutRouteSelector;
    private Spinner spinnerOrigin;
    private Spinner spinnerDestination;
    private Spinner spinnerMobilityMode;
    private Button btnAll;
    private Button btnCritical;

    private static final String[] MOBILITY_MODES = {
            "🚶 Walking (Pedestrian / Covered Walkway)",
            "♿ Wheelchair / Barrier-Free Ramp",
            "🚴 Cycling (Bicycle / Micro-mobility)",
            "🚗 Driving (Car / Taxi)",
            "🛵 Motorcycle (Motor / Scooter)",
            "🚌 UM Shuttle Bus (Bus Route)"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService routeExecutor = Executors.newSingleThreadExecutor();
    private volatile long routeRequestId = 0L;
    private String pendingRouteKey = null;
    private String cachedRouteKey = null;
    private RouteResult cachedRoute = null;
    private String userAgent = "UM-ClearFlow";

    private GeoPoint myLocation = null;
    private float myAccuracyM = 0f;
    private CancellationSignal locateSignal = null;
    private Runnable locateTimeout = null;

    private final String[] originNames = {
            "Pantai Gate / Lingkungan Budi",
            "FCSIT & Central Library",
            "KK12 Residential College",
            "Faculty of Engineering",
            "📍 My Current Location (GPS)"
    };
    private final GeoPoint[] originPoints = {
            new GeoPoint(3.1190, 101.6622),
            new GeoPoint(3.1281, 101.6505),
            new GeoPoint(3.1338, 101.6518),
            new GeoPoint(3.1185, 101.6560),
            null // resolved from GPS at runtime
    };

    private final ActivityResultLauncher<String[]> locPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean ok = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                        || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (ok) {
                    locateUser();
                } else if (getContext() != null) {
                    Toast.makeText(getContext(), "Location permission denied. Choose a landmark as the route start.",
                            Toast.LENGTH_LONG).show();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext().getApplicationContext();
        SharedPreferences osmPrefs = ctx.getSharedPreferences(ctx.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        IConfigurationProvider cfg = Configuration.getInstance();
        cfg.load(ctx, osmPrefs);
        cfg.setUserAgentValue(ctx.getPackageName());
        // App-private cache: no storage permission needed on modern Android
        File osmBase = new File(ctx.getCacheDir(), "osmdroid");
        cfg.setOsmdroidBasePath(osmBase);
        cfg.setOsmdroidTileCache(new File(osmBase, "tiles"));
        userAgent = ctx.getPackageName() + "/" + BuildConfig.VERSION_NAME;

        View view = inflater.inflate(R.layout.fragment_map, container, false);

        viewModel = new ViewModelProvider(requireActivity()).get(MapViewModel.class);

        tvMapSummary = view.findViewById(R.id.tv_map_summary);
        tvBroadcastBanner = view.findViewById(R.id.tv_broadcast_banner);
        tvRouteStatus = view.findViewById(R.id.tv_route_status);
        layoutRouteSelector = view.findViewById(R.id.layout_route_selector);
        spinnerOrigin = view.findViewById(R.id.spinner_origin);
        spinnerDestination = view.findViewById(R.id.spinner_destination);
        spinnerMobilityMode = view.findViewById(R.id.spinner_mobility_mode);
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
        spinnerOrigin.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, originNames));

        List<CampusRepository.ShelterInfo> shelters = CampusRepository.getInstance().getCampusShelters();
        List<String> destNames = new ArrayList<>();
        for (CampusRepository.ShelterInfo s : shelters) {
            destNames.add(s.name);
        }
        spinnerDestination.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, destNames));

        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, MOBILITY_MODES);
        spinnerMobilityMode.setAdapter(modeAdapter);
        String savedPref = CampusRepository.getInstance()
                .getProfileField("mobility_pref", "Walking (Pedestrian / Covered Walkway)");
        for (int i = 0; i < MOBILITY_MODES.length; i++) {
            if (MOBILITY_MODES[i].toLowerCase(Locale.ROOT).contains(savedPref.toLowerCase(Locale.ROOT).split(" ")[0])) {
                spinnerMobilityMode.setSelection(i, false);
                break;
            }
        }

        Integer savedO = viewModel.getOriginIndex().getValue();
        Integer savedD = viewModel.getDestinationIndex().getValue();
        if (savedO != null && savedO >= 0 && savedO < originNames.length) spinnerOrigin.setSelection(savedO, false);
        if (savedD != null && savedD >= 0 && savedD < destNames.size()) spinnerDestination.setSelection(savedD, false);

        AdapterView.OnItemSelectedListener routeChangeListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                viewModel.setOriginIndex(spinnerOrigin.getSelectedItemPosition());
                viewModel.setDestinationIndex(spinnerDestination.getSelectedItemPosition());
                renderMapOverlays();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        };
        spinnerOrigin.setOnItemSelectedListener(routeChangeListener);
        spinnerDestination.setOnItemSelectedListener(routeChangeListener);
        spinnerMobilityMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                renderMapOverlays();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupControls(View view) {
        btnAll = view.findViewById(R.id.btn_filter_all);
        btnCritical = view.findViewById(R.id.btn_filter_critical);
        Button btnMyLocation = view.findViewById(R.id.btn_my_location);
        CheckBox cbShelters = view.findViewById(R.id.cb_show_shelters);
        CheckBox cbRoute = view.findViewById(R.id.cb_evac_route);
        cbShelters.setChecked(Boolean.TRUE.equals(viewModel.getSheltersVisible().getValue()));
        cbRoute.setChecked(Boolean.TRUE.equals(viewModel.getRouteEnabled().getValue()));

        btnAll.setOnClickListener(v -> viewModel.setFilter("All"));
        btnCritical.setOnClickListener(v -> viewModel.setFilter("Critical"));
        btnMyLocation.setOnClickListener(v -> locateUser());

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

    // ------------------------------------------------------------------ GPS (FR 4.3)

    @SuppressLint("MissingPermission")
    private void locateUser() {
        Context ctx = getContext();
        if (ctx == null) return;
        boolean granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            locPermLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;

        Location cached = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (cached == null || l.getTime() > cached.getTime())) cached = l;
            } catch (SecurityException | IllegalArgumentException ignored) {}
        }
        if (cached != null && System.currentTimeMillis() - cached.getTime() <= MAX_FIX_AGE_MS) {
            applyUserLocation(cached);
            return;
        }

        String provider = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (lm.isProviderEnabled(p)) {
                    provider = p;
                    break;
                }
            } catch (Exception ignored) {}
        }
        final Location fallback = cached;
        if (provider == null) {
            if (fallback != null) applyUserLocation(fallback);
            else Toast.makeText(ctx, "Turn on device location (GPS) and try again.", Toast.LENGTH_LONG).show();
            return;
        }

        cancelLocate();
        final CancellationSignal signal = new CancellationSignal();
        locateSignal = signal;
        Toast.makeText(ctx, "📡 Acquiring GPS fix...", Toast.LENGTH_SHORT).show();
        Runnable timeout = () -> {
            if (locateSignal != signal) return;
            cancelLocate();
            if (!isAdded()) return;
            if (fallback != null) applyUserLocation(fallback);
            else Toast.makeText(requireContext(), "No GPS fix yet. Try again outdoors.", Toast.LENGTH_LONG).show();
        };
        locateTimeout = timeout;
        mainHandler.postDelayed(timeout, LOCATE_TIMEOUT_MS);
        try {
            LocationManagerCompat.getCurrentLocation(lm, provider, signal,
                    ContextCompat.getMainExecutor(ctx), fresh -> {
                        if (locateSignal != signal) return;
                        cancelLocate();
                        if (!isAdded()) return;
                        Location use = fresh != null ? fresh : fallback;
                        if (use != null) applyUserLocation(use);
                        else Toast.makeText(requireContext(), "No GPS fix yet. Try again outdoors.",
                                Toast.LENGTH_LONG).show();
                    });
        } catch (SecurityException | IllegalArgumentException e) {
            cancelLocate();
            if (fallback != null) applyUserLocation(fallback);
        }
    }

    private void cancelLocate() {
        CancellationSignal s = locateSignal;
        Runnable t = locateTimeout;
        locateSignal = null;
        locateTimeout = null;
        if (t != null) mainHandler.removeCallbacks(t);
        if (s != null) s.cancel();
    }

    private void applyUserLocation(@NonNull Location loc) {
        myLocation = new GeoPoint(loc.getLatitude(), loc.getLongitude());
        myAccuracyM = loc.getAccuracy();
        if (mapView == null || !isAdded()) return;
        mapView.getController().animateTo(myLocation);
        spinnerOrigin.setSelection(MY_LOCATION_INDEX); // listener re-renders
        renderMapOverlays();
        Toast.makeText(requireContext(), String.format(Locale.US,
                "📍 Location set (±%.0f m)", myAccuracyM), Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ Rendering

    private static double radiusOf(HazardReport h) {
        return "Critical".equalsIgnoreCase(h.severity) ? 95.0 : 55.0;
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

        // 1. Graded hazard halos + markers (FR 4.1)
        for (HazardReport r : allReports) {
            if ("Resolved".equalsIgnoreCase(r.status)) continue;
            if ("Critical".equalsIgnoreCase(filter) && !"Critical".equalsIgnoreCase(r.severity)) {
                continue;
            }
            activeCount++;
            boolean isCritical = "Critical".equalsIgnoreCase(r.severity);
            if (isCritical) criticalCount++;

            GeoPoint pt = new GeoPoint(r.latitude, r.longitude);

            Polygon outerHalo = new Polygon();
            outerHalo.setPoints(Polygon.pointsAsCircle(pt, isCritical ? 145.0 : 85.0));
            outerHalo.getFillPaint().setColor(isCritical
                    ? Color.argb(45, 220, 38, 38)
                    : Color.argb(35, 245, 158, 11));
            outerHalo.getOutlinePaint().setColor(Color.TRANSPARENT);
            mapView.getOverlays().add(outerHalo);

            Polygon coreCircle = new Polygon();
            coreCircle.setPoints(Polygon.pointsAsCircle(pt, radiusOf(r)));
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

        // 2. Shelters (FR 4.1)
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

        // 3. Safe route (FR 4.2)
        if (showRoute) {
            drawDynamicSafeRoute(allReports);
        }

        // 4. User position (FR 4.3)
        if (myLocation != null) {
            Marker me = new Marker(mapView);
            me.setPosition(myLocation);
            me.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            me.setTitle("📍 You are here");
            me.setSnippet(String.format(Locale.US, "Accuracy ±%.0f m", myAccuracyM));
            me.setIcon(ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_menu_mylocation));
            mapView.getOverlays().add(me);
        }

        tvMapSummary.setText(String.format(Locale.US,
                "Visible Active Reports: %d (%d Critical) • Shelters: %s",
                activeCount, criticalCount, showShelters ? "ON" : "OFF"));

        mapView.invalidate();
    }

    // ------------------------------------------------------------------ Routing (FR 4.2)

    private static String routingService(String mobilityPref) {
        if (mobilityPref.contains("Cycling")) return "routed-bike";
        if (mobilityPref.contains("Driving") || mobilityPref.contains("Motorcycle") || mobilityPref.contains("Bus")) return "routed-car";
        return "routed-foot"; // walking / wheelchair
    }

    private static String hazardSignature(List<HazardReport> reports) {
        StringBuilder sb = new StringBuilder();
        for (HazardReport h : reports) {
            if ("Resolved".equalsIgnoreCase(h.status)) continue;
            sb.append(h.id).append(h.severity)
                    .append(String.format(Locale.US, "%.5f,%.5f;", h.latitude, h.longitude));
        }
        return String.valueOf(sb.toString().hashCode());
    }

    private void drawDynamicSafeRoute(List<HazardReport> allReports) {
        int oIdx = spinnerOrigin.getSelectedItemPosition();
        int dIdx = spinnerDestination.getSelectedItemPosition();
        if (oIdx < 0 || oIdx >= originNames.length) oIdx = 0;

        List<CampusRepository.ShelterInfo> shelters = CampusRepository.getInstance().getCampusShelters();
        if (dIdx < 0 || dIdx >= shelters.size()) dIdx = 0;

        final GeoPoint start;
        String originKey;
        if (oIdx == MY_LOCATION_INDEX) {
            if (myLocation == null) {
                tvRouteStatus.setText("Tap “📍 My Location” to use your GPS position, or pick a landmark as the starting point.");
                return;
            }
            start = myLocation;
            originKey = String.format(Locale.US, "gps%.4f,%.4f", myLocation.getLatitude(), myLocation.getLongitude());
        } else {
            start = originPoints[oIdx];
            originKey = "lm" + oIdx;
        }
        final CampusRepository.ShelterInfo target = shelters.get(dIdx);
        final GeoPoint end = new GeoPoint(target.lat, target.lon);
        String selectedMode = spinnerMobilityMode != null && spinnerMobilityMode.getSelectedItem() != null
                ? spinnerMobilityMode.getSelectedItem().toString()
                : CampusRepository.getInstance().getProfileField("mobility_pref", "Walking (Pedestrian / Covered Walkway)");
        final String mobility = selectedMode;
        final String service = routingService(mobility);
        final String key = originKey + "|" + dIdx + "|" + service + "|" + hazardSignature(allReports);

        if (cachedRoute != null && key.equals(cachedRouteKey)) {
            paintRoute(cachedRoute, target.name, mobility);
            return;
        }
        if (key.equals(pendingRouteKey)) {
            tvRouteStatus.setText("⏳ Calculating a road route that avoids reported hazards...");
            return;
        }

        pendingRouteKey = key;
        final long requestId = ++routeRequestId;
        tvRouteStatus.setText("⏳ Calculating a road route that avoids reported hazards...");
        final List<HazardReport> snapshot = new ArrayList<>(allReports); // snapshot objects are deep copies

        routeExecutor.execute(() -> {
            RouteResult result;
            try {
                result = computeRoute(start, end, service, snapshot);
            } catch (RuntimeException e) {
                result = new RouteResult(Arrays.asList(start, end), ROUTE_STRAIGHT_ESTIMATE, "", -1, -1);
            }
            final RouteResult finalResult = result;
            mainHandler.post(() -> {
                if (!isAdded() || mapView == null || requestId != routeRequestId) return;
                cachedRoute = finalResult;
                cachedRouteKey = key;
                pendingRouteKey = null;
                renderMapOverlays();
            });
        });
    }

    /** Runs on a background thread. */
    private RouteResult computeRoute(GeoPoint start, GeoPoint end, String service, List<HazardReport> reports) {
        // Hazards sitting on the origin/destination themselves cannot be bypassed: warn, don't block.
        List<HazardReport> avoidable = new ArrayList<>();
        StringBuilder terminalNote = new StringBuilder();
        for (HazardReport h : reports) {
            if ("Resolved".equalsIgnoreCase(h.status)) continue;
            GeoPoint hp = new GeoPoint(h.latitude, h.longitude);
            double r = radiusOf(h);
            if (hp.distanceToAsDouble(start) < r || hp.distanceToAsDouble(end) < r) {
                if (terminalNote.length() > 0) terminalNote.append("; ");
                terminalNote.append(h.title);
            } else {
                avoidable.add(h);
            }
        }
        String terminal = terminalNote.length() > 0 ? terminalNote.toString() : "";

        List<RoadRoute> roads = fetchRoadRoutes(service, Arrays.asList(start, end), true);
        HazardReport firstHit = null;
        for (int i = 0; i < roads.size(); i++) {
            HazardReport hit = findHit(roads.get(i).points, avoidable);
            if (hit == null) {
                return new RouteResult(roads.get(i).points,
                        i == 0 ? ROUTE_SAFE_ROAD : ROUTE_DETOUR_ROAD,
                        i == 0 ? terminal : (firstHit != null ? firstHit.title + " (" + firstHit.hazardType + ")" : terminal),
                        roads.get(i).distanceM, roads.get(i).durationS);
            }
            if (i == 0) firstHit = hit;
        }

        if (!roads.isEmpty() && firstHit != null) {
            // Direct road(s) blocked: re-route through a via-point offset sideways from the hazard
            for (GeoPoint via : detourWaypoints(firstHit, start, end)) {
                List<RoadRoute> detour = fetchRoadRoutes(service, Arrays.asList(start, via, end), false);
                if (!detour.isEmpty() && findHit(detour.get(0).points, avoidable) == null) {
                    return new RouteResult(detour.get(0).points, ROUTE_DETOUR_ROAD,
                            firstHit.title + " (" + firstHit.hazardType + ")",
                            detour.get(0).distanceM, detour.get(0).durationS);
                }
            }
            return new RouteResult(roads.get(0).points, ROUTE_BLOCKED,
                    firstHit.title + " (" + firstHit.hazardType + ")",
                    roads.get(0).distanceM, roads.get(0).durationS);
        }

        // Road routing service unreachable (offline): geometric corridor estimate, clearly labelled
        List<List<GeoPoint>> candidates = geometricCandidates(start, end);
        HazardReport directHit = null;
        for (int i = 0; i < candidates.size(); i++) {
            HazardReport hit = findHit(candidates.get(i), avoidable);
            if (hit == null) {
                return new RouteResult(candidates.get(i), ROUTE_STRAIGHT_ESTIMATE, terminal, -1, -1);
            }
            if (i == 0) directHit = hit;
        }
        return new RouteResult(candidates.get(0), ROUTE_BLOCKED,
                directHit != null ? directHit.title + " (" + directHit.hazardType + ")" : "", -1, -1);
    }

    private List<List<GeoPoint>> geometricCandidates(GeoPoint start, GeoPoint end) {
        double midLat = (start.getLatitude() + end.getLatitude()) / 2.0;
        double midLon = (start.getLongitude() + end.getLongitude()) / 2.0;
        double dLat = end.getLatitude() - start.getLatitude();
        double dLon = end.getLongitude() - start.getLongitude();
        double len = Math.hypot(dLat, dLon);
        double normLat = len > 1e-5 ? -dLon / len : 0.0020;
        double normLon = len > 1e-5 ? dLat / len : 0.0020;

        List<List<GeoPoint>> c = new ArrayList<>();
        c.add(Arrays.asList(start, new GeoPoint(midLat, midLon), end));
        c.add(Arrays.asList(start,
                new GeoPoint(midLat + normLat * 0.0020, midLon + normLon * 0.0020), end));
        c.add(Arrays.asList(start,
                new GeoPoint(midLat - normLat * 0.0022, midLon - normLon * 0.0022), end));
        return c;
    }

    private List<GeoPoint> detourWaypoints(HazardReport h, GeoPoint start, GeoPoint end) {
        double lat = h.latitude;
        double lon = h.longitude;
        double cosLat = Math.cos(Math.toRadians(lat));
        double dLat = end.getLatitude() - start.getLatitude();
        double dLonScaled = (end.getLongitude() - start.getLongitude()) * cosLat;
        double len = Math.hypot(dLat, dLonScaled);
        if (len < 1e-9) return Collections.emptyList();
        double nLat = -dLonScaled / len;
        double nLon = dLat / len;
        double offMeters = radiusOf(h) + 80.0;
        double offLat = offMeters / 111320.0;
        double offLon = offMeters / (111320.0 * cosLat);
        return Arrays.asList(
                new GeoPoint(lat + nLat * offLat, lon + nLon * offLon),
                new GeoPoint(lat - nLat * offLat, lon - nLon * offLon));
    }

    /** Background thread only. Returns an empty list on any failure. */
    private List<RoadRoute> fetchRoadRoutes(String service, List<GeoPoint> coords, boolean alternatives) {
        List<RoadRoute> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            StringBuilder sb = new StringBuilder(ROUTING_BASE).append(service).append("/route/v1/driving/");
            for (int i = 0; i < coords.size(); i++) {
                if (i > 0) sb.append(';');
                sb.append(String.format(Locale.US, "%.6f,%.6f",
                        coords.get(i).getLongitude(), coords.get(i).getLatitude()));
            }
            sb.append("?overview=full&geometries=geojson");
            if (alternatives && coords.size() == 2) sb.append("&alternatives=true");

            conn = (HttpURLConnection) new URL(sb.toString()).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", userAgent);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return out;

            StringBuilder body = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) body.append(line);
            }
            JSONObject root = new JSONObject(body.toString());
            if (!"Ok".equals(root.optString("code"))) return out;
            JSONArray routes = root.getJSONArray("routes");
            for (int i = 0; i < routes.length(); i++) {
                JSONObject r = routes.getJSONObject(i);
                JSONArray arr = r.getJSONObject("geometry").getJSONArray("coordinates");
                List<GeoPoint> pts = new ArrayList<>(arr.length());
                for (int k = 0; k < arr.length(); k++) {
                    JSONArray c = arr.getJSONArray(k);
                    pts.add(new GeoPoint(c.getDouble(1), c.getDouble(0)));
                }
                if (pts.size() >= 2) {
                    out.add(new RoadRoute(pts, r.optDouble("distance", -1), r.optDouble("duration", -1)));
                }
            }
        } catch (Exception ignored) {
            out.clear();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    private void paintRoute(RouteResult r, String targetName, String mobility) {
        Polyline line = new Polyline();
        line.setPoints(r.points);
        int color;
        switch (r.state) {
            case ROUTE_SAFE_ROAD: color = Color.rgb(22, 163, 74); break;
            case ROUTE_DETOUR_ROAD: color = Color.rgb(37, 99, 235); break;
            case ROUTE_STRAIGHT_ESTIMATE: color = Color.rgb(217, 119, 6); break;
            default: color = Color.rgb(220, 38, 38); break;
        }
        line.getOutlinePaint().setColor(color);
        line.getOutlinePaint().setStrokeWidth(r.state == ROUTE_BLOCKED ? 8f : 11f);
        mapView.getOverlays().add(line);

        String stats = r.distanceM >= 0
                ? String.format(Locale.US, "%.0f m, ~%.0f min", r.distanceM, Math.max(1.0, r.durationS / 60.0))
                : "distance unknown";
        String access = mobility.contains("Wheelchair")
                ? " Ramps/steps/accessibility are NOT checked by this route." : "";
        String terminal = (r.state == ROUTE_SAFE_ROAD && !r.note.isEmpty())
                ? " ⚠️ Hazard reported at start/destination: " + r.note + "." : "";
        String verify = " Based only on reports on this device; verify conditions and official UM guidance before moving.";

        switch (r.state) {
            case ROUTE_SAFE_ROAD:
                tvRouteStatus.setText("🟢 Road route avoiding recorded hazards (" + stats + ") → " + targetName
                        + "." + terminal + access + verify);
                break;
            case ROUTE_DETOUR_ROAD:
                tvRouteStatus.setText("🔵 The direct road crosses [" + r.note + "]. Detour road route shown ("
                        + stats + ") → " + targetName + "." + access + verify);
                break;
            case ROUTE_STRAIGHT_ESTIMATE:
                tvRouteStatus.setText("🟠 Road routing unavailable (offline?). Straight-line corridor estimate only → "
                        + targetName + "; it may cross buildings or water." + access + verify);
                break;
            default:
                tvRouteStatus.setText("🚨 Every candidate route crosses a locally recorded active hazard"
                        + (r.note.isEmpty() ? "" : " [" + r.note + "]")
                        + ". Follow current official UM instructions; if in immediate danger use SOS.");
                break;
        }
    }

    /** Densify to 10 m so a long segment cannot jump over a hazard circle. */
    private List<GeoPoint> densifyRoute(List<GeoPoint> waypoints, double stepMeters) {
        List<GeoPoint> dense = new ArrayList<>();
        for (int i = 0; i < waypoints.size() - 1; i++) {
            GeoPoint a = waypoints.get(i);
            GeoPoint b = waypoints.get(i + 1);
            double dist = a.distanceToAsDouble(b);
            int steps = Math.max(1, (int) Math.ceil(dist / stepMeters));
            for (int s = 0; s < steps; s++) {
                double ratio = (double) s / steps;
                dense.add(new GeoPoint(
                        a.getLatitude() + ratio * (b.getLatitude() - a.getLatitude()),
                        a.getLongitude() + ratio * (b.getLongitude() - a.getLongitude())));
            }
        }
        if (!waypoints.isEmpty()) {
            dense.add(waypoints.get(waypoints.size() - 1));
        }
        return dense;
    }

    private HazardReport findHit(List<GeoPoint> route, List<HazardReport> hazards) {
        List<GeoPoint> dense = densifyRoute(route, 10.0);
        for (HazardReport h : hazards) {
            GeoPoint hp = new GeoPoint(h.latitude, h.longitude);
            double radius = radiusOf(h);
            for (GeoPoint p : dense) {
                if (p.distanceToAsDouble(hp) < radius) return h;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Lifecycle

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
        cancelLocate();
        routeRequestId++;           // drop any in-flight route result
        pendingRouteKey = null;
        mainHandler.removeCallbacksAndMessages(null);
        if (mapView != null) {
            mapView.getOverlays().clear();
            mapView.onDetach();
            mapView = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        routeExecutor.shutdownNow();
        super.onDestroy();
    }
}
