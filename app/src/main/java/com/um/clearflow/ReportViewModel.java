package com.um.clearflow;

import android.graphics.Bitmap;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class ReportViewModel extends ViewModel {

    public static class TriageSubmissionResult {
        public final long requestId;
        public final String inputKey;
        public final CampusRepository.TriageResult result;

        public TriageSubmissionResult(long requestId, String inputKey, CampusRepository.TriageResult result) {
            this.requestId = requestId;
            this.inputKey = inputKey != null ? inputKey : "";
            this.result = result;
        }
    }

    private final CampusRepository repository = CampusRepository.getInstance();
    private final ExecutorService triageExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong requestIdGenerator = new AtomicLong(0L);
    private final MutableLiveData<TriageSubmissionResult> triageResultLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> showOnlyMyReports = new MutableLiveData<>(false);
    // Survives rotation / Fragment view recreation (Fragment fields do not)
    private Bitmap capturedPhoto;

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

    public LiveData<TriageSubmissionResult> getTriageResult() {
        return triageResultLiveData;
    }

    public LiveData<Boolean> getShowOnlyMyReports() {
        return showOnlyMyReports;
    }

    public void setShowOnlyMyReports(boolean onlyMine) {
        showOnlyMyReports.setValue(onlyMine);
    }

    public long performVisionTriage(Bitmap photo, int hazardTypeIndex, String title, String inputKey) {
        final long reqId = requestIdGenerator.incrementAndGet();
        final String key = inputKey != null ? inputKey : "";
        // Run vision triage on background thread so FastAPI /api/v1/triage can be queried first
        triageExecutor.execute(() -> {
            CampusRepository.TriageResult res = repository.runVisionTriage(photo, hazardTypeIndex, title);
            triageResultLiveData.postValue(new TriageSubmissionResult(reqId, key, res));
        });
        return reqId;
    }

    public long performVisionTriage(Bitmap photo, int hazardTypeIndex, String title) {
        return performVisionTriage(photo, hazardTypeIndex, title, "");
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
