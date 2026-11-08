package com.um.clearflow;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.util.Locale;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private Fragment mapFragment;
    private Fragment reportFragment;
    private Fragment weatherAiFragment;
    private Fragment profileFragment;
    private Fragment activeFragment;

    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                // Notification permission callback
            });
    private final ActivityResultLauncher<String[]> locationPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean ok = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                        || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (ok) {
                    shareLiveLocation();
                } else {
                    Toast.makeText(this, "Location permission is required to share GPS.", Toast.LENGTH_LONG).show();
                }
            });

    public void showSosDialog() {
        CampusRepository repo = CampusRepository.getInstance();
        String emergencyPhone = repo.getProfileField("emergency_phone", "").trim();
        String[] options = {
                "📍 Share Live GPS Link (WhatsApp / SMS / Share)",
                "🚓 Call UM Auxiliary Police (03-7967 7070)",
                "🚑 Call Malaysia Emergency Services (999)",
                "📞 Call My Emergency Contact" + (emergencyPhone.isEmpty() ? " (not set)" : " (" + emergencyPhone + ")")
        };
        new AlertDialog.Builder(this)
                .setTitle("🚨 UM-ClearFlow Emergency SOS")
                .setItems(options, (d, which) -> {
                    if (which == 0) shareLiveLocationWithPermission();
                    else if (which == 1) dial("0379677070");
                    else if (which == 2) dial("999");
                    else if (emergencyPhone.isEmpty()) {
                        Toast.makeText(this, "Set an emergency contact in the Profile tab first.", Toast.LENGTH_SHORT).show();
                    } else dial(emergencyPhone);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void shareLiveLocationWithPermission() {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (fine || coarse) {
            shareLiveLocation();
        } else {
            locationPermLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    @SuppressLint("MissingPermission")
    private Location bestLastKnownLocation() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException | IllegalArgumentException ignored) {}
        }
        return best;
    }

    private void shareLiveLocation() {
        CampusRepository repo = CampusRepository.getInstance();
        String name = repo.getProfileField("student_name", "UM Student");
        String college = repo.getProfileField("college", "UM Campus");
        Location loc = bestLastKnownLocation();

        StringBuilder body = new StringBuilder("[UM-ClearFlow SOS] Emergency assistance needed!\n")
                .append("Sender: ").append(name).append(" (").append(college).append(")\n");
        if (loc != null) {
            long ageMin = Math.max(0, (System.currentTimeMillis() - loc.getTime()) / 60000);
            body.append(String.format(Locale.US, "Location: https://maps.google.com/?q=%.6f,%.6f\n",
                    loc.getLatitude(), loc.getLongitude()));
            body.append(String.format(Locale.US, "(accuracy ±%.0f m, fix taken %d min ago)",
                    loc.getAccuracy(), ageMin));
        } else {
            body.append("GPS location unavailable. Please call me.");
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, body.toString());
        startActivity(Intent.createChooser(send, "Share SOS Location via..."));
    }

    private void dial(String phone) {
        startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone))));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CampusRepository.init(getApplicationContext());
        super.onCreate(savedInstanceState);
        // 初始化 SQLite 与全局上下文

        setContentView(R.layout.activity_main);
        findViewById(R.id.btnTopSos).setOnClickListener(v -> showSosDialog());
        requestNotificationPermissionIfNeeded();

        FragmentManager fm = getSupportFragmentManager();
        if (savedInstanceState == null) {
            mapFragment = new MapFragment();
            reportFragment = new ReportFragment();
            weatherAiFragment = new WeatherAiFragment();
            profileFragment = new ProfileFragment();

            fm.beginTransaction()
                    .add(R.id.fragment_container, profileFragment, "PROFILE").hide(profileFragment)
                    .add(R.id.fragment_container, weatherAiFragment, "WEATHER").hide(weatherAiFragment)
                    .add(R.id.fragment_container, reportFragment, "REPORT").hide(reportFragment)
                    .add(R.id.fragment_container, mapFragment, "MAP")
                    .commit();
            activeFragment = mapFragment;
        } else {
            mapFragment = fm.findFragmentByTag("MAP");
            reportFragment = fm.findFragmentByTag("REPORT");
            weatherAiFragment = fm.findFragmentByTag("WEATHER");
            profileFragment = fm.findFragmentByTag("PROFILE");
            activeFragment = mapFragment;
            Fragment[] all = {mapFragment, reportFragment, weatherAiFragment, profileFragment};
            for (Fragment f : all) {
                if (f != null && !f.isHidden()) {
                    activeFragment = f;
                    break;
                }
            }
        }

        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            Fragment target = mapFragment;
            if (id == R.id.nav_map) {
                target = mapFragment;
            } else if (id == R.id.nav_reports) {
                target = reportFragment;
            } else if (id == R.id.nav_weather_ai) {
                target = weatherAiFragment;
            } else if (id == R.id.nav_profile) {
                target = profileFragment;
            }

            if (target != null && target != activeFragment) {
                FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
                ft.hide(activeFragment).show(target).commit();
                activeFragment = target;
            }
            return true;
        });
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }
}