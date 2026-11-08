package com.um.clearflow;

import android.graphics.Bitmap;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.List;

public class ReportViewModel extends ViewModel {

    private final CampusRepository repository = CampusRepository.getInstance();
    private final MutableLiveData<CampusRepository.TriageResult> triageResultLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> showOnlyMyReports = new MutableLiveData<>(false);

    public LiveData<List<HazardReport>> getReports() {
        return repository.getReportsLiveData();
    }

    public LiveData<String> getUserRole() {
        return repository.getUserRoleLiveData();
    }

    public LiveData<CampusRepository.TriageResult> getTriageResult() {
        return triageResultLiveData;
    }

    public LiveData<Boolean> getShowOnlyMyReports() {
        return showOnlyMyReports;
    }

    public void setShowOnlyMyReports(boolean onlyMine) {
        showOnlyMyReports.setValue(onlyMine);
    }

    public void performVisionTriage(Bitmap photo, int hazardTypeIndex, String title) {
        CampusRepository.TriageResult res = repository.runVisionTriage(photo, hazardTypeIndex, title);
        triageResultLiveData.setValue(res);
    }

    public void submitNewReport(HazardReport report) {
        repository.addReport(report);
    }

    public void upvote(String id) {
        repository.upvoteReport(id);
    }

    public void voteResolve(String id) {
        repository.voteResolveReport(id);
    }

    public void updateStatus(String id, String status) {
        repository.updateReportStatus(id, status);
    }
}