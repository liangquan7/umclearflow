package com.um.clearflow;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

public class ProfileFragment extends Fragment {

    private TextView tvUserProfileInfo;
    private ProfileViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_profile, container, false);
        tvUserProfileInfo = view.findViewById(R.id.tvUserProfileInfo);
        TextView tvSystemInfo = view.findViewById(R.id.tvSystemInfo);

        viewModel = new ViewModelProvider(this).get(ProfileViewModel.class);

        viewModel.getUserRole().observe(getViewLifecycleOwner(), role -> updateProfileUi());
        viewModel.getUserEmail().observe(getViewLifecycleOwner(), email -> updateProfileUi());

        Button btnToggleAdminRole = view.findViewById(R.id.btnToggleAdminRole);
        btnToggleAdminRole.setOnClickListener(v -> {
            viewModel.toggleAdminRole();
            String currentRole = viewModel.getUserRole().getValue();
            if ("JPPHB Maintenance Admin".equalsIgnoreCase(currentRole)) {
                Toast.makeText(requireContext(), getString(R.string.toast_switched_admin), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), getString(R.string.toast_switched_student), Toast.LENGTH_SHORT).show();
            }
        });

        if (tvSystemInfo != null) {
            tvSystemInfo.setText(getString(R.string.privacy_info_text, BuildConfig.API_BASE_URL));
        }

        return view;
    }

    private void updateProfileUi() {
        if (viewModel == null || tvUserProfileInfo == null) return;
        String roleStr = viewModel.getUserRole().getValue();
        String emailStr = viewModel.getUserEmail().getValue();
        String displayRole = roleStr != null ? roleStr : "Student (FCSIT)";
        String displayEmail = emailStr != null ? emailStr : "24231773@siswa.um.edu.my";

        String text = "• Name: Tan Liang Chuan (Siswa ID: 24231773)\n" +
                "• Siswa Mail: " + displayEmail + "\n" +
                "• Current Role: " + displayRole + "\n" +
                "• Assigned Zone: Universiti Malaya Central Estates";
        tvUserProfileInfo.setText(text);
    }
}