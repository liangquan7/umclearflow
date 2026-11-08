package com.um.clearflow;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.List;

public class ReportViewModel extends ViewModel {

    public static class TriageResult {
        public final String severity;
        public final double confidence;
        public final String note;

        public TriageResult(String severity, double confidence, String note) {
            this.severity = severity;
            this.confidence = confidence;
            this.note = note;
        }
    }

    private final CampusRepository repository = CampusRepository.getInstance();
    private final MutableLiveData<TriageResult> triageResultLiveData = new MutableLiveData<>();

    public ReportViewModel() {
        // Set a default initial rule-based triage
        triageResultLiveData.setValue(new TriageResult(
                "Medium",
                0.90,
                "Rule-based Triage: Awaiting photo or details scan."
        ));
    }

    public LiveData<List<HazardReport>> getReports() {
        return repository.getReportsLiveData();
    }

    public LiveData<TriageResult> getTriageResult() {
        return triageResultLiveData;
    }

    public void runTriage(int hazardTypeIndex, String userTitle) {
        String severity;
        double confidence;
        String note;

        String titleSuffix = (userTitle != null && !userTitle.trim().isEmpty()) ? " (" + userTitle.trim() + ")" : "";

        if (hazardTypeIndex == 0) { // Blocked Drain
            severity = "Critical";
            confidence = 0.92;
            note = "Rule-based Assessment: Blocked drain tagged with high overflow risk" + titleSuffix + ".";
        } else if (hazardTypeIndex == 1) { // Flash Flood
            severity = "Critical";
            confidence = 0.95;
            note = "Rule-based Assessment: Road flooding detected" + titleSuffix + ". Passability: Unsafe for low-clearance vehicles.";
        } else { // Fallen Tree
            severity = "Medium";
            confidence = 0.88;
            note = "Rule-based Assessment: Fallen tree branch obstructing campus walkway/roadway" + titleSuffix + ".";
        }

        triageResultLiveData.setValue(new TriageResult(severity, confidence, note));
    }

    public void submitReport(String title, String locationStr, String hazardType, boolean isOffline) {
        TriageResult currentTriage = triageResultLiveData.getValue();
        String severity = currentTriage != null ? currentTriage.severity : "Medium";
        double confidence = currentTriage != null ? currentTriage.confidence : 0.90;
        String note = currentTriage != null ? currentTriage.note : "Rule-based report submitted.";

        String finalTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "Reported " + hazardType + " at UM";

        double lat = 3.1281 + (Math.random() - 0.5) * 0.003;
        double lon = 101.6505 + (Math.random() - 0.5) * 0.003;

        List<HazardReport> currentList = repository.getReportsLiveData().getValue();
        int count = currentList != null ? currentList.size() : 0;

        HazardReport newReport = new HazardReport(
                "UM-" + (105 + count),
                finalTitle,
                hazardType,
                severity,
                locationStr,
                lat, lon,
                note,
                confidence,
                isOffline ? "Saved Offline (Pending Sync)" : "Pending JPPHB Action",
                1,
                repository.getCurrentUserEmail(),
                "Just now",
                !isOffline
        );

        repository.addReport(newReport);
    }

    public void upvoteReport(String id) {
        repository.upvoteReport(id);
    }

    public void updateReportStatus(String id, String status, boolean synced) {
        repository.updateReportStatus(id, status, synced);
    }
}