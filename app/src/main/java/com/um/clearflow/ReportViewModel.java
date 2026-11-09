package com.um.clearflow;

import android.graphics.Bitmap;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReportViewModel extends ViewModel {

    public static class TriageResponse {
        public final long requestId;
        public final String inputKey;
        public final CampusRepository.TriageResult result;

        TriageResponse(long requestId, String inputKey, CampusRepository.TriageResult result) {
            this.requestId = requestId;
            this.inputKey = inputKey;
            this.result = result;
        }
    }

    private final CampusRepository repository = CampusRepository.getInstance();
    private final ExecutorService triageExecutor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<TriageResponse> triageResultLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> showOnlyMyReports = new MutableLiveData<>(false);
    // Survives rotation / Fragment view recreation (Fragment fields do not)
    private Bitmap capturedPhoto;
    private long nextTriageRequestId = 0L;

    public Bitmap getCapturedPhoto() {
        return capturedPhoto;
    }

    public void setCapturedPhoto(Bitmap photo) {
        capturedPhoto = photo;
    }

    public LiveData<List<HazardReport>> getReports() {
        return repository.getReportsLiveData();
    }

    public LiveData<String> getUserRole() {
        return repository.getUserRoleLiveData();
    }

    public LiveData<TriageResponse> getTriageResult() {
        return triageResultLiveData;
    }

    public LiveData<Boolean> getShowOnlyMyReports() {
        return showOnlyMyReports;
    }

    public void setShowOnlyMyReports(boolean onlyMine) {
        showOnlyMyReports.setValue(onlyMine);
    }

    public synchronized long performVisionTriage(Bitmap photo, int hazardTypeIndex, String title, String inputKey) {
        // Run vision triage on background thread so FastAPI /api/v1/triage can be queried first
        long requestId = ++nextTriageRequestId;
        triageExecutor.execute(() -> {
            CampusRepository.TriageResult result;
            try {
                result = repository.runVisionTriage(photo, hazardTypeIndex, title);
            } catch (RuntimeException e) {
                result = repository.runTriage(hazardTypeIndex, title);
            }
            triageResultLiveData.postValue(new TriageResponse(requestId, inputKey, result));
        });
        return requestId;
    }

    @Override
    protected void onCleared() {
        triageExecutor.shutdownNow();
        super.onCleared();
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

    public void clearTriage() {
        triageResultLiveData.setValue(null);
    }
}
