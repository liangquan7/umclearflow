package com.um.clearflow;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

public class ProfileViewModel extends ViewModel {

    private final CampusRepository repository = CampusRepository.getInstance();

    public LiveData<String> getUserRole() {
        return repository.getUserRoleLiveData();
    }

    public LiveData<String> getUserEmail() {
        return repository.getUserEmailLiveData();
    }

    public void toggleAdminRole() {
        String currentRole = repository.getCurrentUserRole();
        if ("Student (FCSIT)".equals(currentRole)) {
            repository.setUserRole("JPPHB Maintenance Admin");
        } else {
            repository.setUserRole("Student (FCSIT)");
        }
    }
}