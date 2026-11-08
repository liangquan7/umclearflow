package com.um.clearflow;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 初始化 SQLite 与全局上下文
        CampusRepository.init(getApplicationContext());
        setContentView(R.layout.activity_main);

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