package com.um.clearflow;

import java.util.List;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

public class ProfileViewModel extends ViewModel {

    private final CampusRepository repository = CampusRepository.getInstance();

    public LiveData<String> getUserRole() {
        return repository.getUserRoleLiveData();
    }

    public LiveData<List<HazardReport>> getReports() {
        return repository.getReportsLiveData();
    }

    public LiveData<String> getBroadcastBanner() {
        return repository.getBroadcastBannerLiveData();
    }

    public boolean verifyAdminPin(String pin) {
        return repository.authenticateAndSetAdmin(pin);
    }

    public void switchToStudent() {
        repository.switchToStudentRole();
    }

    public void sendBroadcast(String message) {
        repository.publishAdminBroadcast(message);
    }

    public String getHotspotStats() {
        return repository.generateHotspotRankingText();
    }

    public String exportCsvContent() {
        return repository.exportReportsToCsv();
    }

    public LiveData<String> getSyncStatus() {
        return repository.getSyncStatusLiveData();
    }

    public void syncNow() {
        repository.requestSync();
    }
}
