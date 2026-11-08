package com.um.clearflow;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        View mainRoot = findViewById(R.id.main_root);
        View topHeader = findViewById(R.id.topHeader);
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);

        if (mainRoot != null && topHeader != null && bottomNav != null) {
            ViewCompat.setOnApplyWindowInsetsListener(mainRoot, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                topHeader.setPadding(
                        topHeader.getPaddingLeft(),
                        insets.top + 14,
                        topHeader.getPaddingRight(),
                        topHeader.getPaddingBottom()
                );
                bottomNav.setPadding(
                        bottomNav.getPaddingLeft(),
                        bottomNav.getPaddingTop(),
                        bottomNav.getPaddingRight(),
                        insets.bottom
                );
                return WindowInsetsCompat.CONSUMED;
            });
        }

        Button btnTopSos = findViewById(R.id.btnTopSos);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new MapFragment())
                    .commit();
        }

        bottomNav.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;
            int id = item.getItemId();
            if (id == R.id.nav_map) {
                selectedFragment = new MapFragment();
            } else if (id == R.id.nav_reports) {
                selectedFragment = new ReportFragment();
            } else if (id == R.id.nav_weather_ai) {
                selectedFragment = new WeatherAiFragment();
            } else if (id == R.id.nav_profile) {
                selectedFragment = new ProfileFragment();
            }

            if (selectedFragment != null) {
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, selectedFragment)
                        .commit();
            }
            return true;
        });

        if (btnTopSos != null) {
            btnTopSos.setOnClickListener(v -> showUmSosDialog());
        }
    }

    private void showUmSosDialog() {
        String[] options = {
                getString(R.string.sos_um_security),
                getString(R.string.sos_jpphb_maintenance),
                getString(R.string.sos_ummc_emergency),
                getString(R.string.sos_share_gps)
        };

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.sos_dialog_title))
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        dialPhone("0379677070");
                    } else if (which == 1) {
                        dialPhone("0379673286");
                    } else if (which == 2) {
                        dialPhone("0379494422");
                    } else {
                        String userEmail = CampusRepository.getInstance().getCurrentUserEmail();
                        new AlertDialog.Builder(this)
                                .setTitle(getString(R.string.gps_alert_sent_title))
                                .setMessage(getString(R.string.gps_alert_sent_msg, "3.1281° N, 101.6505° E", userEmail))
                                .setPositiveButton(getString(R.string.btn_ok), null)
                                .show();
                    }
                })
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show();
    }

    private void dialPhone(String phone) {
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone));
        startActivity(intent);
    }
}